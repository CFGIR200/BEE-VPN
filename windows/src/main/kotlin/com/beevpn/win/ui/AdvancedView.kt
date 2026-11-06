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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
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
import com.beevpn.win.AppPaths
import com.beevpn.win.SecureStore
import com.beevpn.win.engine.WindowsAdmin
import com.beevpn.win.shard.ShardImport
import com.beevpn.win.tunnel.TunnelController
import com.beevpn.win.warp.WarpProvisioner

/// The workbench: elevation, Smart Split (fragment) toggle, manual wg-quick,
/// link import, identity regenerate, and the engine log tail. The desktop
/// twin of the iOS Advanced tab plus the pieces desktop buyers actually need.
@Composable
fun AdvancedView() {
    var fragment by remember { mutableStateOf(TunnelController.fragmentOnDirect) }
    var manual by remember { mutableStateOf(TunnelController.manualWgQuick ?: "") }
    var savedMsg by remember { mutableStateOf<String?>(null) }
    var logText by remember { mutableStateOf("") }
    var importText by remember { mutableStateOf("") }
    var importMsg by remember { mutableStateOf<String?>(null) }
    val admin = remember { WindowsAdmin.isAdmin() }

    Column(Modifier.fillMaxSize().background(Honey.canvas).verticalScroll(rememberScrollState()).padding(horizontal = 36.dp, vertical = 22.dp)) {
        Text("پیشرفته", style = T.h2)
        Spacer(Modifier.height(12.dp))

        if (!admin) {
            HoneyCard(Modifier.width(680.dp)) {
                Column {
                    Text("⛔ برنامه با دسترسی مدیر اجرا نشده است", style = T.body.copy(color = Honey.danger))
                    Text("ساخت تونل VPN و مسیردهی کامل ترافیک به دسترسی مدیر نیاز دارد؛ در غیر این صورت «اتصال» ناموفق می‌شود.", style = T.muted)
                    Spacer(Modifier.height(10.dp))
                    HoneyButton(
                        title = "🛡 اجرای دوباره با دسترسی مدیر",
                        filled = true,
                        modifier = Modifier.width(320.dp),
                    ) {
                        val ok = WindowsAdmin.relaunchElevated()
                        savedMsg = if (ok) "درخواست ارتقا فرستاده شد — پنجرهٔ UAC را تأیید کن" else "فقط برای نسخهٔ نصب‌شده (exe) کار می‌کند؛ فایل BEE-VPN.bat را اجرا کن"
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // Smart Split / fragment
        HoneyCard(Modifier.width(680.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Smart Split (فرگمنت TLS)", style = T.body)
                    Text(
                        "قطعه‌قطعه کردن ClientHello برای عبور از DPI — همان سازوکار اندروید، روی مسیر مستقیم",
                        style = T.muted,
                    )
                }
                SwitchHoney(fragment) {
                    fragment = it
                    TunnelController.fragmentOnDirect = it
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // Manual wg-quick
        HoneyCard(Modifier.width(680.dp)) {
            Column {
                Text("کانفیگ دستی wg-quick (WARP دستی)", style = T.body)
                Text("مثل تب پیشرفتهٔ iOS: کلید خصوصی، Address، PublicKey و Endpoint لازم است", style = T.muted)
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .background(Honey.track, RoundedCornerShape(10.dp))
                        .border(1.dp, Honey.divider, RoundedCornerShape(10.dp))
                        .padding(8.dp),
                ) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = manual,
                        onValueChange = { manual = it },
                        textStyle = T.mono.copy(color = Honey.ink, fontSize = 11.5.sp),
                        modifier = Modifier.fillMaxSize().horizontalScroll(rememberScrollState()),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    HoneyButton(title = "ذخیرهٔ کانفیگ", filled = true, modifier = Modifier.width(200.dp)) {
                        TunnelController.manualWgQuick = manual.ifBlank { null }
                        savedMsg = "ذخیره شد — حالت «دستی» را از خانه انتخاب کن"
                    }
                    HoneyButton(title = "🎲 هویت جدید WARP", filled = false, modifier = Modifier.width(200.dp)) {
                        TunnelController.regenerateIdentity()
                        savedMsg = "هویت قبلی پاک شد؛ دفعهٔ بعدِ اتصال ثبت‌نام تازه می‌شود"
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // Link import
        HoneyCard(Modifier.width(680.dp)) {
            Column {
                Text("ورود دستی کانفیگ‌ها", style = T.body)
                Text("هر خط یک لینک: vless:// trojan:// ss:// hysteria2:// anytls:// vmess://", style = T.muted)
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(90.dp)
                        .background(Honey.track, RoundedCornerShape(10.dp))
                        .border(1.dp, Honey.divider, RoundedCornerShape(10.dp))
                        .padding(8.dp),
                ) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        textStyle = T.mono.copy(color = Honey.ink, fontSize = 11.5.sp),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                HoneyButton(title = "افزودن به استخر", filled = false, modifier = Modifier.width(200.dp)) {
                    val parsed = ShardImport.parseBatch(importText)
                    if (parsed.isEmpty()) {
                        importMsg = "لینک قابل خواندنی پیدا نشد"
                    } else {
                        // Extra links survive restarts in shard-extra.txt, merged
                        // over the publisher's body — refreshes never lose them.
                        persistExtraLinks(importText)
                        val n = TunnelController.mergeImported(parsed)
                        importMsg = "‎$n نود اضافه شد"
                        importText = ""
                    }
                }
                importMsg?.let { Text(it, style = T.muted.copy(color = Honey.primary)) }
            }
        }
        Spacer(Modifier.height(12.dp))

        // Engine info
        HoneyCard(Modifier.width(680.dp)) {
            Column {
                InfoRow("پوشهٔ موتورها", AppPaths.coresDir()?.path ?: "پیدا نشد (xray.exe / sing-box.exe)", AppPaths.coresDir()?.let { Honey.ok } ?: Honey.danger)
                InfoRow("SOCKS محلی", "127.0.0.1:${com.beevpn.win.engine.EngineConfig.SOCKS_PORT} (همان پورت اندروید)")
                InfoRow("سایدکار AnyTLS", "127.0.0.1:${com.beevpn.win.engine.EngineConfig.ANYTLS_SIDECAR_PORT}")
                InfoRow("گزارش‌ها", AppPaths.engineLog.path)
            }
        }
        Spacer(Modifier.height(12.dp))

        // Log tail
        HoneyCard(Modifier.width(680.dp)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("گزارش پیشرفته", style = T.body)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "↻ بروزرسانی",
                        style = T.accent.copy(fontSize = 12.sp),
                        modifier = Modifier.clickable {
                            logText = try {
                                AppPaths.engineLog.let { f -> if (f.isFile) f.readText().lines().takeLast(200).joinToString("\n") else "هنوز گزارشی نیست" }
                            } catch (_: Exception) { "خواندن گزارش ناموفق بود" }
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .background(Color(0xFF060A0E), RoundedCornerShape(10.dp))
                        .border(1.dp, Honey.divider, RoundedCornerShape(10.dp))
                        .padding(8.dp),
                ) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = logText,
                        onValueChange = {},
                        readOnly = true,
                        textStyle = T.mono.copy(color = Honey.muted, fontSize = 10.5.sp),
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
                    )
                }
            }
        }

        savedMsg?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = T.body.copy(color = Honey.primary))
        }
        Spacer(Modifier.height(24.dp))
    }
}

/// Extra imported links survive restarts in their own file, appended to the
/// subscription cache — separate from the publisher's body so refreshes never
/// lose user pins.
private fun persistExtraLinks(text: String) {
    val f = java.io.File(AppPaths.baseDir, "shard-extra.txt")
    try {
        f.appendText(if (text.endsWith("\n")) text else text + "\n")
    } catch (_: Exception) {}
}

@Composable
fun SwitchHoney(checked: Boolean, onChange: (Boolean) -> Unit) {
    val w = 46.dp
    val h = 26.dp
    Box(
        Modifier
            .width(w)
            .height(h)
            .background(if (checked) Honey.primary else Honey.track, RoundedCornerShape(h / 2))
            .border(1.dp, if (checked) Honey.primary else Honey.divider, RoundedCornerShape(h / 2))
            .clickable { onChange(!checked) },
        contentAlignment = if (checked) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Box(
            Modifier
                .padding(3.dp)
                .height(20.dp)
                .width(20.dp)
                .background(if (checked) Color(0xFF1A1200) else Honey.muted, RoundedCornerShape(10.dp)),
        )
    }
}
