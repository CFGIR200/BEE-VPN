package com.beevpn.win.engine

import com.beevpn.win.AppPaths
import com.beevpn.win.warp.WarpIdentity
import java.io.File

/// The Android engine, running on Windows.
///
/// The phone's WARP-family connections (WireGuard, MASQUE, WARP-on-WARP "gool",
/// MASQUE-over-MASQUE "mim") are carried by the Rust core `core/aether`, driven
/// entirely through `AETHER_*` environment variables. Upstream
/// (CluvexStudio/aether) publishes the same core as a standalone Windows
/// binary that consumes the exact same variables — so the desktop can run the
/// REAL engine instead of an approximation: the Ptech endpoint scanner,
/// ECH-backed registration, the lastconn quick-reconnect cache, MASQUE over
/// H2, gool's WireGuard-inside-WireGuard — every fast-connect detail the
/// phone has, byte for byte.
///
/// The standalone is pinned at 2.4.0 (windows.yml): 2.4.0 is the release
/// that fixed the mid-run «کد ۱» exits — the WARP API calls now go by
/// api.devices.cloudflare.com (the name Cloudflare's own client has called
/// since 2026.6.0) with api.cloudflareclient.com as the fallback, plus the
/// rewritten reconnect logic. The phone's FFI core stays at its own pin.
///
/// This object is the desktop twin of `CoreConfig.env()` + the identity-file
/// handling of `WarpIdentityFetcher.kt` / `IdentityProvisioner.kt`:
///   1. identity TOMLs live next to `AETHER_CONFIG`, whose sibling paths the
///      core derives itself with derive_sibling_path (`aether-masque.toml`,
///      `aether-secondary.toml` for gool's inner leg, `aether-masque-secondary.toml`
///      for mim's, `<config>-lastconn`) — the 1.4.0 build checked the WRONG
///      sibling name for gool (`aether-gool.toml`), a name the core never reads;
///   2. `AETHER_REGISTER` fills any missing identity in place — and an
///      identity file already present is KEPT, never replaced, so re-running
///      it before every connect is a no-op when the identity is warm;
///   3. the connect env mirrors the constants CoreConfig.kt sends on Android.
object AetherEngine {

    /// The core owns the app's one SOCKS port in WARP-family modes — the same
    /// listener Android probes in `waitForSocksReady`.
    const val SOCKS_PORT = EngineConfig.SOCKS_PORT

    /// The embedded Quantum ECH key Android uses when the roster has nothing
    /// fresher (CoreConfig.kt — EchEndpointRoster fallback): keeps the
    /// registration API reachable on carriers that filter plain DoH.
    const val EMBEDDED_API_ECH =
        "AEX+DQBBrwAgACCbK1mYDYFz/BAn6S5t+Q/v+Oej3eFNxtPWgz50fNnFPAAEAAEAAQASY2xvdWRmbGFyZS1lY2guY29tAAA="

    /// Ptech scanner surface — the exact candidate list CoreConfig.kt:162-170
    /// sends (7 WireGuard-quality /24s + 2 IPv6 /48s, ports 2408/500/1701/4500).
    const val PTECH_CIDRS_V4 =
        "162.159.192.0/24,162.159.193.0/24,162.159.195.0/24," +
            "188.114.96.0/24,188.114.97.0/24,188.114.98.0/24,188.114.99.0/24"
    const val PTECH_CIDRS_V6 = "2606:4700:d0::/48,2606:4700:d1::/48"
    const val WG_PORTS = "2408,500,1701,4500"

    /// Per-transport MTUs — MtuConfig.kt defaults (MASQUE 1304, WireGuard 1440,
    /// WOW 1280 applied to BOTH legs).
    const val MTU_WG = 1440
    const val MTU_MASQUE = 1304
    const val MTU_WOW = 1280

    /// How long the app waits for the engine's SOCKS listener before giving up
    /// on one connect — Android's waitForSocksReadyBudget (MsnGuardVpnService):
    /// WireGuard 110 s / MASQUE+MiM 300 s / gool 200 s.
    ///
    /// This is THE fix for the “kill and reconnect every 110 s” loop: on a cold
    /// start over a filtered link the engine legitimately needs scan (up to
    /// 120 s balanced) + registration retries (up to ~100 s) before it even
    /// opens the listener — a 110 s budget kills a perfectly healthy
    /// provisioning run and pays the whole cost again, forever.
    fun socksReadyBudgetMs(protocol: Protocol): Long = when (protocol) {
        Protocol.WG -> 110_000L
        Protocol.MASQUE, Protocol.MIM -> 300_000L
        Protocol.GOOL -> 200_000L
    }

    enum class Protocol(val id: String, val register: String) {
        WG("wg", "wg"),
        MASQUE("masque", "masque"),
        // The classic WireGuard-in-WireGuard gool — the shape the phone runs.
        // aether 2.4.0 redefined the bare `gool` register to the MASQUE-shell
        // shape it now runs by default; the two-WireGuard-hop classic this
        // app expects (aether.toml + aether-secondary.toml) registers by the
        // `gool-classic` name there. The RUN shape stays pinned via
        // AETHER_GOOL_MODE=classic below.
        GOOL("gool", "gool-classic"),
        MIM("mim", "mim"),
    }

    // ------------------------------------------------------- identity files

    /// The base identity path — the engine derives every sibling from it.
    fun configPath(): File = File(AppPaths.baseDir, "aether.toml")

    fun masquePath(): File = File(AppPaths.baseDir, "aether-masque.toml")

    /// The inner legs — the names derive_sibling_path produces in the core
    /// (lib.rs: `derive_sibling_path(&wireguard, "secondary")` and the masque
    /// sibling's own `-secondary`). IdentityProvisioner.kt on the phone
    /// persists exactly these names.
    fun secondaryPath(): File = File(AppPaths.baseDir, "aether-secondary.toml")

    fun masqueSecondaryPath(): File = File(AppPaths.baseDir, "aether-masque-secondary.toml")

    /// Files that must be complete for `protocol` to be connectable. gool
    /// carries wireguard + an inner wireguard (`aether-secondary.toml`); mim
    /// carries masque + its inner (`aether-masque-secondary.toml`).
    fun identityFiles(protocol: Protocol): List<File> = when (protocol) {
        Protocol.WG -> listOf(configPath())
        Protocol.MASQUE -> listOf(masquePath())
        Protocol.GOOL -> listOf(configPath(), secondaryPath())
        Protocol.MIM -> listOf(masquePath(), masqueSecondaryPath())
    }

    /// The completeness rule IdentityProvisioner.validIdentityFile enforces on
    /// the phone: device_id + access_token + ipv4 non-blank, the WireGuard
    /// keys real 32-byte base64, and for masque-family files a real cert pair
    /// with a live issued-at. The 1.4.0 shape (bare key-presence) passed a
    /// half-written file as ready and let a dead identity poison a connect.
    fun identityComplete(file: File): Boolean {
        if (!file.isFile) return false
        val text = try { file.readText() } catch (_: Exception) { return false }
        val value = { key: String ->
            Regex("^\\s*$key\\s*=\\s*\"([^\"]*)\"", RegexOption.MULTILINE)
                .find(text)?.groupValues?.get(1)
        }
        val b32 = { key: String ->
            val raw = value(key)
            if (raw == null) false
            else runCatching { java.util.Base64.getDecoder().decode(raw).size == 32 }.getOrDefault(false)
        }
        val masqueFamily = file.name.contains("masque")
        val base = !value("device_id").isNullOrBlank() &&
            !value("access_token").isNullOrBlank() &&
            !value("ipv4").isNullOrBlank() &&
            b32("wg_private_key") && b32("wg_peer_public_key")
        if (!base) return false
        if (!masqueFamily) return true
        return (value("cert_pem") ?: "").contains("BEGIN CERTIFICATE") &&
            (value("key_pem") ?: "").contains("BEGIN PRIVATE KEY") &&
            (Regex("^\\s*cert_issued_at\\s*=\\s*(\\d+)", RegexOption.MULTILINE)
                .find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L) > 0L
    }

    fun identitiesReady(protocol: Protocol): Boolean =
        identityFiles(protocol).all { identityComplete(it) }

    /// Writes the registered WARP identity as the aether.toml the core reads —
    /// the exact field set IdentityProvisioner.relayToml() persists on Android.
    fun writeWgIdentity(identity: WarpIdentity) {
        val assignedHost = identity.endpoint.substringBeforeLast(":")
        val toml = buildString {
            appendLine("device_id = " + q(identity.deviceId))
            appendLine("access_token = " + q(identity.apiToken))
            appendLine("cert_pem = \"\"")
            appendLine("key_pem = \"\"")
            appendLine("cert_issued_at = 0")
            appendLine("ipv4 = " + q(identity.addressV4))
            appendLine("ipv6 = " + q(identity.addressV6))
            appendLine("wg_private_key = " + q(identity.privateKey))
            appendLine("wg_peer_public_key = " + q(identity.peerPublicKey))
            appendLine("client_id = " + q(identity.clientIdB64))
            appendLine("organization = \"\"")
            appendLine("gateway_proxy = \"\"")
            appendLine("assigned_endpoint = " + q(assignedHost))
        }
        configPath().writeText(toml)
        // The saved store keeps the JSON form the rest of the app reads
        // (Advanced-tab wg-quick display, diagnostics); the TOML is the core's.
        com.beevpn.win.warp.WarpProvisioner.save(identity)
    }

    /// A manual wg-quick config as a wireguard identity the core can run.
    /// Same shape as writeWgIdentity, but with no API token and no reserved
    /// bytes — and the endpoint pinned through AETHER_PEER, exactly like the
    /// phone's `manual_endpoint` → `AETHER_PEER` + `AETHER_WG_PEER`.
    fun writeManualIdentity(identity: WarpIdentity, endpoint: String) {
        writeWgIdentity(
            identity.copy(
                deviceId = "manual",
                apiToken = "",
                endpoint = endpoint,
                clientIdB64 = identity.clientIdB64,
            ),
        )
    }

    /// TOML string quoting — the escaping relayToml() applies.
    private fun q(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r") + "\""

    // ------------------------------------------------------------- env map

    /// The connect-time environment — the desktop rendering of the constants
    /// CoreConfig.env() sends on the phone. `ipv6` mirrors Android's
    /// `default_scan` ("both" when the machine actually has IPv6, "v4" when
    /// not — MASQUE/MiM are hardwired to v4 there, the desktop keeps the same
    /// rule because the registration surface is IPv4-first).
    fun connectEnv(
        protocol: Protocol,
        ipv6Available: Boolean,
        apiEch: String? = EMBEDDED_API_ECH,
    ): Map<String, String> {
        val env = sortedMapOf<String, String>()
        val masqueFamily = protocol == Protocol.MASQUE || protocol == Protocol.MIM
        env["AETHER_CONFIG"] = configPath().absolutePath
        env["AETHER_PROTOCOL"] = protocol.id
        env["AETHER_SOCKS"] = "127.0.0.1:$SOCKS_PORT"
        env["AETHER_LOG_LEVEL"] = "info"
        env["AETHER_STATS"] = "1"
        // 60 s instead of the phone's 86400: the desktop has no FFI stats
        // snapshot, so the periodic “[=] up … down … uptime …” log line is the
        // only heartbeat the advanced report ever sees — and it is exactly what
        // proves whether a dead tunnel stopped carrying bytes.
        env["AETHER_STATS_SECS"] = "60"
        env["AETHER_SCAN"] = "balanced"
        // Android: ip_scan "v4" hardwired for masque/mim, pref "both" otherwise.
        if (masqueFamily) env["AETHER_IP"] = "v4"
        else env["AETHER_IP"] = if (ipv6Available) "both" else "v4"
        // Obfuscation: engine default for wireguard; skipped for masque/mim —
        // the core hardcodes MASQUE to H2 and refuses the knob there.
        if (!masqueFamily) env["AETHER_NOIZE"] = "balanced"
        // Ptech scanner: WireGuard/WoW only, never masque (CoreConfig.kt:510-532).
        if (!masqueFamily) {
            env["AETHER_PTECH_SCAN"] = "1"
            env["AETHER_SCAN_CIDRS_V4"] = PTECH_CIDRS_V4
            env["AETHER_SCAN_CIDRS_V6"] = PTECH_CIDRS_V6
            env["AETHER_WG_PORTS"] = WG_PORTS
        }
        // MTU: the MtuConfig defaults, applied the way Android applies them —
        // gool sets BOTH legs to the WOW value.
        when (protocol) {
            Protocol.WG -> env["AETHER_WG_MTU"] = MTU_WG.toString()
            Protocol.MASQUE -> env["AETHER_MASQUE_MTU"] = MTU_MASQUE.toString()
            Protocol.GOOL -> {
                env["AETHER_WG_MTU"] = MTU_WOW.toString()
                env["AETHER_MASQUE_MTU"] = MTU_WOW.toString()
            }
            Protocol.MIM -> env["AETHER_MASQUE_MTU"] = MTU_MASQUE.toString()
        }
        // MASQUE family: H2 hardwired + the PattNG SNI default (CoreConfig:534-539).
        if (masqueFamily) {
            env["AETHER_MASQUE_HTTP2"] = "1"
            env["AETHER_MASQUE_SNI"] = "www.cloudflare.com"
        }
        // ECH: the registration API key rides WireGuard/WoW connects; the
        // MASQUE family strips every ECH variable (CoreConfig:585-647).
        if (!masqueFamily && apiEch != null) env["AETHER_API_ECH"] = apiEch
        // The verified-lastconn ring — the single biggest fast-connect lever
        // on the phone (CoreConfig:682-689).
        env["AETHER_QUICK_RECONNECT"] = "1"
        // gool is the classic WireGuard-in-WireGuard shape (CoreConfig:352-354).
        if (protocol == Protocol.GOOL) env["AETHER_GOOL_MODE"] = "classic"
        return env
    }

    /// The minimal registration environment — WarpIdentityFetcher stage C:
    /// config path + ECH + what to register. `upstream` (a socks5:// URL the
    /// SHARD listener serves) is the rescue rail the phone rides whenever the
    /// carrier blocks the API: the core routes the account API through it
    /// (Android's provisionIdentityThroughShard → CoreConfig AETHER_UPSTREAM).
    fun registerEnv(
        protocol: Protocol,
        apiEch: String? = EMBEDDED_API_ECH,
        upstream: String? = null,
    ): Map<String, String> {
        val env = sortedMapOf<String, String>()
        env["AETHER_CONFIG"] = configPath().absolutePath
        env["AETHER_LOG_LEVEL"] = "info"
        env["AETHER_REGISTER"] = protocol.register
        if (apiEch != null) {
            // AETHER_API_ECH is the phone FFI's name; the standalone binary
            // (the one Windows runs) reads AETHER_ECH — send BOTH so the
            // registration API rides ECH on either build.
            env["AETHER_API_ECH"] = apiEch
            env["AETHER_ECH"] = apiEch
        }
        if (upstream != null) env["AETHER_UPSTREAM"] = upstream
        return env
    }

    /// The Tor reverse-carrier environment — the tunnel through tor, the same
    /// core facility the phone's Tor transport is shaped after. `reverse`
    /// carries BOTH the registration and the tunnel through the tor circuit;
    /// lyrebird (obfs4/snowflake/meek) rides along as the pluggable transport.
    fun torEnv(protocol: Protocol, cores: File): Map<String, String> {
        val env = connectEnv(protocol, ipv6Available = false).toMutableMap()
        env["AETHER_TOR"] = "reverse"
        val lyrebird = File(cores, "lyrebird.exe")
        if (lyrebird.isFile) {
            env["AETHER_TOR_PT"] = lyrebird.absolutePath
            val ptDir = File(AppPaths.runDir, "pt-state").apply { mkdirs() }
            env["AETHER_TOR_PT_DIR"] = ptDir.absolutePath
        }
        val torDir = File(AppPaths.runDir, "tor-state").apply { mkdirs() }
        env["AETHER_TOR_DIR"] = torDir.absolutePath
        return env
    }

    // ------------------------------------------------------------ process

    /// Runs a registration pass and waits for the identity files it should
    /// produce. Idempotent: files already present are kept by the core, so a
    /// warm identity makes this a fast no-op. Returns true when every file
    /// the protocol needs is complete.
    fun registerIfNeeded(
        protocol: Protocol,
        cores: File,
        budgetMs: Long = 180_000,
        onLine: ((String) -> Unit)? = null,
        upstream: String? = null,
    ): Boolean {
        if (identitiesReady(protocol)) return true
        val exe = File(cores, "aether.exe")
        if (!exe.isFile) return false
        val proc = EngineProcess(
            if (upstream != null) "aether-register-shard" else "aether-register",
            exe,
            emptyList(),
            AppPaths.baseDir,
            env = registerEnv(protocol, upstream = upstream),
        )
        proc.onLine = onLine
        proc.start()
        try {
            val deadline = System.currentTimeMillis() + budgetMs
            while (System.currentTimeMillis() < deadline) {
                if (identitiesReady(protocol)) return true
                if (!proc.isAlive) return identitiesReady(protocol)
                Thread.sleep(500)   // the phone polls the same files at 400 ms
            }
            return identitiesReady(protocol)
        } finally {
            proc.stop()
        }
    }

    /// The executable inside the bundled cores (or a user-provided one).
    fun exe(cores: File): File? = File(cores, "aether.exe").takeIf { it.isFile }

    /// The lastconn quick-reconnect ring — <config>-lastconn beside every
    /// identity file. After two failed connects the app must evict it (Android
    /// clears the gateway cache at reconnectAttempts >= 2): a cached edge that
    /// stopped answering would otherwise be re-verified on every retry before
    /// the engine finally scans.
    fun clearQuickReconnectCache() {
        val dir = AppPaths.baseDir
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith("-lastconn") } ?: emptyArray()
        files.forEach { runCatching { it.delete() } }
    }
}

/// Logging helper mirroring the engine log the rest of the app writes.
internal fun aetherLog(line: String) {
    try {
        AppPaths.engineLog.appendText("[aether] $line\n")
    } catch (_: Exception) {}
}
