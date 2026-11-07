package com.beevpn.win.engine

import com.beevpn.win.warp.WarpIdentity
import com.beevpn.win.warp.WarpProvisioner
import org.json.JSONArray
import org.json.JSONObject

/// The full Xray configuration — the desktop port of the Android core's
/// config assembly. ONE xray process carries everything, exactly like the
/// phone: the TUN inbound (native Wintun on Windows) + the SOCKS 1819
/// listener (the same port the Android app uses) + the selected exit +
/// the direct/block legs.
object EngineConfig {

    /// The one SOCKS port the app uses, everywhere — same number as CoreConfig.kt.
    const val SOCKS_PORT = 1819

    /// The anytls sidecar's listener — same slot plan as Android (1826).
    const val ANYTLS_SIDECAR_PORT = 1826

    /// The TUN interface's own IPv4 (the /30 gateway). TunnelVerifier checks
    /// the system default route against this — keep in sync with it.
    const val TUN_INTERFACE_IP = "172.19.0.1"

    /// The provisioning listener — the desktop ShardManager.SOCKS_PORT (1824):
    /// a listener-only xray raised for identity registration when the carrier
    /// blocks the account API. The aether core's AETHER_UPSTREAM rides it, the
    /// same rail provisionIdentityThroughShard opens on the phone.
    const val PROVISION_SOCKS_PORT = 1824

    private const val TUN_GATEWAY_V4 = "$TUN_INTERFACE_IP/30"
    private const val TUN_GATEWAY_V6 = "fdfe:dcba:9876::1/126"

    private val PRIVATE_CIDRS = listOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8",
        "169.254.0.0/16", "100.64.0.0/10", "0.0.0.0/8", "224.0.0.0/3",
        "::1/128", "fc00::/7", "fe80::/10",
    )

    /// Builds the config. `exitOutbound` carries the tag the routing rule
    /// points at (the builder already set tag "bee-exit"); pass null to run
    /// the direct leg as the exit (fragment mode without a node).
    /// `ipv6Available` gates every IPv6 entry in the TUN inbound: when IPv6
    /// is disabled system-wide (registry DisabledComponents — common on
    /// Iranian machines), Xray's TUN **refuses to start** if the config
    /// carries IPv6 gateway/route entries (proxy/tun README), which presented
    /// to buyers as «کانکت می‌شود و وصل نمی‌شود». IPv4-only is always valid.
    fun build(
        useTun: Boolean,
        ipv6Available: Boolean,
        exitOutbound: JSONObject?,
        fragmentOnDirect: Boolean,
        shardParity: Boolean = false,
        includeSocksIn: Boolean = true,
    ): JSONObject {
        val root = JSONObject()
        root.put("log", JSONObject().put("loglevel", "warning"))

        val inbounds = JSONArray()
        if (useTun) {
            inbounds.put(
                JSONObject()
                    .put("tag", "tun-in")
                    .put("protocol", "tun")
                    .put(
                        "settings",
                        JSONObject()
                            .put("name", "BeeVPN")
                            .put("desc", "BEE-VPN")
                            .put("mtu", 1500)
                            .put("gateway", JSONArray().apply {
                                put(TUN_GATEWAY_V4)
                                if (ipv6Available) put(TUN_GATEWAY_V6)
                            })
                            .put("dns", JSONArray().put("1.1.1.1").put("8.8.8.8"))
                            .put("autoSystemRoutingTable", JSONArray().apply {
                                put("0.0.0.0/0")
                                if (ipv6Available) put("::/0")
                            })
                            .put("autoOutboundsInterface", "auto")
                            // Windows queries other interfaces' DNS servers
                            // regardless of the routing table — without the
                            // WFP leak block, DNS walks around the tunnel and
                            // the buyer sees «وصل است ولی سایت‌ها باز نمی‌شوند».
                            .put("autoSystemWfpBlockLeak", JSONArray().put("dns")),
                    )
                    .put(
                        "sniffing",
                        JSONObject().put("enabled", true)
                            .put("destOverride", JSONArray().put("http").put("tls").put("quic")),
                    ),
            )
        }
        inbounds.put(
            JSONObject()
                .put("tag", "socks-in")
                .put("listen", "127.0.0.1")
                .put("port", SOCKS_PORT)
                .put("protocol", "socks")
                .put("settings", JSONObject().put("auth", "noauth").put("udp", true))
                .put(
                    "sniffing",
                    JSONObject().put("enabled", true)
                        .put("destOverride", JSONArray().put("http").put("tls").put("quic")),
                ),
        )
        if (!includeSocksIn) inbounds.remove(inbounds.length() - 1)
        root.put("inbounds", inbounds)

        val outbounds = JSONArray()
        if (exitOutbound != null) outbounds.put(exitOutbound)
        outbounds.put(
            JSONObject()
                .put("tag", "direct")
                .put("protocol", "freedom")
                .put(
                    "settings",
                    JSONObject()
                        .put("domainStrategy", "AsIs")
                        .apply {
                            if (fragmentOnDirect) {
                                put(
                                    "fragment",
                                    JSONObject()
                                        .put("packets", "tlshello")
                                        .put("length", "100-200")
                                        .put("interval", "10-20"),
                                )
                            }
                        },
                ),
        )
        outbounds.put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        // Android's live SHARD policy (ShardConfigs.tunnelConfig): keep every
        // connection alive indefinitely and let the OS own the idle timeout —
        // a desktop tunnel must never die from xray's default 300 s connIdle.
        if (shardParity) {
            root.put(
                "policy",
                JSONObject().put(
                    "levels",
                    JSONObject()
                        .put("0", JSONObject().put("uplinkOnly", 0).put("downlinkOnly", 0))
                        .put(
                            "1",
                            JSONObject().put("uplinkOnly", 0).put("downlinkOnly", 0)
                                .put("connIdle", 12),
                        ),
                ),
            )
        }
        root.put("outbounds", outbounds)

        val rules = JSONArray().put(
            JSONObject()
                .put("type", "field")
                .put("ip", JSONArray().apply { PRIVATE_CIDRS.forEach { put(it) } })
                .put("outboundTag", "direct"),
        )
        root.put(
            "routing",
            JSONObject()
                .put("domainStrategy", "AsIs")
                .put("rules", rules),
        )
        // xray routes unmatched traffic to the FIRST outbound — the exit was
        // put first above, so the default lands right without extra fields.
        return root
    }

    /// The selected SHARD node as an xray outbound, tagged "bee-exit".
    /// VLESS nodes carry Android's exact mux (concurrency 8, xudp 16, QUIC
    /// skip) — the measured fast-connect lever on the phone.
    fun nodeExit(profile: com.beevpn.win.shard.ShardProfile): JSONObject {
        val exit = com.beevpn.win.shard.ShardOutboundBuilder.outbound(profile, "bee-exit")
        if (profile.configType == com.beevpn.win.shard.ShardType.VLESS) {
            exit.put(
                "mux",
                JSONObject()
                    .put("enabled", true)
                    .put("concurrency", 8)
                    .put("xudpConcurrency", 16)
                    .put("xudpProxyUDP443", "skip"),
            )
        }
        return exit
    }

    /// The WARP wireguard outbound — the registered (or manual wg-quick)
    /// identity, the same data the Android core keeps in aether.toml.
    /// peers[0].reserved carries the registration's client_id bytes — the
    /// exact bytes the aether core injects at pkt[1..4]. Cloudflare silently
    /// drops reserved-less handshakes, which showed up as
    /// «تونل بالا آمد اما خروجی (نود/WARP) ترافیک پاس نمی‌دهد».
    fun warpExit(identity: WarpIdentity): JSONObject = JSONObject()
        .put("tag", "bee-exit")
        .put("protocol", "wireguard")
        .put(
            "settings",
            JSONObject()
                .put("secretKey", identity.privateKey)
                .put(
                    "address",
                    JSONArray()
                        .put(identity.addressV4.substringBefore("/"))
                        .apply { if (identity.addressV6.isNotEmpty()) put(identity.addressV6.substringBefore("/")) },
                )
                .put(
                    "peers",
                    JSONArray().put(
                        JSONObject()
                            .put("publicKey", identity.peerPublicKey)
                            .put("endpoint", identity.endpoint)
                            .put("allowedIPs", JSONArray().put("0.0.0.0/0").put("::/0"))
                            .apply {
                                identity.reservedBytes()?.let { reserved ->
                                    put("reserved", JSONArray().apply { reserved.forEach { put(it) } })
                                }
                            },
                    ),
                )
                .put("noKernelTun", true)
                .put("mtu", 1280)
                .put("remoteDNS", JSONArray().put("1.1.1.1").put("1.0.0.1")),
        )

    /// AnyTLS profiles: Xray has no anytls outbound, so the exit is a SOCKS
    /// hop to the sing-box sidecar — the desktop twin of Android's anytls
    /// sidecar on 1826.
    fun anytlsExit(): JSONObject = JSONObject()
        .put("tag", "bee-exit")
        .put("protocol", "socks")
        .put(
            "settings",
            JSONObject()
                .put("address", "127.0.0.1")
                .put("port", ANYTLS_SIDECAR_PORT),
        )

    /// The WARP-family exit: a SOCKS hop into the aether core's listener on
    /// 1819 — the desktop twin of tun2socks dialing the phone's core. UDP
    /// rides the same hop so DNS and QUIC flow like they do on Android.
    fun aetherBridgeExit(): JSONObject = JSONObject()
        .put("tag", "bee-exit")
        .put("protocol", "socks")
        .put(
            "settings",
            JSONObject()
                .put("address", "127.0.0.1")
                .put("port", AetherEngine.SOCKS_PORT)
                .put("udp", true),
        )

    /// The wg-quick text for the identity — shown in the Advanced tab and
    /// handed to the user for the manual mode, byte-for-byte the iOS shape.
    fun wgQuickOf(identity: WarpIdentity): String = WarpProvisioner.buildWGQuick(identity)

    /// The listener-only config identity provisioning rides: one SOCKS inbound
    /// on 1824 + the selected SHARD node as the exit — NO TUN, no system route,
    /// nothing the user can see. The desktop twin of Android raising xray on
    /// 1824 for registration while the user's screen still says «WireGuard».
    fun provisioningConfig(profile: com.beevpn.win.shard.ShardProfile): JSONObject {
        val root = JSONObject()
        root.put("log", JSONObject().put("loglevel", "warning"))
        root.put(
            "inbounds",
            org.json.JSONArray().put(
                JSONObject()
                    .put("tag", "socks-provision")
                    .put("listen", "127.0.0.1")
                    .put("port", PROVISION_SOCKS_PORT)
                    .put("protocol", "socks")
                    .put("settings", JSONObject().put("auth", "noauth").put("udp", false)),
            ),
        )
        root.put(
            "outbounds",
            org.json.JSONArray()
                .put(nodeExit(profile))
                .put(JSONObject().put("tag", "block").put("protocol", "blackhole")),
        )
        return root
    }
}
