package com.beevpn.win.shard

import com.beevpn.win.engine.EngineProcess
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/// The node race — the desktop port of Android's ShardManager.start() sweep:
/// health-ranked, endpoint-diversified slices of the pool probed through a
/// REAL xray process (one inbound per node, port 21100+i), first success wins,
/// least-ping winner across slices, with Android's exact budgets.
///
/// Why a real request and not a TCP dial: every node in the pool resolves to
/// the same Cloudflare edge, so connect() always succeeds and proves nothing
/// about whether the node's worker and credential still work. Only bytes that
/// come back through the node's own proxy inbound are evidence.
object ShardRace {

    /// Slice width and budgets — ShardManager constants, unchanged.
    const val RACE_WIDTH = 24
    const val PROBE_TIMEOUT_MS = 5_000
    const val RACE_BUDGET_MS = 12_000

    /// xray probe block 21100+, anytls block 21200+ (never collide at width 24).
    const val PROBE_BASE_PORT = 21100
    const val ANYTLS_PROBE_BASE_PORT = 21200

    /// Early exit — after 5 successes, keep scanning only while the fastest
    /// answer is still slow (Android: `allSuccess >= 5 && (tested >= 60 || best < 800)`).
    const val EARLY_EXIT_SUCCESSES = 5
    const val EARLY_EXIT_MIN_TESTED = 60
    const val EARLY_EXIT_BEST_MS = 800

    // ------------------------------------------------------------ probe

    /// The three probe targets — three different operators so one dead site
    /// never reads as a dead node (ShardProbe.TARGETS, unchanged).
    private val TARGETS = listOf(
        Triple("cp.cloudflare.com", "/generate_204", 80),
        Triple("www.gstatic.com", "/generate_204", 80),
        Triple("captive.apple.com", "/hotspot-detect.html", 80),
    )

    enum class ProbeVerdict { PASS, NODE_DEAD, ENDPOINT_BAD }

    /// One SOCKS5 CONNECT + GET through the node's probe inbound — Android's
    /// probeOnceDetailed, stage-for-stage. NODE_DEAD ends the ladder; a site
    /// that answers wrongly (ENDPOINT_BAD) hands the baton to the next target.
    fun probeOnceDetailed(
        socksPort: Int,
        timeoutMs: Int,
    ): Pair<ProbeVerdict, String> {
        var lastReason = "no target tried"
        for ((host, path, port) in TARGETS) {
            val (verdict, detail) = probeTarget(socksPort, host, path, port, timeoutMs)
            when (verdict) {
                ProbeVerdict.PASS -> return ProbeVerdict.PASS to "ok"
                ProbeVerdict.NODE_DEAD -> return ProbeVerdict.NODE_DEAD to detail
                ProbeVerdict.ENDPOINT_BAD -> lastReason = detail
            }
        }
        return ProbeVerdict.NODE_DEAD to lastReason
    }

    private fun probeTarget(
        socksPort: Int,
        host: String,
        path: String,
        port: Int,
        timeoutMs: Int,
    ): Pair<ProbeVerdict, String> {
        Socket().use { socket ->
            try {
                socket.tcpNoDelay = true
                socket.soTimeout = timeoutMs
                try {
                    socket.connect(InetSocketAddress("127.0.0.1", socksPort), timeoutMs)
                } catch (_: java.net.SocketTimeoutException) {
                    return ProbeVerdict.NODE_DEAD to "tcp connect timeout to lo:$socksPort"
                } catch (_: java.net.ConnectException) {
                    return ProbeVerdict.NODE_DEAD to "tcp connect refused lo:$socksPort"
                } catch (_: Exception) {
                    return ProbeVerdict.NODE_DEAD to "tcp connect failed"
                }
                val output = socket.getOutputStream()
                val input = socket.getInputStream()

                output.write(byteArrayOf(0x05, 0x01, 0x00)); output.flush()
                val greeting = readExactly(input, 2)
                    ?: return ProbeVerdict.NODE_DEAD to "socks greeting no reply"
                if (greeting[0] != 0x05.toByte() || greeting[1] != 0x00.toByte()) {
                    return ProbeVerdict.NODE_DEAD to "socks greeting rejected"
                }

                val hostBytes = host.toByteArray(Charsets.US_ASCII)
                val request = ByteArray(7 + hostBytes.size)
                request[0] = 0x05; request[1] = 0x01; request[2] = 0x00
                request[3] = 0x03   // ATYP domain — the tunnel resolves it
                request[4] = hostBytes.size.toByte()
                System.arraycopy(hostBytes, 0, request, 5, hostBytes.size)
                request[5 + hostBytes.size] = ((port shr 8) and 0xFF).toByte()
                request[6 + hostBytes.size] = (port and 0xFF).toByte()
                output.write(request); output.flush()

                val reply = readExactly(input, 4)
                    ?: return ProbeVerdict.NODE_DEAD to "socks connect no reply"
                if (reply[1] != 0x00.toByte()) {
                    return ProbeVerdict.NODE_DEAD to "socks reply 0x${reply[1].toInt().and(0xFF).toString(16)} for $host"
                }
                val addressLength = when (reply[3].toInt() and 0xFF) {
                    0x01 -> 4
                    0x04 -> 16
                    0x03 -> (readExactly(input, 1)?.get(0)?.toInt()?.and(0xFF))
                        ?: return ProbeVerdict.NODE_DEAD to "socks bnd addr len missing"
                    else -> return ProbeVerdict.NODE_DEAD to "socks bnd atyp bad"
                }
                readExactly(input, addressLength + 2)
                    ?: return ProbeVerdict.NODE_DEAD to "socks bnd addr truncated"

                try { sendRequest(output, host, path) }
                catch (_: Exception) { return ProbeVerdict.NODE_DEAD to "http request write failed" }
                val statusLine = readStatusLine(input)
                    ?: return ProbeVerdict.ENDPOINT_BAD to "no http status from $host"
                val passed = statusLine.contains(" 204") ||
                    (host == "captive.apple.com" && statusLine.contains(" 200"))
                return if (passed) ProbeVerdict.PASS to "ok"
                else ProbeVerdict.ENDPOINT_BAD to "http ${statusLine.take(64)} from $host"
            } catch (_: java.net.SocketTimeoutException) {
                return ProbeVerdict.NODE_DEAD to "read timeout after ${timeoutMs}ms"
            } catch (_: Exception) {
                return ProbeVerdict.NODE_DEAD to "error"
            }
        }
    }

    private fun sendRequest(output: OutputStream, host: String, path: String) {
        val request = buildString {
            append("GET ").append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(host).append("\r\n")
            append("User-Agent: Mozilla/5.0\r\n")
            append("Accept: */*\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(request.toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    private fun readStatusLine(input: InputStream): String? {
        val builder = StringBuilder(64)
        while (builder.length < 128) {
            val byte = input.read()
            if (byte < 0) return builder.takeIf { it.isNotEmpty() }?.toString()
            if (byte == '\n'.code) return builder.toString()
            if (byte != '\r'.code) builder.append(byte.toChar())
        }
        return builder.toString()
    }

    private fun readExactly(input: InputStream, count: Int): ByteArray? {
        val buf = ByteArray(count)
        var off = 0
        while (off < count) {
            val r = input.read(buf, off, count - off)
            if (r < 0) return null
            off += r
        }
        return buf
    }

    // ------------------------------------------------------- probe configs

    /// One xray process, one SOCKS inbound per node (21100+i), one outbound per
    /// node, one routing rule per inbound — ShardConfigs.probeConfig, with the
    /// mux OFF the probe path (probe runs mux=false on Android).
    fun xrayProbeConfig(candidates: List<ShardProfile>, basePort: Int): JSONObject {
        val inbounds = JSONArray()
        val outbounds = JSONArray()
        val rules = JSONArray()
        candidates.forEachIndexed { index, profile ->
            val port = basePort + index
            inbounds.put(
                JSONObject()
                    .put("tag", "in-$index")
                    .put("listen", "127.0.0.1")
                    .put("port", port)
                    .put("protocol", "socks")
                    .put("settings", JSONObject().put("auth", "noauth").put("udp", false)),
            )
            outbounds.put(ShardOutboundBuilder.outbound(profile, "out-$index"))
            rules.put(
                JSONObject()
                    .put("type", "field")
                    .put("inboundTag", JSONArray().put("in-$index"))
                    .put("outboundTag", "out-$index"),
            )
        }
        outbounds.put(JSONObject().put("tag", "blackhole").put("protocol", "blackhole"))
        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning").put("access", "none"))
            .put("inbounds", inbounds)
            .put("outbounds", outbounds)
            .put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", rules))
    }

    /// The anytls variant: sing-box binds one anytls listener per node from the
    /// SAME arrangement expressed in its own config format (21200+i).
    fun anytlsProbeConfig(candidates: List<ShardProfile>, basePort: Int): JSONObject {
        val servers = JSONArray()
        candidates.forEachIndexed { index, profile ->
            servers.put(
                JSONObject()
                    .put("listen", "127.0.0.1:${basePort + index}")
                    .put("server", "${profile.server}:${profile.serverPort?.toIntOrNull() ?: 443}")
                    .put("password", profile.password ?: "")
                    .put("min_idle_session", 5),
            )
        }
        return JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("servers", servers)
            .put("route", JSONObject().put("auto_detect_interface", true))
    }

    // ---------------------------------------------------------- selection

    /// Reorder a ranked pool so consecutive entries are different endpoints,
    /// fast variants (fm) before ECH ones inside each group — the exact
    /// ordering rule Android's diversify() applies, with its exact rationale:
    /// fm needs no DNS, ECH needs a UDP-53 query many ISPs block.
    fun diversify(ranked: List<ShardProfile>): List<ShardProfile> {
        val groups = LinkedHashMap<String, MutableList<ShardProfile>>()
        ranked.forEach { p ->
            // Identity excludes the server address deliberately — that is what
            // varies between variants of the same endpoint. The CDN host and
            // port are included: 443 and 8080 on one host are genuinely
            // different paths (Android: protocol|credential|host|path|port).
            val identity = "${p.configType}|${p.password}|${p.host}|${p.path}|${p.serverPort}"
            groups.getOrPut(identity) { mutableListOf() }.add(p)
        }
        groups.values.forEach { variants ->
            variants.sortWith(
                compareBy(
                    { if (!it.finalMask.isNullOrBlank()) 0 else 1 },
                    { if (!it.echConfigList.isNullOrBlank()) 0 else 1 },
                ),
            )
        }
        val out = ArrayList<ShardProfile>(ranked.size)
        var round = 0
        while (out.size < ranked.size) {
            var added = false
            groups.values.forEach { variants ->
                variants.getOrNull(round)?.let {
                    out.add(it)
                    added = true
                }
            }
            if (!added) break
            round++
        }
        return out
    }

    /// One slice race: launch the probe process(es), fire one probe thread per
    /// candidate, first PASS wins the latch. Returns (winner, elapsedMs) —
    /// null when the slice came up empty inside the budget.
    fun raceSlice(
        candidates: List<ShardProfile>,
        cores: File,
        onProgress: (String) -> Unit = {},
    ): Pair<ShardProfile, Int>? {
        if (candidates.isEmpty()) return null
        val xrayCandidates = candidates.filter { ShardOutboundBuilder.supports(it) }
        val anytlsCandidates = candidates.filter { it.configType == ShardType.ANYTLS }
        val portOf = HashMap<ShardProfile, Int>(candidates.size * 2)
        candidates.forEachIndexed { index, node -> portOf[node] = PROBE_BASE_PORT + index }

        var xrayProc: EngineProcess? = null
        var sbProc: EngineProcess? = null
        if (xrayCandidates.isNotEmpty()) {
            val cfgFile = File(com.beevpn.win.AppPaths.runDir, "probe.json")
            cfgFile.writeText(xrayProbeConfig(xrayCandidates, PROBE_BASE_PORT).toString(2))
            xrayProc = EngineProcess(
                "xray-probe", File(cores, "xray.exe"),
                listOf("run", "-c", cfgFile.absolutePath), cores,
            )
            xrayProc.onLine = { EngineProcess.logLine("[xray-probe] $it") }
            xrayProc.start()
        }
        if (anytlsCandidates.isNotEmpty()) {
            anytlsCandidates.forEachIndexed { i, n -> portOf[n] = ANYTLS_PROBE_BASE_PORT + i }
            val cfgFile = File(com.beevpn.win.AppPaths.runDir, "probe-sidecar.json")
            cfgFile.writeText(anytlsProbeConfig(anytlsCandidates, ANYTLS_PROBE_BASE_PORT).toString(2))
            sbProc = EngineProcess(
                "sing-box-probe", File(cores, "sing-box.exe"),
                listOf("run", "-c", cfgFile.absolutePath), cores,
            )
            sbProc.onLine = { EngineProcess.logLine("[sing-box-probe] $it") }
            sbProc.start()
        }
        if (xrayProc == null && sbProc == null) return null

        try {
            // Listeners bind a moment after exec — wait for the FIRST port of
            // each engine (4 s budget, Android awaitPortUp).
            val readyX = xrayProc == null || awaitPortUp(PROBE_BASE_PORT) { xrayProc.isAlive }
            val readyS = sbProc == null || awaitPortUp(portOf[anytlsCandidates.first()] ?: ANYTLS_PROBE_BASE_PORT) { sbProc.isAlive }
            if (!readyX || !readyS) {
                onProgress("probe listeners did not bind")
                return null
            }

            val winner = AtomicReference<ShardProfile?>(null)
            val winnerLatency = AtomicInteger(0)
            val latch = java.util.concurrent.CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(candidates.size.coerceAtMost(RACE_WIDTH))
            candidates.forEach { node ->
                pool.execute {
                    if (winner.get() != null) return@execute
                    val started = System.currentTimeMillis()
                    val (verdict, _) = probeOnceDetailed(portOf.getValue(node), PROBE_TIMEOUT_MS)
                    val elapsed = (System.currentTimeMillis() - started).toInt()
                    if (verdict == ProbeVerdict.PASS) {
                        ShardHealth.recordSuccess(node, elapsed)
                        if (winner.compareAndSet(null, node)) {
                            winnerLatency.set(elapsed)
                            latch.countDown()
                        }
                    } else {
                        ShardHealth.recordFailure(node)
                    }
                }
            }
            latch.await(RACE_BUDGET_MS.toLong(), TimeUnit.MILLISECONDS)
            pool.shutdownNow()
            val chosen = winner.get() ?: return null
            return chosen to winnerLatency.get()
        } finally {
            sbProc?.stop()
            xrayProc?.stop()
        }
    }

    /// Wait up to 4 s for the port to accept.
    private fun awaitPortUp(port: Int, alive: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 4_000
        while (System.currentTimeMillis() < deadline) {
            if (EngineProcess.portOpen(port, 300)) return true
            if (!alive()) return false
            Thread.sleep(100)
        }
        return false
    }

    /// The full sweep — ranked, diversified, sliced; the least-ping winner
    /// across all slices with Android's early-exit rule. Null = dead pool.
    fun sweep(
        pool: List<ShardProfile>,
        cores: File,
        onProgress: (String) -> Unit = {},
    ): ShardProfile? {
        ShardHealth.prune(pool)
        val ranked = diversify(ShardHealth.rank(pool))
        if (ranked.isEmpty()) return null
        val allSuccess = mutableListOf<Pair<ShardProfile, Int>>()
        var tested = 0
        var bestLatency = Int.MAX_VALUE
        var sliceIdx = 0
        while (sliceIdx * RACE_WIDTH < ranked.size) {
            val candidates = ranked.drop(sliceIdx * RACE_WIDTH).take(RACE_WIDTH)
            if (candidates.isEmpty()) break
            onProgress("بررسی نودها ${tested + 1}–${tested + candidates.size} از ${ranked.size}")
            val before = System.currentTimeMillis()
            val sliceWinner = raceSlice(candidates, cores, onProgress)
            val elapsed = (System.currentTimeMillis() - before).toInt()
            tested += candidates.size
            if (sliceWinner != null) {
                // The slice's wall-clock time to first success is the race
                // metric across slices (Android measures the same thing).
                allSuccess.add(sliceWinner.first to elapsed)
                if (elapsed < bestLatency) bestLatency = elapsed
            }
            sliceIdx++
            if (allSuccess.size >= EARLY_EXIT_SUCCESSES &&
                (tested >= EARLY_EXIT_MIN_TESTED || bestLatency < EARLY_EXIT_BEST_MS)
            ) break
        }
        if (allSuccess.isEmpty()) return null
        return allSuccess.minByOrNull { it.second }?.first
    }
}
