package com.beevpn.win

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.beevpn.win.license.LicenseManager
import kotlinx.coroutines.delay
import com.beevpn.win.tunnel.TunnelController
import com.beevpn.win.ui.AuroraBackground
import com.beevpn.win.ui.AboutView
import com.beevpn.win.ui.AdvancedView
import com.beevpn.win.ui.GateView
import com.beevpn.win.ui.GlassChip
import com.beevpn.win.ui.HomeView
import com.beevpn.win.ui.Honey
import com.beevpn.win.ui.NavItem
import com.beevpn.win.ui.SubscriptionView
import com.beevpn.win.ui.T

/// BEE-VPN for Windows — entry point. RTL Persian shell: an obsidian stage
/// under a drifting aurora, a glass navigation rail on the right, and content
/// that slides in with depth. Gated by the same Ed25519 entitlement the phone
/// runs.
fun main() {
    maybeRelaunchElevated()
    // Last-resort hygiene: an app death while it owns the system proxy
    // (proxy mode, TUN off) must not leave the machine pointing at a dead
    // port forever — the hook restores whatever the user had. No-op when
    // the proxy was never applied.
    java.lang.Runtime.getRuntime().addShutdownHook(
        Thread {
            try { com.beevpn.win.engine.WindowsAdmin.clearSystemProxy() } catch (_: Throwable) {}
        },
    )
    // A thread that dies mid-connect must never leave the UI wedged on
    // «Connecting» — the audit's PROVEN path: an Error escaping
    // connectBlocking (or any engine onExit callback) had no handler
    // anywhere, so the state froze on whatever the last paint was. The
    // handler logs the crash and forces a terminal state when the crash
    // landed while the tunnel was being built.
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
        try {
            com.beevpn.win.engine.EngineProcess.logLine(
                "[crash] ${t.name}: ${e.javaClass.simpleName}: ${e.message ?: "(no message)"}",
            )
        } catch (_: Throwable) {}
        try {
            com.beevpn.win.tunnel.TunnelController.forceFail(
                "خطای داخلی: ${e.message ?: e.javaClass.simpleName}",
            )
        } catch (_: Throwable) {}
    }
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "BEE-VPN",
            state = rememberWindowState(width = 1120.dp, height = 720.dp),
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                AppRoot()
            }
        }
    }
}

/// Tunneling on Windows (the Wintun adapter, the 0.0.0.0/0 routes, the WFP
/// DNS-leak block) needs an elevated process — the desktop's VpnService.
/// A buyer double-clicking the shortcut must never meet «Run as
/// administrator»: the packaged launcher asks UAC ONCE and hands the session
/// to its elevated twin. The 90 s marker window keeps a cancelled UAC from
/// ping-ponging prompts — that launch runs unelevated, the connect button
/// shows the honest admin message, and the manual relaunch button in Home
/// still works.
private fun maybeRelaunchElevated() {
    if (!com.beevpn.win.AppPaths.isWindows()) return
    if (com.beevpn.win.AppPaths.exeDir() == null) return            // dev `gradle run`
    if (com.beevpn.win.engine.WindowsAdmin.isAdmin()) return        // already elevated
    val marker = java.io.File(com.beevpn.win.AppPaths.baseDir, "uac-relaunch.flag")
    val now = System.currentTimeMillis()
    val last = runCatching { marker.readText().trim().toLongOrNull() ?: 0L }.getOrDefault(0L)
    if (now - last < 90_000L) {
        com.beevpn.win.engine.EngineProcess.logLine(
            "[launch] UAC relaunch was just cancelled — running unelevated; tunneling will ask for «Run as administrator»",
        )
        return
    }
    runCatching { marker.writeText(now.toString()) }
    if (com.beevpn.win.engine.WindowsAdmin.relaunchElevated()) {
        com.beevpn.win.engine.EngineProcess.logLine("[launch] not elevated — handed off to the UAC-elevated twin")
        kotlin.system.exitProcess(0)
    }
}

private enum class Tab(val label: String, val icon: String) {
    HOME("خانه", "⏻"),
    SUBSCRIPTION("اشتراک", "◆"),
    ADVANCED("پیشرفته", "⚙"),
    ABOUT("درباره", "ℹ"),
}

@Composable
private fun AppRoot() {
    LaunchedEffect(Unit) {
        // Startup work off the UI thread; a broken store or cache must never
        // take the window down — the gate simply shows instead.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                LicenseManager.start()
            } catch (t: Throwable) {
                com.beevpn.win.engine.EngineProcess.logLine("[license] start failed: $t")
            }
            try {
                TunnelController.init()
            } catch (t: Throwable) {
                com.beevpn.win.engine.EngineProcess.logLine("[tunnel] init failed: $t")
            }
        }
    }
    var tab by remember { mutableStateOf(Tab.HOME) }
    val gate = LicenseManager.gate

    // The unlock transition: while locked the rail only offers «درباره», so
    // after a successful activation snap the content back to خانه.
    LaunchedEffect(gate) {
        if (gate == LicenseManager.Gate.GRANTED && tab != Tab.HOME) tab = Tab.HOME
        // خروج از حساب / انقضا / ابطال: اپ قفل می‌شود — تونل هم باید همان
        // لحظه قطع شود؛ اپِ قفل هرگز نباید ترافیک را عبور دهد.
        if (gate == LicenseManager.Gate.LOCKED) {
            try { TunnelController.disconnect() } catch (_: Throwable) {}
        }
    }

    // QA tour hook (BEE_UI_TOUR=1): cycle tabs so offscreen screenshot rigs
    // can capture every page without synthetic clicks. Inert in production.
    if (System.getenv("BEE_UI_TOUR") == "1") {
        LaunchedEffect(gate) {
            if (gate != LicenseManager.Gate.GRANTED) return@LaunchedEffect
            val order = Tab.entries
            var i = 0
            while (true) {
                delay(2_600)
                i = (i + 1) % order.size
                tab = order[i]
            }
        }
    }

    val unlocked = gate == LicenseManager.Gate.GRANTED
    val tabs = if (unlocked) Tab.entries else listOf(Tab.ABOUT)

    Box(Modifier.fillMaxSize().background(Honey.canvas)) {
        AuroraBackground(Modifier.fillMaxSize())

        Row(Modifier.fillMaxSize()) {
            // ── glass navigation rail (RTL → sits on the right visually) ──
            Box(
                Modifier
                    .width(216.dp)
                    .fillMaxHeight()
                    .background(
                        Brush.horizontalGradient(
                            listOf(Honey.surface.copy(alpha = 0.66f), Honey.canvasHi.copy(alpha = 0.80f)),
                        ),
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(
                            listOf(Honey.glassEdgeTop, Honey.glassEdgeBot),
                        ),
                        shape = RectangleShape,
                    )
                    .padding(vertical = 18.dp),
            ) {
                Column(Modifier.fillMaxHeight()) {
                    // brand — drawn hexagon hive with the bee inside
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HiveLogo()
                        Spacer(Modifier.width(9.dp))
                        Column {
                            Text(
                                "BEE-VPN",
                                style = T.h2.copy(
                                    color = Honey.primary,
                                    shadow = Shadow(Honey.glow.copy(alpha = 0.55f), blurRadius = 16f),
                                ),
                            )
                            Text("عسلِ بی‌دردسر", style = T.muted.copy(fontSize = 10.5.sp))
                        }
                    }
                    Spacer(Modifier.height(14.dp))

                    // sliding indicator + items
                    val selectedIndex = tabs.indexOfFirst { it == tab }.coerceAtLeast(0)
                    val itemH = 50.dp
                    val indicatorY by animateDpAsState(
                        itemH * selectedIndex,
                        spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow),
                        label = "navIndicator",
                    )
                    Box {
                        Column {
                            tabs.forEach { t ->
                                Box(Modifier.height(itemH)) { NavItem(t.label, t.icon, selected = tab == t) { tab = t } }
                            }
                        }
                        if (unlocked) {
                            Box(
                                Modifier
                                    .offset(y = indicatorY)
                                    .width(3.dp)
                                    .height(38.dp)
                                    .align(Alignment.TopStart),
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .background(
                                            Brush.verticalGradient(listOf(Honey.goldTop, Honey.goldBot)),
                                            RoundedCornerShape(2.dp),
                                        ),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.weight(1f))
                    Box(Modifier.padding(horizontal = 14.dp)) {
                        GlassChip(
                            "نسخهٔ " + com.beevpn.win.faDigits(com.beevpn.win.config.BeeConfig.VERSION),
                            color = Honey.muted,
                        )
                    }
                }
            }

            // ── content stage ──
            Column(Modifier.weight(1f).fillMaxHeight()) {
                // top bar — page title + live connection pill
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 26.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when {
                            gate == LicenseManager.Gate.LOCKED -> "فعال‌سازی"
                            else -> tab.label
                        },
                        style = T.h2.copy(color = Honey.ink),
                    )
                    Spacer(Modifier.weight(1f))
                    if (unlocked) StatusPill(onClick = { tab = Tab.HOME })
                }

                AnimatedContent(
                    targetState = when {
                        gate == LicenseManager.Gate.UNKNOWN -> "boot"
                        gate == LicenseManager.Gate.LOCKED -> "gate"
                        else -> tab.name
                    },
                    transitionSpec = {
                        (fadeIn(tween(240)) + slideInVertically(spring(stiffness = Spring.StiffnessMediumLow)) { it / 10 })
                            .togetherWith(fadeOut(tween(150)) + slideOutVertically(tween(150)) { -it / 12 })
                    },
                    label = "pages",
                ) { key ->
                    when {
                        key == "boot" -> {}
                        key == "gate" -> GateView()
                        else -> when (tab) {
                            Tab.HOME -> HomeView(onOpenProfile = { tab = Tab.SUBSCRIPTION })
                            Tab.SUBSCRIPTION -> SubscriptionView()
                            Tab.ADVANCED -> AdvancedView()
                            Tab.ABOUT -> AboutView()
                        }
                    }
                }
            }
        }
    }
}

/// The official BEE-VPN logo — the bee-face hexagon inside its molten ring,
/// loaded once from the jar's resources (beevpn-logo.png — the same art the
/// MSI/shortcut icon uses). Replaces the old drawn placeholder: buyers see
/// the real brand at first launch, on the gate, in the rail and in About.
private val beeLogoBitmap: ImageBitmap by lazy {
    // skia lives on the same classloader as the app resources — the safe
    // way to reach beevpn-logo.png inside the packaged jar.
    val stream = requireNotNull(
        org.jetbrains.skia.Image::class.java.classLoader.getResourceAsStream("beevpn-logo.png"),
    ) { "beevpn-logo.png missing from the app resources" }
    stream.use { org.jetbrains.skia.Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }
}

@Composable
fun HiveLogo(size: Dp = 44.dp) {
    Image(
        bitmap = beeLogoBitmap,
        contentDescription = "BEE-VPN",
        modifier = Modifier.size(size),
    )
}

/// The always-visible connection lamp — a glass pill that mirrors the tunnel
/// state from anywhere in the app; tap to jump home.
@Composable
private fun StatusPill(onClick: () -> Unit) {
    val state = TunnelController.state
    val (color, label) = when (state) {
        TunnelController.State.CONNECTED -> Honey.ok to "وصل"
        TunnelController.State.STARTING, TunnelController.State.PROVISIONING,
        TunnelController.State.VERIFYING, TunnelController.State.STOPPING,
        -> Honey.primary to "در حال اتصال…"
        TunnelController.State.FAILED -> Honey.danger to "قطع — خطا"
        else -> Honey.muted to "آفلاین"
    }
    val t = rememberInfiniteTransition(label = "lamp")
    val pulse by t.animateFloat(
        0.75f, 1.25f,
        infiniteRepeatable(tween(1_100, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Honey.surface.copy(alpha = 0.72f))
            .border(1.dp, color.copy(alpha = 0.30f), RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .padding(horizontal = 13.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = pulse; scaleY = pulse }
                    .background(color, RoundedCornerShape(5.dp)),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(label, style = T.chip.copy(color = color, fontSize = 12.sp))
    }
}
