package com.beevpn.win.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font as DesktopFont
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/// The honey palette — byte-for-byte the iOS Theme.swift copy (which itself
/// mirrors SubscriptionInfoActivity / LicenseGateActivity on Android).
object Honey {
    val canvas = Color(0xFF04070B)
    val surface = Color(0xFF0B1116)
    val divider = Color(0xFF1C2429)
    val ink = Color(0xFFEAF6F3)
    val muted = Color(0xFF9DB0B5)
    val primary = Color(0xFFFFC107)
    val danger = Color(0xFFFF6B7F)
    val ok = Color(0xFF59D98E)
    val track = Color(0xFF141C22)
}

/// Vazirmatn v33.003 (OFL) — the same face the bot and the site use.
val Vazir = FontFamily(
    DesktopFont("vazir_black", Res.bytes("fonts/Vazirmatn-Black.ttf"), FontWeight.Black),
    DesktopFont("vazir_bold", Res.bytes("fonts/Vazirmatn-Bold.ttf"), FontWeight.Bold),
    DesktopFont("vazir", Res.bytes("fonts/Vazirmatn-Regular.ttf"), FontWeight.Normal),
)

/// Resource bytes, cached — one read per file per process.
object Res {
    private val cache = mutableMapOf<String, ByteArray>()
    fun bytes(path: String): ByteArray = synchronized(cache) {
        cache.getOrPut(path) {
            val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(path)
                ?: ClassLoader.getSystemResourceAsStream(path)
                ?: error("resource missing: $path")
            stream.readBytes()
        }
    }
}

// ------------------------------------------------------------- text styles

object T {
    val h1 = TextStyle(fontFamily = Vazir, fontWeight = FontWeight.Black, fontSize = 26.sp, color = Honey.ink)
    val h2 = TextStyle(fontFamily = Vazir, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Honey.ink)
    val body = TextStyle(fontFamily = Vazir, fontWeight = FontWeight.Normal, fontSize = 14.5.sp, color = Honey.ink)
    val muted = TextStyle(fontFamily = Vazir, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, color = Honey.muted)
    val accent = TextStyle(fontFamily = Vazir, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Honey.primary)
    val mono = TextStyle(fontFamily = Vazir, fontWeight = FontWeight.Normal, fontSize = 13.sp, color = Honey.ink)
}

// ------------------------------------------------------------- components

/// The honey card — same corner, stroke and fill the iOS HoneyCard draws.
@Composable
fun HoneyCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .background(Honey.surface, RoundedCornerShape(14.dp))
            .border(1.dp, Honey.divider, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) { content() }
}

@Composable
fun InfoRow(title: String, value: String, valueColor: Color = Honey.ink) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = T.muted)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = T.body.copy(color = valueColor, fontSize = 13.5.sp),
            maxLines = 1,
        )
    }
}

@Composable
fun HoneyButton(
    title: String,
    filled: Boolean,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    enabled: Boolean = true,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.45f
    val bg = when {
        danger -> Honey.danger.copy(alpha = 0.16f * alpha)
        filled -> Honey.primary.copy(alpha = alpha)
        else -> Honey.surface
    }
    val fg = when {
        filled && !danger -> Color(0xFF1A1200).copy(alpha = alpha)
        danger -> Honey.danger.copy(alpha = alpha)
        else -> Honey.ink.copy(alpha = alpha)
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(bg, RoundedCornerShape(12.dp))
            .border(
                1.dp,
                if (filled && !danger) Honey.primary.copy(alpha = alpha) else Honey.divider,
                RoundedCornerShape(12.dp),
            )
            .clickable(enabled = enabled && !busy) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (busy) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.height(17.dp).width(17.dp),
                    strokeWidth = 2.dp,
                    color = fg,
                    trackColor = Color.Transparent,
                )
                Spacer(Modifier.width(9.dp))
            }
            Text(title, style = T.body.copy(color = fg, fontWeight = FontWeight.Bold, fontSize = 15.sp))
        }
    }
}

/// The tab pill used by the side navigation.
@Composable
fun NavItem(label: String, icon: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) Honey.primary.copy(alpha = 0.14f) else Color.Transparent
    val fg = if (selected) Honey.primary else Honey.muted
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .background(bg, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, fontSize = 17.sp)
        Spacer(Modifier.width(10.dp))
        Text(label, style = T.body.copy(color = fg, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal))
    }
}

/// Section header with the small honey dot, mirroring the Android settings
/// section headers.
@Composable
fun SectionHeader(title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)) {
        Box(Modifier.size(6.dp).background(Honey.primary, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(8.dp))
        Text(title, style = T.accent)
    }
}
