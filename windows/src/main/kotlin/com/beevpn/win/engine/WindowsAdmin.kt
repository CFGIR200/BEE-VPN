package com.beevpn.win.engine

/// Windows admin (elevation) helpers — creating the Wintun adapter and the
/// 0.0.0.0/0 routes needs an elevated process, exactly like every other
/// desktop VPN client. The app detects a non-elevated launch and offers a
/// one-click elevated relaunch instead of failing mid-connect.
object WindowsAdmin {

    /// The elevation state cannot change while the process lives, and probing
    /// it spawns a `net session` process each time — cache the first answer.
    @Volatile private var cached: Boolean? = null

    fun isAdmin(): Boolean {
        cached?.let { return it }
        val v = probeAdmin()
        cached = v
        return v
    }

    private fun probeAdmin(): Boolean {
        if (!com.beevpn.win.AppPaths.isWindows()) return false
        return try {
            val p = ProcessBuilder("net", "session").start()
            p.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    /// Relaunches the packaged app elevated (UAC prompt). Only meaningful for
    /// the jpackage/portable launcher — a dev `gradle run` has no exe to raise.
    fun relaunchElevated(): Boolean {
        val exeDir = com.beevpn.win.AppPaths.exeDir() ?: return false
        val exe = java.io.File(exeDir, "BEE-VPN.exe")
        if (!exe.isFile) return false
        return try {
            val cmd = listOf(
                "powershell", "-NoProfile", "-Command",
                "Start-Process -FilePath '${exe.absolutePath}' -Verb RunAs",
            )
            ProcessBuilder(cmd).start()
            true
        } catch (_: Exception) {
            false
        }
    }

    // ------------------------------------------------------- system proxy
    //
    // Proxy mode (TUN off) has no Wintun adapter and no default route — the
    // only way traffic actually flows is the WinINET system proxy pointing at
    // the engine's local HTTP inbound. The user's previous proxy settings are
    // saved once and restored exactly on clear, so an app crash can never
    // leave the machine pointing at a dead port forever (the shutdown hook
    // and the next connect's teardown both clear it too).

    private const val PROXY_REG =
        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"

    @Volatile private var savedProxyEnable: String? = null
    @Volatile private var savedProxyServer: String? = null
    @Volatile private var proxyApplied = false

    /// Whether the app currently owns the system proxy.
    fun isSystemProxyApplied(): Boolean = proxyApplied

    /// Points the system proxy at 127.0.0.1:[port]. Returns false on any
    /// failure — a failed proxy set is honest proxy mode: the engine runs,
    /// the apps that honour the proxy just keep going direct.
    fun setSystemProxy(port: Int): Boolean {
        if (!com.beevpn.win.AppPaths.isWindows()) return false
        if (proxyApplied) return true
        return try {
            if (savedProxyEnable == null) {
                savedProxyEnable = regQuery("ProxyEnable") ?: "0"
                savedProxyServer = regQuery("ProxyServer")
            }
            regAdd("ProxyEnable", "1", "REG_DWORD") && regAdd("ProxyServer", "127.0.0.1:$port").also { proxyApplied = it }
        } catch (_: Exception) {
            false
        }
    }

    /// Restores whatever the user had before [setSystemProxy].
    fun clearSystemProxy() {
        if (!proxyApplied) return
        proxyApplied = false
        try {
            val enable = savedProxyEnable ?: "0"
            regAdd("ProxyEnable", if (enable == "1") "1" else "0", "REG_DWORD")
            val server = savedProxyServer
            if (server.isNullOrBlank()) {
                regDelete("ProxyServer")
            } else {
                regAdd("ProxyServer", server)
            }
        } catch (_: Exception) {
        } finally {
            savedProxyEnable = null
            savedProxyServer = null
        }
    }

    private fun regQuery(name: String): String? = try {
        val p = ProcessBuilder("reg", "query", PROXY_REG, "/v", name).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        // "    ProxyEnable    REG_DWORD    0x1" — the last token is the value,
        // hex for DWORDs; normalise to decimal so the restore compares equal.
        out.trim().lineLastOrNull()?.takeIf { it.contains(name) }?.substringAfterLast(' ')
            ?.takeIf { it.isNotEmpty() }
            ?.let { if (it.startsWith("0x")) it.removePrefix("0x").toLongOrNull(16)?.toString() ?: it else it }
    } catch (_: Exception) {
        null
    }

    private fun regAdd(name: String, value: String, type: String = "REG_SZ"): Boolean = try {
        val p = ProcessBuilder("reg", "add", PROXY_REG, "/v", name, "/t", type, "/d", value, "/f").start()
        p.waitFor() == 0
    } catch (_: Exception) {
        false
    }

    private fun regDelete(name: String): Boolean = try {
        val p = ProcessBuilder("reg", "delete", PROXY_REG, "/v", name, "/f").start()
        p.waitFor() == 0
    } catch (_: Exception) {
        false
    }

    private fun String.lineLastOrNull(): String? = trim().lines().lastOrNull { it.isNotBlank() }
}
