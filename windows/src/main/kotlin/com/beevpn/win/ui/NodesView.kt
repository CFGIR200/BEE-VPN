package com.beevpn.win.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beevpn.win.faDigits
import com.beevpn.win.faNum
import com.beevpn.win.shard.ShardProfile
import com.beevpn.win.shard.ShardProber
import com.beevpn.win.shard.ShardType
import com.beevpn.win.tunnel.TunnelController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/// The SHARD pool — the same subscription the Android app syncs, ranked by
/// live TCP latency. Tap to pin a node; the engine always prefers the pin.
@Composable
fun NodesView() {
    var probing by androidx.compose.runtime.remember { mutableStateOf(false) }
    var results by androidx.compose.runtime.remember { mutableStateOf<List<ShardProber.Result>>(TunnelController.lastProbed) }
    var pinned by androidx.compose.runtime.remember { mutableStateOf(TunnelController.pinnedNodeKey) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(Honey.canvas).padding(horizontal = 36.dp, vertical = 22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("نودهای SHARD", style = T.h2)
            Spacer(Modifier.weight(1f))
            Text(
                if (pinned != null) "نود انتخابی فعال است" else "حالت خودکار: سریع‌ترین نود",
                style = T.muted,
            )
            Spacer(Modifier.width(12.dp))
            HoneyButton(
                title = if (probing) "در حال پینگ…" else "↻ به‌روزرسانی و پینگ",
                filled = false,
                modifier = Modifier.width(240.dp),
                busy = probing,
                enabled = !probing,
            ) {
                probing = true
                scope.launch {
                    val ranked = withContext(Dispatchers.IO) {
                        try {
                            val pool = TunnelController.refreshPoolNow()
                            ShardProber.probeAll(pool)
                        } catch (_: Throwable) {
                            TunnelController.nodePool.map { ShardProber.Result(it, -1) }
                        }
                    }
                    results = ranked
                    TunnelController.noteProbed(ranked)
                    probing = false
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "منبع: همان اشتراک اندروید — هر ۶ ساعت با ETag بازخوانی می‌شود",
            style = T.muted,
        )
        Spacer(Modifier.height(12.dp))

        LazyColumn(Modifier.fillMaxSize()) {
            items(results.ifEmpty { TunnelController.nodePool.map { ShardProber.Result(it, -2) } }) { r ->
                NodeRow(
                    profile = r.profile,
                    latency = r.latencyMs,
                    pinned = pinned == r.profile.key(),
                    onPin = {
                        val key = r.profile.key()
                        TunnelController.pinnedNodeKey = if (pinned == key) null else key
                        pinned = TunnelController.pinnedNodeKey
                    },
                )
            }
        }
    }
}

@Composable
private fun NodeRow(profile: ShardProfile, latency: Int, pinned: Boolean, onPin: () -> Unit) {
    val bg = if (pinned) Honey.primary.copy(alpha = 0.10f) else Honey.surface
    HoneyCard(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(bg, RoundedCornerShape(14.dp)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(
                    profile.displayLabel(),
                    style = T.body.copy(fontSize = 13.5.sp),
                    maxLines = 1,
                )
                Text(
                    "${protoLabel(profile.configType)} · ${profile.server ?: "?"}:${faDigits(profile.port)}" +
                        (if (profile.security == "reality") " · REALITY" else if (profile.security == "tls") " · TLS" else ""),
                    style = T.muted.copy(fontSize = 11.5.sp),
                    maxLines = 1,
                )
            }
            val (latText, latColor) = when {
                latency == -2 -> "—" to Honey.muted
                latency < 0 -> "بی‌پاسخ" to Honey.danger
                else -> faNum(latency) + " ms" to Honey.ok
            }
            Text(latText, style = T.body.copy(color = latColor, fontSize = 12.5.sp))
            Spacer(Modifier.width(10.dp))
            Text(
                if (pinned) "★" else "☆",
                fontSize = 17.sp,
                color = if (pinned) Honey.primary else Honey.muted,
                modifier = Modifier.clickable { onPin() },
            )
        }
    }
}

private fun protoLabel(t: ShardType): String = when (t) {
    ShardType.VLESS -> "VLESS"
    ShardType.VMESS -> "VMESS"
    ShardType.TROJAN -> "TROJAN"
    ShardType.SHADOWSOCKS -> "SS"
    ShardType.SOCKS -> "SOCKS"
    ShardType.WIREGUARD -> "WG"
    ShardType.HYSTERIA2 -> "HY2"
    ShardType.ANYTLS -> "ANYTLS"
}
