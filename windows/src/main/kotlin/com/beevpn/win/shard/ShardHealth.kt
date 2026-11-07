package com.beevpn.win.shard

import com.beevpn.win.AppPaths
import java.io.File

/// Per-node health memory — the desktop port of Android's ShardHealth.kt,
/// same scoring, same smoothing, same persistence format (`key:latency:streak:
/// failures`, line per node). Without it every connect pays the full race from
/// scratch; with it a bad node stops being tried first after it dies and a
/// steady node outranks a flaky one.
object ShardHealth {

    /// Above this the node is recorded as a failure instead: 8 s is not "slow
    /// but usable", it is a node that will feel broken.
    const val LATENCY_CEILING_MS = 8_000

    /// Consecutive failures before a node drops behind untried ones.
    const val FAILURE_TOLERANCE = 2

    private val file: File get() = File(AppPaths.baseDir, "shard-health.txt")

    /// What we know about one node — Android's Score, desktop edition. The
    /// kbps throughput bonus is deliberately absent: its only input on the
    /// phone is the live-tunnel transfer sampler, and with kbps = 0 the rank
    /// formula is identical anyway.
    data class Score(
        val latencyMs: Int,
        val streak: Int,
        val failures: Int,
    ) {
        val everWorked: Boolean get() = latencyMs > 0

        /// Sort key: lower is tried earlier. Failed nodes sink behind untried
        /// ones (but are never removed); untried sit in the middle.
        fun rank(): Int = when {
            failures >= FAILURE_TOLERANCE -> 900_000 + latencyMs
            !everWorked -> 500_000
            else -> latencyMs - (streak.coerceAtMost(5) * 500)
        }
    }

    private fun load(): MutableMap<String, Score> = try {
        if (!file.isFile) mutableMapOf()
        else file.readLines().mapNotNull { line ->
            val parts = line.trim().split(':')
            if (parts.size < 3) return@mapNotNull null
            val key = parts[0]
            val latency = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            val streak = parts.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null
            val failures = parts.getOrNull(3)?.toIntOrNull() ?: 0
            if (key.isEmpty()) null else key to Score(latency, streak, failures)
        }.toMap().toMutableMap()
    } catch (_: Exception) {
        mutableMapOf()
    }

    private fun save(map: Map<String, Score>) {
        try {
            file.writeText(map.entries.joinToString("\n") { (k, s) ->
                "$k:${s.latencyMs}:${s.streak}:${s.failures}"
            })
        } catch (_: Exception) {}
    }

    fun score(profile: ShardProfile): Score = load()[profile.key()] ?: Score(0, 0, 0)

    /** Record a successful probe. A latency past the ceiling counts as a failure. */
    fun recordSuccess(profile: ShardProfile, latencyMs: Int) {
        if (latencyMs > LATENCY_CEILING_MS) {
            recordFailure(profile)
            return
        }
        val map = load()
        val previous = map[profile.key()] ?: Score(0, 0, 0)
        // Smoothed toward the new sample 2:1 — one lucky probe must not
        // promote a node, but a genuine improvement shows up quickly.
        val blended = if (previous.everWorked) {
            (previous.latencyMs + latencyMs * 2) / 3
        } else {
            latencyMs
        }
        map[profile.key()] = Score(blended, previous.streak + 1, 0)
        save(map)
    }

    fun recordFailure(profile: ShardProfile) {
        val map = load()
        val previous = map[profile.key()] ?: Score(0, 0, 0)
        map[profile.key()] = Score(previous.latencyMs, 0, previous.failures + 1)
        save(map)
    }

    /// Order the pool for the next race: most likely to work, fastest first.
    /// Stable within a rank, so unknown nodes keep the publisher's order.
    fun rank(profiles: List<ShardProfile>): List<ShardProfile> {
        val scores = profiles.associate { it to score(it) }
        return profiles.sortedBy { scores[it]?.rank() ?: 500_000 }
    }

    /// Drop memory for nodes no longer in the pool — called after each
    /// subscription refresh so departed nodes do not linger forever.
    fun prune(pool: List<ShardProfile>) {
        val live = pool.map { it.key() }.toSet()
        val map = load()
        val kept = map.filterKeys { it in live }
        if (kept.size != map.size) save(kept)
    }

    /// Wipe the whole memory — the "reset node knowledge" action and tests.
    fun clear() {
        try { file.delete() } catch (_: Exception) {}
    }
}
