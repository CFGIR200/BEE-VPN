package com.beevpn.win.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.beevpn.win.tunnel.TunnelController

/// The honey orb — the connect button, the status lamp and the brand mark in
/// one breathing circle. The desktop cousin of the Android OrbitDialView and
/// the iOS HomeView orb: dark halo, honey ring when live, spinner when busy.
@Composable
fun Orb(state: TunnelController.State, modifier: Modifier = Modifier) {
    val spinning = state == TunnelController.State.STARTING || state == TunnelController.State.PROVISIONING
    val transition = rememberInfiniteTransition(label = "orb")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1_100, easing = LinearEasing)),
        label = "spin",
    )
    val breathe by transition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_600, easing = LinearEasing), RepeatMode.Reverse),
        label = "breathe",
    )

    val ringColor = when (state) {
        TunnelController.State.CONNECTED -> Honey.ok
        TunnelController.State.STARTING, TunnelController.State.PROVISIONING, TunnelController.State.STOPPING -> Honey.primary
        TunnelController.State.FAILED -> Honey.danger
        else -> Honey.muted
    }
    val haloAlpha = when (state) {
        TunnelController.State.CONNECTED -> 0.30f * breathe
        TunnelController.State.FAILED -> 0.22f * breathe
        else -> 0.16f * breathe
    }

    Box(modifier.size(248.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(248.dp)) {
            val c = center
            val halo = Brush.radialGradient(
                colors = listOf(ringColor.copy(alpha = haloAlpha), Color.Transparent),
                center = c,
                radius = size.minDimension / 2f,
            )
            drawCircle(halo, radius = size.minDimension / 2f, center = c)
            drawCircle(
                color = ringColor,
                radius = (86.dp.toPx()) * if (spinning) 1f else breathe,
                center = c,
                style = Stroke(width = 3.dp.toPx()),
            )
            if (spinning) {
                val arc = kotlin.math.min(140f, spin)
                drawArc(
                    color = Honey.primary,
                    startAngle = spin,
                    sweepAngle = arc,
                    useCenter = false,
                    topLeft = Offset(c.x - 108.dp.toPx() / 2, c.y - 108.dp.toPx() / 2),
                    style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
                    size = androidx.compose.ui.geometry.Size(108.dp.toPx(), 108.dp.toPx()),
                )
            }
        }
        Text("🐝", fontSize = 58.sp)
    }
}
