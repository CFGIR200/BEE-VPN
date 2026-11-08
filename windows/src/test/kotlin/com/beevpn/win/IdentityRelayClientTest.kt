package com.beevpn.win

import com.beevpn.win.engine.AetherEngine
import com.beevpn.win.warp.IdentityRelayClient
import com.beevpn.win.warp.PinnedHttp
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

private fun b64(bytes: Int, fill: (Int) -> Byte = { it.toByte() }) =
    java.util.Base64.getEncoder().encodeToString(ByteArray(bytes) { fill(it) })

/// The phone's identity ladder, desktop edition — the enrollment flow, the
/// TOML it stores, and the atomic install it rides. These pin the exact wire
/// behavior a fresh install on a filtered carrier depends on: a registration
/// carries the X25519 "key", warp_enabled is PATCHed, MASQUE carries a real
/// secp256r1 cert pair, and a failed install rolls back.
class IdentityRelayClientTest {

    /// A scripted account API — the same surface the relay Workers, the direct
    /// API and the SHARD SOCKS path all speak through ApiCall.
    private class FakeApi : IdentityRelayClient.ApiCall {
        val calls = mutableListOf<Triple<String, String, JSONObject>>()
        var warpEnabled = true

        override fun call(method: String, path: String, body: String, token: String?): JSONObject {
            calls += Triple(method, path, JSONObject(body))
            when {
                method == "POST" && path == IdentityRelayClient.REGISTER_PATH -> {
                    assertTrue(body.contains("\"key\""), "the registration must carry the client's public key")
                    return JSONObject()
                        .put("id", "dev-abc123")
                        .put("token", "tok-1")
                        .put("warp_enabled", warpEnabled)
                        .put(
                            "config", JSONObject()
                                .put("client_id", b64(3))
                                .put(
                                    "interface", JSONObject()
                                        .put("addresses", JSONObject().put("v4", "172.16.0.2").put("v6", "2606:4700:110::/128")),
                                )
                                .put(
                                    "peers", JSONArray().put(
                                        JSONObject()
                                            .put("public_key", b64(32))
                                            .put("endpoint", JSONObject().put("v4", "162.159.192.1:2408")),
                                    ),
                                )
                                .put("services", JSONObject().put("http_proxy", "")),
                        )
                        .put("account", JSONObject().put("organization", ""))
                }
                method == "PATCH" && JSONObject(body).has("warp_enabled") -> {
                    assertTrue(token != null, "the warp_enabled PATCH must be authenticated")
                    return JSONObject().put("warp_enabled", true)
                }
                method == "PATCH" -> {
                    // The MASQUE key PATCH: key + key_type secp256r1.
                    assertEquals("secp256r1", JSONObject(body).optString("key_type"))
                    return JSONObject(body)
                }
                else -> error("unexpected $method $path")
            }
        }
    }

    @Test
    fun `enrollment on any transport POSTs the key and PATCHes warp_enabled when missing`() {
        val api = FakeApi()
        val hop = IdentityRelayClient.enrollVia(api, masque = false)
        assertEquals("POST", api.calls[0].first)
        if (!api.warpEnabled) assertEquals("PATCH", api.calls[1].first)
        // The private key comes back as raw 32 bytes — the wg-quick form.
        assertEquals(32, hop.wgPrivate.size)
        assertTrue(hop.cert.isBlank() && hop.issued == 0L)
    }

    @Test
    fun `masque enrollment generates a real secp256r1 certificate pair`() {
        val api = FakeApi()
        val hop = IdentityRelayClient.enrollVia(api, masque = true)
        assertTrue(hop.cert.contains("BEGIN CERTIFICATE"))
        assertTrue(hop.key.contains("BEGIN PRIVATE KEY"))
        assertTrue(hop.issued > 0L)
        // The certificate is self-signed for CN=<deviceId> and parses.
        val cf = java.security.cert.CertificateFactory.getInstance("X.509")
        val cert = cf.generateCertificate(hop.cert.byteInputStream()) as java.security.cert.X509Certificate
        assertEquals("CN=dev-abc123", cert.subjectX500Principal.name)
        assertEquals("EC", cert.publicKey.algorithm)
    }

    @Test
    fun `relayToml stores the exact field set the core reads`() {
        val api = FakeApi()
        val hop = IdentityRelayClient.enrollVia(api, masque = true)
        val text = IdentityRelayClient.relayToml(hop)
            ?: error("a complete enrollment must serialize")
        val file = File(AetherEngine.masquePath().absolutePath + ".test")
        try {
            file.writeText(text)
            // The same completeness rule the connect path enforces.
            assertTrue(AetherEngine.identityComplete(file), "the stored identity must be connectable")
            assertTrue(text.contains("assigned_endpoint = \"162.159.192.1\""))
            assertTrue(Regex("client_id = \"[A-Za-z0-9+/]{4}\"").containsMatchIn(text))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `relayToml refuses a response without the fields the tunnel needs`() {
        val hop = IdentityRelayClient.Hop(
            response = JSONObject().put("id", "dev").put("token", "t"), // no config
            wgPrivate = ByteArray(32), cert = "", key = "", issued = 0,
        )
        assertEquals(null, IdentityRelayClient.relayToml(hop))
    }

    @Test
    fun `installRelayedFiles rolls back on a broken staged identity`() {
        val target = File(AetherEngine.configPath().absolutePath + ".relaytest")
        val target2 = File(AetherEngine.secondaryPath().absolutePath + ".relaytest")
        try {
            target.writeText("device_id = \"old\"\n")
            // The second file is invalid → the whole install must fail and the
            // first file must come back byte for byte.
            val good = File.createTempFile("good", ".toml").also {
                it.writeText("device_id = \"d\"\naccess_token = \"t\"\nipv4 = \"172.16.0.2\"\n" +
                    "wg_private_key = \"" + b64(32) + "\"\nwg_peer_public_key = \"" + b64(32) + "\"\n")
            }
            val broken = File.createTempFile("broken", ".toml").also { it.writeText("garbage") }
            val ok = IdentityRelayClient.installRelayedFiles(
                listOf(target to good.readText(), target2 to broken.readText()),
            )
            assertFalse(ok)
            assertEquals("device_id = \"old\"", target.readText().trim())
            assertFalse(target2.exists())
            good.delete(); broken.delete()
        } finally {
            target.delete(); target2.delete()
        }
    }

    @Test
    fun `installRelayedFiles swaps atomically when every identity is valid`() {
        val target = File(AetherEngine.configPath().absolutePath + ".relaytest")
        try {
            target.writeText("device_id = \"old\"\n")
            val good = "device_id = \"d\"\naccess_token = \"t\"\nipv4 = \"172.16.0.2\"\n" +
                "wg_private_key = \"" + b64(32) + "\"\nwg_peer_public_key = \"" + b64(32) + "\"\n"
            assertTrue(IdentityRelayClient.installRelayedFiles(listOf(target to good)))
            assertEquals("d", Regex("device_id = \"(\\w+)\"").find(target.readText())!!.groupValues[1])
        } finally {
            target.delete()
        }
    }

    // ------------------------------------------------- the pinned-edge ladder

    /// The phone's `apiRoutes()` — both hostnames, then the two pinned
    /// Cloudflare edge IPs with the API's name kept for SNI, the HTTP Host
    /// and the certificate check. This ladder IS the rescue on a carrier
    /// that poisons the API's names: the 1.6.1 desktop shipped without the
    /// edges and reported «هویت MASQUE ساخته نشد» from networks the phone
    /// registers from.
    @Test
    fun `direct route ladder is the phone's four routes with the edge host rewrite`() {
        val routes = IdentityRelayClient.API_ROUTES
        assertEquals(4, routes.size)
        assertEquals("api.devices.cloudflare.com", routes[0].connectAddress)
        assertEquals("api.devices.cloudflare.com", routes[0].host)
        assertEquals("api.cloudflareclient.com", routes[1].connectAddress)
        assertEquals("api.cloudflareclient.com", routes[1].host)
        assertEquals("104.24.4.6", routes[2].connectAddress)
        assertEquals("api.devices.cloudflare.com", routes[2].host)
        assertEquals("104.18.0.6", routes[3].connectAddress)
        assertEquals("api.devices.cloudflare.com", routes[3].host)
    }

    @Test
    fun `request bytes carry the exact header set the API is picky about`() {
        val raw = PinnedHttp.requestBytes(
            "api.devices.cloudflare.com", "POST", "/v0a4471/reg",
            "{\"key\":\"k\"}", token = "tok-1",
        )
        val text = String(raw, Charsets.UTF_8)
        assertTrue(text.startsWith("POST /v0a4471/reg HTTP/1.1\r\n"))
        assertTrue(text.contains("Host: api.devices.cloudflare.com\r\n"))
        assertTrue(text.contains("Content-Type: application/json; charset=UTF-8\r\n"))
        assertTrue(text.contains("Accept: application/json\r\n"))
        assertTrue(text.contains("User-Agent: WARP for Android\r\n"))
        assertTrue(text.contains("CF-Client-Version: a-6.35-4471\r\n"))
        assertTrue(text.contains("Authorization: Bearer tok-1\r\n"))
        assertTrue(text.contains("Content-Length: 11\r\n"))
        assertTrue(text.contains("Connection: close\r\n"))
        assertTrue(text.endsWith("\r\n\r\n{\"key\":\"k\"}"))
        // No token — no Authorization line at all (an anonymous registration).
        val anon = String(
            PinnedHttp.requestBytes("h", "POST", "/p", "{}", token = null),
            Charsets.UTF_8,
        )
        assertFalse(anon.contains("Authorization"))
    }

    @Test
    fun `response parser decodes content-length, chunked and to-EOF bodies`() {
        // Content-Length framing.
        val cl = PinnedHttp.parse(
            ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                "Content-Length: 14\r\n\r\n{\"id\":\"dev-1\"}xxx").toByteArray(Charsets.ISO_8859_1),
        )
        assertEquals(200, cl.status)
        assertEquals("{\"id\":\"dev-1\"}", cl.bodyText())
        assertEquals("application/json", cl.header("content-type"))
        // Chunked framing — a size line with extensions, two chunks, terminator.
        val chunked = PinnedHttp.parse(
            ("HTTP/1.1 201 Created\r\nTransfer-Encoding: chunked\r\n\r\n" +
                "5;ext=1\r\n{\"id\"\r\n7\r\n:\"dev\"}\r\n0\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
        )
        assertEquals(201, chunked.status)
        assertEquals("{\"id\":\"dev\"}", chunked.bodyText())
        // To-EOF framing (Connection: close, no length at all).
        val eof = PinnedHttp.parse("HTTP/1.1 400 Bad Request\r\n\r\nno json here".toByteArray(Charsets.ISO_8859_1))
        assertEquals(400, eof.status)
        assertEquals("no json here", eof.bodyText())
    }

    @Test
    fun `response parser rejects a carrier block page masquerading as HTTP`() {
        val garbage = "<html><body>blocked by carrier</body></html>".toByteArray()
        org.junit.jupiter.api.assertThrows<java.io.IOException> { PinnedHttp.parse(garbage) }
        // A headerless non-HTTP blob must never parse as a status either.
        org.junit.jupiter.api.assertThrows<java.io.IOException> { PinnedHttp.parse(ByteArray(0)) }
    }
}
