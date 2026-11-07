package com.beevpn.win.shard

import org.json.JSONObject
import java.util.Base64

/// Batch import — the port of ShardImport.kt's parseBatch (AngConfigManager
/// order) plus the vmess/anytls formats the desktop manual import accepts.
object ShardImport {

    fun parseBatch(body: String?): List<ShardProfile> {
        if (body.isNullOrEmpty()) return emptyList()
        // Try decoded then raw — exactly like importBatchConfig.
        var list = tryParse(ShardUtils.decode(body))
        if (list.isNotEmpty()) return list
        list = tryParse(body)
        return list
    }

    private fun tryParse(text: String?): List<ShardProfile> {
        if (text.isNullOrEmpty()) return emptyList()
        val profiles = mutableListOf<ShardProfile>()
        val distinctReversed = text.lines().distinct().reversed()
        for (raw in distinctReversed) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val p = parseOne(line) ?: continue
            // The publisher ships "Sni-Spoofing" variants that dial
            // 127.0.0.1:<port> — they only work behind a LOCAL forwarder this
            // app does not run, so every one of them is a race slot burned on
            // a guaranteed probe failure. Loopback servers never enter the pool.
            if (p.server == "127.0.0.1" || p.server == "localhost" || p.server == "::1" || p.server == "[::1]") continue
            profiles.add(p)
        }
        return profiles
    }

    fun parseOne(line: String): ShardProfile? = try {
        when {
            line.startsWith(ShardType.VLESS.scheme, ignoreCase = true) -> VlessFmt.parse(line)
            line.startsWith(ShardType.VMESS.scheme, ignoreCase = true) -> VmessFmt.parse(line)
            line.startsWith(ShardType.TROJAN.scheme, ignoreCase = true) -> TrojanFmt.parse(line)
            line.startsWith(ShardType.SHADOWSOCKS.scheme, ignoreCase = true) -> ShadowsocksFmt.parse(line)
            line.startsWith("socks://", ignoreCase = true) || line.startsWith("socks5://", ignoreCase = true) -> SocksFmt.parse(line)
            line.startsWith(ShardType.HYSTERIA2.scheme, ignoreCase = true) || line.startsWith("hysteria://", ignoreCase = true) -> Hysteria2Fmt.parse(line)
            line.startsWith(ShardType.ANYTLS.scheme, ignoreCase = true) -> AnytlsFmt.parse(line)
            else -> null
        }
    } catch (_: Exception) { null }
}

internal object ShardUtils {

    /// Whole-body base64 — the way subscriptions ship. Returns the original
    /// when the body is not base64 (plain list).
    fun decode(body: String): String {
        val t = body.trim()
        if (!t.matches(Regex("^[A-Za-z0-9+/=_\\s-]+$"))) return body
        return try {
            var s = t.replace("-", "+").replace("_", "/").replace("\n", "").replace("\r", "")
            while (s.length % 4 != 0) s += "="
            val decoded = String(Base64.getMimeDecoder().decode(s), Charsets.UTF_8)
            if (decoded.contains("://")) decoded else body
        } catch (_: Exception) { body }
    }

    fun isDomainName(s: String?): Boolean {
        if (s.isNullOrEmpty()) return false
        return s.matches(Regex("^(?!-)[A-Za-z0-9-]{1,63}(?<!-)(\\.[A-Za-z0-9-]{1,63})*\\.[A-Za-z]{2,}$"))
    }

    fun safeUrl(line: String): java.net.URI = java.net.URI.create(line.trim())
}

/// Shared query handling for the URL-based schemes.
internal abstract class FmtBase {
    protected fun queryOf(uri: java.net.URI): Map<String, String> {
        val raw = uri.rawQuery ?: return emptyMap()
        val out = mutableMapOf<String, String>()
        for (pair in raw.split("&")) {
            val i = pair.indexOf('=')
            if (i <= 0) continue
            val k = urlDecode(pair.substring(0, i))
            val v = urlDecode(pair.substring(i + 1))
            out[k] = v
        }
        return out
    }

    private fun urlDecode(s: String): String = try {
        java.net.URLDecoder.decode(s, Charsets.UTF_8)
    } catch (_: Exception) { s }

    protected fun remark(uri: java.net.URI): String? = try {
        if (uri.rawFragment.isNullOrEmpty()) null
        else java.net.URLDecoder.decode(uri.rawFragment, Charsets.UTF_8)
    } catch (_: Exception) { uri.rawFragment }
}

internal object VlessFmt : FmtBase() {
    fun parse(line: String): ShardProfile {
        val uri = ShardUtils.safeUrl(line)
        val q = queryOf(uri)
        val hostPort = uri.authority.substringAfter("@")
        val host = hostPort.substringBeforeLast(":")
        val port = hostPort.substringAfterLast(":")
        return ShardProfile(
            configType = ShardType.VLESS,
            password = uri.userInfo,
            server = host,
            serverPort = port,
            method = q["encryption"] ?: "none",
            flow = q["flow"],
            name = remark(uri),
            network = q["type"] ?: "tcp",
            headerType = q["headerType"],
            host = q["host"],
            path = q["path"],
            xhttpMode = q["mode"]?.takeIf { q["type"] == "xhttp" },
            mode = q["mode"]?.takeIf { q["type"] == "grpc" },
            serviceName = q["serviceName"],
            authority = q["authority"],
            security = q["security"],
            sni = q["sni"] ?: q["host"],
            fingerPrint = q["fp"],
            alpn = q["alpn"],
            cipherSuites = q["cis"],
            echConfigList = q["ech"] ?: q["echConfigList"],
            verifyPeerCertByName = q["vpcn"],
            pinnedCA256 = q["pcs"],
            publicKey = q["pbk"],
            shortId = q["sid"],
            spiderX = q["spx"],
            mldsa65Verify = q["mls65"] ?: q["mlkem65Verify"],
            insecure = (q["allowInsecure"] ?: q["insecure"])?.let { it == "1" || it.equals("true", true) },
        )
    }
}

internal object VmessFmt : FmtBase() {
    /// vmess://base64(json) — the v2rayN format.
    fun parse(line: String): ShardProfile {
        var t = line.substring("vmess://".length)
        var s = t.replace('-', '+').replace('_', '/')
        while (s.length % 4 != 0) s += "="
        val obj = JSONObject(String(Base64.getDecoder().decode(s), Charsets.UTF_8))
        return ShardProfile(
            configType = ShardType.VMESS,
            password = obj.optString("id"),
            server = obj.optString("add"),
            serverPort = obj.optString("port"),
            method = obj.optString("scy", "auto").ifEmpty { "auto" },
            alterId = obj.optInt("aid", 0),
            name = obj.optString("ps"),
            network = obj.optString("net", "tcp"),
            headerType = obj.optString("headerType"),
            host = obj.optString("host"),
            path = obj.optString("path"),
            serviceName = obj.optString("serviceName"),
            authority = obj.optString("authority"),
            security = obj.optString("tls", "").ifEmpty { null },
            sni = obj.optString("sni"),
            fingerPrint = obj.optString("fp"),
            alpn = obj.optString("alpn"),
            insecure = if (obj.has("verify_cert")) !obj.optBoolean("verify_cert", true) else null,
        )
    }
}

internal object TrojanFmt : FmtBase() {
    fun parse(line: String): ShardProfile {
        val uri = ShardUtils.safeUrl(line)
        val q = queryOf(uri)
        val hostPort = uri.authority.substringAfter("@")
        val host = hostPort.substringBeforeLast(":")
        val port = hostPort.substringAfterLast(":")
        return ShardProfile(
            configType = ShardType.TROJAN,
            password = uri.userInfo,
            server = host,
            serverPort = port,
            name = remark(uri),
            network = q["type"] ?: "tcp",
            headerType = q["headerType"],
            host = q["host"],
            path = q["path"],
            serviceName = q["serviceName"],
            security = q["security"] ?: "tls",
            sni = q["sni"] ?: q["peer"],
            fingerPrint = q["fp"],
            alpn = q["alpn"],
            verifyPeerCertByName = q["vpcn"],
            insecure = (q["allowInsecure"] ?: q["insecure"])?.let { it == "1" || it.equals("true", true) },
        )
    }
}

internal object ShadowsocksFmt : FmtBase() {
    fun parse(line: String): ShardProfile {
        val body = line.substring("ss://".length)
        // Form A: ss://base64(method:pass)@host:port/?params#remark
        // Form B: ss://base64(method:pass@host:port)#remark
        val frag = body.substringAfter("#", "")
        val remark = if (frag.isEmpty()) null else try {
            java.net.URLDecoder.decode(frag, Charsets.UTF_8)
        } catch (_: Exception) { frag }
        val main = body.substringBefore("#").substringBefore("?")
        return if (main.contains("@")) {
            val userinfo = main.substringBefore("@")
            val hostPort = main.substringAfter("@").trimEnd('/').trimStart('/')
            val decoded = decodePart(userinfo)
            val method = decoded.substringBefore(":")
            val pass = decoded.substringAfter(":", "")
            val host = hostPort.substringBeforeLast(":")
            val port = hostPort.substringAfterLast(":")
            ShardProfile(
                configType = ShardType.SHADOWSOCKS, server = host, serverPort = port,
                method = method, password = pass, name = remark,
            )
        } else {
            val decoded = decodePart(main.trimEnd('/'))
            val method = decoded.substringBefore(":")
            val rest = decoded.substringAfter(":")
            val pass = rest.substringBeforeLast("@")
            val hostPort = rest.substringAfterLast("@")
            ShardProfile(
                configType = ShardType.SHADOWSOCKS,
                server = hostPort.substringBeforeLast(":"),
                serverPort = hostPort.substringAfterLast(":"),
                method = method, password = pass, name = remark,
            )
        }
    }

    private fun decodePart(part: String): String {
        var s = part.replace('-', '+').replace('_', '/')
        while (s.length % 4 != 0) s += "="
        return try {
            String(Base64.getDecoder().decode(s), Charsets.UTF_8)
        } catch (_: Exception) { part }
    }
}

internal object SocksFmt : FmtBase() {
    fun parse(line: String): ShardProfile {
        val uri = ShardUtils.safeUrl(line.replace("socks5://", "socks://", ignoreCase = true))
        val hostPort = uri.authority.substringAfterLast("@")
        return ShardProfile(
            configType = ShardType.SOCKS,
            server = hostPort.substringBeforeLast(":"),
            serverPort = hostPort.substringAfterLast(":"),
            username = if (uri.userInfo != null) uri.userInfo.substringBefore(":") else null,
            password = if (uri.userInfo != null) uri.userInfo.substringAfter(":", "") else null,
            name = remark(uri),
        )
    }
}

internal object Hysteria2Fmt : FmtBase() {
    fun parse(line: String): ShardProfile {
        val uri = ShardUtils.safeUrl(line)
        val q = queryOf(uri)
        val hostPort = uri.authority.substringAfterLast("@")
        val host = hostPort.substringBeforeLast(":").removePrefix("[")
        val port = hostPort.substringAfterLast(":").removeSuffix("]")
        return ShardProfile(
            configType = ShardType.HYSTERIA2,
            password = uri.userInfo,
            server = host,
            serverPort = port,
            name = remark(uri),
            network = "hysteria",
            security = "tls",
            sni = q["sni"] ?: q["peer"],
            insecure = (q["insecure"] ?: q["allowInsecure"])?.let { it == "1" || it.equals("true", true) },
            alpn = q["alpn"] ?: "h3",
            obfsPassword = q["obfs-password"] ?: q["obfsParam"],
        )
    }
}

internal object AnytlsFmt : FmtBase() {
    fun parse(line: String): ShardProfile {
        val uri = ShardUtils.safeUrl(line)
        val q = queryOf(uri)
        val hostPort = uri.authority.substringAfter("@")
        return ShardProfile(
            configType = ShardType.ANYTLS,
            password = uri.userInfo,
            server = hostPort.substringBeforeLast(":"),
            serverPort = hostPort.substringAfterLast(":"),
            name = remark(uri),
            security = "tls",
            sni = q["sni"],
            insecure = q["insecure"]?.let { it == "1" || it.equals("true", true) },
        )
    }
}
