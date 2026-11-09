package com.msnguard.vpn.shard

/** Batch import — mirrors AngConfigManager.parseBatchConfig order exactly. */
internal object ShardImport {

    fun parseBatch(body: String?): List<ShardProfile> {
        if (body.isNullOrEmpty()) return emptyList()
        // Try decoded then raw — exactly like importBatchConfig
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
            // 127.0.0.1:<port> — they only answer behind a LOCAL forwarder this
            // app does not run, so every one of them is a race slot burned on
            // a guaranteed probe failure. Loopback servers never enter the pool
            // (same rule the Windows client enforces in ShardFmt).
            if (p.server == "127.0.0.1" || p.server == "localhost" || p.server == "::1" || p.server == "[::1]") continue
            TlsSettingsCheck.fixImportedAlpn(p)
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
            line.startsWith(ShardType.WIREGUARD.scheme, ignoreCase = true) -> WireguardFmt.parse(line)
            else -> null
        }
    } catch (_: Exception) { null }
}
