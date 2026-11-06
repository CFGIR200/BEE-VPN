package com.beevpn.win.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beevpn.win.faNum
import com.beevpn.win.daysLeftHuman
import com.beevpn.win.license.LicenseManager
import com.beevpn.win.tunnel.TunnelController

/// Home — the honey orb, the transport picker and the connect switch.
/// The desktop twin of HomeView.swift / the Android orbit screen.
@Composable
fun HomeView() {
    val state = TunnelController.state
    val info = LicenseManager.subscriptionInfo()

    Box(Modifier.fillMaxSize().background(Honey.canvas)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 36.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(4.dp))
            Orb(state)
            Spacer(Modifier.height(8.dp))

            Text(
                TunnelController.statusText,
                style = T.h2.copy(
                    color = when (state) {
                        TunnelController.State.CONNECTED -> Honey.ok
                        TunnelController.State.FAILED -> Honey.danger
                        TunnelController.State.STARTING, TunnelController.State.PROVISIONING, TunnelController.State.STOPPING -> Honey.primary
                        else -> Honey.ink
                    },
                ),
            )
            TunnelController.lastError?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = T.muted.copy(color = Honey.danger, fontSize = 13.sp))
            }

            // Subscription banner — the days-left line from the buyer's own hive pass.
            if (info.hasToken) {
                Spacer(Modifier.height(10.dp))
                HoneyCard(Modifier.width(430.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("💎", fontSize = 18.sp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                "اشتراک فعال — " + faNum(
                                    ((info.expiryEpochSec - System.currentTimeMillis() / 1000) / 86_400).toInt() + 1
                                ) + " روز باقی مانده",
                                style = T.body.copy(fontSize = 13.5.sp),
                            )
                            Text("پلن: " + planLabel(info.plan), style = T.muted)
                        }
                        Spacer(Modifier.weight(1f))
                        Text(daysLeftHuman(info.expiryEpochSec, System.currentTimeMillis() / 1000), style = T.accent.copy(fontSize = 13.sp))
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            ModeRow()

            Spacer(Modifier.height(16.dp))
            val busy = state == TunnelController.State.STARTING || state == TunnelController.State.PROVISIONING ||
                state == TunnelController.State.STOPPING
            HoneyButton(
                title = when {
                    busy -> "…"
                    state == TunnelController.State.CONNECTED -> "قطع اتصال"
                    else -> "اتصال"
                },
                filled = true,
                modifier = Modifier.width(430.dp),
                enabled = !busy,
            ) {
                if (state == TunnelController.State.CONNECTED) TunnelController.disconnect() else TunnelController.connect()
            }

            if (TunnelController.mode == TunnelController.Mode.AUTO) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "خودکار: سریع‌ترین نود SHARD، و اگر زنده نبود WARP",
                    style = T.muted,
                )
            }

            Spacer(Modifier.height(12.dp))
            Text("ربات فروش و خرید اشتراک: @BEESELL_GP", style = T.muted)
        }
    }
}

@Composable
private fun ModeRow() {
    var selected by remember(TunnelController.mode) { mutableStateOf(TunnelController.mode) }
    val items = listOf(
        TunnelController.Mode.AUTO to "⚡ خودکار",
        TunnelController.Mode.SHARD to "🕸 SHARD",
        TunnelController.Mode.WARP to "☁ WARP",
        TunnelController.Mode.MANUAL to "✍ دستی",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (m, label) ->
            val on = selected == m
            Box(
                Modifier
                    .background(
                        if (on) Honey.primary.copy(alpha = 0.16f) else Honey.surface,
                        RoundedCornerShape(10.dp),
                    )
                    .border(1.dp, if (on) Honey.primary.copy(alpha = 0.5f) else Honey.divider, RoundedCornerShape(10.dp))
                    .clickable {
                        selected = m
                        TunnelController.mode = m
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    label,
                    style = T.body.copy(
                        color = if (on) Honey.primary else Honey.muted,
                        fontSize = 13.sp,
                        fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                    ),
                )
            }
        }
    }
}

internal fun planLabel(plan: String): String = when (plan) {
    "trial" -> "آزمایشی (۱ روز)"
    "m1" -> "۱ ماهه"
    "m3" -> "۳ ماهه"
    "m6" -> "۶ ماهه"
    "m12" -> "۱۲ ماهه"
    else -> "—"
}

internal fun kindLabel(kind: String): String = when (kind) {
    "code" -> "کد فعال‌سازی"
    "account" -> "حساب کاربری"
    else -> "—"
}
