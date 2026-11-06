package com.beevpn.win.engine

/// Windows admin (elevation) helpers — creating the Wintun adapter and the
/// 0.0.0.0/0 routes needs an elevated process, exactly like every other
/// desktop VPN client. The app detects a non-elevated launch and offers a
/// one-click elevated relaunch instead of failing mid-connect.
object WindowsAdmin {

    fun isAdmin(): Boolean {
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
}
