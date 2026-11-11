import Foundation
import NetworkExtension
import WireGuardKit
import os

/// The packet-tunnel extension: feeds the wg-quick config the app saved into
/// WireGuardKit's Go-backed adapter, which installs the complete network
/// settings derived from the parsed TunnelConfiguration (addresses, routes,
/// DNS, MTU) — the app-side routing overlays (bypass Iran / LAN / DNS / MTU /
/// endpoint) are already baked into the AllowedIPs and lines before they get
/// here, so the extension has no settings of its own to second-guess.
///
/// The Go bridge (libwg-go.a) is built from WireGuardKitGo by
/// Scripts/prepare-wireguard.sh on CI (see ios/README-FA.md) and linked
/// through LIBRARY_SEARCH_PATHS in project.yml.
class PacketTunnelProvider: NEPacketTunnelProvider {

    private static let log = OSLog(subsystem: "app.beevpn.tunnel", category: "wireguard")

    /// The shared container the Logs screen reads — the tunnel-side stream,
    /// the way Android's Logs screen carries the core stream.
    private static let groupIdentifier = "group.app.beevpn"
    private static let logFile = "tunnel.log"
    private static let logCapBytes = 256 * 1024

    private lazy var adapter = WireGuardAdapter(with: self, logHandler: { level, message in
        let levelName = (level == .verbose) ? "debug" : "error"
        os_log("[%{public}@] %{public}@", log: PacketTunnelProvider.log, type: .default, levelName, message)
        Self.appendLog("\(levelName): \(message)")
    })

    private enum ProviderError: Error {
        case couldNotDetermineWireGuardConfiguration
    }

    override func startTunnel(options: [String: NSObject]?,
                              completionHandler: @escaping (Error?) -> Void) {
        Self.appendLog("tunnel starting")
        guard let protocolConfiguration = protocolConfiguration as? NETunnelProviderProtocol,
              let providerConfiguration = protocolConfiguration.providerConfiguration,
              let wgQuickConfig = providerConfiguration["WgQuickConfig"] as? String,
              !wgQuickConfig.isEmpty else {
            os_log("no WgQuickConfig in provider configuration", log: Self.log, type: .error)
            Self.appendLog("failed: no WgQuickConfig in provider configuration")
            completionHandler(ProviderError.couldNotDetermineWireGuardConfiguration)
            return
        }

        let tunnelConfiguration: TunnelConfiguration
        do {
            tunnelConfiguration = try TunnelConfiguration(fromWgQuickConfig: wgQuickConfig)
        } catch {
            os_log("invalid WireGuard configuration: %{public}@", log: Self.log, type: .error, "\(error)")
            Self.appendLog("failed: invalid WireGuard configuration")
            completionHandler(error)
            return
        }

        adapter.start(tunnelConfiguration: tunnelConfiguration) { [weak self] adapterError in
            if let adapterError {
                os_log("adapter start failed: %{public}@", log: Self.log, type: .error, "\(adapterError)")
                Self.appendLog("adapter start failed: \(adapterError)")
            } else {
                Self.appendLog("adapter started — WireGuard data plane is up")
            }
            completionHandler(adapterError)
        }
    }

    override func stopTunnel(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {
        Self.appendLog("tunnel stopping (reason \(reason.rawValue))")
        adapter.stop { _ in }
        super.stopTunnel(with: reason, completionHandler: completionHandler)
    }

    override func handleAppMessage(_ messageData: Data, completionHandler: ((Data?) -> Void)?) {
        guard let message = String(data: messageData, encoding: .utf8), message == "ping" else {
            completionHandler?(nil)
            return
        }
        completionHandler?("pong".data(using: .utf8))
    }

    // ------------------------------------------------------------- logging

    /// Appends to the shared-container log with the same 256 KB rolling cap
    /// and token redaction rules Android's ConnectionLog keeps.
    private static func appendLog(_ line: String) {
        guard let dir = FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: groupIdentifier) else { return }
        let url = dir.appendingPathComponent(logFile)
        let stamp: String = {
            let fmt = DateFormatter()
            fmt.dateFormat = "HH:mm:ss"
            fmt.timeZone = TimeZone(identifier: "UTC")
            return fmt.string(from: Date())
        }()
        let stamped = "[\(stamp)] \(redact(line))\n"
        if let handle = try? FileHandle(forWritingTo: url) {
            defer { try? handle.close() }
            _ = try? handle.seekToEnd()
            _ = try? handle.write(contentsOf: Data(stamped.utf8))
        } else {
            try? Data(stamped.utf8).write(to: url)
        }
        trim(url: url)
    }

    /// License tokens (`v1.…`) never reach the log on disk — the same promise
    /// Android's LogRedactor makes.
    private static func redact(_ line: String) -> String {
        var out = line
        while let r = out.range(of: "v1\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+", options: .regularExpression) {
            out.replaceSubrange(r, with: "v1.[redacted]")
        }
        return out
    }

    private static func trim(url: URL) {
        guard let size = (try? FileManager.default.attributesOfItem(atPath: url.path))?[.size] as? Int,
              size > logCapBytes,
              let data = try? Data(contentsOf: url) else { return }
        let keep = data.suffix(logCapBytes / 2)
        if let nl = keep.firstIndex(of: 0x0A) {
            try? keep[(keep.index(after: nl))...].write(to: url)
        } else {
            try? keep.write(to: url)
        }
    }
}
