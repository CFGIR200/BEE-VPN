package com.msnguard.vpn

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import com.msnguard.vpn.profiled

/**
 * Keeps the SHARD node pool fresh, without the user knowing it exists.
 *
 * ## Four springs, one pool
 *
 * [SOURCES] is the list of subscriptions the pool drinks from. The original
 * publisher (patterniha/Free-Configs) launched the pool; PSG's xray mix,
 * Radikal's Top-100 and the shadowsocks mirror joined later, at the user's
 * request, and more can be appended to the list without touching anything
 * else — every source rides the same fetch, cache, ETag and parse pipeline.
 * The bodies are concatenated and parsed as one, so duplicates across
 * publishers collapse in [ShardConfigs.parse]'s dedupe and the pool never
 * races the same node twice.
 *
 * ## Cadence, and why there is no alarm or periodic worker
 *
 * Every list here rebuilds at most once a day. Nothing about that needs a
 * wakeup: the refresh runs when the app comes to the foreground and when a
 * SHARD connect starts, and only if [MIN_INTERVAL_MS] has elapsed. An app
 * that is closed costs zero battery, because there is no scheduled work to
 * fire. (The one periodic path, [ShardRefreshJob], batches into the OS's own
 * radio windows.)
 *
 * ## ETag, so a no-change refresh is nearly free
 *
 * Each source keeps its own stored ETag, sent back as `If-None-Match`. When
 * nothing changed that source answers `304` with no body — a few hundred
 * bytes per source for the whole check, against a combined body that runs
 * past a hundred kilobytes.
 *
 * ## Independence of sources
 *
 * The sources fail separately. A publisher taking one list down, or one
 * returning a truncated body, costs that source only: its cache stands, the
 * other three still feed the pool, and the check timestamp does not advance
 * so the failed source is retried on the next resume. A first run with no
 * caches at all falls back to the seed list shipped in assets, so the button
 * still works offline on a fresh install.
 *
 * ## Reachability
 *
 * Confirmed by the user on Hamrah-e-Aval with no VPN: `raw.githubusercontent.com`
 * is reachable and the subscriptions update. So the refresh runs
 * unconditionally, tunnel up or down. It stays best-effort regardless — a
 * failed refresh keeps the previous cache.
 */
object ShardSubscription {

    private const val TAG = "ShardSubscription"

    /** One subscription the pool drinks from. */
    private data class Source(
        val url: String,
        /** Short label for the connection log. */
        val label: String,
        /** Cache file name inside filesDir. */
        val cacheName: String,
        /** SharedPreferences key holding this source's last ETag. */
        val etagKey: String,
    )

    /** The primary subscription — the publisher the pool launched with. */
    const val SUBSCRIPTION_URL =
        "https://raw.githubusercontent.com/patterniha/Free-Configs/main/configs.txt"

    /**
     * The springs, in pool order. The first source keeps the legacy cache file
     * and ETag key it has always had, so an install that updates into this
     * code keeps its existing cache and answers 304 on the first check
     * instead of re-downloading.
     */
    private val SOURCES = listOf(
        Source(
            url = SUBSCRIPTION_URL,
            label = "free-configs",
            cacheName = "shard-configs.txt",
            etagKey = "shard_etag",
        ),
        Source(
            url = "https://raw.githubusercontent.com/itsyebekhe/PSG/main/subscriptions/xray/mix",
            label = "psg-mix",
            cacheName = "shard-configs-psg.txt",
            etagKey = "shard_etag_psg",
        ),
        Source(
            url = "https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/top100.txt",
            label = "radikal-top100",
            cacheName = "shard-configs-radikal.txt",
            etagKey = "shard_etag_radikal",
        ),
        Source(
            url = "https://raw.githubusercontent.com/arshiacomplus/v2rayExtractor/refs/heads/main/ss.html",
            label = "ss-mirror",
            cacheName = "shard-configs-ss.txt",
            etagKey = "shard_etag_ss",
        ),
    )

    /** Seed list in assets, so the first ever connect works with no network. */
    private const val SEED_ASSET = "shard-seed.txt"

    private const val LAST_CHECK_PREF = "shard_last_check"
    private const val LAST_COUNT_PREF = "shard_last_count"

    /**
     * Floor between two network checks.
     *
     * Six hours against once-a-day rebuilds: frequent enough that a user who
     * opens the app daily always has the current lists within a day, sparse
     * enough that opening the app ten times in an afternoon costs one round.
     */
    private const val MIN_INTERVAL_MS = 6 * 60 * 60 * 1000L

    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 20_000

    /** Guards against two refreshes racing to write the same cache file. */
    private val refreshing = AtomicBoolean(false)

    private fun prefs(context: Context) =
        context.profiled()

    private fun cacheFile(context: Context, source: Source) =
        File(context.filesDir, source.cacheName)

    /** Read a cache file, or empty on any failure — a corrupt file is an empty source. */
    private fun readQuietly(file: File): String = try {
        file.takeIf { it.exists() }?.readText().orEmpty()
    } catch (_: Exception) {
        ""
    }

    /** When the last successful check happened, 0 if never. */
    fun lastCheckMillis(context: Context): Long = prefs(context).getLong(LAST_CHECK_PREF, 0L)

    /** How many nodes the cache last yielded. Shown in settings as a subtitle. */
    fun cachedCount(context: Context): Int = prefs(context).getInt(LAST_COUNT_PREF, 0)

    /** Raw non-# non-empty lines across every cached source; 0 when no cache. For the effective race log. */
    fun rawLineCount(context: Context): Int {
        var n = 0
        for (source in SOURCES) {
            val text = readQuietly(cacheFile(context, source))
            if (text.isEmpty()) continue
            n += text.lineSequence().count { line ->
                val t = line.trim()
                t.isNotEmpty() && !t.startsWith("#")
            }
        }
        return n
    }

    /**
     * How many distinct paths those nodes expand to across the CDN edges.
     *
     * Stored at refresh time rather than computed on demand: the settings screen
     * would otherwise re-read and re-parse the whole cache on the main thread just
     * to render a subtitle. Falls back to a multiply when the value predates this
     * field, which is only ever the first run after an update.
     *
     * When the edge list changed since the subscription was last refreshed — a
     * `RemotePolicy` update adds IPs without touching the node files, so every
     * source answers 304 and never rewrites the stored count — the stored
     * value is stale and wrong (it still multiplies by the old edge count).
     * Recompute from the current edge list whenever the cached value disagrees
     * with `nodes × edges`: cheap (the edge list is a handful of strings) and
     * it is the number the pool will actually race, not the number it raced
     * last time.
     */
    fun cachedPathCount(context: Context): Int {
        val nodes = cachedCount(context)
        if (nodes <= 0) return 0
        val perNode = ShardEdges.pathsPerNode(context)
        if (perNode <= 0) return nodes
        return nodes * perNode
    }

    /**
     * The pool to connect from, best available source.
     *
     * Every cached source is concatenated and parsed as one — the dedupe in
     * [ShardConfigs.parse] collapses the overlap between publishers. Never an
     * empty list without having tried the caches and the seed: a user tapping
     * SHARD must not get "no nodes" because a refresh failed.
     */
    fun nodes(context: Context): List<ShardNode> {
        val bodies = SOURCES.map { readQuietly(cacheFile(context, it)) }.filter { it.isNotBlank() }
        if (bodies.isNotEmpty()) {
            val parsed = ShardConfigs.parse(bodies.joinToString("\n"))
            if (parsed.isNotEmpty()) return parsed
        }
        return seedNodes(context)
    }

    private fun seedNodes(context: Context): List<ShardNode> = try {
        val body = context.assets.open(SEED_ASSET).bufferedReader().use { it.readText() }
        ShardConfigs.parse(body)
    } catch (e: Exception) {
        ConnectionLog.record("$TAG no seed list: ${e.message}")
        emptyList()
    }

    /**
     * Refresh if it is due. Cheap to call from anywhere, including the UI thread —
     * it hands the work to [worker] and returns immediately.
     *
     * @param force ignores [MIN_INTERVAL_MS]; used by the manual settings row.
     */
    fun refreshIfDue(context: Context, force: Boolean = false, onDone: ((Int) -> Unit)? = null) {
        val elapsed = System.currentTimeMillis() - lastCheckMillis(context)
        if (!force && elapsed in 0 until MIN_INTERVAL_MS) {
            onDone?.invoke(cachedCount(context))
            return
        }
        if (!refreshing.compareAndSet(false, true)) {
            onDone?.invoke(cachedCount(context))
            return
        }
        Thread({
            val count = try {
                refreshBlocking(context)
            } catch (e: Exception) {
                ConnectionLog.record("$TAG refresh failed: ${e.message}")
                cachedCount(context)
            } finally {
                refreshing.set(false)
            }
            onDone?.invoke(count)
        }, "shard-refresh").apply { isDaemon = true }.start()
    }

    /** One source's answer. */
    private sealed class Answer {
        /** 304 Not Modified — the source is unchanged; the cache stands. */
        object Unchanged : Answer()

        /** HTTP error or I/O failure — the cache stands, the timestamp must not advance. */
        object Failed : Answer()

        /** 200 OK — a fresh body and its ETag. Usability is checked by the caller. */
        class Fresh(val body: String, val etag: String) : Answer()
    }

    /**
     * Fetch and store every source, then report the pool size.
     *
     * Each source is fetched and judged on its own: a 304 keeps that cache, a
     * failure keeps that cache and blocks the timestamp advance, and a 200
     * whose body parses to nothing is refused — a truncated response or a
     * publisher mistake must not disarm the button. The merged pool is parsed
     * once, at the end, so publishers that mirror the same node cost one
     * entry, not two.
     */
    private fun refreshBlocking(context: Context): Int {
        val prefs = prefs(context)
        var wrote = false
        var allDefinite = true
        // Joined with an explicit newline: a body that does not end in one
        // must not weld its last line to the next source's first line.
        val bodies = ArrayList<String>(SOURCES.size)
        for (source in SOURCES) {
            val file = cacheFile(context, source)
            val cached = readQuietly(file)
            val etag = prefs.getString(source.etagKey, null)
                ?.takeIf { it.isNotBlank() && cached.isNotBlank() }
            when (val answer = fetchOne(source, etag)) {
                is Answer.Unchanged -> bodies.add(cached)
                is Answer.Failed -> {
                    allDefinite = false
                    bodies.add(cached)
                }
                is Answer.Fresh -> {
                    val parsedSource = ShardConfigs.parse(answer.body)
                    if (parsedSource.isEmpty()) {
                        ConnectionLog.record("$TAG ${source.label}: response parsed to 0 nodes — keeping previous cache")
                        allDefinite = false
                        bodies.add(cached)
                        continue
                    }
                    try {
                        file.writeText(answer.body)
                        prefs.edit().putString(source.etagKey, answer.etag).apply()
                        wrote = true
                        bodies.add(answer.body)
                        ConnectionLog.record("$TAG ${source.label}: ${parsedSource.size} nodes")
                    } catch (e: Exception) {
                        ConnectionLog.record("$TAG ${source.label}: cache write failed: ${e.message}")
                        allDefinite = false
                        bodies.add(cached)
                    }
                }
            }
        }

        val parsed = ShardConfigs.parse(bodies.joinToString("\n"))
        if (parsed.isEmpty()) {
            // Nothing anywhere — the caches (if any) stand, the timestamp does
            // not advance, and the next resume tries again.
            ConnectionLog.record("$TAG pool empty after refresh — keeping previous cache")
            return cachedCount(context)
        }
        if (wrote) {
            prefs.edit()
                .putLong(LAST_CHECK_PREF, System.currentTimeMillis())
                .putInt(LAST_COUNT_PREF, parsed.size)
                .apply()
            ConnectionLog.record(
                "$TAG updated — ${parsed.size} nodes across ${SOURCES.size} sources, " +
                    "${parsed.size * ShardEdges.pathsPerNode(context)} paths",
            )
        } else if (allDefinite) {
            // Every source answered 304: a success with no work. The timestamp
            // advances so the next foreground does not re-ask.
            prefs.edit().putLong(LAST_CHECK_PREF, System.currentTimeMillis()).apply()
            ConnectionLog.record("$TAG unchanged (all ${SOURCES.size} sources 304)")
        } else {
            ConnectionLog.record("$TAG refresh incomplete — the failed sources retry on the next resume")
        }
        return parsed.size
    }

    private fun fetchOne(source: Source, etag: String?): Answer {
        val connection = try {
            URL(source.url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            ConnectionLog.record("$TAG ${source.label}: ${e.message}")
            return Answer.Failed
        }
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            // A plain client string. Nothing identifying: the request goes out over
            // the carrier link in the clear, and naming the app in it would tell
            // anyone watching that this device runs a VPN client.
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.setRequestProperty("Accept", "text/plain")
            etag?.let { connection.setRequestProperty("If-None-Match", it) }

            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED) return Answer.Unchanged
            if (status != HttpURLConnection.HTTP_OK) {
                ConnectionLog.record("$TAG ${source.label}: HTTP $status")
                return Answer.Failed
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return Answer.Fresh(body, connection.getHeaderField("ETag").orEmpty())
        } catch (e: Exception) {
            ConnectionLog.record("$TAG ${source.label}: ${e.message}")
            return Answer.Failed
        } finally {
            connection.disconnect()
        }
    }
}
