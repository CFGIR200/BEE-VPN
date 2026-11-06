import Foundation
import CryptoKit

/// Ed25519 verification of BEE-VPN entitlement tokens — the exact same
/// `v1.<payloadB64url>.<sigB64url>` format the Android app verifies, signed by
/// the license Worker (see license/license-worker.js). CryptoKit's
/// Curve25519.Signing *is* Ed25519, so verification is native — no crypto
/// library dependency.
enum BeeCrypto {

    struct Entitlement {
        let subject: String      // s — license code or account username
        let kind: String         // k — "code" | "account"
        let plan: String         // p — trial | m1 | m3 | m6 | m12
        let installId: String    // i — device binding
        let expiry: Int64        // e — epoch seconds (grace period IS the subscription)
        let issuedAt: Int64      // t
    }

    /// RFC 4648 base64url decode with restored padding.
    static func b64urlDecode(_ input: String) -> Data? {
        var s = input
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while s.count % 4 != 0 { s += "=" }
        return Data(base64Encoded: s)
    }

    /// Verifies the token signature, the device binding and the clock — the
    /// identical check LicenseManager.kt performs. Returns nil when the token
    /// must not be trusted (wrong key, foreign device, expired).
    static func verifyToken(_ token: String,
                            pubKeyB64: String,
                            installId: String,
                            now: Int64 = Int64(Date().timeIntervalSince1970)) -> Entitlement? {
        let parts = token.split(separator: ".", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 3, parts[0] == "v1" else { return nil }
        guard let payload = b64urlDecode(parts[1]), let signature = b64urlDecode(parts[2]) else { return nil }
        guard !pubKeyB64.isEmpty, let pubData = b64urlDecode(pubKeyB64),
              let publicKey = try? Curve25519.Signing.PublicKey(rawRepresentation: pubData) else { return nil }
        guard publicKey.isValidSignature(signature, for: payload) else { return nil }
        guard let obj = (try? JSONSerialization.jsonObject(with: payload)) as? [String: Any] else { return nil }
        guard let subject = obj["s"] as? String,
              let kind = obj["k"] as? String,
              let plan = obj["p"] as? String,
              let install = obj["i"] as? String,
              let expiry = (obj["e"] as? NSNumber)?.int64Value,
              let issued = (obj["t"] as? NSNumber)?.int64Value else { return nil }
        guard install == installId else { return nil }   // bound to exactly one device
        guard expiry > now else { return nil }           // offline grace is the subscription itself
        return Entitlement(subject: subject, kind: kind, plan: plan,
                           installId: install, expiry: expiry, issuedAt: issued)
    }
}
