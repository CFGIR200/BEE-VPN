package com.beevpn.win.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beevpn.win.faNum
import com.beevpn.win.daysLeftHuman
import com.beevpn.win.license.LicenseManager
import com.beevpn.win.tunnel.TunnelController

/// Home — the honey orb on its obsidian stage, the glowing status line, the
/// transport segmented control and the hive pass banner. The desktop twin of
/// HomeView.swift / the Android orbit screen.
@Composable
fun HomeView(onOpenProfile: () -> Unit = {}) {
    val state = TunnelController.state
    val info = LicenseManager.subscriptionInfo()

    val statusColor = when (state) {
        TunnelController.State.CONNECTED -> Honey.ok
        TunnelController.State.FAILED -> Honey.danger
        TunnelController.State.STARTING, TunnelController.State.PROVISIONING,
        TunnelController.State.VERIFYING, TunnelController.State.STOPPING,
        -> Honey.primary
        else -> Honey.ink
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 36.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Orb(state, onToggle = {
                if (state == TunnelController.State.CONNECTED) TunnelController.disconnect() else TunnelController.connect()
            })
            Spacer(Modifier.height(2.dp))

            Text(
                TunnelController.statusText,
                style = T.h0.copy(
                    color = statusColor,
                    shadow = Shadow(statusColor.copy(alpha = 0.65f), blurRadius = 22f),
                ),
            )
            TunnelController.lastError?.let { err ->
                Spacer(Modifier.height(8.dp))
                GlassCard(Modifier.width(470.dp)) {
                    Column {
                        Text(err, style = T.body.copy(color = Honey.danger, fontSize = 13.sp))
                        if (err.contains("مدیر")) {
                            Spacer(Modifier.height(8.dp))
                            HoneyButton(title = "اجرای دوباره با دسترسی مدیر", filled = false) {
                                com.beevpn.win.engine.WindowsAdmin.relaunchElevated()
                            }
                        }
                    }
                }
            }

            // Subscription banner — the days-left line from the buyer's own hive pass.
            // Tap it to open the profile page (plans, expiry, خروج از حساب).
            if (info.hasToken) {
                Spacer(Modifier.height(12.dp))
                GlassCard(Modifier.width(470.dp).clickable { onOpenProfile() }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Honey.primary.copy(alpha = 0.22f), Honey.goldBot.copy(alpha = 0.12f)),
                                    ),
                                )
                                .border(1.dp, Honey.primary.copy(alpha = 0.35f), RoundedCornerShape(11.dp)),
                            contentAlignment = Alignment.Center,
                        ) { Text("💎", fontSize = 16.sp) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "اشتراک فعال — " + faNum(
                                    ((info.expiryEpochSec - System.currentTimeMillis() / 1000) / 86_400).toInt() + 1
                                ) + " روز باقی مانده",
                                style = T.body.copy(fontSize = 13.5.sp),
                            )
                            Text("پلن: " + planLabel(info.plan), style = T.muted)
                        }
                        GlassChip(daysLeftHuman(info.expiryEpochSec, System.currentTimeMillis() / 1000))
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            ModeRow()

            Spacer(Modifier.height(14.dp))
            val busy = state == TunnelController.State.STARTING || state == TunnelController.State.PROVISIONING ||
                state == TunnelController.State.VERIFYING || state == TunnelController.State.STOPPING
            HoneyButton(
                title = when (state) {
                    TunnelController.State.CONNECTED, TunnelController.State.STOPPING -> "قطع اتصال"
                    else -> "اتصال"
                },
                filled = true,
                modifier = Modifier.width(470.dp),
                busy = busy,
                enabled = !busy,
            ) {
                if (state == TunnelController.State.CONNECTED) TunnelController.disconnect() else TunnelController.connect()
            }

            if (TunnelController.mode == TunnelController.Mode.AUTO) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "خودکار: مثل اندروید — مسابقهٔ واقعی نودهای SHARD، و اگر زنده نبودند WARP (موتور اندروید)",
                    style = T.muted,
                )
            }

            Spacer(Modifier.height(10.dp))
            Text("ربات فروش و خرید اشتراک: @BEESELL_GP", style = T.muted)
            Spacer(Modifier.height(14.dp))
        }
    }
}

/// The transport selector — the phone's protocol list, desktop-shaped: seven
/// modes in a two-row glass grid (4 + 3), the chosen one carried by the same
/// molten gold pill. AUTO/SHARD/WARP/MASQUE on the first row, GOOL/MiM/manual
/// beneath — the exact protocol family the Android app exposes.
@Composable
private fun ModeRow() {
    var selected by remember(TunnelController.mode) { mutableStateOf(TunnelController.mode) }
    val row1 = listOf(
        TunnelController.Mode.AUTO to "⚡ خودکار",
        TunnelController.Mode.SHARD to "◈ SHARD",
        TunnelController.Mode.WARP to "☁ WARP",
        TunnelController.Mode.MASQUE to "🎭 ماسک",
    )
    val row2 = listOf(
        TunnelController.Mode.GOOL to "🔗 گول",
        TunnelController.Mode.MIM to "🪆 ماسک²",
        TunnelController.Mode.MANUAL to "✍ دستی",
    )
    val segW = 96.dp

    Box(
        Modifier
            .clip(RoundedCornerShape(15.dp))
            .background(Honey.track.copy(alpha = 0.9f))
            .border(1.dp, Honey.divider, RoundedCornerShape(15.dp))
            .padding(4.dp),
    ) {
        Column {
            listOf(row1, row2).forEach { items ->
                Box {
                    val on = items.any { it.first == selected }
                    if (on) {
                        Box(
                            Modifier
                                .offset(x = segW * items.indexOfFirst { it.first == selected })
                                .width(segW)
                                .height(36.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(
                                    Brush.verticalGradient(listOf(Honey.goldTop, Honey.goldBot)),
                                )
                                .border(
                                    1.dp,
                                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), Color.Transparent)),
                                    RoundedCornerShape(11.dp),
                                ),
                        )
                    }
                    Row {
                        items.forEach { (m, label) ->
                            val isOn = selected == m
                            Box(
                                Modifier
                                    .width(segW)
                                    .height(36.dp)
                                    .clickable {
                                        selected = m
                                        TunnelController.mode = m
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    label,
                                    style = T.body.copy(
                                        color = if (isOn) Honey.onGold else Honey.muted,
                                        fontSize = 12.5.sp,
                                        fontWeight = if (isOn) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                                    ),
                                )
                            }
                        }
                    }
                }
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
