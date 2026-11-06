package com.beevpn.win.shard

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/// TCP handshake latency probe — the light-weight cousin of the Android
/// ShardRealDelayTester: ranks the pool by dial time so «اتصال خودکار» always
/// picks a node that is actually up, without running real requests.
object ShardProber {

    data class Result(val profile: ShardProfile, val latencyMs: Int)  // -1 = dead

    private val pool = Executors.newFixedThreadPool(12)

    fun probeAll(profiles: List<ShardProfile>, timeoutMs: Int = 2_500, limit: Int = 40): List<Result> {
        val candidates = profiles.take(limit)
        val futures = candidates.map { p ->
            pool.submit(Callable { Result(p, probeOne(p, timeoutMs)) })
        }
        return futures.mapNotNull { f ->
            try { f.get(timeoutMs + 1_000L, TimeUnit.MILLISECONDS) } catch (_: Exception) { null }
        }.sortedBy { if (it.latencyMs < 0) Int.MAX_VALUE else it.latencyMs }
    }

    fun probeOne(p: ShardProfile, timeoutMs: Int = 2_500): Int {
        val host = p.server ?: return -1
        val port = p.serverPort?.toIntOrNull() ?: 443
        return try {
            Socket().use { s ->
                val t0 = System.currentTimeMillis()
                s.connect(InetSocketAddress(host, port), timeoutMs)
                (System.currentTimeMillis() - t0).toInt()
            }
        } catch (_: Exception) { -1 }
    }
}
