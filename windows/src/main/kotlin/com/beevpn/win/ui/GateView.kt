package com.beevpn.win.ui

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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beevpn.win.faDigits
import com.beevpn.win.license.LicenseAPI
import com.beevpn.win.license.LicenseManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/// The gate — the buyer's first screen: paste the BEE- code from the Telegram
/// invoice, or log into the account the admin issued. The visual twin of
/// LicenseGateActivity / GateView.swift, staged as a glowing glass vault door.
///
/// Activation UX contract (the QA-hardened one) — UNCHANGED:
///   * the network call never runs on the UI thread and never outlives a
///     45-second hard ceiling — even a hung DNS cannot spin the button forever,
///   * `busy` is reset in a finally-equivalent path, so NO throwable (Error
///     included) can leave the button frozen on «…»,
///   * one automatic retry for transport trouble (never for server verdicts),
///   * live phase text, spinner, Enter-to-submit, auto-focus and a retry
///     button under the error.
@Composable
fun GateView() {
    var tab by remember { mutableStateOf(0) } // 0 = code, 1 = account
    var busy by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val codeFocus = remember { FocusRequester() }
    val userFocus = remember { FocusRequester() }
    val passFocus = remember { FocusRequester() }

    LaunchedEffect(tab) {
        try {
            if (tab == 0) codeFocus.requestFocus() else userFocus.requestFocus()
        } catch (_: Exception) {}
    }

    fun submit() {
        if (busy) return
        if (tab == 0 && code.replace("-", "").isBlank()) {
            error = "کد فعال‌سازی را از فاکتور تلگرام وارد کن"
            return
        }
        if (tab == 1 && (user.isBlank() || pass.isBlank())) {
            error = "نام کاربری و رمز عبور را وارد کن"
            return
        }
        busy = true
        error = null
        phase = "در حال بررسی لایسنس…"
        scope.launch {
            val res: String? = withContext(Dispatchers.IO) {
                var last: String? = null
                repeat(2) { attempt ->
                    try {
                        last = withTimeout(45_000L) {
                            if (tab == 0) LicenseManager.activate(code) else LicenseManager.login(user, pass)
                        }
                        return@withContext last      // null = success
                    } catch (t: TimeoutCancellationException) {
                        last = "پاسخ سرور بیش از حد طول کشید — اتصال اینترنت را بررسی کن و دوباره تلاش کن"
                    } catch (c: CancellationException) {
                        throw c                      // screen went away — stop quietly
                    } catch (t: LicenseAPI.LicenseError) {
                        last = t.message             // the server's verdict — do not retry
                        return@withContext t.message
                    } catch (t: Throwable) {
                        // Transport-level trouble (DNS, reset, offline…) — retry once.
                        last = "ارتباط با سرور لایسنس برقرار نشد — اتصال اینترنت را بررسی کن" +
                            (t.message?.let { " ($it)" } ?: "")
                    }
                    if (attempt == 0) {
                        phase = "اتصال اول موفق نبود — یک تلاش دیگر…"
                        delay(1_500)
                    }
                }
                last
            }
            busy = false
            phase = null
            if (res != null) error = res   // null = success; the gate already flipped to GRANTED
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 40.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // hero — the hive logo with its molten hexagon
            com.beevpn.win.HiveLogo()
            Spacer(Modifier.height(8.dp))
            Text(
                "BEE-VPN",
                style = T.h0.copy(
                    fontSize = 32.sp,
                    color = Honey.primary,
                    shadow = androidx.compose.ui.graphics.Shadow(Honey.glow.copy(alpha = 0.6f), blurRadius = 24f),
                ),
            )
            Text("برای شروع، اشتراک خود را فعال کن", style = T.muted, modifier = Modifier.padding(top = 4.dp))

            // The server's verdict from the last background /verify — expiry and
            // revocation land here, exactly like Android's EXTRA_REASON=expired
            // status line on LicenseGateActivity. Cleared on a fresh activation.
            LicenseManager.lockReason?.let { reason ->
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .width(460.dp)
                        .clip(FieldShape)
                        .background(Honey.danger.copy(alpha = 0.10f))
                        .border(1.dp, Honey.danger.copy(alpha = 0.35f), FieldShape)
                        .padding(12.dp),
                ) {
                    Text(reason, style = T.body.copy(color = Honey.danger, fontSize = 13.sp))
                }
            }

            Spacer(Modifier.height(20.dp))

            GlassCard(Modifier.width(460.dp)) {
                Column(Modifier.padding(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TabChip("🎟 کد فعال‌سازی", tab == 0, enabled = !busy) { tab = 0 }
                        TabChip("👤 حساب کاربری", tab == 1, enabled = !busy) { tab = 1 }
                    }
                    Spacer(Modifier.height(14.dp))

                    if (tab == 0) {
                        BeeField(
                            value = code,
                            onValue = { code = it.uppercase().trim() },
                            hint = "BEE-XXXX-XXXX-XXXX",
                            enabled = !busy,
                            focusRequester = codeFocus,
                            onEnter = ::submit,
                        )
                    } else {
                        BeeField(
                            value = user,
                            onValue = { user = it },
                            hint = "نام کاربری",
                            enabled = !busy,
                            focusRequester = userFocus,
                            onEnter = {
                                if (pass.isBlank()) passFocus.requestFocus() else submit()
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                        BeeField(
                            value = pass,
                            onValue = { pass = it },
                            hint = "رمز عبور",
                            enabled = !busy,
                            secret = true,
                            focusRequester = passFocus,
                            onEnter = ::submit,
                        )
                    }

                    error?.let {
                        Spacer(Modifier.height(10.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(FieldShape)
                                .background(Honey.danger.copy(alpha = 0.10f))
                                .border(1.dp, Honey.danger.copy(alpha = 0.35f), FieldShape)
                                .padding(12.dp),
                        ) {
                            Column {
                                Text(it, style = T.body.copy(color = Honey.danger, fontSize = 13.sp))
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "🔄 تلاش دوباره",
                                    style = T.body.copy(
                                        color = Honey.primary,
                                        fontSize = 13.sp,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    ),
                                    modifier = Modifier.clickable(enabled = !busy) {
                                        error = null
                                        submit()
                                    },
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    HoneyButton(
                        title = when {
                            tab == 1 -> "ورود"
                            else -> "فعال‌سازی"
                        },
                        filled = true,
                        busy = busy,
                        enabled = !busy,
                    ) { submit() }

                    phase?.let {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spinner(14.dp, Honey.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(it, style = T.muted.copy(color = Honey.primary, fontSize = 12.sp))
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        LinkText("🛍 خرید", com.beevpn.win.config.BeeConfig.TELEGRAM_URL)
                        // Deep link straight into the bot's 📝 ثبت‌نام flow —
                        // the username/password registration the profile tab
                        // of this gate signs into.
                        LinkText("📝 ثبت‌نام", telegramRegisterUrl())
                        LinkText("🆘 پشتیبانی", com.beevpn.win.config.BeeConfig.SUPPORT_URL)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "شناسهٔ دستگاه: ${faDigits(LicenseManager.installId.take(12))}… (برای انتقال اشتراک با پشتیبانی تماس بگیر)",
                style = T.muted,
            )
        }
    }
}

/// The bot deep link that opens the 📝 ثبت‌نام flow directly, so a buyer
/// without any entitlement can register (username + password) in one tap.
private fun telegramRegisterUrl(): String {
    val base = com.beevpn.win.config.BeeConfig.TELEGRAM_URL
    if (base.isBlank()) return base
    return base + (if (base.contains('?')) "&" else "?") + "start=register"
}

@Composable
private fun TabChip(title: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.45f
    val bg: Brush = if (selected) {
        Brush.verticalGradient(listOf(Honey.goldTop.copy(alpha = 0.28f * alpha), Honey.goldBot.copy(alpha = 0.18f * alpha)))
    } else {
        Brush.verticalGradient(listOf(Honey.track.copy(alpha = 0.9f), Honey.track.copy(alpha = 0.9f)))
    }
    val fg = if (selected) Honey.primary else Honey.muted
    Box(
        Modifier
            .clip(ChipShape)
            .background(bg)
            .border(
                1.dp,
                if (selected) Honey.primary.copy(alpha = 0.55f * alpha) else Honey.divider,
                ChipShape,
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            title,
            style = T.body.copy(
                color = fg.copy(alpha = alpha),
                fontSize = 13.5.sp,
                fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
            ),
        )
    }
}

@Composable
fun BeeField(
    value: String,
    onValue: (String) -> Unit,
    hint: String,
    enabled: Boolean = true,
    secret: Boolean = false,
    focusRequester: FocusRequester? = null,
    onEnter: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    var modifier = Modifier
        .fillMaxWidth()
        .height(46.dp)
    if (focusRequester != null) modifier = modifier.focusRequester(focusRequester)
    if (onEnter != null) {
        modifier = modifier.onPreviewKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown &&
                (e.key == Key.Enter || e.key == Key.NumPadEnter)
            ) {
                onEnter()
                true
            } else {
                false
            }
        }
    }
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValue,
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        textStyle = T.body.copy(fontSize = 14.sp),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Honey.primary),
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(FieldShape)
            .background(
                Brush.verticalGradient(
                    listOf(Honey.track.copy(alpha = 0.95f), Honey.track.copy(alpha = 0.75f)),
                ),
            )
            .border(
                1.dp,
                if (focused) Honey.primary.copy(alpha = 0.75f) else Honey.divider,
                FieldShape,
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(hint, style = T.muted)
                inner()
            }
        },
    )
}
