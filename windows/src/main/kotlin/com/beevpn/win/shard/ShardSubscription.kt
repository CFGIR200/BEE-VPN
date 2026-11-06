package com.beevpn.win.shard

import com.beevpn.win.AppPaths
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/// Keeps the SHARD node pool fresh, without the user knowing it exists —
/// the desktop port of ShardSubscription.kt: same URL, same ETag handshake,
/// same 6-hour floor between checks, best-effort with a cached fallback.
object ShardSubscription {

    /// The subscription — direct from publisher, refreshed every few hours.
    /// The app itself fetches and extracts all configs from this link.
    const val SUBSCRIPTION_URL =
        "https://raw.githubusercontent.com/patterniha/Free-Configs/main/configs.txt"

    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MIN_INTERVAL_MS = 6 * 60 * 60 * 1000L

    private val refreshing = AtomicBoolean(false)

    fun lastCheckMillis(): Long =
        lastCheckFile().let { if (it.isFile) it.readText().trim().toLongOrNull() ?: 0L else 0L }

    fun cachedProfiles(): List<ShardProfile> {
        val f = AppPaths.shardCache
        if (!f.isFile) return emptyList()
        return ShardImport.parseBatch(f.readText())
    }

    fun needsCheck(): Boolean =
        System.currentTimeMillis() - lastCheckMillis() > MIN_INTERVAL_MS

    /// Refresh in the background. Returns the (possibly cached) pool either way.
    fun refreshIfDue(): List<ShardProfile> {
        if (needsCheck() && refreshing.compareAndSet(false, true)) {
            Thread {
                try { refreshNow() } finally { refreshing.set(false) }
            }.start()
        }
        return cachedProfiles()
    }

    /// Blocking refresh — used by the explicit "به‌روزرسانی" button.
    fun refreshNow(): List<ShardProfile> {
        val conn = URL(SUBSCRIPTION_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.setRequestProperty("User-Agent", "BEE-VPN-Windows/1.0")
        val etagFile = AppPaths.shardEtag
        if (etagFile.isFile && AppPaths.shardCache.isFile) {
            conn.setRequestProperty("If-None-Match", etagFile.readText().trim())
        }
        try {
            if (conn.responseCode == 304) {
                stamp()
                return cachedProfiles()
            }
            if (conn.responseCode != 200) return cachedProfiles()
            val body = conn.inputStream.bufferedReader().readText()
            if (ShardImport.parseBatch(body).isNotEmpty()) {
                AppPaths.shardCache.writeText(body)
                etagFile.parentFile?.mkdirs()
                conn.getHeaderField("ETag")?.let { etagFile.writeText(it) }
            }
            stamp()
            return cachedProfiles()
        } catch (_: Exception) {
            return cachedProfiles()
        } finally {
            conn.disconnect()
        }
    }

    private fun stamp() {
        AppPaths.shardEtag.parentFile?.mkdirs()
        lastCheckFile().writeText(System.currentTimeMillis().toString())
    }

    private fun lastCheckFile() = java.io.File(AppPaths.baseDir, "shard-last-check.txt")
}
