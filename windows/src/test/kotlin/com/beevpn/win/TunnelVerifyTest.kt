package com.beevpn.win

import com.beevpn.win.engine.EngineConfig
import com.beevpn.win.shard.ShardSubscription
import com.beevpn.win.tunnel.TunnelVerifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONArray

/// The tunnel-verification suite — the desktop twin of the phone's
/// "never trust the engine" contract. The route parser, the trace parser,
/// the reconnect ladder, and the IPv6-conditional TUN config are the exact
/// code paths that decide whether the buyer sees «متصل» or an honest error.
class TunnelVerifyTest {

    // ---------------------------------------------------- route table parse

    private val routeTable = """
        ===========================================================================
        Interface List
         12...aa bb cc dd ee ff ......Intel(R) Wi-Fi 6 AX201 160MHz
         45...zz yy xx ww vv ......BEE-VPN
        ===========================================================================
        ===========================================================================
        Active Routes:
        ===========================================================================
         Network Destination        Netmask          Gateway       Interface  Metric
                  0.0.0.0          0.0.0.0      192.168.1.1    192.168.1.100     25
                  0.0.0.0          0.0.0.0       172.19.0.1      172.19.0.1      1
                 127.0.0.0        255.0.0.0         On-link         127.0.0.1    331
                 192.168.1.0    255.255.255.0     On-link       192.168.1.100    281
        ===========================================================================
        Persistent Routes:
          None
    """.trimIndent()

    @Test
    fun `tun default route wins when xray installs it`() {
        assertTrue(TunnelVerifier.tunRouteWinsIn(routeTable, "172.19.0.1"))
    }

    @Test
    fun `physical only default route means the tun lost the metric race`() {
        val physicalOnly = """
             Network Destination        Netmask          Gateway       Interface  Metric
                      0.0.0.0          0.0.0.0      192.168.1.1    192.168.1.100     25
        """.trimIndent()
        assertFalse(TunnelVerifier.tunRouteWinsIn(physicalOnly, "172.19.0.1"))
    }

    @Test
    fun `empty route table is a miss not a crash`() {
        assertFalse(TunnelVerifier.tunRouteWinsIn("", "172.19.0.1"))
        assertFalse(TunnelVerifier.tunRouteWinsIn("garbage\n", "172.19.0.1"))
    }

    // -------------------------------------------------------- trace parse

    @Test
    fun `trace body parses the egress ip`() {
        val body = "fl=123\nh=www.cloudflare.com\nip=198.51.100.7\nts=1234\nwarp=off\n"
        assertEquals("198.51.100.7", TunnelVerifier.parseTraceIp(body))
    }

    @Test
    fun `trace body without ip is null`() {
        assertNull(TunnelVerifier.parseTraceIp("HTTP/1.1 500"))
        assertNull(TunnelVerifier.parseTraceIp(""))
    }

    // ------------------------------------------------- reconnect ladder

    @Test
    fun `reconnect ladder matches android 5-15-30-60-120 and clamps at 120`() {
        val ladder = TunnelVerifier.RECONNECT_BACKOFF_S
        assertEquals(intArrayOf(5, 15, 30, 60, 120).toList(), ladder.toList())
        val attempt = 9
        assertEquals(120, ladder[attempt.coerceAtMost(ladder.size - 1)])
    }

    // --------------------------------------- ipv6-conditional tun config

    @Test
    fun `tun config is ipv4-only when the system has no ipv6`() {
        val cfg = EngineConfig.build(useTun = true, ipv6Available = false, exitOutbound = null, fragmentOnDirect = false)
        val tun = cfg.getJSONArray("inbounds").getJSONObject(0).getJSONObject("settings")
        assertEquals(1, tun.getJSONArray("gateway").length())
        assertEquals("172.19.0.1/30", tun.getJSONArray("gateway").getString(0))
        assertEquals(listOf("0.0.0.0/0"), tun.getJSONArray("autoSystemRoutingTable").map { it as String })
        // the WFP DNS-leak block must always ride along on Windows
        assertEquals(listOf("dns"), tun.getJSONArray("autoSystemWfpBlockLeak").map { it as String })
    }

    @Test
    fun `tun config carries ipv6 when the system has it`() {
        val cfg = EngineConfig.build(useTun = true, ipv6Available = true, exitOutbound = null, fragmentOnDirect = false)
        val tun = cfg.getJSONArray("inbounds").getJSONObject(0).getJSONObject("settings")
        assertEquals(2, tun.getJSONArray("gateway").length())
        assertEquals("fdfe:dcba:9876::1/126", tun.getJSONArray("gateway").getString(1))
        assertEquals(listOf("0.0.0.0/0", "::/0"), tun.getJSONArray("autoSystemRoutingTable").map { it as String })
    }

    @Test
    fun `socks inbound keeps the android port 1819 with the tun present`() {
        val cfg = EngineConfig.build(useTun = true, ipv6Available = false, exitOutbound = null, fragmentOnDirect = false)
        val socks = cfg.getJSONArray("inbounds").getJSONObject(1)
        assertEquals(1819, socks.getInt("port"))
    }

    // ------------------------------------------------------ offline seed

    @Test
    fun `bundled seed yields a non-empty pool when github raw is blocked`() {
        val seed = ShardSubscription.seedProfiles()
        assertNotNull(seed)
        // The seed is a live snapshot of the subscription — at least one node
        // must parse out of it, or the offline fallback is decorative.
        assertTrue(seed.isNotEmpty(), "seed must parse to at least one profile")
    }

    // --------------------------------------------------- tun constants

    @Test
    fun `verifier and engine config agree on the tun interface ip`() {
        assertEquals(EngineConfig.TUN_INTERFACE_IP, TunnelVerifier.TUN_INTERFACE_IP)
        assertEquals("172.19.0.1", TunnelVerifier.TUN_INTERFACE_IP)
    }

    private fun <T> JSONArray.map(f: (Any) -> T): List<T> =
        (0 until length()).map { f(get(it)) }
}
