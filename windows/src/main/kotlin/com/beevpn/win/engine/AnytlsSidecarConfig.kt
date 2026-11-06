package com.beevpn.win.engine

import com.beevpn.win.shard.ShardProfile
import org.json.JSONObject

/// The sing-box sidecar for AnyTLS nodes — Xray has no anytls outbound, and
/// rather than give buyers fewer protocols than the phone, the desktop runs
/// the same sidecar pattern Android runs: a second small engine listening on
/// 127.0.0.1:1826, with the anytls hop inside it.
object AnytlsSidecarConfig {

    fun build(profile: ShardProfile, logFile: String): JSONObject {
        val tls = JSONObject()
            .put("enabled", true)
        val sni = profile.sni?.takeIf { it.isNotBlank() } ?: profile.server
        if (!sni.isNullOrBlank()) tls.put("server_name", sni)
        if (profile.insecure == true) tls.put("insecure", true)
        if (!profile.alpn.isNullOrBlank()) {
            tls.put("alpn", org.json.JSONArray().put(profile.alpn))
        }
        return JSONObject()
            .put(
                "log",
                JSONObject().put("level", "warn").put("output", logFile),
            )
            .put(
                "inbounds",
                org.json.JSONArray().put(
                    JSONObject()
                        .put("type", "socks")
                        .put("tag", "sidecar-in")
                        .put("listen", "127.0.0.1")
                        .put("listen_port", EngineConfig.ANYTLS_SIDECAR_PORT),
                ),
            )
            .put(
                "outbounds",
                org.json.JSONArray().put(
                    JSONObject()
                        .put("type", "anytls")
                        .put("tag", "anytls-out")
                        .put("server", profile.server ?: "")
                        .put("server_port", profile.serverPort?.toIntOrNull() ?: 443)
                        .put("password", profile.password ?: "")
                        .put("tls", tls),
                ),
            )
            .put(
                // The sidecar's own dialing must ride the physical interface —
                // otherwise the TUN would capture it into a loop.
                "route",
                JSONObject().put("auto_detect_interface", true),
            )
    }
}
