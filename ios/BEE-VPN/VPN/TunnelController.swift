import Foundation
import NetworkExtension
import Combine

/// Owns the NETunnelProviderManager for the BeeTunnel packet-tunnel
/// extension: saves the wg-quick config into the VPN profile, starts/stops
/// the tunnel and publishes the connection status to the UI.
@MainActor
final class TunnelController: ObservableObject {

    static let tunnelBundleId = "app.beevpn.tunnel"

    @Published var status: NEVPNStatus = .invalid
    @Published var lastError: String?

    private var manager: NETunnelProviderManager?
    private var observing = false

    init() {
        Task { await refresh() }
    }

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
        } catch {
            lastError = "خواندن پروفایل VPN ممکن نشد: \(error.localizedDescription)"
        }
    }

    /// Saves the configuration and starts the tunnel. The first call makes iOS
    /// show the system "add VPN configuration" prompt.
    func connect(wgConfig: String, endpoint: String) async {
        lastError = nil
        do {
            if manager == nil { await refresh() }
            guard let manager else {
                lastError = "پروفایل VPN ساخته نشد — اپ را دوباره باز کن"
                return
            }
            let protocolConfiguration = NETunnelProviderProtocol()
            protocolConfiguration.providerBundleIdentifier = Self.tunnelBundleId
            protocolConfiguration.serverAddress = endpoint
            protocolConfiguration.providerConfiguration = [
                "WgQuickConfig": wgConfig,
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
        } catch {
            lastError = "شروع تونل ناموفق بود: \(error.localizedDescription)"
        }
    }

    func disconnect() {
        manager?.connection.stopVPNTunnel()
    }

    /// Convenience for the Home button: resolve config (registering WARP on
    /// first use) then start.
    func connectOrProvision() async {
        lastError = nil
        do {
            let resolved = try await WarpProvisioner.resolveConfig()
            await connect(wgConfig: resolved.config, endpoint: resolved.endpoint)
        } catch {
            lastError = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }

    /// Re-runs WARP registration (the "new identity" action in Advanced).
    func regenerateIdentity() async {
        do {
            _ = try await WarpProvisioner.register()
        } catch {
            lastError = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }

    private func observeStatus(_ manager: NETunnelProviderManager) {
        guard !observing else { return }
        observing = true
        NotificationCenter.default.addObserver(
            forName: .NEVPNStatusDidChange, object: manager.connection, queue: .main
        ) { [weak self] _ in
            Task { [weak self] in
                await self?.publishStatus(of: manager)
            }
        }
    }

    @MainActor
    private func publishStatus(of manager: NETunnelProviderManager) {
        status = manager.connection.status
    }

    var statusText: String {
        switch status {
        case .connected: return "متصل"
        case .connecting: return "در حال اتصال…"
        case .disconnecting: return "در حال قطع…"
        case .disconnected: return "قطع شده"
        case .invalid: return "تنظیم نشده"
        case .reasserting: return "در حال بازپیکربندی…"
        default: return "—"
        }
    }

    var isConnected: Bool { status == .connected }
}
