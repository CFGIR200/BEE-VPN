package com.beevpn.win

import com.beevpn.win.engine.AetherEngine
import com.beevpn.win.engine.EngineProcess
import com.beevpn.win.warp.WarpIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/// The AETHER_* environment the desktop sends must be the environment the
/// phone sends (CoreConfig.env) — these tests pin every constant, because a
/// drifting value here is a silent behavioral fork from Android.
class AetherEngineTest {

    private fun identity(clientId: String = "AAA") = WarpIdentity(
        deviceId = "dev-1", apiToken = "tok-1",
        privateKey = "priv", peerPublicKey = "peer",
        addressV4 = "172.16.0.2/32", addressV6 = "2606:4700:110:8b57::/128",
        endpoint = "162.159.192.1:2408", clientIdB64 = clientId,
    )

    // ------------------------------------------------------------ env maps

    @Test
    fun `wg env mirrors CoreConfig env`() {
        val env = AetherEngine.connectEnv(AetherEngine.Protocol.WG, ipv6Available = false)
        assertEquals("wg", env["AETHER_PROTOCOL"])
        assertEquals("127.0.0.1:${AetherEngine.SOCKS_PORT}", env["AETHER_SOCKS"])
        assertEquals(AetherEngine.configPath().absolutePath, env["AETHER_CONFIG"])
        assertEquals("balanced", env["AETHER_SCAN"])
        assertEquals("v4", env["AETHER_IP"])
        assertEquals("balanced", env["AETHER_NOIZE"])
        // The Ptech scanner — the phone's default discovery surface.
        assertEquals("1", env["AETHER_PTECH_SCAN"])
        assertEquals(
            "162.159.192.0/24,162.159.193.0/24,162.159.195.0/24," +
                "188.114.96.0/24,188.114.97.0/24,188.114.98.0/24,188.114.99.0/24",
            env["AETHER_SCAN_CIDRS_V4"],
        )
        assertEquals("2606:4700:d0::/48,2606:4700:d1::/48", env["AETHER_SCAN_CIDRS_V6"])
        assertEquals("2408,500,1701,4500", env["AETHER_WG_PORTS"])
        assertEquals("1440", env["AETHER_WG_MTU"])
        // Registration-API ECH rides WireGuard connects; quick reconnect on.
        assertEquals(AetherEngine.EMBEDDED_API_ECH, env["AETHER_API_ECH"])
        assertEquals("1", env["AETHER_QUICK_RECONNECT"])
        assertEquals("1", env["AETHER_STATS"])
        // 60 s: the desktop heartbeat — the periodic “up/down/uptime” line is
        // the only stats surface a Windows report has (no FFI snapshot).
        assertEquals("60", env["AETHER_STATS_SECS"])
        assertFalse(env.containsKey("AETHER_GOOL_MODE"))
        assertFalse(env.containsKey("AETHER_MASQUE_HTTP2"))
    }

    @Test
    fun `socks-ready budget follows the android waitForSocksReadyBudget ladder`() {
        // The 110-seconds-everywhere budget was the reconnect loop: a cold
        // MASQUE start legitimately needs scan + registration > 110 s.
        assertEquals(110_000L, AetherEngine.socksReadyBudgetMs(AetherEngine.Protocol.WG))
        assertEquals(300_000L, AetherEngine.socksReadyBudgetMs(AetherEngine.Protocol.MASQUE))
        assertEquals(300_000L, AetherEngine.socksReadyBudgetMs(AetherEngine.Protocol.MIM))
        assertEquals(200_000L, AetherEngine.socksReadyBudgetMs(AetherEngine.Protocol.GOOL))
    }

    @Test
    fun `masque env is the H2 shape without wg scanner or noize`() {
        val env = AetherEngine.connectEnv(AetherEngine.Protocol.MASQUE, ipv6Available = true)
        assertEquals("masque", env["AETHER_PROTOCOL"])
        assertEquals("1", env["AETHER_MASQUE_HTTP2"])
        assertEquals("www.cloudflare.com", env["AETHER_MASQUE_SNI"])
        assertEquals("1304", env["AETHER_MASQUE_MTU"])
        assertEquals("v4", env["AETHER_IP"])
        // MASQUE strips every obfuscation, scanner and ECH variable.
        assertFalse(env.containsKey("AETHER_NOIZE"))
        assertFalse(env.containsKey("AETHER_PTECH_SCAN"))
        assertFalse(env.containsKey("AETHER_WG_PORTS"))
        assertFalse(env.containsKey("AETHER_API_ECH"))
        assertFalse(env.containsKey("AETHER_WG_MTU"))
    }

    @Test
    fun `gool sets classic mode and the WOW mtu on both legs`() {
        val env = AetherEngine.connectEnv(AetherEngine.Protocol.GOOL, ipv6Available = false)
        assertEquals("classic", env["AETHER_GOOL_MODE"])
        assertEquals("1280", env["AETHER_WG_MTU"])
        assertEquals("1280", env["AETHER_MASQUE_MTU"])
        assertEquals("2408,500,1701,4500", env["AETHER_WG_PORTS"])
        assertEquals(AetherEngine.EMBEDDED_API_ECH, env["AETHER_API_ECH"])
    }

    @Test
    fun `mim is masque family on v4 with no ech`() {
        val env = AetherEngine.connectEnv(AetherEngine.Protocol.MIM, ipv6Available = true)
        assertEquals("mim", env["AETHER_PROTOCOL"])
        assertEquals("v4", env["AETHER_IP"])
        assertEquals("1", env["AETHER_MASQUE_HTTP2"])
        assertFalse(env.containsKey("AETHER_NOIZE"))
        assertFalse(env.containsKey("AETHER_API_ECH"))
        assertFalse(env.containsKey("AETHER_GOOL_MODE"))
    }

    @Test
    fun `ipv6 capable machines scan both families on wg`() {
        val env = AetherEngine.connectEnv(AetherEngine.Protocol.WG, ipv6Available = true)
        assertEquals("both", env["AETHER_IP"])
        // …while the masque family stays hardwired to v4 (Android parity).
        val masque = AetherEngine.connectEnv(AetherEngine.Protocol.MASQUE, ipv6Available = true)
        assertEquals("v4", masque["AETHER_IP"])
    }

    @Test
    fun `register env is the minimal WarpIdentityFetcher stage C set`() {
        val env = AetherEngine.registerEnv(AetherEngine.Protocol.MASQUE)
        assertEquals("masque", env["AETHER_REGISTER"])
        assertEquals(AetherEngine.EMBEDDED_API_ECH, env["AETHER_API_ECH"])
        assertEquals(AetherEngine.configPath().absolutePath, env["AETHER_CONFIG"])
        // Nothing else: no tunnel-carrying keys, no scanner, no noize.
        assertEquals(4, env.size)
    }

    // ------------------------------------------------------ identity TOML

    @Test
    fun `wg identity TOML carries the Android relayToml field set`() {
        AetherEngine.writeWgIdentity(identity())
        val toml = AetherEngine.configPath().readText()
        listOf(
            "device_id = \"dev-1\"",
            "access_token = \"tok-1\"",
            "ipv4 = \"172.16.0.2/32\"",
            "ipv6 = \"2606:4700:110:8b57::/128\"",
            "wg_private_key = \"priv\"",
            "wg_peer_public_key = \"peer\"",
            // The reserved bytes — the field the whole 1.2.0 investigation
            // was about. Cloudflare drops handshakes without them.
            "client_id = \"AAA\"",
            "assigned_endpoint = \"162.159.192.1\"",
        ).forEach { assertTrue(toml.contains(it), "missing: $it") }
        assertTrue(AetherEngine.identityComplete(AetherEngine.configPath()))
    }

    @Test
    fun `masque completeness demands the cert pair`() {
        val f = File(AetherEngine.masquePath().absolutePath + ".test")
        try {
            f.writeText("device_id = \"d\"\nwg_private_key = \"p\"\nwg_peer_public_key = \"k\"\n")
            assertFalse(AetherEngine.identityComplete(f))
            f.writeText(f.readText() + "cert_pem = \"-----BEGIN CERTIFICATE-----\"\nkey_pem = \"-----BEGIN PRIVATE KEY-----\"\n")
            assertTrue(AetherEngine.identityComplete(f))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `manual identity keeps the pinned endpoint and loses the token`() {
        AetherEngine.writeManualIdentity(identity(), "1.2.3.4:500")
        val toml = AetherEngine.configPath().readText()
        assertTrue(toml.contains("device_id = \"manual\""))
        assertTrue(toml.contains("access_token = \"\""))
        assertTrue(toml.contains("assigned_endpoint = \"1.2.3.4\""))
        EngineProcess.logLine("")   // touch the log helper once for coverage
    }
}
