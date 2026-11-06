package com.beevpn.win

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.material3.Text
import com.beevpn.win.license.LicenseManager
import com.beevpn.win.tunnel.TunnelController
import com.beevpn.win.ui.AboutView
import com.beevpn.win.ui.AdvancedView
import com.beevpn.win.ui.GateView
import com.beevpn.win.ui.HomeView
import com.beevpn.win.ui.Honey
import com.beevpn.win.ui.NavItem
import com.beevpn.win.ui.NodesView
import com.beevpn.win.ui.SubscriptionView
import com.beevpn.win.ui.T

/// BEE-VPN for Windows — entry point. RTL Persian shell with the honey
/// navigation rail, gated by the same Ed25519 entitlement the phone runs.
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "BEE-VPN",
        state = rememberWindowState(width = 1060.dp, height = 700.dp),
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            AppRoot()
        }
    }
}

private enum class Tab(val label: String, val icon: String) {
    HOME("خانه", "🏠"),
    NODES("نودها", "🌐"),
    SUBSCRIPTION("اشتراک", "💎"),
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
    }

    Column(Modifier.fillMaxSize().background(Honey.canvas)) {
        // title bar strip
        Row(
            Modifier
                .fillMaxSize()
                .weight(1f),
        ) {
            // navigation rail (RTL → sits on the right visually)
            Column(
                Modifier
                    .width(196.dp)
                    .fillMaxHeight()
                    .background(Honey.surface)
                    .padding(vertical = 16.dp),
            ) {
                Text(
                    "🐝 BEE-VPN",
                    style = T.h2.copy(color = Honey.primary),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
                val tabs = if (gate == LicenseManager.Gate.GRANTED) Tab.entries else listOf(Tab.ABOUT)
                tabs.forEach { t ->
                    NavItem(t.label, t.icon, selected = tab == t) { tab = t }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "نسخهٔ " + com.beevpn.win.faDigits(com.beevpn.win.config.BeeConfig.VERSION),
                    style = T.muted,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            // content
            Column(Modifier.weight(1f).fillMaxHeight()) {
                when {
                    gate == LicenseManager.Gate.UNKNOWN -> {}
                    gate == LicenseManager.Gate.LOCKED -> GateView()
                    else -> when (tab) {
                        Tab.HOME -> HomeView()
                        Tab.NODES -> NodesView()
                        Tab.SUBSCRIPTION -> SubscriptionView()
                        Tab.ADVANCED -> AdvancedView()
                        Tab.ABOUT -> AboutView()
                    }
                }
            }
        }
    }
}
