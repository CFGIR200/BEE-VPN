package com.beevpn.win

import com.beevpn.win.shard.ShardHealth
import com.beevpn.win.shard.ShardProfile
import com.beevpn.win.shard.ShardRace
import com.beevpn.win.shard.ShardType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/// Android's ShardHealth scoring and the race's selection order, pinned.
class ShardHealthRaceTest {

    /// The memory persists across tests (same file, same node keys) — start
    /// every test from a clean slate, exactly like a fresh install.
    @BeforeEach
    fun reset() {
        ShardHealth.clear()
    }

    private fun profile(
        server: String = "1.2.3.4",
        host: String? = "cdn.example.com",
        path: String? = "/ws",
        fm: String? = null,
        ech: String? = null,
        name: String = server,
    ) = ShardProfile(
        configType = ShardType.VLESS,
        server = server,
        serverPort = "443",
        password = "uuid-1",
        network = "ws",
        host = host,
        path = path,
        security = "tls",
        finalMask = fm,
        echConfigList = ech,
        name = name,
    )

    // --------------------------------------------------------- ShardHealth

    @Test
    fun `untried nodes sit in the middle of the rank`() {
        val untried = profile(name = "untried")
        assertEquals(500_000, ShardHealth.score(untried).rank())
    }

    @Test
    fun `two consecutive failures drop a node behind untried ones`() {
        val node = profile(name = "dead")
        ShardHealth.recordFailure(node)
        val afterOne = ShardHealth.score(node).rank()
        // One failure on an untried node leaves it untried-ranked (latency 0);
        // only the SECOND failure sinks it.
        assertEquals(500_000, afterOne)
        ShardHealth.recordFailure(node)
        val afterTwo = ShardHealth.score(node).rank()
        assertTrue(afterTwo >= 900_000, "two failures sink the node: $afterTwo")
        val untried = profile(server = "8.8.4.4", name = "fresh")
        assertTrue(afterTwo > ShardHealth.score(untried).rank())
    }

    @Test
    fun `latency past the 8s ceiling counts as a failure`() {
        val node = profile(name = "slow")
        ShardHealth.recordSuccess(node, ShardHealth.LATENCY_CEILING_MS + 1)
        assertEquals(1, ShardHealth.score(node).failures)
        assertEquals(0, ShardHealth.score(node).streak)
    }

    @Test
    fun `success streak buys exactly 500ms per rung up to five`() {
        val node = profile(name = "steady")
        repeat(3) { ShardHealth.recordSuccess(node, 2_000) }
        val score = ShardHealth.score(node)
        // smoothed: 2000, then (2000 + 2*2000)/3 twice with fresh recording…
        assertEquals(3, score.streak)
        assertEquals(0, score.failures)
        assertTrue(score.latencyMs <= 2_000)
        assertEquals(score.latencyMs - 3 * 500, score.rank())
    }

    @Test
    fun `smoothing blends the previous latency two-to-one toward the new`() {
        val node = profile(name = "blend")
        ShardHealth.recordSuccess(node, 1_000)
        ShardHealth.recordSuccess(node, 2_000)
        // second sample: (1000 + 2*2000)/3 = 1666
        assertEquals(1_666, ShardHealth.score(node).latencyMs)
    }

    @Test
    fun `a success resets failures and a failure resets the streak`() {
        val node = profile(name = "mixed")
        ShardHealth.recordFailure(node)
        ShardHealth.recordSuccess(node, 900)
        assertEquals(0, ShardHealth.score(node).failures)
        assertEquals(1, ShardHealth.score(node).streak)
        ShardHealth.recordFailure(node)
        assertEquals(0, ShardHealth.score(node).streak)
    }

    @Test
    fun `prune drops departed nodes and keeps live ones`() {
        val keep = profile(name = "keep")
        val drop = profile(server = "9.9.9.9", name = "drop")
        ShardHealth.recordSuccess(keep, 700)
        ShardHealth.recordSuccess(drop, 700)
        ShardHealth.prune(listOf(keep))
        assertTrue(ShardHealth.score(keep).everWorked)
        assertEquals(0, ShardHealth.score(drop).latencyMs)
    }

    // ----------------------------------------------------------- diversity

    @Test
    fun `diversify round-robins endpoint groups and puts fm variants first`() {
        val plainA = profile(host = "a.example.com", name = "a-plain")
        val fmA = profile(host = "a.example.com", fm = "{\"tcp\":[]}", name = "a-fm")
        val plainB = profile(host = "b.example.com", name = "b-plain")
        val ranked = listOf(plainA, fmA, plainB)
        val out = ShardRace.diversify(ranked)
        // Same endpoint (host+path+port) → fm first within the group.
        val aIdx = out.indexOfFirst { it.host == "a.example.com" }
        assertEquals("a-fm", out[aIdx].name)
        // Round-robin: endpoint B's variant appears before A's second variant.
        val order = out.map { it.name }
        assertTrue(order.indexOf("b-plain") < order.lastIndexOf("a-plain") || order.last() == "a-plain")
    }

    @Test
    fun `ech variants sort after fm inside a group`() {
        val echOnly = profile(host = "c.example.com", ech = "cloudflare-ech.com+udp://1.1.1.1", name = "c-ech")
        val fmAndEch = profile(host = "c.example.com", ech = "x", fm = "{\"tcp\":[]}", name = "c-fm")
        val out = ShardRace.diversify(listOf(echOnly, fmAndEch))
        assertEquals("c-fm", out[0].name)
        assertEquals("c-ech", out[1].name)
    }

    // ------------------------------------------------------- probe configs

    @Test
    fun `xray probe config binds one inbound and rule per node`() {
        val nodes = listOf(profile(name = "n1"), profile(server = "5.6.7.8", name = "n2"))
        val cfg = ShardRace.xrayProbeConfig(nodes, ShardRace.PROBE_BASE_PORT)
        val inbounds = cfg.getJSONArray("inbounds")
        val outbounds = cfg.getJSONArray("outbounds")
        val rules = cfg.getJSONObject("routing").getJSONArray("rules")
        assertEquals(2, inbounds.length())
        assertEquals(3, outbounds.length())   // 2 nodes + blackhole
        assertEquals(2, rules.length())
        assertEquals(
            ShardRace.PROBE_BASE_PORT,
            inbounds.getJSONObject(0).getInt("port"),
        )
        assertEquals("blackhole", outbounds.getJSONObject(2).getString("tag"))
        // Probes never carry mux (Android: probe runs mux=false).
        assertFalse(outbounds.getJSONObject(0).has("mux"))
    }

    @Test
    fun `race constants match ShardManager exactly`() {
        assertEquals(24, ShardRace.RACE_WIDTH)
        assertEquals(5_000, ShardRace.PROBE_TIMEOUT_MS)
        assertEquals(12_000, ShardRace.RACE_BUDGET_MS)
        assertEquals(21100, ShardRace.PROBE_BASE_PORT)
        assertEquals(21200, ShardRace.ANYTLS_PROBE_BASE_PORT)
        assertEquals(5, ShardRace.EARLY_EXIT_SUCCESSES)
        assertEquals(60, ShardRace.EARLY_EXIT_MIN_TESTED)
        assertEquals(800, ShardRace.EARLY_EXIT_BEST_MS)
    }

    private fun assertFalse(value: Boolean) = org.junit.jupiter.api.Assertions.assertFalse(value)
}
