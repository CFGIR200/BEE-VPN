package com.beevpn.win.engine

import com.beevpn.win.AppPaths
import java.io.File

/// Extracts the bundled cores (xray.exe / sing-box.exe / wintun.dll /
/// geo*.dat) from the application jar into %APPDATA%/BEE-VPN/cores.
///
/// CI drops them under cores-bin/cores/ before packaging, so both the MSI and
/// the portable zip carry the engines INSIDE the app — no jpackage resource
/// quirks, no separate downloads, works on a clean machine. Existing cores
/// (BEE_CORES_DIR, next-to-exe) always win; extraction is the fallback.
/// A size-manifest guards against partial or stale extractions.
object CoreExtractor {

    private val REQUIRED = listOf("xray.exe", "wintun.dll")
    private val OPTIONAL = listOf("sing-box.exe", "geoip.dat", "geosite.dat")

    /// Returns the cores dir ready to use, or null when nothing usable could
    /// be produced (the caller turns that into a buyer-readable message).
    fun ensure(): File? {
        // Explicit or pre-existing locations win — never overwrite them.
        AppPaths.coresDir()?.let { return it }

        val target = File(AppPaths.baseDir, "cores")
        val sizes = manifestSizes()
        if (sizes.isEmpty()) return null   // dev build without bundled cores

        var changed = false
        for ((name, expected) in sizes) {
            val out = File(target, name)
            if (out.isFile && out.length() == expected) continue
            val stream = coresResource(name) ?: continue
            target.mkdirs()
            val tmp = File(target, "$name.part")
            try {
                stream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                if (!tmp.renameTo(out)) {
                    out.delete()
                    if (!tmp.renameTo(out)) {
                        tmp.delete()
                        continue
                    }
                }
                changed = true
            } catch (_: Exception) {
                tmp.delete()
            }
        }

        // Hard requirement: xray + wintun must exist with the manifest sizes.
        for (name in REQUIRED) {
            val f = File(target, name)
            val expected = sizes[name] ?: return null
            if (!f.isFile || f.length() != expected) return null
        }
        if (changed) EngineProcess.logLine("[cores] extracted bundled engines to ${target.path}")
        return target
    }

    /// name → size for the manifest.txt bundled next to the cores.
    private fun manifestSizes(): Map<String, Long> {
        val stream = coresResource("manifest.txt") ?: return emptyMap()
        return try {
            stream.use { s ->
                s.bufferedReader().readLines().mapNotNull { line ->
                    val i = line.lastIndexOf(':')
                    if (i <= 0) return@mapNotNull null
                    val name = line.substring(0, i).trim()
                    val size = line.substring(i + 1).trim().toLongOrNull() ?: return@mapNotNull null
                    if (name.isEmpty()) null else name to size
                }.toMap()
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun coresResource(name: String) =
        Thread.currentThread().contextClassLoader
            ?.getResourceAsStream("cores/$name")
            ?: ClassLoader.getSystemResourceAsStream("cores/$name")
}
