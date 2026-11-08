package com.beevpn.win.tunnel

import com.beevpn.win.AppPaths
import com.beevpn.win.faNum
import com.beevpn.win.license.LicenseManager
import com.beevpn.win.SecureStore
import com.beevpn.win.engine.AetherEngine
import com.beevpn.win.engine.AnytlsSidecarConfig
import com.beevpn.win.engine.CoreExtractor
import com.beevpn.win.engine.EngineConfig
import com.beevpn.win.engine.EngineProcess
import com.beevpn.win.engine.WindowsAdmin
import com.beevpn.win.shard.ShardImport
import com.beevpn.win.shard.ShardProfile
import com.beevpn.win.shard.ShardProber
import com.beevpn.win.shard.ShardRace
import com.beevpn.win.shard.ShardSubscription
import com.beevpn.win.warp.WarpIdentity
import com.beevpn.win.warp.WarpProvisioner
import com.beevpn.win.warp.IdentityRelayClient
import java.net.InetSocketAddress
import java.net.Proxy
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

/// Orchestrates the engines — the desktop twin of MsnGuardVpnService, now
/// running the PHONE'S OWN ENGINE for the WARP family:
///
///   WARP (wireguard) / MASQUE / GOOL (WARP-on-WARP) / MiM (MASQUE-in-MASQUE)
///     → aether.exe with the exact AETHER_* env Android's CoreConfig.env()
///       sends: the Ptech endpoint scanner (7 CIDRs × 2408/500/1701/4500),
///       ECH-backed registration, the lastconn quick-reconnect ring, MASQUE
///       over H2, gool classic. xray becomes the TUN bridge into the core's
///       SOCKS 1819 — the same shape as tun2socks → 1819 on the phone.
///
///   SHARD / AnyTLS → xray (exactly what the phone runs), now with Android's
///     REAL-request race (24-wide slices through probe inbounds 21100+i,
///     5 s probes, 12 s budget, early exit at 5 successes), health memory,
///     endpoint diversification (fm first), VLESS mux 8/16/skip-443 and the
///     live-tunnel policy.
///
///   AUTO → the Android order: fastest live SHARD node, falling back to WARP.
///   MANUAL → the user's wg-quick text on the xray wireguard outbound.
///
/// Every connect still has to EARN «متصل است» — route + engine e2e + system
/// e2e, then the 30 s watchdog with Android's 5/15/30/60/120 ladder.
object TunnelController {

    enum class Mode { AUTO, SHARD, WARP, MASQUE, GOOL, MIM, TOR, MANUAL }

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
    private var aether: EngineProcess? = null
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
        // A human pressing the dial is a fresh start — the ladder belongs to
        // the auto-reconnect cycle, not to the user's own action.
        reconnectAttempts.set(0)
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

        // Android clears the cached gateway after two failed attempts — here
        // that is the engine's lastconn quick-reconnect ring: a cached edge
        // that stopped answering must be evicted, not re-verified forever.
        if (reconnectAttempts.get() >= 2) {
            AetherEngine.clearQuickReconnectCache()
            TunnelVerifier.log("reconnect #${reconnectAttempts.get()} — lastconn cache evicted, the engine scans fresh")
        }

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
            fail("موتورهای VPN (xray.exe / sing-box.exe / aether.exe) پیدا نشدند — بستهٔ رسمی (MSI یا ZIP قابل حمل) را نصب کن یا پوشهٔ cores را کنار exe بگذار")
            return
        }
        // Elevated (packaged) runs ask Defender once to leave the cores dir
        // alone — the anti-quarantine treaty that keeps the engines intact.
        if (com.beevpn.win.engine.WindowsAdmin.isAdmin()) CoreExtractor.defenderExclusionOnce()

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

            val ipv6 = withContext(Dispatchers.IO) { TunnelVerifier.ipv6Available() }
            val (exit, followers) = resolveExit()
            if (exit == null) return   // resolveExit already failed with a message

            // ── the attempt ladder ─────────────────────────────────────────
            // The 1.6.2 field lesson (the «خروجی (نود) ترافیک پاس نمی‌دهد»
            // reports): a node that passed the 12 s probe can be dead by the
            // time the real tunnel verified. Android's ShardManager never
            // gives up after ONE winner — it re-selects. So the ladder now
            // walks every live race candidate before declaring failure, and
            // AUTO then escalates to the engine rungs (WireGuard → MASQUE)
            // exactly like the phone's Auto-Scan: a UDP-blocked carrier kills
            // WG every time while MASQUE (HTTP/2 over TCP) still rides. Every
            // attempt re-verifies with the SAME honest checks; a tunnel only
            // earns «متصل است» from real bytes.
            val attempts = ArrayDeque<Attempt>()
            fun enqueueNode(e: Exit) {
                when (e) {
                    is Exit.Node -> attempts.add(Attempt.NodeTunnel(e.profile, anytls = false))
                    is Exit.Anytls -> attempts.add(Attempt.NodeTunnel(e.profile, anytls = true))
                    else -> {}
                }
            }
            enqueueNode(exit)
            followers.forEach { enqueueNode(it) }
            if (mode == Mode.AUTO) {
                when (exit) {
                    is Exit.Node, is Exit.Anytls -> {
                        attempts.add(Attempt.Engine(AetherEngine.Protocol.WG))
                        attempts.add(Attempt.Engine(AetherEngine.Protocol.MASQUE))
                    }
                    is Exit.Aether -> if (exit.protocol == AetherEngine.Protocol.WG) {
                        attempts.add(Attempt.Engine(AetherEngine.Protocol.MASQUE))
                    }
                    else -> {}
                }
            }

            var verdict: Verdict? = null
            var earned = false
            while (true) {
                val attempt = attempts.removeFirstOrNull()
                if (attempt == null) break
                if (verdict != null) {
                    // The previous attempt left engines/routes behind — a clean
                    // slate per attempt, exactly like a fresh connect.
                    killEnginesQuiet()
                    setState(State.PROVISIONING, "تلاش مسیر بعدی…")
                }
                verdict = when (attempt) {
                    is Attempt.NodeTunnel ->
                        if (attempt.anytls) bringUpAnytls(attempt.profile, cores, baseline, ipv6)
                        else bringUpShardNode(attempt.profile, cores, baseline, ipv6)
                    is Attempt.Engine -> {
                        TunnelVerifier.log(
                            "verification failed — the ladder escalates to the ${attempt.protocol.id} engine (Android parity)",
                        )
                        bringUpAether(attempt.protocol, cores, baseline, ipv6)
                    }
                }
                if (verdict.message == null) { earned = true; break }
            }

            if (!earned) {
                fail(verdict?.message ?: "اتصال برقرار نشد")
                return
            }

            connectedSince = System.currentTimeMillis() / 1000
            lastExitSummary = exitSummary
            setState(State.CONNECTED, "متصل است — $exitSummary")
            startWatchdog()
        } catch (e: Exception) {
            fail("خطای غیرمنتظره: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------ aether

    /// The WARP-family path — the phone's engine with the phone's env:
    /// aether.exe serves SOCKS 1819, xray bridges the system TUN into it.
    private suspend fun bringUpAether(
        protocol: AetherEngine.Protocol,
        cores: File,
        baseline: String?,
        ipv6: Boolean,
    ): Verdict {
        setState(State.PROVISIONING, "هویت WARP…")
        val identityFailure = ensureAetherIdentity(protocol, cores)
        if (identityFailure != null) return Verdict(identityFailure)

        setState(State.STARTING, "راه‌اندازی موتور اندروید (aether)…")
        val exe = AetherEngine.exe(cores)
            ?: return Verdict("aether.exe در بستهٔ برنامه نیست — بستهٔ رسمی را نصب کن")
        val proc = EngineProcess(
            "aether", exe, emptyList(), AppPaths.baseDir,
            env = AetherEngine.connectEnv(protocol, ipv6),
        )
        proc.onLine = { line -> EngineProcess.logLine("[aether] $line") }
        proc.onExit = { code ->
            if (state == State.CONNECTED) {
                scheduleAutoReconnect("موتور aether ناگهان متوقف شد (کد $code)")
            } else if (state == State.STARTING || state == State.VERIFYING) {
                scope.launch {
                    mutex.withLock { withContext(Dispatchers.IO) { killEnginesQuiet() } }
                }
            }
        }
        proc.start()
        aether = proc
        // The per-protocol budget Android's waitForSocksReadyBudget enforces:
        // WG 110 s / MASQUE+MiM 300 s / gool 200 s. A cold start over a
        // filtered link legitimately needs scan (≤120 s) + registration
        // retries before the listener opens — killing the run earlier just
        // restarts the whole cost, which was the 110-second reconnect loop.
        val socksBudgetMs = AetherEngine.socksReadyBudgetMs(protocol)
        if (!EngineProcess.awaitPort(AetherEngine.SOCKS_PORT, socksBudgetMs) { proc.isAlive }) {
            return Verdict("موتور aether روی پورت ${AetherEngine.SOCKS_PORT} بالا نیامد (بودجهٔ ${faNum((socksBudgetMs / 1000).toInt())} ثانیه، مثل اندروید) — گزارش پیشرفته را ببین")
        }
        TunnelVerifier.log("aether SOCKS up on ${AetherEngine.SOCKS_PORT} (${protocol.id})")

        // The TUN bridge — the desktop twin of tun2socks: TUN → socks 1819.
        // No xray socks inbound: the core owns 1819 exactly like the phone.
        setState(State.STARTING, "ساخت تونل سیستم…")
        val bridge = EngineConfig.build(
            useTun = true,
            ipv6Available = ipv6,
            exitOutbound = EngineConfig.aetherBridgeExit(),
            fragmentOnDirect = false,
            includeSocksIn = false,
        )
        val bridgeFile = File(AppPaths.runDir, "xray.json")
        bridgeFile.writeText(bridge.toString(2))
        val xr = EngineProcess(
            "xray", File(cores, "xray.exe"),
            listOf("run", "-c", bridgeFile.absolutePath), cores,
        )
        xr.onLine = { line -> EngineProcess.logLine("[xray] $line") }
        xr.onExit = { code ->
            if (state == State.CONNECTED) {
                scheduleAutoReconnect("موتور VPN ناگهان متوقف شد (کد $code)")
            } else if (state == State.STARTING || state == State.VERIFYING) {
                scope.launch {
                    mutex.withLock { withContext(Dispatchers.IO) { killEnginesQuiet() } }
                }
            }
        }
        xr.start()
        xray = xr

        setState(State.VERIFYING, "بررسی تونل سیستم…")
        exitSummary = when (protocol) {
            AetherEngine.Protocol.WG -> "WARP — موتور اندروید"
            AetherEngine.Protocol.MASQUE -> "MASQUE — موتور اندروید"
            AetherEngine.Protocol.GOOL -> "WARP روی WARP (گول) — موتور اندروید"
            AetherEngine.Protocol.MIM -> "MASQUE روی MASQUE — موتور اندروید"
        }
        return withContext(Dispatchers.IO) { verifyTunnelUp(baseline, warpExit = true) }
    }

    /// The identity ladder, in the phone's order (IdentityRelayClient +
    /// IdentityProvisioner + provisionIdentityThroughShard, top to bottom):
    ///
    ///   1. saved identity — a complete file is never touched, so warm
    ///      identities reconnect through the lastconn ring at full speed;
    ///   2. the in-process ladder — relay Workers (when the build carries the
    ///      HMAC secret), then the direct API across its host ladder. This is
    ///      what rescues every fresh install on a carrier that poisons the
    ///      account API — and what the 1.4.0 desktop did not have at all,
    ///      which is the «هویت MASQUE ساخته نشد» report from the field;
    ///   3. registration through SHARD — a listener-only xray on 1824 (the
    ///      phone's ShardManager port) with the race winner as the exit, the
    ///      engine's own registration riding it via AETHER_UPSTREAM, and an
    ///      in-process SOCKS enrollment as the fallback of the fallback;
    ///   4. the engine's own ECH-backed registration, direct (AETHER_REGISTER
    ///      — the same call the phone's WarpIdentityFetcher makes).
    ///
    /// Registration is idempotent at every rung. Returns null when the
    /// identity is ready, else the Persian failure — the message flows back
    /// as the connect's Verdict so a provisioning failure can NEVER fall
    /// through to a fake «متصل است».
    private suspend fun ensureAetherIdentity(protocol: AetherEngine.Protocol, cores: File): String? {
        if (AetherEngine.identitiesReady(protocol)) return null

        if (protocol == AetherEngine.Protocol.WG) {
            setState(State.PROVISIONING, "ثبت‌نام WARP…")
            val saved = WarpProvisioner.saved()
            if (saved != null && saved.clientIdB64.isNotBlank()) {
                AetherEngine.writeWgIdentity(saved)
                if (AetherEngine.identitiesReady(protocol)) return null
            }
        }

        setState(State.PROVISIONING, "دریافت هویت از سرورهای پروژه…")
        // The ladder is walked ALWAYS — the phone never gates its direct rung
        // behind the blocked-API cache (that cache only steers the SHARD
        // orchestration there). On a filtered carrier the pinned-edge routes
        // are exactly what still works, and 1.6.1's allowDirect=false verdict
        // (a hostname-only probe) skipped the whole rung — the report was
        // «هویت MASQUE ساخته نشد» from networks the phone registers from.
        runCatching { IdentityRelayClient.provision(protocol) }
            .onSuccess { outcome ->
                if (outcome.success) {
                    TunnelVerifier.log("identity via relay/direct ladder: ${outcome.error}")
                    if (AetherEngine.identitiesReady(protocol)) return null
                } else {
                    TunnelVerifier.log("relay/direct ladder failed: ${outcome.error}")
                }
            }
            .onFailure { TunnelVerifier.log("relay/direct ladder threw: ${it.message}") }

        setState(State.PROVISIONING, "ثبت‌نام از طریق تونل SHARD…")
        if (registerThroughShard(protocol, cores)) return null

        setState(State.PROVISIONING, "ثبت‌نام از مسیر رمزگذاری‌شده (ECH)…")
        if (AetherEngine.registerIfNeeded(protocol, cores, onLine = { line ->
            EngineProcess.logLine("[aether-register] $line")
        })) return null

        return "هویت " + when (protocol) {
            AetherEngine.Protocol.WG -> "WARP"
            AetherEngine.Protocol.MASQUE -> "MASQUE"
            AetherEngine.Protocol.GOOL -> "گول (WARP روی WARP)"
            AetherEngine.Protocol.MIM -> "ماسک روی ماسک"
        } + " ساخته نشد — اتصال اینترنت را بررسی کن، یک بار به حالت SHARD وصل شو و دوباره تلاش کن؛ در تب پیشرفته هم می‌توانی کانفیگ دستی بگذاری"
    }

    /// The SHARD-backed registration — the desktop provisionIdentityThroughShard.
    /// Raises a listener-only xray on 1824 (no TUN, nothing the user sees),
    /// lets the engine register through it via AETHER_UPSTREAM, and falls back
    /// to an in-process SOCKS enrollment when the engine's register cannot.
    /// Every identity file the protocol needs (gool/mim carry two) is handled.
    private suspend fun registerThroughShard(
        protocol: AetherEngine.Protocol,
        cores: File,
    ): Boolean {
        val pool = refreshPool()
        if (pool.isEmpty()) {
            TunnelVerifier.log("SHARD-backed registration skipped — the node pool is empty")
            return false
        }
        setState(State.PROVISIONING, "ثبت‌نام — مسابقه نودها…")
        var winner: ShardProfile? = null
        withContext(Dispatchers.IO) {
            winner = ShardRace.sweep(pool, cores) { progress ->
                setState(State.PROVISIONING, "ثبت‌نام — $progress")
            }
        }
        val node = winner ?: run {
            TunnelVerifier.log("SHARD-backed registration skipped — no node answered")
            return false
        }

        setState(State.PROVISIONING, "ثبت‌نام از مسیر تونل SHARD…")
        val cfgFile = File(AppPaths.runDir, "provision.json")
        cfgFile.writeText(EngineConfig.provisioningConfig(node).toString(2))
        val xr = EngineProcess(
            "xray-provision", File(cores, "xray.exe"),
            listOf("run", "-c", cfgFile.absolutePath), cores,
        )
        xr.onLine = { line -> EngineProcess.logLine("[xray-provision] $line") }
        xr.start()
        try {
            if (!EngineProcess.awaitPort(EngineConfig.PROVISION_SOCKS_PORT, 20_000) { xr.isAlive }) {
                TunnelVerifier.log("provisioning listener did not open — SHARD registration abandoned")
                return false
            }
            val upstream = "socks5://127.0.0.1:${EngineConfig.PROVISION_SOCKS_PORT}"

            // 2a — the engine's own registration riding the tunnel: the exact
            // mechanism the phone uses (AETHER_UPSTREAM → the SOCKS listener).
            val viaEngine = AetherEngine.registerIfNeeded(
                protocol, cores,
                budgetMs = 240_000,
                onLine = { line -> EngineProcess.logLine("[aether-register] $line") },
                upstream = upstream,
            )
            if (viaEngine) {
                TunnelVerifier.log("identity registered through SHARD (engine + AETHER_UPSTREAM)")
                return true
            }

            // 2b — in-process enrollment over the same SOCKS listener: speaks
            // the registration API itself (IdentityRelayClient), so an engine
            // register that cannot still leaves a complete identity on disk.
            setState(State.PROVISIONING, "ثبت‌نام مستقیم از مسیر SHARD…")
            val outcome = runCatching {
                IdentityRelayClient.provisionThroughSocks(
                    protocol,
                    Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", EngineConfig.PROVISION_SOCKS_PORT)),
                )
            }
            outcome.onSuccess {
                if (it.success) {
                    TunnelVerifier.log("identity registered through SHARD (in-process SOCKS)")
                    if (AetherEngine.identitiesReady(protocol)) return true
                } else {
                    TunnelVerifier.log("in-process SHARD enrollment failed: ${it.error}")
                }
            }.onFailure { TunnelVerifier.log("in-process SHARD enrollment threw: ${it.message}") }
            return false
        } finally {
            xr.stop()
        }
    }
    /// The Tor transport — the tunnel through tor (AETHER_TOR=reverse), the
    /// same core facility the phone's Tor mode is the sibling of: the tor
    /// circuit carries BOTH the registration and the wireguard tunnel, with
    /// lyrebird (obfs4/snowflake/meek) as the pluggable transport. Bootstrap
    /// is the slow part, so the listener budget is the MASQUE-family one.
    private suspend fun bringUpTor(
        cores: File,
        baseline: String?,
        ipv6: Boolean,
    ): Verdict {
        setState(State.PROVISIONING, "هویت WARP…")
        val identityFailure = ensureAetherIdentity(AetherEngine.Protocol.WG, cores)
        if (identityFailure != null) return Verdict(identityFailure)

        setState(State.STARTING, "راه‌اندازی موتور اندروید (aether) روی تور…")
        val exe = AetherEngine.exe(cores)
            ?: return Verdict("aether.exe در بستهٔ برنامه نیست — بستهٔ رسمی را نصب کن")
        val proc = EngineProcess(
            "aether", exe, emptyList(), AppPaths.baseDir,
            env = AetherEngine.torEnv(AetherEngine.Protocol.WG, cores),
        )
        proc.onLine = { line -> EngineProcess.logLine("[aether-tor] $line") }
        proc.onExit = { code ->
            if (state == State.CONNECTED) {
                scheduleAutoReconnect("موتور aether ناگهان متوقف شد (کد $code)")
            } else if (state == State.STARTING || state == State.VERIFYING) {
                scope.launch {
                    mutex.withLock { withContext(Dispatchers.IO) { killEnginesQuiet() } }
                }
            }
        }
        proc.start()
        aether = proc
        val socksBudgetMs = AetherEngine.socksReadyBudgetMs(AetherEngine.Protocol.MASQUE)
        if (!EngineProcess.awaitPort(AetherEngine.SOCKS_PORT, socksBudgetMs) { proc.isAlive }) {
            return Verdict("موتور aether روی پورت ${AetherEngine.SOCKS_PORT} بالا نیامد (بودجهٔ ${faNum((socksBudgetMs / 1000).toInt())} ثانیه، مثل اندروید) — گزارش پیشرفته را ببین")
        }
        TunnelVerifier.log("aether SOCKS up on ${AetherEngine.SOCKS_PORT} (tor-reverse/wg)")

        setState(State.STARTING, "ساخت تونل سیستم…")
        val bridge = EngineConfig.build(
            useTun = true,
            ipv6Available = ipv6,
            exitOutbound = EngineConfig.aetherBridgeExit(),
            fragmentOnDirect = false,
            includeSocksIn = false,
        )
        val bridgeFile = File(AppPaths.runDir, "xray.json")
        bridgeFile.writeText(bridge.toString(2))
        val xr = EngineProcess(
            "xray", File(cores, "xray.exe"),
            listOf("run", "-c", bridgeFile.absolutePath), cores,
        )
        xr.onLine = { line -> EngineProcess.logLine("[xray] $line") }
        xr.onExit = { code ->
            if (state == State.CONNECTED) {
                scheduleAutoReconnect("موتور VPN ناگهان متوقف شد (کد $code)")
            } else if (state == State.STARTING || state == State.VERIFYING) {
                scope.launch {
                    mutex.withLock { withContext(Dispatchers.IO) { killEnginesQuiet() } }
                }
            }
        }
        xr.start()
        xray = xr

        setState(State.VERIFYING, "بررسی تونل سیستم…")
        exitSummary = "تور (WARP روی تور) — موتور اندروید"
        return withContext(Dispatchers.IO) { verifyTunnelUp(baseline, warpExit = true) }
    }

    // ------------------------------------------------------------- shard

    /// SHARD/AnyTLS live path: the race winner as the single xray outbound —
    /// with Android's mux (VLESS), the live policy, and the anytls sidecar.
    private suspend fun bringUpShardNode(
        profile: ShardProfile,
        cores: File,
        baseline: String?,
        ipv6: Boolean,
    ): Verdict {
        setState(State.STARTING, "راه‌اندازی موتور…")
        val xrayConfig = EngineConfig.build(
            useTun = true,
            ipv6Available = ipv6,
            exitOutbound = EngineConfig.nodeExit(profile),
            fragmentOnDirect = fragmentOnDirect,
            shardParity = true,
        )
        val xrayConfigFile = File(AppPaths.runDir, "xray.json")
        xrayConfigFile.writeText(xrayConfig.toString(2))
        return startXrayTunnel(xrayConfigFile, cores, null, baseline, "SHARD — ${profile.displayLabel()}", warpExit = false)
    }

    private suspend fun bringUpAnytls(
        profile: ShardProfile,
        cores: File,
        baseline: String?,
        ipv6: Boolean,
    ): Verdict {
        setState(State.STARTING, "راه‌اندازی موتور…")
        val xrayConfig = EngineConfig.build(
            useTun = true,
            ipv6Available = ipv6,
            exitOutbound = EngineConfig.anytlsExit(),
            fragmentOnDirect = fragmentOnDirect,
            shardParity = true,
        )
        val xrayConfigFile = File(AppPaths.runDir, "xray.json")
        xrayConfigFile.writeText(xrayConfig.toString(2))
        val sidecarConfig = AnytlsSidecarConfig.build(profile, File(AppPaths.logsDir, "sidecar.log").absolutePath)
        val sidecarFile = File(AppPaths.runDir, "sidecar.json")
        sidecarFile.writeText(sidecarConfig.toString(2))
        return startXrayTunnel(xrayConfigFile, cores, sidecarFile, baseline, "AnyTLS — ${profile.displayLabel()}", warpExit = false)
    }

    /// MANUAL: the wg-quick text on the xray wireguard outbound — unchanged
    /// from 1.2.0 (the escape hatch that also runs plain non-Cloudflare
    /// WireGuard servers, which the aether core is not shaped for).
    private suspend fun bringUpManualWarp(
        identity: WarpIdentity,
        cores: File,
        baseline: String?,
        ipv6: Boolean,
    ): Verdict {
        setState(State.STARTING, "راه‌اندازی موتور…")
        val xrayConfig = EngineConfig.build(
            useTun = true,
            ipv6Available = ipv6,
            exitOutbound = EngineConfig.warpExit(identity),
            fragmentOnDirect = fragmentOnDirect,
        )
        val xrayConfigFile = File(AppPaths.runDir, "xray.json")
        xrayConfigFile.writeText(xrayConfig.toString(2))
        return startXrayTunnel(
            xrayConfigFile, cores, null, baseline,
            "دستی — ${identity.endpoint}", warpExit = true,
        )
    }

    /// Shared xray launcher: config file + optional sidecar, port wait,
    /// then the system verification. The single tunnel shape every xray mode
    /// rides (the SOCKS 1819 inbound is xray's own here).
    private suspend fun startXrayTunnel(
        configFile: File,
        cores: File,
        sidecarFile: File?,
        baseline: String?,
        summary: String,
        warpExit: Boolean,
    ): Verdict {
        val proc = EngineProcess(
            "xray", File(cores, "xray.exe"),
            listOf("run", "-c", configFile.absolutePath), cores,
        )
        proc.onLine = { line -> EngineProcess.logLine("[xray] $line") }
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

        if (sidecarFile != null) {
            val sc = EngineProcess(
                "sing-box", File(cores, "sing-box.exe"),
                listOf("run", "-c", sidecarFile.absolutePath), cores,
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

        setState(State.VERIFYING, "بررسی تونل سیستم…")
        exitSummary = summary
        return withContext(Dispatchers.IO) { verifyTunnelUp(baseline, warpExit) }
    }

    // -------------------------------------------------------- resolution

    /// verifyTunnelUp's answer: null message = earned CONNECTED.
    private class Verdict(val message: String?)

    /// One resolved transport per connect.
    private sealed class Exit {
        data class Aether(val protocol: AetherEngine.Protocol) : Exit()
        data class Node(val profile: ShardProfile) : Exit()
        data class Anytls(val profile: ShardProfile) : Exit()
        data class Manual(val identity: WarpIdentity) : Exit()
        data object Tor : Exit()
    }

    /// One rung of the connect ladder — a live race candidate (node tunnel)
    /// or an aether engine protocol (AUTO's escalation rungs).
    private sealed class Attempt {
        data class NodeTunnel(val profile: ShardProfile, val anytls: Boolean) : Attempt()
        data class Engine(val protocol: AetherEngine.Protocol) : Attempt()
    }

    /// How many live race candidates one connect may walk before the honest
    /// failure message. The probe's 12 s budget is not a guarantee the node
    /// still works 30 s later — the ladder absorbs that reality.
    private val NODE_RETRY_CANDIDATES = 3

    @Volatile private var exitSummary: String = ""

    /// Picks the transport exactly like the phone:
    /// AUTO = the fastest live SHARD nodes (walked in order) → aether WARP
    ///     fallback;
    /// SHARD = the race winners in order (same transport, next node — never a
    ///     silent transport change);
    /// WARP/MASQUE/GOOL/MIM = the aether engine;
    /// MANUAL = the Advanced-tab wg-quick text.
    /// Returns the first exit plus the ranked follower exits for the ladder.
    private suspend fun resolveExit(): Pair<Exit?, List<Exit>> {
        val poolEmpty = refreshPool().isEmpty()
        return when (mode) {
            Mode.MANUAL -> when (val e = manualExit()) {
                null -> null to emptyList()
                else -> e to emptyList()
            }
            Mode.WARP -> Exit.Aether(AetherEngine.Protocol.WG) to emptyList()
            Mode.MASQUE -> Exit.Aether(AetherEngine.Protocol.MASQUE) to emptyList()
            Mode.GOOL -> Exit.Aether(AetherEngine.Protocol.GOOL) to emptyList()
            Mode.MIM -> Exit.Aether(AetherEngine.Protocol.MIM) to emptyList()
            Mode.TOR -> Exit.Tor to emptyList()
            Mode.AUTO -> {
                if (poolEmpty) return Exit.Aether(AetherEngine.Protocol.WG) to emptyList()
                val ranked = runShardRaceRanked()
                if (ranked.isEmpty()) Exit.Aether(AetherEngine.Protocol.WG) to emptyList()
                else ranked.map { shardExit(it) }.let { it.first() to it.drop(1) }
            }
            Mode.SHARD -> {
                if (poolEmpty) {
                    fail("استخر SHARD خالی است — اتصال اینترنت را بررسی کن یا «به‌روزرسانی نودها» را بزن")
                    return null to emptyList()
                }
                val ranked = runShardRaceRanked()
                if (ranked.isEmpty()) {
                    return null to emptyList()   // runShardRaceRanked already failed with a message
                }
                ranked.map { shardExit(it) }.let { it.first() to it.drop(1) }
            }
        }
    }

    private fun shardExit(profile: ShardProfile): Exit =
        if (profile.configType == com.beevpn.win.shard.ShardType.ANYTLS) {
            Exit.Anytls(profile)
        } else {
            Exit.Node(profile)
        }

    /// The Android node race over the live pool — real-request probes,
    /// health-ranked and diversified, keeping the top NODE_RETRY_CANDIDATES
    /// live nodes for the connect ladder. A pinned node is honored first,
    /// exactly like the phone: probed alone, used when alive, otherwise the
    /// race decides (AUTO) or the mode refuses (SHARD).
    private suspend fun runShardRaceRanked(): List<ShardProfile> {
        val cores = CoreExtractor.ensure() ?: return emptyList()
        val pool = refreshPool()
        val pinned = pinnedNodeKey?.let { key -> pool.firstOrNull { it.key() == key } }
        if (pinned != null) {
            setState(State.PROVISIONING, "بررسی نود منتخب…")
            val pinnedHit = withContext(Dispatchers.IO) {
                ShardRace.raceSlice(listOf(pinned), cores)
            }
            if (pinnedHit != null) return listOf(pinned)
            if (mode == Mode.SHARD) {
                fail("نود انتخابی پاسخ نمی‌دهد — نود دیگری را در تب نودها انتخاب کن")
                return emptyList()
            }
        }
        return runCooledRace(pool, cores)
    }

    private suspend fun runCooledRace(pool: List<ShardProfile>, cores: File): List<ShardProfile> {
        setState(State.PROVISIONING, "مسابقهٔ نودها (مثل اندروید)…")
        var ranked: List<ShardProfile> = emptyList()
        withContext(Dispatchers.IO) {
            ranked = ShardRace.sweepRanked(pool, cores, maxKeep = NODE_RETRY_CANDIDATES) { progress ->
                setState(State.PROVISIONING, progress)
            }
        }
        if (ranked.isEmpty() && mode == Mode.SHARD) {
            fail("هیچ نودی از استخر SHARD پاسخ نداد — اتصال اینترنت را بررسی کن یا «به‌روزرسانی نودها» را بزن")
        }
        return ranked
    }

    private fun manualExit(): Exit? {
        val text = manualWgQuick
            ?: run {
                fail("حالت دستی فعال است اما کانفیگ wg-quick ذخیره نشده — تب پیشرفته را پر کن")
                return null
            }
        val identity = WarpProvisioner.parseWGQuick(text)
            ?: run {
                fail("کانفیگ wg-quick قابل خواندن نیست — PrivateKey و PublicKey و Endpoint لازم است")
                return null
            }
        return Exit.Manual(identity)
    }

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

        // 2) engine end-to-end: bytes through SOCKS 1819 reach the far end.
        //    In aether modes this probes the CORE's listener — the same
        //    waitForSocksReady the phone runs against its engine. Two shapes:
        //    the cloudflare trace, then a generate_204 — some blackholes drop
        //    one far end but carry the other, and a tunnel that moves bytes
        //    must not be declared dead because one host was unlucky.
        var exitIp: String? = null
        repeat(2) {
            if (exitIp != null) return@repeat
            exitIp = TunnelVerifier.traceIpViaSocks(AetherEngine.SOCKS_PORT, 15_000)
            if (exitIp == null && TunnelVerifier.tunnel204ViaSocks(AetherEngine.SOCKS_PORT, 15_000)) {
                exitIp = "/live"
            }
        }
        if (exitIp == null) {
            TunnelVerifier.log("engine egress: DEAD (no trace answer through SOCKS)")
            return if (warpExit) {
                Verdict(
                    "تونل بالا آمد اما خروجی WARP ترافیک پاس نمی‌دهد — شبکه‌ات UDP را فیلتر می‌کند. " +
                        "به حالت SHARD برو (نودها را «به‌روزرسانی نودها» کن) یا در تب پیشرفته کانفیگ دستی بگذار",
                )
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
                        (aether?.let { it.isAlive } != false) &&
                        EngineProcess.portOpen(AetherEngine.SOCKS_PORT) &&
                        (
                            TunnelVerifier.traceIpViaSocks(AetherEngine.SOCKS_PORT, 10_000) != null ||
                                TunnelVerifier.tunnel204ViaSocks(AetherEngine.SOCKS_PORT, 10_000)
                            )
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
    /// when the user themselves pressed disconnect.
    ///
    /// The ladder restarts at 5 s ONLY when the dying session actually lived
    /// (≥ 5 minutes of real use): a tunnel that drops soon after every
    /// reconnect must escalate 5→15→30→60→120, or a sick network pins the app
    /// in a permanent 5-second retry loop — the exact «اتصال مجدد خودکار در
    /// ۵ ثانیه» loop reported from the field.
    private fun scheduleAutoReconnect(reason: String) {
        if (userDisconnect) return
        watchdogJob?.cancel()
        val sessionUptimeS = if (connectedSince > 0) System.currentTimeMillis() / 1000 - connectedSince else 0
        val attempt = reconnectAttempts.getAndIncrement()
        val ladder = TunnelVerifier.RECONNECT_BACKOFF_S
        val wait = ladder[attempt.coerceAtMost(ladder.size - 1)]
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            mutex.withLock {
                if (userDisconnect) return@withLock
                withContext(Dispatchers.IO) { stopEngines(null) }
                if (sessionUptimeS >= 300) reconnectAttempts.set(0)
                setState(State.PROVISIONING, "$reason — اتصال مجدد خودکار تا ${faNum(wait)} ثانیه…")
                delay(wait * 1000L)
                if (!userDisconnect) withContext(Dispatchers.IO) { connectBlocking() }
            }
        }
    }

    // ---------------------------------------------------------- identity

    fun regenerateIdentity() {
        scope.launch {
            mutex.withLock {
                withContext(Dispatchers.IO) {
                    stopEngines("هویت جدید…")
                    WarpProvisioner.purge()
                    SecureStore.put("warp.manualConfig", "")
                    // The engine's own identity files + its lastconn ring:
                    // a regenerated identity must reconnect cold, exactly like
                    // ACTION_RESET_IDENTITIES on the phone.
                    AetherEngine.identityFiles(AetherEngine.Protocol.WG).forEach { it.delete() }
                    AetherEngine.identityFiles(AetherEngine.Protocol.MASQUE).forEach { it.delete() }
                    AetherEngine.identityFiles(AetherEngine.Protocol.GOOL).forEach { it.delete() }
                    AetherEngine.identityFiles(AetherEngine.Protocol.MIM).forEach { it.delete() }
                    // The inner-leg files the engine derives — same set, named
                    // exactly as derive_sibling_path names them in the core.
                    AetherEngine.secondaryPath().delete()
                    AetherEngine.masqueSecondaryPath().delete()
                    // The inner-leg files the engine derives — same set, named
                    // exactly as derive_sibling_path names them in the core.
                    AetherEngine.secondaryPath().delete()
                    AetherEngine.masqueSecondaryPath().delete()
                    File(AppPaths.baseDir, "aether.toml-lastconn").delete()
                }
            }
        }
    }

    // ---------------------------------------------------------- disconnect

    /// Stops the engines WITHOUT touching the UI state — the early-exit crash
    /// handler uses it while a connect is still visually in progress.
    private fun killEnginesQuiet() {
        watchdogJob?.cancel()
        sidecar?.stop()
        sidecar = null
        aether?.stop()
        aether = null
        xray?.stop()
        xray = null
        if (AppPaths.isWindows()) TunnelVerifier.removeFallbackRoute()
    }

    private fun stopEngines(caption: String?) {
        killEnginesQuiet()
        connectedSince = 0
        exitSummary = ""
        setState(State.DISCONNECTED, caption ?: "قطع است")
    }
}
