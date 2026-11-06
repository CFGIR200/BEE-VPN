import Foundation
import CryptoKit

/// One registered WARP identity — the same shape IdentityProvisioner.kt
/// persists on Android: a device-generated X25519 keypair enrolled at
/// api.cloudflareclient.com, plus the WireGuard peer data from the response.
struct WarpIdentity: Codable {
    var deviceId: String        // "id" in the registration response
    var apiToken: String        // "token" — bearer for future account edits
    var privateKey: String      // X25519 raw key, standard base64 (wg-quick form)
    var peerPublicKey: String   // peers[0].public_key
    var addressV4: String       // interface.addresses.v4 (e.g. 172.16.0.2)
    var addressV6: String       // interface.addresses.v6
    var endpoint: String        // WireGuard UDP endpoint host:port
}

/// Registers WARP identities and turns them into wg-quick configurations.
///
/// iOS v1 speaks to the registration API directly (the route most carriers
/// leave open). The Android app's extra escape hatches — the HMAC relay
/// Workers, SHARD nodes, ECH fronting — remain Android-side transports; here
/// the fallback for a hostile network is the manual wg-quick config in the
/// Advanced tab.
enum WarpProvisioner {

    static let registerURL = URL(string: "https://api.cloudflareclient.com/v0a4471/reg")!
    static let defaultEndpoint = "engage.cloudflareclient.com:2408"
    /// Wire ports the Ptech-quality WARP surfaces actually answer on — the
    /// same ladder CoreConfig.kt uses.
    static let portLadder = [2408, 500, 1701, 4500]

    static let keychainKey = "warp.identity"
    static let manualKey = "warp.manualConfig"

    static func saved() -> WarpIdentity? {
        guard let data = KeychainStore.data(forKey: keychainKey) else { return nil }
        return try? JSONDecoder().decode(WarpIdentity.self, from: data)
    }

    static func save(_ identity: WarpIdentity) {
        if let data = try? JSONEncoder().encode(identity) {
            KeychainStore.setData(data, forKey: keychainKey)
        }
    }

    static func purge() {
        KeychainStore.remove(keychainKey)
        KeychainStore.remove(manualKey)
    }

    /// Registers a fresh identity. Throws a Persian, buyer-readable error on
    /// failure — the message ends up on the Home screen, not in a log.
    static func register() async throws -> WarpIdentity {
        let key = Curve25519.KeyAgreement.PrivateKey()
        let publicKeyB64 = key.publicKey.rawRepresentation.base64EncodedString()

        let body: [String: Any] = [
            "install_id": "",
            "fcm_token": "",
            "tos": nowISO(),
            "model": deviceModel(),
            "serial_number": "unknown",
            "locale": Locale.current.identifier,
            "key_type": "curve25519",
            "tunnel_type": "wireguard",
        ]

        var request = URLRequest(url: registerURL)
        request.httpMethod = "POST"
        request.timeoutInterval = 20
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        // The exact headers the Android registration uses — the API is picky.
        request.setValue("okhttp/3.12.1", forHTTPHeaderField: "User-Agent")
        request.setValue("a-6.41-2158", forHTTPHeaderField: "CF-Client-Version")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await URLSession.shared.data(for: request)
        } catch {
            throw ProvisionError.network("ثبت‌نام WARP ممکن نشد — اپراتور مسیر api.cloudflareclient.com را می‌بندد؟ (\(error.localizedDescription))")
        }
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            let code = (response as? HTTPURLResponse)?.statusCode ?? -1
            throw ProvisionError.network("سرور WARP کد \(code) برگرداند — کمی بعد یا با تنظیم دستی تلاش کن")
        }
        guard let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let deviceId = obj["id"] as? String,
              let apiToken = obj["token"] as? String,
              let config = obj["config"] as? [String: Any],
              let interface = config["interface"] as? [String: Any],
              let addresses = interface["addresses"] as? [String: Any],
              let v4 = addresses["v4"] as? String, !v4.isEmpty,
              let peers = config["peers"] as? [[String: Any]],
              let peer0 = peers.first,
              let peerKey = peer0["public_key"] as? String, !peerKey.isEmpty else {
            throw ProvisionError.network("پاسخ ثبت‌نام WARP ناقص بود — دوباره تلاش کن")
        }
        let v6 = (addresses["v6"] as? String) ?? ""
        let identity = WarpIdentity(
            deviceId: deviceId,
            apiToken: apiToken,
            privateKey: key.rawRepresentation.base64EncodedString(),
            peerPublicKey: peerKey,
            addressV4: v4,
            addressV6: v6,
            endpoint: defaultEndpoint,
        )
        save(identity)
        return identity
    }

    enum ProvisionError: LocalizedError {
        case network(String)
        var errorDescription: String? { switch self { case .network(let m): return m } }
    }

    /// Builds the wg-quick configuration the tunnel provider consumes.
    static func buildWGQuick(_ identity: WarpIdentity) -> String {
        func withPrefix(_ addr: String, prefix: String) -> String {
            addr.contains("/") ? addr : addr + prefix
        }
        var lines: [String] = [
            "[Interface]",
            "PrivateKey = \(identity.privateKey)",
        ]
        var addrs = withPrefix(identity.addressV4, prefix: "/32")
        if !identity.addressV6.isEmpty {
            addrs += ", " + withPrefix(identity.addressV6, prefix: "/128")
        }
        lines.append("Address = \(addrs)")
        lines.append("DNS = 1.1.1.1, 1.0.0.1")
        lines.append("MTU = 1280")
        lines.append("")
        lines.append("[Peer]")
        lines.append("PublicKey = \(identity.peerPublicKey)")
        lines.append("AllowedIPs = 0.0.0.0/0, ::/0")
        lines.append("Endpoint = \(identity.endpoint)")
        lines.append("PersistentKeepalive = 25")
        return lines.joined(separator: "\n")
    }

    /// The configuration the tunnel should run: a manual override wins, then
    /// the registered identity, and registration happens lazily on connect.
    static func manualConfig() -> String? {
        let m = KeychainStore.string(forKey: manualKey)
        return (m?.isEmpty == false) ? m : nil
    }

    static func setManualConfig(_ config: String?) {
        if let config, !config.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            KeychainStore.setString(config, forKey: manualKey)
        } else {
            KeychainStore.remove(manualKey)
        }
    }

    /// Resolves the wg-quick config, registering a WARP identity first when
    /// neither a manual config nor a saved identity exists. Returns
    /// (config, endpoint host:port for display).
    static func resolveConfig() async throws -> (config: String, endpoint: String) {
        if let manual = manualConfig() {
            return (manual, endpoint(ofManual: manual) ?? defaultEndpoint)
        }
        let identity: WarpIdentity
        if let saved = saved() {
            identity = saved
        } else {
            identity = try await register()
        }
        return (buildWGQuick(identity), identity.endpoint)
    }

    static func endpoint(ofManual config: String) -> String? {
        for line in config.split(separator: "\n") {
            let parts = line.split(separator: "=", maxSplits: 1)
            if parts.count == 2, parts[0].trimmingCharacters(in: .whitespaces) == "Endpoint" {
                return parts[1].trimmingCharacters(in: .whitespaces)
            }
        }
        return nil
    }

    // ------------------------------------------------------------ helpers

    private static func nowISO() -> String {
        let fmt = ISO8601DateFormatter()
        fmt.formatOptions = [.withInternetDateTime]
        return fmt.string(from: Date())
    }

    private static func deviceModel() -> String {
        var systemInfo = utsname()
        uname(&systemInfo)
        let mirror = Mirror(reflecting: systemInfo.machine)
        let model = mirror.children.reduce(into: "") { result, element in
            guard let value = element.value as? Int8, value != 0 else { return }
            result.append(Character(UnicodeScalar(UInt8(value))))
        }
        return model.isEmpty ? "iOS" : model
    }
}
