package com.beevpn.win

import java.io.File

/// Where everything lives on disk. Mirrors the Android filesDir layout so the
/// mental model transfers 1:1:
///
///   %APPDATA%/BEE-VPN/
///   ├─ store.json          ← license + WARP identity + prefs (SecureStore)
///   ├─ shard-configs.txt   ← subscription cache
///   ├─ logs/engine.log     ← the cores' output (redacted lines only survive)
///   └─ run/                ← generated xray / sing-box JSON for this session
object AppPaths {

    val baseDir: File by lazy {
        val appData = System.getenv("APPDATA")
        val root = if (!appData.isNullOrBlank()) File(appData, "BEE-VPN")
        else File(System.getProperty("user.home"), ".beevpn")
        root.mkdirs()
        root
    }

    val storeFile: File get() = File(baseDir, "store.json")
    val shardCache: File get() = File(baseDir, "shard-configs.txt")
    val shardEtag: File get() = File(baseDir, "shard-etag.txt")
    val logsDir: File get() = File(baseDir, "logs").apply { mkdirs() }
    val engineLog: File get() = File(logsDir, "engine.log")
    val runDir: File get() = File(baseDir, "run").apply { mkdirs() }

    /// The bundled cores. Order: BEE_CORES_DIR (dev / custom install) →
    /// the compose app-resources dir (packaged MSI/portable:
    /// <resources>/cores) → next-to-exe → app data (user dropped their own).
    /// Returns null when none has xray.exe, which the UI surfaces as a
    /// buyer-readable message, never a stack trace.
    fun coresDir(): File? {
        val candidates = mutableListOf<File>()
        System.getenv("BEE_CORES_DIR")?.let { candidates.add(File(it)) }
        // jpackage/compose: the packaged app exposes its resources dir here.
        System.getProperty("compose.application.resources.dir")?.let {
            candidates.add(File(it, "cores"))
        }
        exeDir()?.let { candidates.add(File(it, "cores")) }
        candidates.add(File(baseDir, "cores"))
        return candidates.firstOrNull { File(it, "xray.exe").isFile }
    }

    fun hasCores(): Boolean = coresDir() != null

    /// The jpackage launcher's directory, when running as a packaged app.
    fun exeDir(): File? {
        val cmd = ProcessHandle.current().info().command().orElse("")
        if (cmd.isBlank()) return null
        val f = File(cmd).absoluteFile
        val name = f.name.lowercase()
        // A bundled launcher (BEE-VPN.exe) — not a java.exe/javaw.exe dev run.
        if ((name.endsWith(".exe") && !name.startsWith("java"))) return f.parentFile
        return null
    }

    fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().contains("windows", ignoreCase = true)
}
