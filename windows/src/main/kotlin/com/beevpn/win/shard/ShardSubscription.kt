package com.beevpn.win.shard

import com.beevpn.win.AppPaths
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/// Keeps the SHARD node pool fresh, without the user knowing it exists —
/// the desktop port of ShardSubscription.kt: same URLs, same ETag handshake,
/// same 6-hour floor between checks, best-effort with a cached fallback.
///
/// Four springs, one pool: the original publisher launched it; PSG's xray
/// mix, Radikal's Top-100 and the shadowsocks mirror joined at the user's
/// request. Each source caches, etags and fails on its own — a publisher
/// taking one list down costs that list only, and the merged pool is deduped
/// downstream by the same rules the phone applies.
object ShardSubscription {

    /// One subscription the pool drinks from.
    private data class Source(
        val url: String,
        val label: String,
        val cacheName: String,
        val etagName: String,
    )

    /// The primary subscription — the publisher the pool launched with.
    const val SUBSCRIPTION_URL =
        "https://raw.githubusercontent.com/patterniha/Free-Configs/main/configs.txt"

    /// The first source keeps the legacy cache and etag file names it has
    /// always had, so an install that updates into this code keeps its
    /// existing cache and answers 304 on the first check.
    private val SOURCES = listOf(
        Source(SUBSCRIPTION_URL, "free-configs", "shard-configs.txt", "shard-etag.txt"),
        Source(
            "https://raw.githubusercontent.com/itsyebekhe/PSG/main/subscriptions/xray/mix",
            "psg-mix", "shard-configs-psg.txt", "shard-etag-psg.txt",
        ),
        Source(
            "https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/top100.txt",
            "radikal-top100", "shard-configs-radikal.txt", "shard-etag-radikal.txt",
        ),
        Source(
            "https://raw.githubusercontent.com/arshiacomplus/v2rayExtractor/refs/heads/main/ss.html",
            "ss-mirror", "shard-configs-ss.txt", "shard-etag-ss.txt",
        ),
    )

    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MIN_INTERVAL_MS = 6 * 60 * 60 * 1000L

    private val refreshing = AtomicBoolean(false)

    fun lastCheckMillis(): Long =
        lastCheckFile().let { if (it.isFile) it.readText().trim().toLongOrNull() ?: 0L else 0L }

    private fun cacheFile(source: Source) = java.io.File(AppPaths.baseDir, source.cacheName)

    private fun etagFile(source: Source) = java.io.File(AppPaths.baseDir, source.etagName)

    /// Read a cache file, or empty on any failure — a corrupt file is an empty source.
    private fun readQuietly(file: java.io.File): String = try {
        if (file.isFile) file.readText() else ""
    } catch (_: Exception) {
        ""
    }

    /// Never throws — a corrupt or locked cache file must not crash startup
    /// or a connect attempt; it just yields an empty pool.
    fun cachedProfiles(): List<ShardProfile> = try {
        val merged = SOURCES.joinToString("\n") { src -> readQuietly(cacheFile(src)) }
        if (merged.isBlank()) emptyList() else ShardImport.parseBatch(merged)
    } catch (_: Exception) {
        emptyList()
    }

    fun needsCheck(): Boolean =
        System.currentTimeMillis() - lastCheckMillis() > MIN_INTERVAL_MS

    /// Refresh in the background. Returns the (possibly cached) pool either way.
    /// Android parity: when there is no cache yet (fresh install, GitHub raw
    /// unreachable) the bundled seed fills in instantly so the buyer can
    /// connect while the background refresh keeps trying.
    fun refreshIfDue(): List<ShardProfile> {
        if (needsCheck() && refreshing.compareAndSet(false, true)) {
            Thread {
                try { refreshNow() } finally { refreshing.set(false) }
            }.start()
        }
        return cachedProfiles().ifEmpty { seedProfiles() }
    }

    /// One source's answer.
    private sealed class Answer {
        /// 304 Not Modified — the source is unchanged; the cache stands.
        object Unchanged : Answer()

        /// HTTP error or I/O failure — the cache stands, the timestamp must not advance.
        object Failed : Answer()

        /// 200 OK — a fresh body and its ETag. Usability is checked by the caller.
        class Fresh(val body: String, val etag: String) : Answer()
    }

    /// Blocking refresh — used by the explicit "به‌روزرسانی" button.
    ///
    /// Each source is fetched and judged on its own: a 304 keeps that cache, a
    /// failure keeps that cache and blocks the timestamp advance, and a 200
    /// whose body parses to nothing is refused — a truncated response or a
    /// publisher mistake must not disarm the connect button.
    fun refreshNow(): List<ShardProfile> {
        var wrote = false
        var allDefinite = true
        for (source in SOURCES) {
            val cache = cacheFile(source)
            val etagF = etagFile(source)
            val cached = readQuietly(cache)
            val etag = etagF.takeIf { it.isFile && cached.isNotBlank() }
                ?.let { runCatching { it.readText().trim() }.getOrNull() }
                ?.takeIf { it.isNotBlank() }
            when (val answer = fetchOne(source, etag)) {
                is Answer.Unchanged -> {}
                is Answer.Failed -> allDefinite = false
                is Answer.Fresh -> {
                    if (ShardImport.parseBatch(answer.body).isEmpty()) {
                        allDefinite = false
                    } else {
                        try {
                            cache.parentFile?.mkdirs()
                            cache.writeText(answer.body)
                            etagF.parentFile?.mkdirs()
                            etagF.writeText(answer.etag)
                            wrote = true
                        } catch (_: Exception) {
                            allDefinite = false
                        }
                    }
                }
            }
        }
        // Only a round where every source answered for itself (304 or a fresh,
        // usable body) advances the check timestamp; a failed source is
        // retried on the next resume.
        if (allDefinite) stamp()
        return cachedProfiles().ifEmpty { seedProfiles() }
    }

    private fun fetchOne(source: Source, etag: String?): Answer {
        val conn = try {
            URL(source.url).openConnection() as HttpURLConnection
        } catch (_: Exception) {
            return Answer.Failed
        }
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            // A neutral browser UA, exactly like the phone sends: the fetch must
            // not identify itself as a VPN client to the network it rides.
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (!etag.isNullOrBlank()) conn.setRequestProperty("If-None-Match", etag)

            val status = conn.responseCode
            if (status == 304) return Answer.Unchanged
            if (status != 200) return Answer.Failed
            val body = conn.inputStream.bufferedReader().readText()
            return Answer.Fresh(body, conn.getHeaderField("ETag").orEmpty())
        } catch (_: Exception) {
            return Answer.Failed
        } finally {
            conn.disconnect()
        }
    }

    /// The offline pool — the same idea as Android's assets/shard-seed.txt:
    /// a snapshot of the subscriptions shipped inside the app so a blocked
    /// GitHub raw never leaves the pool empty on first run.
    fun seedProfiles(): List<ShardProfile> = try {
        val stream = Thread.currentThread().contextClassLoader
            ?.getResourceAsStream("seed/shard-seed.txt")
            ?: ClassLoader.getSystemResourceAsStream("seed/shard-seed.txt")
        stream?.use { ShardImport.parseBatch(it.bufferedReader().readText()) } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun stamp() {
        lastCheckFile().parentFile?.mkdirs()
        lastCheckFile().writeText(System.currentTimeMillis().toString())
    }

    private fun lastCheckFile() = java.io.File(AppPaths.baseDir, "shard-last-check.txt")
}
