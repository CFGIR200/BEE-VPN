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
import androidx.compose.foundation.text.selection.SelectionContainer
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
import com.beevpn.win.engine.EngineConfig
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
                when (state) {
                    TunnelController.State.CONNECTED -> TunnelController.disconnect()
                    TunnelController.State.STARTING, TunnelController.State.PROVISIONING,
                    TunnelController.State.VERIFYING,
                    -> TunnelController.cancelConnect()
                    // STOPPING is the dead zone: a teardown (or a cancel) is
                    // already running — taps wait for DISCONNECTED.
                    TunnelController.State.STOPPING -> {}
                    else -> TunnelController.connect()
                }
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

            Spacer(Modifier.height(10.dp))
            TunRow()

            Spacer(Modifier.height(14.dp))
            val connecting = state == TunnelController.State.STARTING || state == TunnelController.State.PROVISIONING ||
                state == TunnelController.State.VERIFYING
            val busy = connecting || state == TunnelController.State.STOPPING
            HoneyButton(
                title = when {
                    state == TunnelController.State.CONNECTED || state == TunnelController.State.STOPPING -> "قطع اتصال"
                    connecting -> "لغو اتصال"
                    else -> "اتصال"
                },
                filled = true,
                modifier = Modifier.width(470.dp),
                busy = busy,
                // A connecting dial answers «لغو» — the busy look stays, the
                // tap is never swallowed (STOPPING is the only dead zone: the
                // teardown is already running).
                enabled = !busy || connecting,
            ) {
                when {
                    state == TunnelController.State.CONNECTED -> TunnelController.disconnect()
                    connecting -> TunnelController.cancelConnect()
                    else -> TunnelController.connect()
                }
            }

            when (TunnelController.mode) {
                TunnelController.Mode.AUTO -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "خودکار: مثل اندروید — مسابقهٔ واقعی بین مسیرها؛ اگر هیچ‌کدام زنده نبودند، موتور ابری (موتور اندروید)",
                        style = T.muted,
                    )
                }
                TunnelController.Mode.TOR -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "تور: تونل ابری روی مدار تور (با lyrebird) — راه‌اندازی آهسته است، تا یک دقیقه صبر کن؛ گزینهٔ نجات روی سخت‌ترین شبکه‌ها",
                        style = T.muted,
                    )
                }
                else -> {}
            }

            Spacer(Modifier.height(10.dp))
            Text("ربات فروش و خرید اشتراک: @BEESELL_GP", style = T.muted)
            Spacer(Modifier.height(14.dp))
        }
    }
}

/// The transport selector — the phone's protocol list, desktop-shaped: six
/// modes in a two-row glass grid (3 + 3), the chosen one carried by the same
/// molten gold pill. AUTO/MASQUE/GOOL on the first row, MiM/Tor/manual
/// beneath. SHARD and WARP are deliberately NOT offered here (operator's
/// call): their names never appear on screen, while both engines still serve
/// unseen inside AUTO's ladder — a stored SHARD/WARP choice migrates to AUTO.
@Composable
private fun ModeRow() {
    var selected by remember(TunnelController.mode) { mutableStateOf(TunnelController.mode) }
    val row1 = listOf(
        TunnelController.Mode.AUTO to "⚡ خودکار",
        TunnelController.Mode.MASQUE to "🎭 ماسک",
        TunnelController.Mode.GOOL to "🔗 گول",
    )
    val row2 = listOf(
        TunnelController.Mode.MIM to "🪆 ماسک²",
        TunnelController.Mode.TOR to "🧅 تور",
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

/// The TUN switch — the user's ask, one row. ON = the full tunnel (Wintun
/// adapter, default route, admin required). OFF = proxy mode: the engine
/// serves SOCKS 1819 + HTTP 1818 locally and the system proxy (WinINET)
/// points at 1818, so browsers and proxy-aware apps ride the tunnel with NO
/// admin rights. A change takes effect on the next connect. Under the switch
/// sits the local-address line the user asked for — «127.0.0.1:1818 + SOCKS
/// 1819» — so anyone pointing a browser or app at the tunnel knows exactly
/// where to dial: in TUN mode 1818 is not listening (includeHttpIn = !tun),
/// so the line shrinks to the SOCKS port alone.
@Composable
private fun TunRow() {
    var on by remember { mutableStateOf(TunnelController.tunEnabled) }
    GlassCard(Modifier.width(470.dp)) {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("تونل کامل (TUN)", style = T.body.copy(fontSize = 13.5.sp))
                    Text(
                        if (on) "روشن — همهٔ ترافیک سیستم از تونل می‌گذرد (نیاز به دسترسی مدیر)"
                        else "خاموش — حالت پروکسی: پروکسی سیستم روی 127.0.0.1:${faNum(EngineConfig.HTTP_PROXY_PORT)} — بدون نیاز به دسترسی مدیر",
                        style = T.muted.copy(fontSize = 11.5.sp),
                    )
                }
                SwitchHoney(checked = on) { next ->
                    on = next
                    TunnelController.tunEnabled = next
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (on) "پروکسی محلی در حالت TUN (برای مرورگر و برنامه‌های دیگر):"
                else "پروکسی محلی — این آدرس را در مرورگر یا برنامه‌های دیگر تنظیم کن:",
                style = T.muted.copy(fontSize = 11.sp),
            )
            Spacer(Modifier.height(3.dp))
            // Selectable, so the buyer can copy the address straight into
            // their browser's proxy settings. Latin digits — an address the
            // user has to type or paste must never wear Persian numerals.
            SelectionContainer {
                Text(
                    if (on) "127.0.0.1:${EngineConfig.SOCKS_PORT} (SOCKS)"
                    else "127.0.0.1:${EngineConfig.HTTP_PROXY_PORT} (HTTP) + SOCKS ${EngineConfig.SOCKS_PORT}",
                    style = T.body.copy(fontSize = 11.5.sp),
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
