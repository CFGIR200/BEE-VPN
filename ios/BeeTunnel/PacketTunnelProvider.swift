import Foundation
import NetworkExtension
import WireGuardKit
import os

/// The packet-tunnel extension: feeds the wg-quick config the app saved into
/// WireGuardKit's Go-backed adapter and installs the network settings.
///
/// The Go bridge (libwg-go.a) is built from WireGuardKitGo by
/// Scripts/… on CI (see ios/README-FA.md) and linked through
/// LIBRARY_SEARCH_PATHS in project.yml.
class PacketTunnelProvider: NEPacketTunnelProvider {

    private static let log = OSLog(subsystem: "app.beevpn.tunnel", category: "wireguard")

    private lazy var adapter = WireGuardAdapter(with: self, logHandler: { level, message in
        let levelName = (level == .verbose) ? "debug" : "error"
        os_log("[%{public}@] %{public}@", log: PacketTunnelProvider.log, type: .default, levelName, message)
    })

    private enum ProviderError: Error {
        case couldNotDetermineWireGuardConfiguration
    }

    override func startTunnel(options: [String: NSObject]?,
                              completionHandler: @escaping (Error?) -> Void) {
        guard let protocolConfiguration = protocolConfiguration as? NETunnelProviderProtocol,
              let providerConfiguration = protocolConfiguration.providerConfiguration,
              let wgQuickConfig = providerConfiguration["WgQuickConfig"] as? String,
              !wgQuickConfig.isEmpty else {
            os_log("no WgQuickConfig in provider configuration", log: Self.log, type: .error)
            completionHandler(ProviderError.couldNotDetermineWireGuardConfiguration)
            return
        }

        let tunnelConfiguration: TunnelConfiguration
        do {
            tunnelConfiguration = try TunnelConfiguration(fromWgQuickConfig: wgQuickConfig)
        } catch {
            os_log("invalid WireGuard configuration: %{public}@", log: Self.log, type: .error, "\(error)")
            completionHandler(error)
            return
        }

        let settings = Self.networkSettings(from: providerConfiguration)
        setTunnelNetworkSettings(settings) { [weak self] error in
            guard let self else { return }
            if let error {
                os_log("setTunnelNetworkSettings failed: %{public}@", log: Self.log, type: .error, "\(error)")
                completionHandler(error)
                return
            }
            self.adapter.start(tunnelConfiguration: tunnelConfiguration) { adapterError in
                if let adapterError {
                    os_log("adapter start failed: %{public}@", log: Self.log, type: .error, "\(adapterError)")
                }
                completionHandler(adapterError)
            }
        }
    }

    override func stopTunnel(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {
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

    // ------------------------------------------------------------- settings

    /// Builds the packet-tunnel network settings from plain strings the app
    /// stashed in providerConfiguration ("AddressV4", "AddressV6", "DNS",
    /// "MTU", "Endpoint") — no dependency on TunnelConfiguration accessors.
    static func networkSettings(from providerConfiguration: [String: Any]) -> NEPacketTunnelNetworkSettings {
        let endpoint = providerConfiguration["Endpoint"] as? String ?? WarpEndpoint.defaultEndpoint
        let host = endpoint.split(separator: ":").dropLast().joined(separator: ":")
        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: host.isEmpty ? "162.159.192.1" : String(host))

        let v4 = (providerConfiguration["AddressV4"] as? String) ?? ""
        let v6 = (providerConfiguration["AddressV6"] as? String) ?? ""

        let v4Pairs = v4.split(separator: ",").map(String.init).map(cidrParse)
        let v4Valid = v4Pairs.filter { $0 != nil }.map { $0! }
        if !v4Valid.isEmpty {
            let ipv4 = NEIPv4Settings(
                addresses: v4Valid.map { $0.address },
                subnetMasks: v4Valid.map { Self.ipv4SubnetMask($0.prefix) })
            ipv4.includedRoutes = [NEIPv4Route(destinationAddress: "0.0.0.0", subnetMask: "0.0.0.0")]
            settings.ipv4Settings = ipv4
        }

        let v6Pairs = v6.split(separator: ",").map(String.init).map(cidrParse)
        let v6Valid = v6Pairs.filter { $0 != nil }.map { $0! }
        if !v6Valid.isEmpty {
            let ipv6 = NEIPv6Settings(
                addresses: v6Valid.map { $0.address },
                networkPrefixLengths: v6Valid.map { NSNumber(value: $0.prefix) })
            ipv6.includedRoutes = [NEIPv6Route(destinationAddress: "::", networkPrefixLength: 0)]
            settings.ipv6Settings = ipv6
        }

        let dns = ((providerConfiguration["DNS"] as? String) ?? "1.1.1.1,1.0.0.1")
            .split(separator: ",")
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        if !dns.isEmpty {
            settings.dnsSettings = NEDNSSettings(servers: dns)
        }

        let mtu = Int(providerConfiguration["MTU"] as? String ?? "1280") ?? 1280
        settings.mtu = NSNumber(value: mtu > 0 ? mtu : 1280)
        return settings
    }

    /// "172.16.0.2/32" → ("172.16.0.2", 32)
    private static func cidrParse(_ cidr: String) -> (address: String, prefix: UInt8)? {
        let parts = cidr.split(separator: "/")
        guard let address = parts.first, !address.isEmpty else { return nil }
        let prefix = parts.count > 1 ? UInt8(parts[1]) : 0
        return (String(address), prefix ?? 0)
    }

    /// 32 → "255.255.255.255", 24 → "255.255.255.0"
    private static func ipv4SubnetMask(_ prefix: UInt8) -> String {
        let p = min(prefix, 32)
        let mask: UInt32 = p == 0 ? 0 : (~UInt32(0) << (32 - UInt32(p)))
        return [24, 16, 8, 0].map { String((mask >> $0) & 0xFF) }.joined(separator: ".")
    }
}

/// Endpoint defaults shared with the app process (kept literal here so the
/// extension never depends on app-side types).
private enum WarpEndpoint {
    static let defaultEndpoint = "engage.cloudflareclient.com:2408"
}
