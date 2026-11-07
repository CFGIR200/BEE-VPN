package com.beevpn.win.engine

import com.beevpn.win.AppPaths
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/// Spawns and supervises the engine processes (xray.exe, sing-box.exe),
/// tees their output into logs/engine.log, and exposes simple readiness
/// probes. One EngineProcess per binary; TunnelController owns the pair.
class EngineProcess(
    private val name: String,
    private val exe: File,
    private val args: List<String>,
    private val workingDir: File,
    private val env: Map<String, String> = emptyMap(),
) {

    @Volatile var onLine: ((String) -> Unit)? = null
    @Volatile var onExit: ((Int) -> Unit)? = null

    private var process: Process? = null
    private val lock = Any()

    fun start() {
        synchronized(lock) {
            check(process == null) { "$name already running" }
            val pb = ProcessBuilder(exe.absolutePath, *args.toTypedArray())
                .directory(workingDir)
                .redirectErrorStream(true)
            val penv = pb.environment()
            // Env hygiene: strip every inherited AETHER_* key first — a stale
            // AETHER_UPSTREAM from the parent shell must never leak into the
            // engine (the phone's applyEnv() unsets the same set between
            // connects, CoreConfig.kt:759-774).
            penv.keys.removeAll { it.startsWith("AETHER_") }
            env.forEach { (k, v) -> penv[k] = v }
            penv["BEE_ENGINE"] = name
            val p = pb.start()
            process = p
            Thread({
                try {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        onLine?.invoke(line)
                    }
                } catch (_: Exception) {}
            }, "$name-log").apply { isDaemon = true }.start()
            Thread({
                val code = try { p.waitFor() } catch (_: Exception) { -1 }
                onExit?.invoke(code)
            }, "$name-watch").apply { isDaemon = true }.start()
        }
    }

    val isAlive: Boolean get() = process?.isAlive == true

    fun stop() {
        synchronized(lock) {
            val p = process ?: return
            process = null
            try {
                p.destroy()
                if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly()
                    p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                }
            } catch (_: Exception) {}
        }
    }

    companion object {
        fun logLine(line: String) {
            try {
                AppPaths.engineLog.appendText("$line\n")
            } catch (_: Exception) {}
        }

        /// True when a TCP listener accepts on the loopback port.
        fun portOpen(port: Int, timeoutMs: Int = 400): Boolean = try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                true
            }
        } catch (_: Exception) { false }

        /// Polls until the port answers or the deadline lapses.
        fun awaitPort(port: Int, timeoutMs: Long, alive: () -> Boolean): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (portOpen(port)) return true
                if (!alive()) return false
                Thread.sleep(120)
            }
            return portOpen(port)
        }
    }
}
