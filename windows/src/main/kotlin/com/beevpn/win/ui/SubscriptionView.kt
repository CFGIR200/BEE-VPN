package com.beevpn.win.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.beevpn.win.gregorianString
import com.beevpn.win.jalaliString
import com.beevpn.win.license.LicenseManager

/// The buyer's own hive pass — the exact mirror of Android's
/// SubscriptionInfoActivity and the iOS SubscriptionView: status, days left
/// (Persian digits), plan, Jalali + Gregorian expiry, term progress, device
/// binding, last verification. Deliberately OFFLINE: it reads the
/// Ed25519-verified token already on the device, so the profile can never
/// disagree with the gate.
@Composable
fun SubscriptionView() {
    val info = LicenseManager.subscriptionInfo()
    val now = System.currentTimeMillis() / 1000

    Box(Modifier.fillMaxSize().background(Honey.canvas)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 36.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("🐝", fontSize = 36.sp)
            Text("BEE-VPN", style = T.h1.copy(fontSize = 24.sp, color = Honey.primary))
            Text("اشتراک", style = T.muted)

            Spacer(Modifier.height(16.dp))

            if (!info.configured) {
                HoneyCard(Modifier.width(520.dp)) {
                    Text(
                        "این نسخه بدون سرور لایسنس ساخته شده است",
                        style = T.body.copy(color = Honey.muted),
                    )
                }
            } else {
                // hero
                HoneyCard(Modifier.width(520.dp)) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (info.hasToken) {
                            val daysLeft = ((info.expiryEpochSec - now) / 86_400).toInt() + 1
                            Text("✅ اشتراک فعال", style = T.h2.copy(color = Honey.ok))
                            Text(faNum(daysLeft) + " روز باقی مانده", style = T.body)
                            Spacer(Modifier.height(10.dp))
                            TermProgress(info.activatedAtEpochSec, info.expiryEpochSec, now)
                        } else {
                            Text(
                                "⚠️ اشتراک فعالی روی این دستگاه نیست",
                                style = T.h2.copy(color = Honey.danger, fontSize = 16.sp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                // rows
                HoneyCard(Modifier.width(520.dp)) {
                    Column {
                        InfoRow("وضعیت", if (info.hasToken) "فعال" else "—", if (info.hasToken) Honey.ok else Honey.muted)
                        InfoRow("پلن", planLabel(info.plan))
                        InfoRow(
                            "تاریخ انقضا",
                            if (info.expiryEpochSec > 0)
                                jalaliString(info.expiryEpochSec) + "  (" + gregorianString(info.expiryEpochSec) + ")"
                            else "هنوز شروع نشده — ساعت از اولین فعال‌سازی می‌چرخد",
                        )
                        InfoRow(
                            "شروع دوره",
                            if (info.activatedAtEpochSec > 0) jalaliString(info.activatedAtEpochSec)
                            else "هنوز شروع نشده — ساعت از اولین فعال‌سازی می‌چرخد",
                        )
                        InfoRow("نوع", kindLabel(info.kind))
                        InfoRow("شناسه", info.subject.ifEmpty { "—" })
                        InfoRow("دستگاه", faDigits(info.installId.take(12)) + "…")
                        InfoRow(
                            "آخرین بررسی",
                            if (info.lastCheckEpochSec > 0) jalaliString(info.lastCheckEpochSec) else "بررسی نشده",
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))

                Column(Modifier.width(520.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                    HoneyButton(title = "🛍 خرید اشتراک", filled = true) { openUrl(com.beevpn.win.config.BeeConfig.TELEGRAM_URL) }
                    HoneyButton(title = "🆘 پشتیبانی", filled = false) { openUrl(com.beevpn.win.config.BeeConfig.SUPPORT_URL) }
                    HoneyButton(
                        title = "🚪 خروج از حساب",
                        filled = false,
                        danger = true,
                    ) {
                        LicenseManager.signOut()
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "هر اشتراک فقط به یک دستگاه وصل است. دستگاه را عوض کردی؟ برای انتقال از پشتیبانی بخواه.",
                    style = T.muted.copy(fontSize = 11.5.sp),
                )
            }
        }
    }
}

@Composable
private fun TermProgress(activatedAt: Long, expiry: Long, now: Long) {
    if (activatedAt <= 0 || expiry <= activatedAt) return
    val total = (expiry - activatedAt).toFloat()
    val elapsed = (now - activatedAt).toFloat().coerceIn(0f, total)
    val fraction = (elapsed / total).coerceAtLeast(0.02f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(Honey.track, RoundedCornerShape(4.dp)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(8.dp)
                .background(Honey.primary, RoundedCornerShape(4.dp)),
        )
    }
}

internal fun openUrl(url: String) {
    try { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) } catch (_: Exception) {}
}
