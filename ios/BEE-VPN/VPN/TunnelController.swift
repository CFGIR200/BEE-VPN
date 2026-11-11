import Foundation
import NetworkExtension
import Combine

/// The seven connection modes the Android TransportRail offers. On iOS the
/// WireGuard engine (WARP identity or manual wg-quick) is the one that runs;
/// the other six are Android/Windows-core engines and are shown but marked.
enum TunnelMode: String, CaseIterable, Identifiable {
    case wireguard = "wireguard"
    case masque = "masque"
    case wow = "wow"             // WARP-on-WARP
    case psiphon = "psiphon"
    case tor = "tor"
    case foxy = "foxy"
    case shard = "shard"

    var id: String { rawValue }

    var supportedOnIOS: Bool { self == .wireguard }

    /// The localized display name — keys come verbatim from the Android
    /// mode screen (Connection mode).
    var displayName: String {
        switch self {
        case .wireguard: return BeeStrings.t("WireGuard")
        case .masque: return BeeStrings.t("MASQUE")
        case .wow: return BeeStrings.t("WARP-on-WARP")
        case .psiphon: return BeeStrings.t("Psiphon")
        case .tor: return BeeStrings.t("Tor")
        case .foxy: return BeeStrings.t("Foxy")
        case .shard: return BeeStrings.t("SHARD")
        }
    }

    var description: String {
        switch self {
        case .wireguard: return BeeStrings.t("WireGuard tunnel")
        case .masque: return BeeStrings.t("HTTP/3 or HTTP/2 tunnel")
        case .wow: return BeeStrings.t("Double-layer tunnel")
        case .psiphon: return BeeStrings.t("Anti-censorship tunnel")
        case .tor: return BeeStrings.t("Onion routing; slowest but hardest to block")
        case .foxy: return BeeStrings.t("Firefox-account VPN via Fastly HTTP/2")
        case .shard: return BeeStrings.t("Public nodes, auto-selected; no setup")
        }
    }

    /// Short rail label — Android's rail shows "WoW" for the double hop.
    var railLabel: String {
        self == .wow ? "WoW" : displayName
    }
}

/// Public-IP reporter — the Home console's exit-node card and the tunnel
/// verification probe both read `https://www.cloudflare.com/cdn-cgi/trace`
/// through the tunnel, the way Android's verification gate proves bytes
/// really flow before claiming "connected".
enum IPReporter {
    struct Trace {
        let ip: String
        let location: String
        let rttMs: Int
    }

    static func trace(timeout: TimeInterval = 12) async -> Trace? {
        guard let url = URL(string: "https://www.cloudflare.com/cdn-cgi/trace") else { return nil }
        var request = URLRequest(url: url)
        request.timeoutInterval = timeout
        request.cachePolicy = .reloadIgnoringLocalCacheData
        let started = Date()
        guard let (data, _) = try? await URLSession.shared.data(for: request),
              let text = String(data: data, encoding: .utf8) else { return nil }
        let rtt = Int(Date().timeIntervalSince(started) * 1000)
        var ip = ""
        var loc = ""
        for line in text.split(separator: "\n") {
            let pair = line.split(separator: "=", maxSplits: 1)
            if pair.count == 2 {
                if pair[0] == "ip" { ip = String(pair[1]) }
                if pair[0] == "loc" { loc = String(pair[1]) }
            }
        }
        guard !ip.isEmpty else { return nil }
        return Trace(ip: ip, location: loc, rttMs: rtt)
    }

    /// The Android connect gate: the tunnel only counts as up when traffic
    /// provably crosses it.
    static func verifyThroughTunnel() async -> Bool {
        await trace(timeout: 12) != nil
    }

    static func flag(for countryCode: String) -> String {
        guard countryCode.count == 2 else { return "" }
        let base: UInt32 = 0x1F1E6
        let scalars = countryCode.uppercased().unicodeScalars.compactMap { scalar -> UnicodeScalar? in
            guard let a = UnicodeScalar(UInt32(65)), let z = UnicodeScalar(UInt32(90)),
                  scalar.value >= a.value, scalar.value <= z.value else { return nil }
            return UnicodeScalar(base + scalar.value - a.value)
        }
        return String(String.UnicodeScalarView(scalars))
    }
}

/// Owns the NETunnelProviderManager for the BeeTunnel packet-tunnel
/// extension: builds the final wg-quick config from the profile settings,
/// runs the connect ladder, verifies traffic, publishes status to the UI.
@MainActor
final class TunnelController: ObservableObject {

    static let tunnelBundleId = "app.beevpn.tunnel"
    /// The same wire ports CoreConfig.kt climbs — WG first, then the fallbacks.
    static let portLadder = [2408, 500, 1701, 4500]
    static let defaultEndpointHost = "engage.cloudflareclient.com"

    enum Phase: Equatable {
        case idle
        case connecting(String)
        case connected
        case degraded
        case failed(String)
    }

    @Published var phase: Phase = .idle
    @Published var status: NEVPNStatus = .invalid
    @Published private(set) var connectDate: Date?

    private var manager: NETunnelProviderManager?
    private var observing = false
    private var userDisconnected = false
    private var ladderRunning = false
    private let settings = AppSettings.shared

    init() {
        Task { await refresh() }
    }

    var mode: TunnelMode { TunnelMode(rawValue: settings.tunnelProtocol) ?? .wireguard }

    func setMode(_ m: TunnelMode) {
        guard m.supportedOnIOS else { return }
        settings.tunnelProtocol = m.rawValue
    }

    // ------------------------------------------------------------ lifecycle

    func refresh() async {
        do {
            let managers = try await NETunnelProviderManager.loadAllFromPreferences()
            let mine = managers.first {
                ($0.protocolConfiguration as? NETunnelProviderProtocol)?.providerBundleIdentifier == Self.tunnelBundleId
            } ?? {
                let fresh = NETunnelProviderManager()
                fresh.localizedDescription = "BEE-VPN"
                return fresh
            }()
            self.manager = mine
            observeStatus(mine)
            status = mine.connection.status
            syncPhaseFromStatus()
        } catch {
            phase = .failed(BeeStrings.t("Connection did not come up") + " — \(error.localizedDescription)")
            BeeLog.write("vpn profile read failed: \(error)")
        }
    }

    /// The dial tap: connected/connecting → disconnect, otherwise connect.
    func toggle() async {
        if status == .connected || status == .connecting || status == .reasserting {
            userDisconnected = true
            disconnect()
            return
        }
        userDisconnected = false
        await connectFromUI()
    }

    private func connectFromUI() async {
        guard mode.supportedOnIOS else {
            phase = .failed(BeeStrings.t("This engine runs on Android and Windows only"))
            return
        }
        // A pinned manual endpoint goes straight out, like Android's
        // manual endpoint — no ladder to second-guess the user.
        let pinned = settings.manualEndpoint.trimmingCharacters(in: .whitespaces)
        if !pinned.isEmpty {
            if await singleConnect(endpoint: pinned, label: pinned) { return }
            return
        }
        // First connect ever: one-time auto scan, the iOS edition of the
        // AUTO ladder (the other engines live in the Android core).
        let savedGood = settings.lastGoodEndpoint.trimmingCharacters(in: .whitespaces)
        if savedGood.isEmpty && !settings.autoScanDone {
            await runAutoScan()
            return
        }
        if await singleConnect(endpoint: savedGood.isEmpty ? nil : savedGood, label: savedGood.isEmpty ? Self.defaultEndpointHost : savedGood) {
            return
        }
        // The remembered rung stopped working — climb the ladder once.
        await runAutoScan()
    }

    /// The port ladder: same wire ports the Android engine climbs, each with
    /// a probe gate so a handshake without traffic cannot win.
    func runAutoScan() async {
        guard !ladderRunning else { return }
        ladderRunning = true
        defer { ladderRunning = false }
        for (index, port) in Self.portLadder.enumerated() {
            let endpoint = "\(Self.defaultEndpointHost):\(port)"
            let progress = BeeStrings.tf(" (%s of %s)", faDigits("\(index + 1)"), faDigits("\(Self.portLadder.count)"))
            phase = .connecting(BeeStrings.t("Auto Scan") + " · " + BeeStrings.tf("Trying %s", endpoint) + progress)
            if await singleConnect(endpoint: endpoint, label: endpoint) {
                settings.lastGoodEndpoint = endpoint
                settings.markAutoScanDone()
                BeeLog.write("auto scan winner: \(endpoint)")
                return
            }
        }
        settings.markAutoScanDone()
        phase = .failed(BeeStrings.t("No port answered — try the manual endpoint"))
    }

    // ------------------------------------------------------------ plumbing

    private func singleConnect(endpoint: String?, label: String) async -> Bool {
        phase = .connecting(BeeStrings.t("Preparing WireGuard tunnel"))
        do {
            let base = try await WarpProvisioner.resolveBaseConfig()
            let built = TunnelConfigBuilder.build(
                base: base,
                dns: settings.customDNS,
                mtu: settings.mtu,
                bypassIran: settings.bypassIran,
                lanAccess: settings.lanAccess)
            if let note = built.routesNote {
                BeeLog.write("routing: \(note)")
            }
            let target = endpoint ?? WarpProvisioner.endpoint(ofManual: built.config) ?? "\(Self.defaultEndpointHost):\(Self.portLadder[0])"
            // The ladder climbs ports, so the Endpoint line must always match
            // the rung being tried — never the identity's saved default.
            let tuned = TunnelConfigBuilder.applyEndpoint(built.config, target)
            try await startVPN(config: tuned, endpoint: target)
            guard await waitForStatus(.connected, seconds: 15) else {
                await settleDisconnect()
                phase = .failed(BeeStrings.t("Connection did not come up") + " (\(label))")
                return false
            }
            connectDate = Date()
            phase = .connecting(BeeStrings.t("reading from inside the tunnel"))
            if await IPReporter.verifyThroughTunnel() {
                BeeLog.write("connected via \(target) — traffic verified")
                phase = .connected
                return true
            }
            await settleDisconnect()
            connectDate = nil
            phase = .failed(BeeStrings.t("Tunnel handshake succeeded but no traffic passes — try another protocol"))
            return false
        } catch {
            connectDate = nil
            let message = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            phase = .failed(message)
            BeeLog.write("connect failed: \(message)")
            return false
        }
    }

    private func startVPN(config: String, endpoint: String) async throws {
        if manager == nil { await refresh() }
        guard let manager else {
            throw ProvisionError.network(BeeStrings.t("Connection did not come up") + " — no VPN profile")
        }
        let protocolConfiguration = NETunnelProviderProtocol()
        protocolConfiguration.providerBundleIdentifier = Self.tunnelBundleId
        protocolConfiguration.serverAddress = endpoint
        protocolConfiguration.providerConfiguration = [
            "WgQuickConfig": config,
            "Endpoint": endpoint,
        ]
        protocolConfiguration.disconnectOnSleep = false
        manager.protocolConfiguration = protocolConfiguration
        manager.localizedDescription = "BEE-VPN"
        manager.isEnabled = true
        try await manager.saveToPreferences()
        try await manager.loadFromPreferences()
        observeStatus(manager)
        try manager.connection.startVPNTunnel()
    }

    func disconnect() {
        manager?.connection.stopVPNTunnel()
    }

    /// Re-runs WARP registration — the "New account" action.
    func regenerateIdentity() async {
        do {
            _ = try await WarpProvisioner.register()
            BeeLog.write("identity regenerated")
        } catch {
            let message = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            phase = .failed(message)
            BeeLog.write("identity regeneration failed: \(message)")
        }
    }

    /// Drops the stored identity and the manual paste — the next connect
    /// registers a fresh identity. Android's "Reset identity".
    func resetIdentity() {
        WarpProvisioner.purgeIdentity()
        settings.lastGoodEndpoint = ""
        settings.resetAutoScanDone()
        BeeLog.write("identity reset — next connect registers fresh")
    }

    // ------------------------------------------------------------ status

    private func waitForStatus(_ target: NEVPNStatus, seconds: TimeInterval) async -> Bool {
        let deadline = Date().addingTimeInterval(seconds)
        while Date() < deadline {
            if let manager, manager.connection.status == target { return true }
            if let manager, manager.connection.status == .disconnected || manager.connection.status == .invalid {
                return false
            }
            try? await Task.sleep(nanoseconds: 300_000_000)
        }
        return manager?.connection.status == target
    }

    private func settleDisconnect() async {
        disconnect()
        _ = await waitForStatus(.disconnected, seconds: 6)
    }

    private func observeStatus(_ manager: NETunnelProviderManager) {
        guard !observing else { return }
        observing = true
        NotificationCenter.default.addObserver(
            forName: .NEVPNStatusDidChange, object: manager.connection, queue: .main
        ) { [weak self] _ in
            Task { [weak self] in
                await self?.handleStatusChange(of: manager)
            }
        }
    }

    private func handleStatusChange(of manager: NETunnelProviderManager) async {
        status = manager.connection.status
        syncPhaseFromStatus()
        // Auto reconnect — Android's PROTECTION toggle. Reconnects only while
        // the app is alive in the foreground; iOS gives no background wakeup
        // for provider drops, so the honest scope is the open app.
        if status == .disconnected, settings.autoReconnect, !userDisconnected, !ladderRunning {
            let wasUp = connectDate != nil
            if wasUp {
                BeeLog.write("tunnel dropped — auto reconnect")
                connectDate = nil
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                await connectFromUI()
            }
        }
    }

    private func syncPhaseFromStatus() {
        switch status {
        case .connected:
            if case .connected = phase { return }
        case .connecting, .reasserting:
            if case .connecting = phase { return }
        case .disconnecting:
            if case .connecting = phase { return }
        case .disconnected:
            if case .connecting = phase {
                phase = .idle
            }
            if case .connected = phase { phase = .idle }
            if case .degraded = phase { phase = .idle }
        case .invalid:
            if case .connecting = phase { phase = .idle }
        default:
            break
        }
    }

    var statusText: String {
        switch phase {
        case .idle: return BeeStrings.t("Tap the dial to connect")
        case .connecting(let detail): return detail
        case .connected: return BeeStrings.t("Connected")
        case .degraded: return BeeStrings.t("Connection degraded")
        case .failed(let message): return message
        }
    }

    var statusDetail: String? {
        switch phase {
        case .degraded: return BeeStrings.t("Tunnel handshake succeeded but no traffic passes — try another protocol")
        default: return nil
        }
    }

    var isConnected: Bool {
        if case .connected = phase { return true }
        return false
    }
}
