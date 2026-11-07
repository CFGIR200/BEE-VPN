package com.beevpn.win.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beevpn.win.faDigits
import com.beevpn.win.config.BeeConfig

/// About — the honeycolophon: who built it, what runs inside, and the licenses
/// of the bundled engines. The Android app's About section, desktop cut.
@Composable
fun AboutView() {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 36.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            com.beevpn.win.HiveLogo(84.dp)
            Text(
                "BEE-VPN for Windows",
                style = T.h1.copy(
                    fontSize = 22.sp,
                    color = Honey.primary,
                    shadow = Shadow(Honey.glow.copy(alpha = 0.5f), blurRadius = 18f),
                ),
            )
            Text(
                "همان پروتکل‌ها، همان سرور لایسنس، همان عسل — این بار روی ویندوز",
                style = T.muted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.padding(8.dp))

            GlassCard(Modifier.width(640.dp)) {
                Column {
                    SectionHeader("درون این جعبه")
                    InfoRow("موتور پروتکل‌ها", "Xray-core (VLESS · VMESS · TROJAN · SS · HYSTERIA2 · REALITY · ECH · FinalMask)")
                    InfoRow("تونل WARP", "WireGuard — ثبت‌نام مستقیم یا از مسیر SHARD")
                    InfoRow("AnyTLS", "سایدکار sing-box روی ۱۲۷.۰.۰.۱:۱۸۲۶")
                    InfoRow("تونل شبکه", "TUN بومی ویندوز (Wintun) + مسیردهی کامل")
                    InfoRow("گیت اشتراک", "Ed25519 — همان /activate /login /verify سرور لایسنس")
                }
            }
            Spacer(Modifier.padding(8.dp))

            GlassCard(Modifier.width(640.dp)) {
                Column {
                    SectionHeader("پیوندها")
                    Row(Modifier.fillMaxWidth()) {
                        LinkText("🤖 ربات فروش: @BEESELL_GP", BeeConfig.TELEGRAM_URL)
                        Spacer(Modifier.width(18.dp))
                        LinkText("🆘 پشتیبانی", BeeConfig.SUPPORT_URL)
                        Spacer(Modifier.width(18.dp))
                        LinkText("🐙 سورس‌کد", BeeConfig.GITHUB_URL)
                    }
                }
            }
            Spacer(Modifier.padding(8.dp))

            GlassCard(Modifier.width(640.dp)) {
                Column {
                    SectionHeader("نسخه و مجوزها")
                    InfoRow("نسخهٔ برنامه", faDigits(BeeConfig.VERSION))
                    InfoRow("طراحی", "Obsidian Hive — شیشهٔ سیاه و طلای مذاب")
                    InfoRow("فونت", "Vazirmatn v33.003 — SIL OFL 1.1")
                    InfoRow("Xray-core", "GPLv3 — github.com/XTLS/Xray-core")
                    InfoRow("sing-box", "MIT — github.com/SagerNet/sing-box")
                    InfoRow("Wintun", "MIT — wintun.net (WireGuard project)")
                }
            }
            Spacer(Modifier.padding(10.dp))
            Text(
                "حریم خصوصی: اپ فقط کد اشتراک و شناسهٔ دستگاه را به سرور لایسنس می‌فرستد؛ ترافیک تو مستقیم داخل تونل می‌رود.",
                style = T.muted.copy(fontSize = 11.5.sp),
                textAlign = TextAlign.Center,
            )
        }
    }
}
