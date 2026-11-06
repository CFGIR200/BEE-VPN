package com.beevpn.win.shard

/// One SHARD / manual node — the same field set ShardProfile.kt carries on
/// Android (only the fields the outbound builder and the UI touch).
data class ShardProfile(
    val configType: ShardType,
    val server: String? = null,
    val serverPort: String? = null,
    val password: String? = null,   // uuid (vless/vmess) | password (trojan/ss/hy2/anytls)
    val method: String? = null,     // encryption (vless) | security (vmess) | cipher (ss)
    val alterId: Int? = null,       // vmess
    val flow: String? = null,       // vless xtls flow
    val name: String? = null,       // remarks
    val username: String? = null,   // socks
    // stream
    val network: String? = null,    // tcp | ws | grpc | h2 | httpupgrade | xhttp | hysteria
    val headerType: String? = null, // http (tcp camouflage)
    val host: String? = null,
    val path: String? = null,
    val xhttpMode: String? = null,
    val xhttpExtra: String? = null,
    val mode: String? = null,       // grpc multiMode
    val serviceName: String? = null,
    val authority: String? = null,
    // tls / reality
    val security: String? = null,   // tls | reality | none
    val sni: String? = null,
    val fingerPrint: String? = null,
    val alpn: String? = null,
    val cipherSuites: String? = null,
    val echConfigList: String? = null,
    val echOutbound: String? = null,
    val verifyPeerCertByName: String? = null,
    val pinnedCA256: String? = null,
    val publicKey: String? = null,  // reality pbk
    val shortId: String? = null,    // reality sid
    val spiderX: String? = null,    // reality spx
    val mldsa65Verify: String? = null,
    val insecure: Boolean? = null,  // allowInsecure
    val finalMask: String? = null,
    // hysteria2
    val obfsPassword: String? = null,
) {
    fun displayLabel(): String {
        val label = name?.takeIf { it.isNotBlank() } ?: return "$server:$port"
        return label
    }

    val port: String get() = serverPort ?: "443"

    fun key(): String = "$configType|$server|$serverPort|${password ?: ""}|${method ?: ""}|${sni ?: ""}"
}

enum class ShardType(val scheme: String) {
    VLESS("vless://"),
    VMESS("vmess://"),
    TROJAN("trojan://"),
    SHADOWSOCKS("ss://"),
    SOCKS("socks://"),
    WIREGUARD("wireguard://"),
    HYSTERIA2("hysteria2://"),
    ANYTLS("anytls://"),
}
