package com.beevpn.win.engine

import com.beevpn.win.AppPaths
import java.io.File

/// Extracts the bundled cores (xray.exe / aether.exe / sing-box.exe /
/// wintun.dll / geo*.dat) from the application jar into the app's cores dir.
///
/// CI drops them under cores-bin/cores/ before packaging, so both the MSI and
/// the portable zip carry the engines INSIDE the app — no jpackage resource
/// quirks, no separate downloads, works on a clean machine.
///
/// THE 1.6.2 FIELD LESSON (the «aether.exe در بستهٔ برنامه نیست» report):
/// the old code returned the FIRST dir holding xray.exe and skipped
/// extraction entirely. A dir seeded by an older build or a half-finished
/// extraction (Defender quarantining an engine mid-copy is the classic) then
/// "won" forever — xray.exe present, aether.exe gone, and the jar's copy was
/// never consulted again. Now the chosen dir is RECONCILED against the jar's
/// manifest on every start: any manifest-listed file that is missing or the
/// wrong size is re-extracted (3 attempts, unique temp names), and aether.exe
/// joins xray.exe + wintun.dll in the hard-required set whenever the jar
/// actually carries it. A dir can no longer go stale.
object CoreExtractor {

    private val REQUIRED = listOf("xray.exe", "wintun.dll")

    /// Engines every shipped build must carry — required ONLY when the jar
    /// itself has them (a dev build without bundled cores must stay buildable).
    private val SHIP_REQUIRED = listOf("aether.exe")

    private const val EXTRACT_ATTEMPTS = 3

    /// Returns the cores dir ready to use, or null when nothing usable could
    /// be produced (the caller turns that into a buyer-readable message).
    fun ensure(): File? {
        val target = File(AppPaths.baseDir, "cores")
        val chosen = AppPaths.coresDir() ?: target
        val sizes = manifestSizes()
        if (sizes.isEmpty()) {
            // Dev build without bundled cores — a pre-existing dir is the only
            // source of truth, take it as-is.
            return if (chosen != target && REQUIRED.all { File(chosen, it).isFile }) chosen else null
        }

        // ── reconcile: every manifest file present, correct size ────────────
        var repaired = 0
        for ((name, expected) in sizes) {
            if (matches(File(chosen, name), expected)) continue
            if (extract(name, expected, chosen)) repaired++
        }
        if (repaired > 0) {
            EngineProcess.logLine(
                "[cores] reconciled $repaired engine(s) in ${chosen.path} — missing or stale files re-extracted from the app jar",
            )
        }

        // ── hard requirements: xray + wintun always, aether when shipped ────
        val required = REQUIRED + SHIP_REQUIRED.filter { sizes.containsKey(it) }
        for (name in required) {
            val expected = sizes[name]
            val f = File(chosen, name)
            if (expected == null) {
                if (!f.isFile) return null
            } else if (!matches(f, expected)) {
                return null
            }
        }
        return chosen
    }

    private fun matches(f: File, expected: Long): Boolean =
        f.isFile && f.length() == expected

    /// Extracts one engine from the jar with up to EXTRACT_ATTEMPTS attempts —
    /// a fresh temp name each try so an AV scanning lock on the previous name
    /// cannot poison the next attempt.
    private fun extract(name: String, expected: Long, dir: File): Boolean {
        for (attempt in 1..EXTRACT_ATTEMPTS) {
            val stream = coresResource(name)
            if (stream == null) {
                EngineProcess.logLine("[cores] $name is not inside the app jar — cannot extract")
                return false
            }
            val tmp = File(dir, "$name.part$attempt")
            try {
                dir.mkdirs()
                stream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                val actual = tmp.length()
                if (actual != expected) {
                    tmp.delete()
                    EngineProcess.logLine("[cores] $name extraction attempt $attempt produced ${actual}B, manifest says $expected B")
                    continue
                }
                val out = File(dir, name)
                out.delete()
                if (tmp.renameTo(out)) return true
                tmp.delete()
            } catch (e: Exception) {
                runCatching { tmp.delete() }
                EngineProcess.logLine("[cores] $name extraction attempt $attempt failed: ${e.message}")
            }
        }
        EngineProcess.logLine("[cores] $name could not be extracted after $EXTRACT_ATTEMPTS attempts — antivirus interference is likely")
        return false
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

    /// ── the antivirus peace treaty ─────────────────────────────────────────
    /// Every engine in the cores dir is an unsigned binary downloaded at
    /// build time (xray, aether, lyrebird…) — exactly the shape Defender's
    /// heuristics quarantine mid-extraction, which stranded buyers with a
    /// half-populated dir that the old extractor then trusted forever.
    /// When we run elevated (the packaged app always does after the UAC
    /// relaunch) we ask Defender — once per install, best-effort, silently —
    /// to exclude ONLY the cores directory. Third-party AVs are untouched.
    fun defenderExclusionOnce() {
        if (!AppPaths.isWindows()) return
        val marker = File(AppPaths.baseDir, "defender-exclusion.done")
        if (marker.isFile) return
        try {
            val proc = ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-Command",
                "try { Add-MpPreference -ExclusionPath '${AppPaths.baseDir.resolve("cores").absolutePath}' -ErrorAction Stop; 'ok' } catch { 'no' }",
            ).redirectErrorStream(true).start()
            // Wait FIRST, read after — the 1.7.x shape called the unbounded
            // readText() BEFORE waitFor, so a wedged powershell pinned the
            // connect thread (the mutex holder) with no deadline.
            if (!proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                EngineProcess.logLine("[cores] Defender exclusion attempt timed out — will retry on the next connect")
                return
            }
            val answer = proc.inputStream.bufferedReader().readText().trim()
            val ok = answer.endsWith("ok")
            marker.writeText(if (ok) "exclusion granted" else "exclusion refused ($answer)")
            EngineProcess.logLine("[cores] Defender exclusion for the cores dir: ${if (ok) "granted" else "refused — real-time protection may quarantine the engines again"}")
        } catch (e: Exception) {
            EngineProcess.logLine("[cores] Defender exclusion attempt failed: ${e.message}")
        }
    }
}
