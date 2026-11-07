package com.beevpn.win.tunnel

import com.beevpn.win.AppPaths
import com.beevpn.win.faNum
import com.beevpn.win.license.LicenseManager
import com.beevpn.win.SecureStore
import com.beevpn.win.engine.AnytlsSidecarConfig
import com.beevpn.win.engine.CoreExtractor
import com.beevpn.win.engine.EngineConfig
import com.beevpn.win.engine.EngineProcess
import com.beevpn.win.engine.WindowsAdmin
import com.beevpn.win.shard.ShardImport
import com.beevpn.win.shard.ShardProfile
import com.beevpn.win.shard.ShardProber
import com.beevpn.win.shard.ShardSubscription
import com.beevpn.win.warp.WarpIdentity
import com.beevpn.win.warp.WarpProvisioner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/// Orchestrates the engines — the desktop twin of MsnGuardVpnService:
/// license gate → pick the transport → build the config → start xray
/// (TUN + SOCKS 1819) → start the anytls sidecar when the exit needs it →
/// **verify the tunnel actually carries the system's traffic** → CONNECTED,
/// then a 30-second watchdog with Android's 5/15/30/60/120 reconnect ladder.
object TunnelController {

    enum class Mode { AUTO, SHARD, WARP, MANUAL }

    enum class State { DISCONNECTED, PROVISIONING, STARTING, VERIFYING, CONNECTED, STOPPING, FAILED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    // Snapshot state — the UI observes these reactively.
    var state by mutableStateOf(State.DISCONNECTED); private set
    var statusText by mutableStateOf("قطع است"); private set
    var lastError by mutableStateOf<String?>(null); private set
    var lastExitSummary by mutableStateOf(""); private set
    var connectedSince: Long = 0

    // Android parity knobs
    @Volatile private var userDisconnect = false
    private var watchdogJob: Job? = null
    private var reconnectJob: Job? = null
    private val reconnectAttempts = AtomicInteger(0)

    // Prefs (persisted through SecureStore like the rest of the identity data)
    var mode: Mode
        get() = runCatching { Mode.valueOf(SecureStore.get("engine.mode") ?: "AUTO") }.getOrDefault(Mode.AUTO)
        set(v) { SecureStore.put("engine.mode", v.name) }

    var fragmentOnDirect: Boolean
        get() = SecureStore.get("engine.fragment") == "1"
        set(v) { SecureStore.put("engine.fragment", if (v) "1" else "0") }

    var pinnedNodeKey: String?
        get() = SecureStore.get("node.pinned")?.takeIf { it.isNotEmpty() }
        set(v) { SecureStore.put("node.pinned", v ?: "") }

    var manualWgQuick: String?
        get() = SecureStore.get("warp.manualConfig")?.takeIf { it.isNotEmpty() }
        set(v) {
            SecureStore.put("warp.manualConfig", v ?: "")
            if (v != null) WarpProvisioner.purge()
        }

    var nodePool by mutableStateOf<List<ShardProfile>>(emptyList()); private set
    var lastProbed by mutableStateOf<List<ShardProber.Result>>(emptyList()); private set

    /// Merges manually imported profiles (Advanced tab) over the current pool
    /// — the UI-facing write path that keeps `nodePool` otherwise read-only.
    fun mergeImported(profiles: List<ShardProfile>): Int {
        val merged = (profiles.associateBy { it.key() } + nodePool.associateBy { it.key() }).values.toList()
        nodePool = merged
        return profiles.size
    }

    fun noteProbed(results: List<ShardProber.Result>) {
        lastProbed = results
    }

    private var xray: EngineProcess? = null
    private var sidecar: EngineProcess? = null

    /// Position in WARP's port ladder for THIS connect — rotated when the
    /// egress is dead (Cloudflare's UDP ports degrade one by one on Iranian
    /// carriers; 2408 is throttled long before 500/1701/4500).
    @Volatile private var warpPortIdx = 0

    fun init() {
        refreshPool()
    }

    fun refreshPool(): List<ShardProfile> {
        nodePool = ShardSubscription.refreshIfDue() + extraProfiles()
        return nodePool
    }

    fun refreshPoolNow(): List<ShardProfile> {
        nodePool = ShardSubscription.refreshNow() + extraProfiles()
        return nodePool
    }

    /// Manually imported links from the Advanced tab — persisted separately so
    /// a subscription refresh never drops them. Never throws.
    private fun extraProfiles(): List<ShardProfile> {
        val f = File(AppPaths.baseDir, "shard-extra.txt")
        if (!f.isFile) return emptyList()
        return try {
            ShardImport.parseBatch(f.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ------------------------------------------------------------- connect

    fun connect() {
        userDisconnect = false
        warpPortIdx = 0   // a fresh user-initiated connect restarts the ladder at 2408
        scope.launch { mutex.withLock { withContext(Dispatchers.IO) { connectBlocking() } } }
    }

    fun disconnect() {
        userDisconnect = true
        watchdogJob?.cancel()
        reconnectJob?.cancel()
        scope.launch { mutex.withLock { withContext(Dispatchers.IO) { stopEngines("قطع اتصال") } } }
    }

    private fun setState(s: State, text: String) {
        state = s
        statusText = text
    }

    private fun fail(message: String) {
        lastError = message
        // Direct call — fail() only ever runs inside the mutex, so a synchronous
        // teardown is safe and keeps FAILED from being clobbered by a delayed
        // DISCONNECTED write (the async variant could land after the state set).
        stopEngines(null)
        setState(State.FAILED, "اتصال ناموفق")
    }

    private suspend fun connectBlocking() {
        if (state == State.CONNECTED || state == State.STARTING ||
            state == State.PROVISIONING || state == State.VERIFYING
        ) return
        lastError = null

        // Android parity — MsnGuardVpnService refuses to bring up a tunnel
        // when the entitlement is gone: "Subscription inactive — renew".
        if (LicenseManager.isConfigured && LicenseManager.gate == LicenseManager.Gate.LOCKED) {
            fail("اشتراک غیرفعال است — برای اتصال، اشتراک را تمدید کن یا دوباره وارد شو")
            return
        }

        setState(State.PROVISIONING, "آماده‌سازی…")
        if (AppPaths.isWindows()) TunnelVerifier.sweepStaleRoute()

        val cores = CoreExtractor.ensure()
        if (cores == null) {
            fail("موتورهای VPN (xray.exe / sing-box.exe) پیدا نشدند — بستهٔ رسمی (MSI یا ZIP قابل حمل) را نصب کن یا پوشهٔ cores را کنار exe بگذار")
            return
        }
        val needsTun = AppPaths.isWindows()
        if (needsTun && !WindowsAdmin.isAdmin()) {
            fail("ساخت تونل VPN دسترسی مدیر می‌خواهد — برنامه را با «Run as administrator» باز کن")
            return
        }

        try {
            // Pre-connect baseline of the bare ISP egress — the yardstick the
            // post-connect system probe is measured against. Nullable: when
            // the trace itself is blocked the ladder falls back to the
            // route + engine checks only.
            setState(State.PROVISIONING, "اندازه‌گیری خط پایه…")
            val baseline = withContext(Dispatchers.IO) { TunnelVerifier.traceIpDirect(6_000L) }
            TunnelVerifier.log("baseline egress: ${baseline ?: "unknown"}")

            /// One resolved exit: config + engines + verification. The WARP
            /// ladder re-runs it per port until the egress answers.
            suspend fun bringUp(exit: ResolvedExit): Verdict {
                setState(State.STARTING, "راه‌اندازی موتور…")

                // xray: TUN + SOCKS 1819 + exit. IPv6 entries are included only
                // when the system actually has IPv6 — with IPv6 disabled
                // system-wide, Xray's TUN refuses to start.
                val ipv6 = withContext(Dispatchers.IO) { TunnelVerifier.ipv6Available() }
                val xrayConfig = EngineConfig.build(
                    useTun = needsTun,
                    ipv6Available = ipv6,
                    exitOutbound = exit.outbound,
                    fragmentOnDirect = fragmentOnDirect,
                )
                val xrayConfigFile = File(AppPaths.runDir, "xray.json")
                xrayConfigFile.writeText(xrayConfig.toString(2))

                if (exit.sidecarConfig != null) {
                    val sidecarFile = File(AppPaths.runDir, "sidecar.json")
                    sidecarFile.writeText(exit.sidecarConfig.toString(2))
                }

                val proc = EngineProcess("xray", File(cores, "xray.exe"), listOf("run", "-c", xrayConfigFile.absolutePath), cores)
                proc.onLine = { line ->
                    EngineProcess.logLine("[xray] $line")
                }
                proc.onExit = { code ->
                    if (state == State.CONNECTED) {
                        scheduleAutoReconnect("موتور VPN ناگهان متوقف شد (کد $code)")
                    } else if (state == State.STARTING || state == State.VERIFYING) {
                        scope.launch {
                            mutex.withLock { withContext(Dispatchers.IO) { killEnginesQuiet() } }
                        }
                    }
                }
                proc.start()
                xray = proc
                if (!EngineProcess.awaitPort(EngineConfig.SOCKS_PORT, 20_000) { proc.isAlive }) {
                    return Verdict("xray روی پورت ${EngineConfig.SOCKS_PORT} بالا نیامد — گزارش پیشرفته را ببین")
                }

                if (exit.sidecarConfig != null) {
                    val sc = EngineProcess(
                        "sing-box", File(cores, "sing-box.exe"),
                        listOf("run", "-c", File(AppPaths.runDir, "sidecar.json").absolutePath),
                        cores,
                    )
                    sc.onLine = { EngineProcess.logLine("[sing-box] $it") }
                    sc.onExit = { code ->
                        if (state == State.CONNECTED && code != 0) {
                            scheduleAutoReconnect("سایدکار AnyTLS متوقف شد (کد $code)")
                        }
                    }
                    sc.start()
                    sidecar = sc
                    if (!EngineProcess.awaitPort(EngineConfig.ANYTLS_SIDECAR_PORT, 12_000) { sc.isAlive }) {
                        return Verdict("سایدکار AnyTLS روی پورت ${EngineConfig.ANYTLS_SIDECAR_PORT} بالا نیامد")
                    }
                }

                if (!needsTun) return Verdict(null)
                setState(State.VERIFYING, "بررسی تونل سیستم…")
                return withContext(Dispatchers.IO) { verifyTunnelUp(baseline, exit.isWarp) }
            }

            var exit = resolveExit(warpPortIdx) ?: return
            var verdict = bringUp(exit)

            // The WARP ladder: a tunnel that comes up but stays deaf is the
            // one failure a different PORT can actually cure — rotate
            // 2408 → 500 → 1701 → 4500 before showing the buyer an error.
            if (verdict.egressDead && exit.isWarp) {
                for (idx in (warpPortIdx + 1) until WarpProvisioner.portLadder.size) {
                    warpPortIdx = idx
                    withContext(Dispatchers.IO) { killEnginesQuiet() }
                    val port = WarpProvisioner.portLadder[idx]
                    setState(State.STARTING, "خروجی WARP جواب نداد — امتحان با پورت ${faNum(port)}…")
                    TunnelVerifier.log("warp ladder: rotating endpoint port → $port")
                    exit = resolveExit(warpPortIdx) ?: return
                    verdict = bringUp(exit)
                    if (!verdict.egressDead) break
                }
            }

            if (verdict.message != null) {
                fail(if (verdict.egressDead && exit.isWarp) warpDeadMessage() else verdict.message)
                return
            }

            connectedSince = System.currentTimeMillis() / 1000
            lastExitSummary = exit.summary
            reconnectAttempts.set(0)   // a verified connect resets the ladder
            setState(State.CONNECTED, "متصل است — ${exit.summary}")
            startWatchdog()
        } catch (e: Exception) {
            fail("خطای غیرمنتظره: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun warpDeadMessage(): String =
        "تونل بالا آمد اما خروجی WARP روی هیچ‌کدام از پورت‌های " +
            WarpProvisioner.portLadder.joinToString("/") +
            " پاس نداد — شبکه‌ات UDP را فیلتر می‌کند. به حالت SHARD برو (نودها را «به‌روزرسانی نودها» کن) " +
            "یا در تب پیشرفته کانفیگ دستی بگذار."

    /// The Android rule: CONNECTED is earned, never assumed. Success only
    /// when (1) the TUN owns the system default route, (2) real bytes
    /// flow through the engine's SOCKS to the far end, and (3) the SYSTEM's
    /// own egress differs from the pre-connect baseline. Anything else is an
    /// honest, actionable Persian failure — never a fake green dot.
    private fun verifyTunnelUp(baseline: String?, warpExit: Boolean): Verdict {
        // 1) default route through the TUN — xray installs it at startup;
        //    give it 15 s, then win the metric race ourselves.
        var routeUp = false
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (TunnelVerifier.tunRouteWins()) { routeUp = true; break }
            Thread.sleep(600)
        }
        if (!routeUp) {
            TunnelVerifier.log("xray route not winning the metric race — installing fallback default route")
            if (!TunnelVerifier.ensureTunRoute()) {
                return Verdict("تونل ساخته شد اما مسیر پیش‌فرض ویندوز از آن عبور نمی‌کند — «گزارش پیشرفته» را ببین")
            }
        }

        // 2) engine end-to-end: bytes through SOCKS 1819 reach the far end
        var exitIp: String? = null
        repeat(2) {
            if (exitIp != null) return@repeat
            exitIp = TunnelVerifier.traceIpViaSocks(EngineConfig.SOCKS_PORT, 15_000)
        }
        if (exitIp == null) {
            TunnelVerifier.log("engine egress: DEAD (no trace answer through SOCKS)")
            return if (warpExit) {
                // The ladder keeps trying — this message is transient.
                Verdict("تونل بالا آمد اما خروجی WARP ترافیک پاس نمی‌دهد", egressDead = true)
            } else {
                Verdict("تونل بالا آمد اما خروجی (نود) ترافیک پاس نمی‌دهد — نود دیگری را امتحان کن یا «به‌روزرسانی نودها» را بزن")
            }
        }
        TunnelVerifier.log("engine egress: $exitIp")

        // 3) system end-to-end: the machine itself must leave through the
        //    tunnel — same egress as the bare ISP means routing failed even
        //    though the engine is healthy.
        if (baseline != null) {
            val sysIp = TunnelVerifier.traceIpDirect(12_000)
            TunnelVerifier.log("system egress: ${sysIp ?: "unknown"} (baseline $baseline)")
            if (sysIp == baseline) {
                return Verdict("تونل سالم است اما ترافیک سیستم از آن عبور نمی‌کند (خروجی سیستم = خروجی قبل از اتصال) — فایروال یا آنتی‌ویروس را بررسی کن")
            }
        } else {
            TunnelVerifier.log("baseline unknown — system probe skipped (engine e2e already passed)")
        }
        return Verdict(null)
    }

    // ------------------------------------------------------------- watchdog

    /// Android's 30-second watchdog: process alive + SOCKS accepting + a
    /// real byte through the tunnel. Two consecutive strikes schedule the
    /// reconnect ladder instead of leaving a dead tunnel labelled «متصل».
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            var strikes = 0
            while (state == State.CONNECTED) {
                delay(30_000)
                if (state != State.CONNECTED) break
                val alive = withContext(Dispatchers.IO) {
                    (xray?.isAlive == true) &&
                        EngineProcess.portOpen(EngineConfig.SOCKS_PORT) &&
                        TunnelVerifier.traceIpViaSocks(EngineConfig.SOCKS_PORT, 10_000) != null
                }
                if (alive) {
                    strikes = 0
                    continue
                }
                strikes++
                TunnelVerifier.log("watchdog strike $strikes")
                if (strikes >= 2) {
                    scheduleAutoReconnect("تونل از کار افتاد")
                    break
                }
            }
        }
    }

    /// Android's scheduleAutoReconnect: backoff 5/15/30/60/120 s, suppressed
    /// when the user themselves pressed disconnect, ladder reset on a
    /// verified connect (see connectBlocking).
    private fun scheduleAutoReconnect(reason: String) {
        if (userDisconnect) return
        watchdogJob?.cancel()
        val attempt = reconnectAttempts.getAndIncrement()
        val ladder = TunnelVerifier.RECONNECT_BACKOFF_S
        val wait = ladder[attempt.coerceAtMost(ladder.size - 1)]
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            mutex.withLock {
                if (userDisconnect) return@withLock
                withContext(Dispatchers.IO) { stopEngines(null) }
                setState(State.PROVISIONING, "$reason — اتصال مجدد خودکار تا $wait ثانیه…")
                delay(wait * 1000L)
                if (!userDisconnect) withContext(Dispatchers.IO) { connectBlocking() }
            }
        }
    }

    // ------------------------------------------------------------ transports

    /// verifyTunnelUp's answer: null message = earned CONNECTED. `egressDead`
    /// marks the «tunnel up, exit deaf» case — the only one the WARP port
    /// ladder can actually cure.
    private class Verdict(val message: String?, val egressDead: Boolean = false)

    private class ResolvedExit(
        val outbound: JSONObject?,
        val sidecarConfig: JSONObject?,
        val summary: String,
        val isWarp: Boolean = false,
    )

    /// Picks the transport exactly like the phone: AUTO = fastest live SHARD
    /// node, falling back to WARP; SHARD = pinned/auto node; WARP = registered
    /// (or manual wg-quick) identity; MANUAL = the Advanced-tab wg-quick text.
    /// `warpPortIdx` selects the WARP UDP port for THIS attempt — the ladder
    /// rotates it when Cloudflare's default 2408 is throttled.
    private suspend fun resolveExit(warpPortIdx: Int): ResolvedExit? {
        val profile = when (mode) {
            Mode.WARP -> null
            Mode.MANUAL -> null
            Mode.SHARD -> pickNode(autoAllowed = false)
            Mode.AUTO -> pickNode(autoAllowed = true)
        }

        // MANUAL: the wg-quick text wins, like the iOS Advanced tab. Its own
        // endpoint is pinned by the user — the port ladder does not touch it.
        if (mode == Mode.MANUAL) {
            val text = manualWgQuick
                ?: run { fail("حالت دستی فعال است اما کانفیگ wg-quick ذخیره نشده — تب پیشرفته را پر کن"); return null }
            val identity = WarpProvisioner.parseWGQuick(text)
                ?: run { fail("کانفیگ wg-quick قابل خواندن نیست — PrivateKey و PublicKey و Endpoint لازم است"); return null }
            return ResolvedExit(EngineConfig.warpExit(identity), null, "دستی — ${identity.endpoint}")
        }

        // WARP (or AUTO fallback below) — endpoint rides the port ladder.
        if (profile == null) {
            setState(State.PROVISIONING, "ثبت‌نام WARP…")
            val identity = ensureWarpIdentity() ?: return null
            val port = WarpProvisioner.portLadder[warpPortIdx.coerceIn(0, WarpProvisioner.portLadder.size - 1)]
            val endpoint = endpointWithPort(identity.endpoint, port)
            return ResolvedExit(
                EngineConfig.warpExit(identity.copy(endpoint = endpoint)),
                null,
                "WARP — $endpoint",
                isWarp = true,
            )
        }

        return when (profile.configType) {
            com.beevpn.win.shard.ShardType.ANYTLS ->
                ResolvedExit(
                    EngineConfig.anytlsExit(),
                    AnytlsSidecarConfig.build(profile, File(AppPaths.logsDir, "sidecar.log").absolutePath),
                    "AnyTLS — ${profile.displayLabel()}",
                )
            else -> {
                if (!com.beevpn.win.shard.ShardOutboundBuilder.supports(profile)) {
                    fail("نوع این نود (${profile.configType}) در این نسخه پشتیبانی نمی‌شود")
                    null
                } else {
                    ResolvedExit(EngineConfig.nodeExit(profile), null, "SHARD — ${profile.displayLabel()}")
                }
            }
        }
    }

    /// "engage.cloudflareclient.com:2408" + 500 → "engage.cloudflareclient.com:500".
    private fun endpointWithPort(endpoint: String, port: Int): String =
        endpoint.substringBeforeLast(":") + ":" + port

    /// AUTO prefers the fastest alive node and falls back to WARP; SHARD mode
    /// refuses to silently change transport — it reports the dead pool instead.
    private fun pickNode(autoAllowed: Boolean): ShardProfile? {
        val pool = refreshPool()
        if (pool.isEmpty() && autoAllowed) return null
        if (pool.isEmpty()) {
            fail("استخر SHARD خالی است — اتصال اینترنت را بررسی کن یا «به‌روزرسانی نودها» را بزن")
            return null
        }
        val pinned = pinnedNodeKey?.let { key -> pool.firstOrNull { it.key() == key } }
        if (pinned != null) {
            if (ShardProber.probeOne(pinned) >= 0) return pinned
            if (!autoAllowed) {
                fail("نود انتخابی پاسخ نمی‌دهد — نود دیگری را در تب نودها انتخاب کن")
                return null
            }
        }
        setState(State.PROVISIONING, "پینگ نودها…")
        val ranked = ShardProber.probeAll(pool)
        lastProbed = ranked
        val best = ranked.firstOrNull { it.latencyMs >= 0 }
        if (best != null) return best.profile
        if (autoAllowed) return null
        fail("هیچ نودی از استخر SHARD پاسخ نداد — اتصال اینترنت را بررسی کن")
        return null
    }

    /// The saved identity, or a fresh registration — first direct, then
    /// through a running SHARD session, mirroring IdentityProvisioner.kt.
    ///
    /// Migration: identities registered before 1.2.0 carry no client_id (the
    /// reserved bytes Cloudflare requires inside the handshake). No port in
    /// the ladder can save those — they are purged so a fresh registration
    /// mints a complete identity.
    private suspend fun ensureWarpIdentity(): WarpIdentity? {
        val current = savedOrManual()
        if (current != null) {
            if (current.deviceId == "manual" || current.clientIdB64.isNotBlank()) return current
            TunnelVerifier.log("saved WARP identity has no client_id (pre-1.2.0) — refreshing registration")
            WarpProvisioner.purge()
        }
        return try {
            WarpProvisioner.register(null)
        } catch (e: Exception) {
            fail(
                (e.message ?: "ثبت‌نام WARP ناموفق بود") +
                    " — می‌توانی از تب «نودها» یک نود SHARD انتخاب کنی یا در تب پیشرفته کانفیگ دستی بگذاری",
            )
            null
        }
    }

    private fun savedOrManual(): WarpIdentity? {
        // A manual wg-quick config outranks the registered identity, exactly
        // like WarpProvisioner.resolveConfig() on iOS.
        manualWgQuick?.let { text ->
            WarpProvisioner.parseWGQuick(text)?.let { return it }
        }
        return WarpProvisioner.saved()
    }

    fun regenerateIdentity() {
        scope.launch {
            mutex.withLock {
                withContext(Dispatchers.IO) {
                    stopEngines("هویت جدید…")
                    WarpProvisioner.purge()
                    SecureStore.put("warp.manualConfig", "")
                }
            }
        }
    }

    // ---------------------------------------------------------- disconnect

    /// Stops the engines WITHOUT touching the UI state — the ladder rotation
    /// and the early-exit crash handler use it while a connect is still
    /// visually in progress.
    private fun killEnginesQuiet() {
        watchdogJob?.cancel()
        sidecar?.stop()
        sidecar = null
        xray?.stop()
        xray = null
        if (AppPaths.isWindows()) TunnelVerifier.removeFallbackRoute()
    }

    private fun stopEngines(caption: String?) {
        killEnginesQuiet()
        connectedSince = 0
        setState(State.DISCONNECTED, caption ?: "قطع است")
    }
}
