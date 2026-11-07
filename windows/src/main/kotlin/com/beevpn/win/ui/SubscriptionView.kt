package com.beevpn.win.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.beevpn.win.daysLeftHuman
import com.beevpn.win.faDigits
import com.beevpn.win.faNum
import com.beevpn.win.gregorianString
import com.beevpn.win.jalaliString
import com.beevpn.win.license.LicenseManager
import kotlinx.coroutines.delay

/// The buyer's own hive pass — the profile page. The visual twin of
/// SubscriptionInfoActivity / the iOS profile, redesigned as a dashboard:
/// a glowing days-left RING (the same honey-orb language as the home
/// screen), expiry early-warning, copyable identity chips and a tile grid.
///
/// Deliberately OFFLINE: it reads the Ed25519-verified token already on the
/// device, so the profile can never disagree with the gate.
@Composable
fun SubscriptionView() {
    val info = LicenseManager.subscriptionInfo()
    val now = System.currentTimeMillis() / 1000
    // خروج یک کار پرخطر است — اول تأیید بگیر؛ رمز و کد از این دستگاه پاک
    // می‌شوند و برای ورود دوباره باید دوباره فعال‌سازی کند.
    var confirmOut by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 36.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!info.configured) {
                Spacer(Modifier.height(20.dp))
                GlassCard(Modifier.width(520.dp)) {
                    Text(
                        "این نسخه بدون سرور لایسنس ساخته شده است",
                        style = T.body.copy(color = Honey.muted),
                    )
                }
            } else {
                // ── identity header ─────────────────────────────────────
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(Honey.primary.copy(alpha = 0.25f), Honey.goldBot.copy(alpha = 0.12f)),
                                ),
                            )
                            .border(1.dp, Honey.primary.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Text("🐝", fontSize = 18.sp) }
                    Column {
                        Text("پروفایل کندو", style = T.h2)
                        Text(kindLabel(info.kind), style = T.muted)
                    }
                }
                Spacer(Modifier.height(10.dp))

                // the entitlement itself, copyable — the username (or code)
                // the buyer needs when talking to support
                IdentityChip(
                    value = info.subject.ifEmpty { "—" },
                    label = if (info.kind == "account") "نام کاربری" else "کد فعال‌سازی",
                )
                Spacer(Modifier.height(14.dp))

                if (!info.hasToken) {
                    GlassCard(Modifier.width(520.dp)) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "⚠️ اشتراکی روی این دستگاه فعال نیست",
                                style = T.h2.copy(
                                    color = Honey.danger,
                                    shadow = Shadow(Honey.danger.copy(alpha = 0.5f), blurRadius = 14f),
                                ),
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "از ربات تلگرام کد بگیر یا با حساب کاربری‌ات وارد شو.",
                                style = T.muted,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        HoneyButton(title = "🛍 خرید اشتراک", filled = true) { openUrl(com.beevpn.win.config.BeeConfig.TELEGRAM_URL) }
                        HoneyButton(title = "🆘 پشتیبانی", filled = false) { openUrl(com.beevpn.win.config.BeeConfig.SUPPORT_URL) }
                    }
                    return@Column
                }

                // ── hero: the days-left ring ────────────────────────────
                val expired = info.expiryEpochSec > 0 && info.expiryEpochSec <= now
                val pending = info.expiryEpochSec <= 0
                GlassCard(Modifier.width(520.dp)) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        when {
                            pending -> {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "⏳ اشتراک آمادهٔ فعال‌سازی است",
                                    style = T.h2.copy(
                                        color = Honey.primary,
                                        shadow = Shadow(Honey.glow.copy(alpha = 0.5f), blurRadius = 16f),
                                    ),
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "ساعت اشتراک از اولین اتصال در همین دستگاه شروع می‌شود.",
                                    style = T.muted,
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            else -> {
                                val daysLeft = ((info.expiryEpochSec - now) / 86_400).toInt() + 1
                                val total = when {
                                    info.activatedAtEpochSec > 0 && info.expiryEpochSec > info.activatedAtEpochSec ->
                                        ((info.expiryEpochSec - info.activatedAtEpochSec) / 86_400).toInt() + 1
                                    else -> daysLeft
                                }
                                DaysRing(
                                    daysLeft = daysLeft,
                                    totalDays = total,
                                    expired = expired,
                                )
                                Text(
                                    if (expired) "🔴 اشتراک منقضی شده" else "✅ اشتراک فعال",
                                    style = T.h2.copy(
                                        color = if (expired) Honey.danger else Honey.ok,
                                        shadow = Shadow(
                                            (if (expired) Honey.danger else Honey.ok).copy(alpha = 0.55f),
                                            blurRadius = 16f,
                                        ),
                                    ),
                                )
                                if (!expired) {
                                    Spacer(Modifier.height(4.dp))
                                    GlassChip(daysLeftHuman(info.expiryEpochSec, now))
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                        TermProgress(info.activatedAtEpochSec, info.expiryEpochSec, now)
                    }
                }
                Spacer(Modifier.height(12.dp))

                // ── expiry early-warning ────────────────────────────────
                if (!pending && !expired) {
                    val daysLeft = ((info.expiryEpochSec - now) / 86_400).toInt() + 1
                    if (daysLeft <= 3) {
                        Row(
                            Modifier
                                .width(520.dp)
                                .clip(FieldShape)
                                .background(Honey.primary.copy(alpha = 0.10f))
                                .border(1.dp, Honey.primary.copy(alpha = 0.4f), FieldShape)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("🍯", fontSize = 16.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "فقط " + faNum(daysLeft) + " روز مانده — همین حالا تمدید کن تا قطع نشوی.",
                                style = T.body.copy(color = Honey.primary, fontSize = 13.sp),
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "تمدید ↗",
                                style = T.body.copy(color = Honey.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                                modifier = Modifier.clickable { openUrl(com.beevpn.win.config.BeeConfig.TELEGRAM_URL) },
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }

                // ── details grid ────────────────────────────────────────
                GlassCard(Modifier.width(520.dp)) {
                    Column {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            InfoTile("پلن", planLabel(info.plan), modifier = Modifier.weight(1f))
                            InfoTile("نوع", kindLabel(info.kind), modifier = Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            InfoTile(
                                "شروع دوره",
                                if (info.activatedAtEpochSec > 0) jalaliString(info.activatedAtEpochSec)
                                else "با اولین اتصال",
                                modifier = Modifier.weight(1f),
                            )
                            InfoTile(
                                "انقضا",
                                if (info.expiryEpochSec > 0)
                                    jalaliString(info.expiryEpochSec) + " — " + gregorianString(info.expiryEpochSec)
                                else "با اولین اتصال",
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            InfoTile(
                                "آخرین بررسی",
                                if (info.lastCheckEpochSec > 0) jalaliString(info.lastCheckEpochSec) else "بررسی نشده",
                                modifier = Modifier.weight(1f),
                            )
                            InfoTile(
                                "شناسهٔ دستگاه",
                                faDigits(info.installId.take(12)) + "…",
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                // ── device chip — one tap to copy, for support transfers ──
                CopyChip(
                    text = info.installId,
                    label = "کپی شناسهٔ دستگاه",
                    doneLabel = "کپی شد ✓",
                )
                Spacer(Modifier.height(12.dp))

                // ── actions ─────────────────────────────────────────────
                Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HoneyButton(
                        title = if (expired) "🛍 خرید اشتراک جدید" else "🛍 خرید / تمدید",
                        filled = true,
                    ) { openUrl(com.beevpn.win.config.BeeConfig.TELEGRAM_URL) }
                    HoneyButton(title = "🆘 پشتیبانی", filled = false) { openUrl(com.beevpn.win.config.BeeConfig.SUPPORT_URL) }
                    HoneyButton(
                        title = "🚪 خروج از حساب",
                        filled = false,
                        danger = true,
                    ) { confirmOut = true }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "هر اشتراک فقط به یک دستگاه وصل است. دستگاه را عوض کردی؟ برای انتقال از پشتیبانی بخواه.",
                    style = T.muted.copy(fontSize = 11.5.sp),
                )
            }
        }
    }

    if (confirmOut) {
        Dialog(onDismissRequest = { confirmOut = false }) {
            GlassCard(Modifier.width(430.dp)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "🚪 خروج از حساب؟",
                        style = T.h2.copy(color = Honey.danger),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "لایسنس از این دستگاه پاک می‌شود و اپ همان لحظه قفل و اتصال قطع می‌شود.\n" +
                            "پروفایل تو در ربات می‌ماند — برای ورود دوباره، کد فعال‌سازی تازه از 🛍 خرید بگیر.",
                        style = T.muted,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HoneyButton(title = "انصراف", filled = false) { confirmOut = false }
                        HoneyButton(title = "خروج", filled = true, danger = true) {
                            confirmOut = false
                            LicenseManager.signOut()
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- ring

/// The glowing days-left ring — the profile's hero. Track = obsidian,
/// progress = the molten gold→green sweep; expired turns the whole ring red.
@Composable
private fun DaysRing(daysLeft: Int, totalDays: Int, expired: Boolean) {
    val fraction = if (totalDays <= 0) 0f else (daysLeft.toFloat() / totalDays.toFloat()).coerceIn(0f, 1f)
    val animated by animateFloatAsState(fraction.coerceAtLeast(0.03f), tween(1000), label = "daysRing")
    val stroke = 11.dp
    val ringColor = if (expired) Honey.danger else Honey.ok
    Box(Modifier.size(170.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(170.dp)) {
            val gap = stroke.toPx() + 3.dp.toPx()
            val arc = Size(size.width - gap * 2, size.height - gap * 2)
            val topLeft = Offset(gap, gap)
            val style = Stroke(width = stroke.toPx(), cap = StrokeCap.Round)
            // track
            drawArc(
                color = Honey.track,
                startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = topLeft, size = arc, style = style,
            )
            // progress sweep — starts at 12 o'clock
            drawArc(
                brush = Brush.sweepGradient(
                    0f to Honey.goldBot.copy(alpha = 0.55f),
                    0.55f to Honey.primary,
                    1f to ringColor.copy(alpha = 0.95f),
                ),
                startAngle = -90f, sweepAngle = 360f * animated, useCenter = false,
                topLeft = topLeft, size = arc, style = style,
            )
            // inner glow halo
            drawArc(
                color = ringColor.copy(alpha = 0.10f),
                startAngle = -90f, sweepAngle = 360f * animated, useCenter = false,
                topLeft = topLeft, size = arc,
                style = Stroke(width = (stroke.toPx() * 2.1f), cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                faNum(daysLeft),
                style = T.h0.copy(
                    fontSize = 46.sp,
                    color = if (expired) Honey.danger else Honey.ink,
                    shadow = Shadow((if (expired) Honey.danger else Honey.glow).copy(alpha = 0.55f), blurRadius = 22f),
                ),
            )
            Text(if (expired) "روز گذشته" else "روز باقی مانده", style = T.muted)
        }
    }
}

// ------------------------------------------------------------- tiles

/// One detail tile of the 2×N grid — obsidian pill, muted caption, ink value.
@Composable
private fun InfoTile(title: String, value: String, valueColor: Color = Honey.ink, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(FieldShape)
            .background(Honey.track.copy(alpha = 0.55f))
            .border(1.dp, Honey.divider, FieldShape)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Column {
            Text(title, style = T.muted.copy(fontSize = 10.5.sp))
            Spacer(Modifier.height(3.dp))
            Text(value, style = T.body.copy(color = valueColor, fontSize = 13.sp), maxLines = 2)
        }
    }
}

/// A copyable identity line — the subject (username/code) with a copy afford.
@Composable
private fun IdentityChip(value: String, label: String) {
    CopyChip(
        text = value,
        label = label,
        doneLabel = "کپی شد ✓",
        centered = true,
        wide = true,
    )
}

/// The reusable copy chip: shows a value + hint, copies on tap, confirms
/// with a «کپی شد ✓» that fades itself after two seconds.
@Composable
private fun CopyChip(
    text: String,
    label: String,
    doneLabel: String,
    centered: Boolean = false,
    wide: Boolean = false,
) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }
    val widthMod = if (wide) Modifier.width(520.dp) else Modifier
    Row(
        widthMod
            .clip(ChipShape)
            .background(Honey.track.copy(alpha = 0.8f))
            .border(
                1.dp,
                if (copied) Honey.ok.copy(alpha = 0.55f) else Honey.divider,
                ChipShape,
            )
            .clickable {
                try {
                    val sel = java.awt.datatransfer.StringSelection(text)
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, null)
                    copied = true
                } catch (_: Exception) {}
            }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
    ) {
        Text(
            if (copied) doneLabel else "📋",
            style = T.body.copy(
                color = if (copied) Honey.ok else Honey.muted,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (copied) label else text,
            style = T.body.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
        )
        if (!copied) {
            Spacer(Modifier.width(8.dp))
            Text(label, style = T.muted.copy(fontSize = 11.sp))
        }
    }
}

@Composable
private fun TermProgress(activatedAt: Long, expiry: Long, now: Long) {
    if (activatedAt <= 0 || expiry <= activatedAt) return
    val total = (expiry - activatedAt).toFloat()
    val elapsed = (now - activatedAt).toFloat().coerceIn(0f, total)
    val target = (elapsed / total).coerceAtLeast(0.02f)
    val fraction by animateFloatAsState(target, tween(900), label = "term")
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(9.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Honey.track),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(9.dp)
                    .background(
                        Brush.horizontalGradient(listOf(Honey.goldBot, Honey.goldTop, Honey.ok)),
                        RoundedCornerShape(5.dp),
                    ),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("شروع دوره", style = T.muted.copy(fontSize = 10.5.sp))
            Spacer(Modifier.weight(1f))
            Text("پایان اشتراک", style = T.muted.copy(fontSize = 10.5.sp))
        }
    }
}

internal fun openUrl(url: String) {
    try { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) } catch (_: Exception) {}
}
