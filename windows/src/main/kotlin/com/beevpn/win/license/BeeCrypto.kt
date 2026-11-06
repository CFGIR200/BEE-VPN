package com.beevpn.win.license

import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/// Ed25519 verification of BEE-VPN entitlement tokens — the exact same
/// `v1.<payloadB64url>.<sigB64url>` format LicenseManager.kt (Android) and
/// BeeCrypto.swift (iOS) verify, signed by license-worker.js.
///
/// The JVM has Ed25519 built in since Java 15 (java.security), so — like
/// CryptoKit on iOS — there is no crypto dependency at all.
object BeeCrypto {

    class Entitlement(
        val subject: String,   // s — license code or account username
        val kind: String,      // k — "code" | "account"
        val plan: String,      // p — trial | m1 | m3 | m6 | m12
        val installId: String, // i — device binding
        val expiry: Long,      // e — epoch seconds (grace period IS the subscription)
        val issuedAt: Long,    // t
    )

    /// RFC 4648 base64url decode with restored padding.
    fun b64urlDecode(input: String): ByteArray? = try {
        var s = input.replace('-', '+').replace('_', '/')
        while (s.length % 4 != 0) s += "="
        Base64.getDecoder().decode(s)
    } catch (_: Exception) { null }

    /// Raw 32-byte Ed25519 public key → X.509 SubjectPublicKeyInfo DER,
    /// which java.security's KeyFactory demands.
    private fun spkiFromRaw(raw: ByteArray): ByteArray {
        val prefix = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
        )
        return prefix + raw
    }

    /// Verifies the token signature, the device binding and the clock — the
    /// identical check the Android app performs. Returns null when the token
    /// must not be trusted (wrong key, foreign device, expired).
    fun verifyToken(
        token: String,
        pubKeyB64: String,
        installId: String,
        now: Long = System.currentTimeMillis() / 1000L,
    ): Entitlement? {
        val parts = token.split(".")
        if (parts.size != 3 || parts[0] != "v1") return null
        val payload = b64urlDecode(parts[1]) ?: return null
        val signature = b64urlDecode(parts[2]) ?: return null
        if (pubKeyB64.isEmpty()) return null
        val pubData = b64urlDecode(pubKeyB64) ?: return null
        val verified: Boolean = try {
            val pub = KeyFactory.getInstance("Ed25519")
                .generatePublic(X509EncodedKeySpec(spkiFromRaw(pubData)))
            val sig = Signature.getInstance("Ed25519")
            sig.initVerify(pub)
            sig.update(payload)
            sig.verify(signature)
        } catch (_: Exception) { false }
        if (!verified) return null

        val obj = try { JSONObject(String(payload, Charsets.UTF_8)) } catch (_: Exception) { return null }
        val subject = obj.optString("s", "")
        val kind = obj.optString("k", "")
        val plan = obj.optString("p", "")
        val install = obj.optString("i", "")
        val expiry = obj.optLong("e", 0L)
        val issued = obj.optLong("t", 0L)
        if (subject.isEmpty() || kind.isEmpty() || plan.isEmpty()) return null
        if (install != installId) return null   // bound to exactly one device
        if (expiry <= now) return null          // offline grace is the subscription itself
        return Entitlement(subject, kind, plan, install, expiry, issued)
    }
}
