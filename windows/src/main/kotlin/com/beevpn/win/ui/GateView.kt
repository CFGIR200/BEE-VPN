package com.beevpn.win.ui

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
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beevpn.win.faDigits
import com.beevpn.win.faNum
import com.beevpn.win.license.LicenseManager
import java.awt.Desktop
import java.net.URI

/// The gate — the buyer's first screen: paste the BEE- code from the Telegram
/// invoice, or log into the account the admin issued. The visual twin of
/// LicenseGateActivity / GateView.swift.
@Composable
fun GateView() {
    var tab by remember { mutableStateOf(0) } // 0 = code, 1 = account
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize().background(Honey.canvas)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 40.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("🐝", fontSize = 44.sp)
            Spacer(Modifier.height(6.dp))
            Text("BEE-VPN", style = T.h1.copy(fontSize = 30.sp, color = Honey.primary))
            Text("برای شروع، اشتراک خود را فعال کن", style = T.muted, modifier = Modifier.padding(top = 4.dp))

            Spacer(Modifier.height(22.dp))

            HoneyCard(Modifier.width(430.dp)) {
                Column(Modifier.padding(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TabChip("🎟 کد فعال‌سازی", tab == 0) { tab = 0 }
                        TabChip("👤 حساب کاربری", tab == 1) { tab = 1 }
                    }
                    Spacer(Modifier.height(14.dp))

                    if (tab == 0) {
                        BeeField(
                            value = code,
                            onValue = { code = it.uppercase().trim() },
                            hint = "BEE-XXXX-XXXX-XXXX",
                            enabled = !busy,
                        )
                    } else {
                        BeeField(value = user, onValue = { user = it }, hint = "نام کاربری", enabled = !busy)
                        Spacer(Modifier.height(8.dp))
                        BeeField(
                            value = pass,
                            onValue = { pass = it },
                            hint = "رمز عبور",
                            enabled = !busy,
                            secret = true,
                        )
                    }

                    error?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, style = T.body.copy(color = Honey.danger, fontSize = 13.sp))
                    }

                    Spacer(Modifier.height(14.dp))
                    HoneyButton(
                        title = if (busy) "…" else "فعال‌سازی",
                        filled = true,
                        enabled = !busy,
                    ) {
                        busy = true
                        error = null
                        Thread {
                            val res = if (tab == 0) LicenseManager.activate(code) else LicenseManager.login(user, pass)
                            busy = false
                            error = res
                        }.start()
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        LinkText("🛍 خرید اشتراک", com.beevpn.win.config.BeeConfig.TELEGRAM_URL)
                        LinkText("🆘 پشتیبانی", com.beevpn.win.config.BeeConfig.SUPPORT_URL)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "شناسهٔ دستگاه: ${faDigits(LicenseManager.installId.take(12))}… (برای انتقال اشتراک با پشتیبانی تماس بگیر)",
                style = T.muted,
            )
        }
    }
}

@Composable
private fun TabChip(title: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) Honey.primary.copy(alpha = 0.16f) else Honey.track
    val fg = if (selected) Honey.primary else Honey.muted
    Box(
        Modifier
            .background(bg, RoundedCornerShape(10.dp))
            .border(1.dp, if (selected) Honey.primary.copy(alpha = 0.5f) else Honey.divider, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) { Text(title, style = T.body.copy(color = fg, fontSize = 13.5.sp)) }
}

@Composable
fun BeeField(
    value: String,
    onValue: (String) -> Unit,
    hint: String,
    enabled: Boolean = true,
    secret: Boolean = false,
) {
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValue,
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        textStyle = T.body.copy(fontSize = 14.sp),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Honey.primary),
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(Honey.track, RoundedCornerShape(10.dp))
            .border(1.dp, Honey.divider, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp)
            .let { it },
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(hint, style = T.muted)
                inner()
            }
        },
    )
}

@Composable
fun LinkText(label: String, url: String) {
    Text(
        label,
        style = T.body.copy(color = Honey.primary, fontSize = 13.sp),
        modifier = Modifier.clickable {
            try {
                Desktop.getDesktop().browse(URI(url))
            } catch (_: Exception) {}
        },
    )
}
