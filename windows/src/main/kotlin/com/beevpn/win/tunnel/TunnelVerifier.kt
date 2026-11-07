package com.beevpn.win.tunnel

import com.beevpn.win.AppPaths
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URI
import javax.net.ssl.HttpsURLConnection

/// The desktop twin of MsnGuardVpnService's "never trust the engine" layer:
/// the phone probes the SOCKS listener AND owns the TUN through VpnService
/// before it says «متصل». On Windows the TUN lives inside xray, so the only
/// honest way to claim CONNECTED is to verify the system routing table and
/// push real traffic through both the engine and the system.
///
/// Everything here is designed to fail CLOSED: when a check cannot run it
/// reports failure, and the caller turns that into a readable Persian
/// message instead of a fake green dot.
object TunnelVerifier {

    /// The IPv4 address EngineConfig assigns to the BeeVPN TUN interface
    /// (gateway prefix 172.19.0.1/30). The system default route, whether
    /// installed by xray or by our fallback, must point at this interface.
    const val TUN_INTERFACE_IP = "172.19.0.1"

    /// Android's reconnect ladder — RECONNECT_BACKOFF_S in MsnGuardVpnService.
    val RECONNECT_BACKOFF_S = intArrayOf(5, 15, 30, 60, 120)

    /// The cloudflare trace endpoint — returns `ip=<egress>` among other
    /// fields. Same idea as the phone's traffic polling: a byte that must
    /// come out the far end of the tunnel.
    private const val TRACE_HOST = "www.cloudflare.com"
    private const val TRACE_PATH = "/cdn-cgi/trace"

    /// The generate_204 far end — cp.cloudflare.com, the same host the
    /// phone's PING_URLS use for the in-tunnel verification probe.
    private const val PROBE_204_HOST = "cp.cloudflare.com"

    // ---------------------------------------------------- system routing

    /// Does the system IPv4 routing table send 0.0.0.0/0 through the TUN?
    /// Parses `route print -4`; matches on the Interface column (always an
    /// IP — the Gateway column is localized text like "On-link").
    fun tunRouteWins(): Boolean = tunRouteWinsIn(routePrint4(), TUN_INTERFACE_IP)

    /// Pure parser — visible for tests. `output` is raw `route print -4`
    /// text; a default route (0.0.0.0 / 0.0.0.0) whose Interface column is
    /// `interfaceIp` means the TUN owns the system default.
    fun tunRouteWinsIn(output: String, interfaceIp: String): Boolean =
        output.lineSequence().any { line ->
            val cols = line.trim().split(Regex("\\s+"))
            cols.size >= 4 &&
                cols[0] == "0.0.0.0" && cols[1] == "0.0.0.0" &&
                cols[3] == interfaceIp
        }

    /// Last-resort full-tunnel enforcement (Android-parity: the phone's TUN
    /// is the default route by construction). If xray's route lost the
    /// metric race against the physical NIC, install our own 0.0.0.0/0
    /// through the TUN with metric 1 — and remember it so stopEngines
    /// removes it again.
    @Volatile private var fallbackRouteInstalled = false

    fun ensureTunRoute(): Boolean {
        if (tunRouteWins()) return true
        val ok = run(
            "route", "add", "0.0.0.0", "mask", "0.0.0.0", TUN_INTERFACE_IP, "metric", "1",
        )
        fallbackRouteInstalled = ok
        return tunRouteWins()
    }

    /// Removes the fallback route (xray removes its own on exit).
    fun removeFallbackRoute() {
        if (!fallbackRouteInstalled) return
        fallbackRouteInstalled = false
        run("route", "delete", "0.0.0.0", "mask", "0.0.0.0", TUN_INTERFACE_IP)
    }

    /// A stale fallback route from a crashed session would blackhole traffic
    /// after the TUN died — sweep it before every connect (no-op if absent).
    fun sweepStaleRoute() {
        run("route", "delete", "0.0.0.0", "mask", "0.0.0.0", TUN_INTERFACE_IP)
    }

    private fun routePrint4(): String = runCapture("route", "print", "-4") ?: ""

    private fun run(vararg cmd: String): Boolean = try {
        val p = ProcessBuilder(*cmd).start()
        p.waitFor() == 0
    } catch (_: Exception) {
        false
    }

    private fun runCapture(vararg cmd: String): String? = try {
        val p = ProcessBuilder(*cmd).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out
    } catch (_: Exception) {
        null
    }

    // ---------------------------------------------------- egress probes

    /// Real traffic through the engine's SOCKS listener: SOCKS5 CONNECT to
    /// the trace endpoint, GET, parse the egress IP. The desktop twin of the
    /// phone's `waitForSocksReady` — but one step further: bytes must come
    /// back, not just a TCP accept.
    fun traceIpViaSocks(port: Int, timeoutMs: Long): String? {
        return try {
            Socket().use { s ->
                s.tcpNoDelay = true
                s.soTimeout = timeoutMs.toInt()
                s.connect(InetSocketAddress("127.0.0.1", port), timeoutMs.toInt())

                val out = s.getOutputStream()
                val input = s.getInputStream()

                // SOCKS5 greeting: ver 5, 1 method, no-auth
                out.write(byteArrayOf(0x05, 0x01, 0x00)); out.flush()
                val greet = readN(input, 2)
                if (greet.size < 2 || greet[0] != 0x05.toByte()) return null

                // CONNECT domain-name
                val host = TRACE_HOST.toByteArray(Charsets.US_ASCII)
                val req = ByteArray(7 + host.size)
                req[0] = 0x05; req[1] = 0x01; req[2] = 0x00
                req[3] = 0x03; req[4] = host.size.toByte()
                host.copyInto(req, 5)
                req[5 + host.size] = 0x00; req[6 + host.size] = 0x50 // 80
                out.write(req); out.flush()

                val rep = readN(input, 10)
                if (rep.size < 2 || rep[1] != 0x00.toByte()) return null

                val http = ("GET $TRACE_PATH HTTP/1.1\r\n" +
                    "Host: $TRACE_HOST\r\n" +
                    "User-Agent: BEE-VPN/1.0\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
                out.write(http); out.flush()

                val body = StringBuilder()
                val buf = ByteArray(4096)
                try {
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        body.append(String(buf, 0, n, Charsets.UTF_8))
                        if (body.length > 64_000) break
                    }
                } catch (_: Exception) { /* close is expected */ }
                parseTraceIp(body.toString())
            }
        } catch (_: Exception) {
            null
        }
    }

    /// Real traffic through the SYSTEM routing table — no proxy anywhere.
    /// If the TUN owns the default route this comes back as the tunnel's
    /// egress IP; if the physical NIC still owns it, it matches the
    /// pre-connect baseline and we must not claim «متصل».
    fun traceIpDirect(timeoutMs: Long): String? {
        return try {
            val conn = URI("https", null, TRACE_HOST, 443, TRACE_PATH, null, null)
                .toURL().openConnection() as HttpsURLConnection
            conn.connectTimeout = timeoutMs.toInt()
            conn.readTimeout = timeoutMs.toInt()
            conn.setRequestProperty("User-Agent", "BEE-VPN/1.0")
            conn.instanceFollowRedirects = false
            try {
                if (conn.responseCode != 200) return null
                parseTraceIp(conn.inputStream.bufferedReader().readText())
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    /// Pure parser — the trace body is `key=value` lines.
    fun parseTraceIp(body: String): String? =
        body.lineSequence()
            .firstOrNull { it.startsWith("ip=") }
            ?.substringAfter("ip=")
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= 45 }

    /// The second e2e shape through the same SOCKS listener: a real
    /// generate_204 request. Some networks blackhole one far end (the trace
    /// host) while the tunnel itself is perfectly alive — a byte that returns
    /// a 204 is the same proof with a different far end, and it is exactly
    /// the probe the phone's UI verification uses (PING_URLS). True when the
    /// far end answered 204/200 through the tunnel.
    fun tunnel204ViaSocks(port: Int, timeoutMs: Long): Boolean {
        return try {
            Socket().use { s ->
                s.tcpNoDelay = true
                s.soTimeout = timeoutMs.toInt()
                s.connect(InetSocketAddress("127.0.0.1", port), timeoutMs.toInt())

                val out = s.getOutputStream()
                val input = s.getInputStream()

                // SOCKS5 greeting: ver 5, 1 method, no-auth
                out.write(byteArrayOf(0x05, 0x01, 0x00)); out.flush()
                val greet = readN(input, 2)
                if (greet.size < 2 || greet[0] != 0x05.toByte()) return false

                // CONNECT cp.cloudflare.com:80 by name — the same ATYT the
                // phone's shard probes use, so the resolver inside the tunnel
                // is what decides, not the carrier's poisoned DNS.
                val host = PROBE_204_HOST.toByteArray(Charsets.US_ASCII)
                val req = ByteArray(7 + host.size)
                req[0] = 0x05; req[1] = 0x01; req[2] = 0x00
                req[3] = 0x03; req[4] = host.size.toByte()
                host.copyInto(req, 5)
                req[5 + host.size] = 0x00; req[6 + host.size] = 0x50 // 80
                out.write(req); out.flush()

                val rep = readN(input, 10)
                if (rep.size < 2 || rep[1] != 0x00.toByte()) return false

                val http = ("GET /generate_204 HTTP/1.1\r\n" +
                    "Host: $PROBE_204_HOST\r\n" +
                    "User-Agent: BEE-VPN/1.0\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
                out.write(http); out.flush()

                val buf = ByteArray(1024)
                var head = ""
                try {
                    val n = input.read(buf)
                    if (n > 0) head = String(buf, 0, n, Charsets.UTF_8)
                } catch (_: Exception) { /* close is fine after the reply */ }
                head.contains(" 204 ") || head.contains(" 200 ")
            }
        } catch (_: Exception) {
            false
        }
    }

    // ---------------------------------------------------- capability probe

    /// True when any non-loopback interface carries an IPv6 address. When
    /// IPv6 is disabled system-wide (registry DisabledComponents — common on
    /// Iranian machines), Xray's TUN refuses to start if the config carries
    /// IPv6 gateway/route entries, so the config must be built IPv4-only.
    fun ipv6Available(): Boolean = try {
        NetworkInterface.getNetworkInterfaces().toList().any { ni ->
            !ni.isLoopback && ni.isUp &&
                ni.inetAddresses.toList().any { it is Inet6Address }
        }
    } catch (_: Exception) {
        false
    }

    private fun readN(input: java.io.InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) break
            off += r
        }
        return buf.copyOf(off)
    }

    /// Best-effort log helper so the advanced report shows what the
    /// verifier saw — the phone logs the same way.
    fun log(line: String) {
        try {
            AppPaths.engineLog.appendText("[verify] $line\n")
        } catch (_: Exception) {}
    }
}
