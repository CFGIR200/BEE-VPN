package com.beevpn.win.tunnel

import com.beevpn.win.AppPaths
import com.beevpn.win.SecureStore
import com.beevpn.win.engine.AnytlsSidecarConfig
import com.beevpn.win.engine.CoreExtractor
import com.beevpn.win.engine.EngineConfig
import com.beevpn.win.engine.EngineProcess
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/// Orchestrates the engines — the desktop twin of MsnGuardVpnService's
/// transport bring-up: pick the transport, build the config, start xray
/// (TUN + SOCKS 1819), start the anytls sidecar when the exit needs it,
/// and report honest state transitions back to the UI.
object TunnelController {

    enum class Mode { AUTO, SHARD, WARP, MANUAL }

    enum class State { DISCONNECTED, PROVISIONING, STARTING, CONNECTED, STOPPING, FAILED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    // Snapshot state — the UI observes these reactively.
    var state by mutableStateOf(State.DISCONNECTED); private set
    var statusText by mutableStateOf("قطع است"); private set
    var lastError by mutableStateOf<String?>(null); private set
    var lastExitSummary by mutableStateOf(""); private set
    var connectedSince: Long = 0

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
    /// a subscription refresh never drops them.
    private fun extraProfiles(): List<ShardProfile> {
        val f = File(AppPaths.baseDir, "shard-extra.txt")
        if (!f.isFile) return emptyList()
        return ShardImport.parseBatch(f.readText())
    }

    // ------------------------------------------------------------- connect

    fun connect() {
        scope.launch { mutex.withLock { withContext(Dispatchers.IO) { connectBlocking() } } }
    }

    fun disconnect() {
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
        if (state == State.CONNECTED || state == State.STARTING || state == State.PROVISIONING) return
        lastError = null
        setState(State.PROVISIONING, "آماده‌سازی…")

        val cores = CoreExtractor.ensure()
        if (cores == null) {
            fail("موتورهای VPN (xray.exe / sing-box.exe) پیدا نشدند — بستهٔ رسمی (MSI یا ZIP قابل حمل) را نصب کن یا پوشهٔ cores را کنار exe بگذار")
            return
        }
        val needsTun = AppPaths.isWindows()
        if (needsTun && !com.beevpn.win.engine.WindowsAdmin.isAdmin()) {
            fail("ساخت تونل VPN دسترسی مدیر می‌خواهد — برنامه را با «Run as administrator» باز کن")
            return
        }

        try {
            val exit = resolveExit()   // picks transport + provisions WARP when needed
            if (exit == null) { return } // resolveExit already called fail()
            val sidecarNeeded = exit.sidecarConfig != null

            setState(State.STARTING, "راه‌اندازی موتور…")

            // xray: TUN + SOCKS 1819 + exit
            val xrayConfig = EngineConfig.build(
                useTun = needsTun,
                exitOutbound = exit.outbound,
                fragmentOnDirect = fragmentOnDirect,
            )
            val xrayConfigFile = File(AppPaths.runDir, "xray.json")
            xrayConfigFile.writeText(xrayConfig.toString(2))

            if (sidecarNeeded) {
                val sidecarFile = File(AppPaths.runDir, "sidecar.json")
                sidecarFile.writeText(exit.sidecarConfig!!.toString(2))
            }

            val xrayExe = File(cores, "xray.exe")
            val proc = EngineProcess("xray", xrayExe, listOf("run", "-c", xrayConfigFile.absolutePath), cores)
            proc.onLine = { line ->
                EngineProcess.logLine("[xray] $line")
                if (line.contains("failed", true) || line.contains("error", true)) EngineProcess.logLine(line)
            }
            proc.onExit = { code ->
                if (state == State.CONNECTED || state == State.STARTING) {
                    scope.launch {
                        mutex.withLock { withContext(Dispatchers.IO) {
                            if (code != 0 && state == State.CONNECTED) {
                                fail("موتور VPN ناگهان متوقف شد (کد $code) — جزئیات در گزارش پیشرفته")
                            } else {
                                stopEngines(null)
                            }
                        } }
                    }
                }
            }
            proc.start()
            xray = proc
            if (!EngineProcess.awaitPort(EngineConfig.SOCKS_PORT, 12_000) { proc.isAlive }) {
                fail("xray روی پورت ${EngineConfig.SOCKS_PORT} بالا نیامد — گزارش پیشرفته را ببین")
                return
            }

            if (sidecarNeeded) {
                val sbExe = File(cores, "sing-box.exe")
                val sc = EngineProcess(
                    "sing-box", sbExe,
                    listOf("run", "-c", File(AppPaths.runDir, "sidecar.json").absolutePath),
                    cores,
                )
                sc.onLine = { EngineProcess.logLine("[sing-box] $it") }
                sc.onExit = { code ->
                    if (state == State.CONNECTED && code != 0) {
                        scope.launch {
                            mutex.withLock { withContext(Dispatchers.IO) {
                                fail("سایدکار AnyTLS متوقف شد (کد $code)")
                            } }
                        }
                    }
                }
                sc.start()
                sidecar = sc
                if (!EngineProcess.awaitPort(EngineConfig.ANYTLS_SIDECAR_PORT, 12_000) { sc.isAlive }) {
                    fail("سایدکار AnyTLS روی پورت ${EngineConfig.ANYTLS_SIDECAR_PORT} بالا نیامد")
                    return
                }
            }

            connectedSince = System.currentTimeMillis() / 1000
            setState(State.CONNECTED, "متصل است — ${exit.summary}")
            lastExitSummary = exit.summary
        } catch (e: Exception) {
            fail("خطای غیرمنتظره: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private class ResolvedExit(
        val outbound: JSONObject?,
        val sidecarConfig: JSONObject?,
        val summary: String,
    )

    /// Picks the transport exactly like the phone: AUTO = fastest live SHARD
    /// node, falling back to WARP; SHARD = pinned/auto node; WARP = registered
    /// (or manual wg-quick) identity; MANUAL = the Advanced-tab wg-quick text.
    private suspend fun resolveExit(): ResolvedExit? {
        val profile = when (mode) {
            Mode.WARP -> null
            Mode.MANUAL -> null
            Mode.SHARD -> pickNode(autoAllowed = false)
            Mode.AUTO -> pickNode(autoAllowed = true)
        }

        // MANUAL: the wg-quick text wins, like the iOS Advanced tab.
        if (mode == Mode.MANUAL) {
            val text = manualWgQuick
                ?: run { fail("حالت دستی فعال است اما کانفیگ wg-quick ذخیره نشده — تب پیشرفته را پر کن"); return null }
            val identity = WarpProvisioner.parseWGQuick(text)
                ?: run { fail("کانفیگ wg-quick قابل خواندن نیست — PrivateKey و PublicKey و Endpoint لازم است"); return null }
            return ResolvedExit(EngineConfig.warpExit(identity), null, "دستی — ${identity.endpoint}")
        }

        // WARP (or AUTO fallback below)
        if (profile == null) {
            setState(State.PROVISIONING, "ثبت‌نام WARP…")
            val identity = ensureWarpIdentity()
            return identity?.let { ResolvedExit(EngineConfig.warpExit(it), null, "WARP — ${it.endpoint}") }
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
    private suspend fun ensureWarpIdentity(): WarpIdentity? {
        savedOrManual()?.let { return it }
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

    private fun stopEngines(caption: String?) {
        sidecar?.stop()
        sidecar = null
        xray?.stop()
        xray = null
        connectedSince = 0
        setState(State.DISCONNECTED, caption ?: "قطع است")
    }
}
