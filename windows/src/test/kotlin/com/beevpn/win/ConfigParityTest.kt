package com.beevpn.win

import com.beevpn.win.engine.EngineConfig
import com.beevpn.win.shard.ShardProfile
import com.beevpn.win.shard.ShardType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/// The live-config parity pins: VLESS mux, the SHARD policy, and the aether
/// bridge shape (xray TUN → the core's SOCKS 1819).
class ConfigParityTest {

    private fun vlessNode() = ShardProfile(
        configType = ShardType.VLESS,
        server = "1.2.3.4", serverPort = "443",
        password = "uuid", method = "none",
        network = "ws", host = "cdn.example.com", path = "/ws",
        security = "tls", sni = "cdn.example.com",
    )

    private fun trojanNode() = ShardProfile(
        configType = ShardType.TROJAN,
        server = "1.2.3.4", serverPort = "443",
        password = "pw", network = "tcp", security = "tls",
    )

    @Test
    fun `vless exit carries the android mux`() {
        val exit = EngineConfig.nodeExit(vlessNode())
        val mux = exit.getJSONObject("mux")
        assertEquals(true, mux.getBoolean("enabled"))
        assertEquals(8, mux.getInt("concurrency"))
        assertEquals(16, mux.getInt("xudpConcurrency"))
        assertEquals("skip", mux.getString("xudpProxyUDP443"))
    }

    @Test
    fun `trojan exit never carries mux - it breaks trojan on the phone too`() {
        val exit = EngineConfig.nodeExit(trojanNode())
        assertFalse(exit.has("mux"))
    }

    @Test
    fun `shard parity adds the live policy with connIdle 12 on level 1`() {
        val cfg = EngineConfig.build(
            useTun = true, ipv6Available = false,
            exitOutbound = EngineConfig.nodeExit(vlessNode()),
            fragmentOnDirect = false, shardParity = true,
        )
        val levels = cfg.getJSONObject("policy").getJSONObject("levels")
        assertEquals(0, levels.getJSONObject("0").getInt("uplinkOnly"))
        assertEquals(0, levels.getJSONObject("0").getInt("downlinkOnly"))
        assertEquals(12, levels.getJSONObject("1").getInt("connIdle"))
    }

    @Test
    fun `without shardParity no policy is emitted`() {
        val cfg = EngineConfig.build(
            useTun = true, ipv6Available = false,
            exitOutbound = null, fragmentOnDirect = false,
        )
        assertFalse(cfg.has("policy"))
    }

    @Test
    fun `aether bridge is a udp-enabled socks hop into the core`() {
        val exit = EngineConfig.aetherBridgeExit()
        assertEquals("socks", exit.getString("protocol"))
        val settings = exit.getJSONObject("settings")
        assertEquals("127.0.0.1", settings.getString("address"))
        assertEquals(1819, settings.getInt("port"))
        assertEquals(true, settings.getBoolean("udp"))
    }

    @Test
    fun `aether bridge config has no xray socks inbound - the core owns 1819`() {
        val cfg = EngineConfig.build(
            useTun = true, ipv6Available = false,
            exitOutbound = EngineConfig.aetherBridgeExit(),
            fragmentOnDirect = false,
            includeSocksIn = false,
        )
        val inbounds = cfg.getJSONArray("inbounds")
        assertEquals(1, inbounds.length())
        assertEquals("tun-in", inbounds.getJSONObject(0).getString("tag"))
        // The TUN's only exit is the bridge hop; private CIDRs stay direct.
        val outbounds = cfg.getJSONArray("outbounds")
        assertEquals("bee-exit", outbounds.getJSONObject(0).getString("tag"))
        val direct = outbounds.getJSONObject(1)
        assertEquals("direct", direct.getString("tag"))
        assertEquals("freedom", direct.getString("protocol"))
    }
}
