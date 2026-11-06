package com.beevpn.win.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.beevpn.win.tunnel.TunnelController

/// The honey orb — the connect button, the status lamp and the brand mark in
/// one 3D machine. Layers, outside-in:
///
///   1. state halo — a wide radial glow that breathes with the connection,
///   2. expanding ripples while CONNECTED (the tunnel visibly radiates),
///   3. a tilted orbit ring carrying three glowing bee-particles that lap at
///      different speeds (one queen with a short trail),
///   4. a slowly rotating conic sheen arc riding the main ring,
///   5. the sphere itself: offset radial gradient = top-left light source,
///      clipped bottom inner-shadow, specular highlight, rim light,
///   6. a drawn power glyph with a soft triple-stroke glow, tinted by state,
///   7. press physics: the whole thing sinks on touch and springs back.
///
/// Tap the orb to connect / disconnect — the button is the machine.
@Composable
fun Orb(state: TunnelController.State, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    val busy = state == TunnelController.State.STARTING || state == TunnelController.State.PROVISIONING ||
        state == TunnelController.State.VERIFYING || state == TunnelController.State.STOPPING
    val connected = state == TunnelController.State.CONNECTED
    val failed = state == TunnelController.State.FAILED

    val transition = rememberInfiniteTransition(label = "orb")
    val spin by transition.animateFloat(
        0f, 360f, infiniteRepeatable(tween(8_000, easing = LinearEasing)), label = "sheenSpin",
    )
    val spinner by transition.animateFloat(
        0f, 360f, infiniteRepeatable(tween(1_100, easing = LinearEasing)), label = "spinner",
    )
    val breathe by transition.animateFloat(
        0.86f, 1f, infiniteRepeatable(tween(1_700, easing = LinearEasing), RepeatMode.Reverse), label = "breathe",
    )
    val ripple0 by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2_600, easing = LinearEasing)), label = "r0",
    )
    val ripple1 by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2_600, easing = LinearEasing), initialStartOffset = StartOffset(866)), label = "r1",
    )
    val ripple2 by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2_600, easing = LinearEasing), initialStartOffset = StartOffset(1_733)), label = "r2",
    )
    val beeA by transition.animateFloat(
        0f, 360f, infiniteRepeatable(tween(9_000, easing = LinearEasing)), label = "beeA",
    )
    val beeB by transition.animateFloat(
        0f, 360f, infiniteRepeatable(tween(15_000, easing = LinearEasing), initialStartOffset = StartOffset(3_000)), label = "beeB",
    )
    val beeQ by transition.animateFloat(
        0f, 360f, infiniteRepeatable(tween(22_000, easing = LinearEasing), initialStartOffset = StartOffset(8_000)), label = "beeQ",
    )

    val pressed = remember { mutableStateOf(false) }
    val press by animateFloatAsState(
        if (pressed.value) 0.955f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "orbPress",
    )

    val ringColor = when {
        connected -> Honey.ok
        busy -> Honey.primary
        failed -> Honey.danger
        else -> Honey.muted
    }
    val glyphColor = when {
        connected -> Honey.ok
        busy -> Honey.primary
        failed -> Honey.danger
        else -> Honey.ink
    }
    val sphereTint = when {
        connected -> Color(0xFFBFF5D6)
        busy -> Honey.goldTop
        failed -> Color(0xFFFFD4DB)
        else -> Color(0xFFE8F2F2)
    }

    Box(
        modifier
            .size(300.dp)
            .graphicsLayer { scaleX = press; scaleY = press }
            .pointerInput(onToggle) {
                detectTapGestures(
                    onPress = {
                        pressed.value = true
                        try {
                            awaitRelease()
                        } finally {
                            pressed.value = false
                        }
                    },
                    onTap = { onToggle() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(300.dp)) {
            val c = center
            val ringR = 118.dp.toPx()
            val sphereR = 92.dp.toPx()

            // 1 — breathing halo
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(ringColor.copy(alpha = 0.26f * breathe), Color.Transparent),
                    center = c,
                    radius = size.minDimension / 2f,
                ),
                radius = size.minDimension / 2f,
                center = c,
            )

            // 2 — ripples while connected
            if (connected) {
                listOf(ripple0, ripple1, ripple2).forEach { t ->
                    val r = ringR + t * 52.dp.toPx()
                    drawCircle(
                        color = Honey.ok.copy(alpha = (1f - t) * 0.34f),
                        radius = r,
                        center = c,
                        style = Stroke(width = (1f + t * 2.4f).dp.toPx()),
                    )
                }
            }

            // 3 — tilted orbit + bee particles
            val orbitA = 146.dp.toPx()
            val orbitB = 46.dp.toPx()
            val tiltDeg = -18f
            fun orbitPoint(angleDeg: Float, squash: Float = 1f): Offset {
                val a = Math.toRadians(angleDeg.toDouble())
                val x = orbitA * squash * kotlin.math.cos(a.toFloat())
                val y = orbitB * squash * kotlin.math.sin(a.toFloat())
                val rad = Math.toRadians(tiltDeg.toDouble())
                val cos = kotlin.math.cos(rad.toFloat())
                val sin = kotlin.math.sin(rad.toFloat())
                return Offset(c.x + x * cos - y * sin, c.y + x * sin + y * cos)
            }
            rotate(tiltDeg, pivot = c) {
                drawOval(
                    color = Honey.primary.copy(alpha = 0.16f),
                    topLeft = Offset(c.x - orbitA, c.y - orbitB),
                    size = androidx.compose.ui.geometry.Size(orbitA * 2f, orbitB * 2f),
                    style = Stroke(width = 1.2.dp.toPx()),
                )
            }
            fun bee(angleDeg: Float, coreR: Float, color: Color) {
                val p = orbitPoint(angleDeg)
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(color.copy(alpha = 0.5f), Color.Transparent),
                        center = p,
                        radius = coreR * 3.4f,
                    ),
                    radius = coreR * 3.4f,
                    center = p,
                )
                drawCircle(color, coreR, p)
            }
            bee(beeA, 2.6f.dp.toPx() / 2f, Honey.primary)
            bee(beeB + 120f, 2.2f.dp.toPx() / 2f, Honey.goldTop)
            // queen with a short trail
            val q = orbitPoint(beeQ)
            drawArc(
                color = Honey.primary.copy(alpha = 0.30f),
                startAngle = beeQ,
                sweepAngle = 22f,
                useCenter = false,
                topLeft = Offset(c.x - orbitA, c.y - orbitB),
                size = androidx.compose.ui.geometry.Size(orbitA * 2f, orbitB * 2f),
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
            )
            bee(beeQ, 3.6f.dp.toPx() / 2f, Honey.goldTop)

            // 4 — main ring: quiet track + rotating conic sheen
            drawCircle(
                color = ringColor.copy(alpha = 0.18f),
                radius = ringR,
                center = c,
                style = Stroke(width = 5.dp.toPx()),
            )
            rotate(spin, pivot = c) {
                drawArc(
                    brush = Brush.sweepGradient(
                        0f to Color.Transparent,
                        0.55f to ringColor.copy(alpha = 0.05f),
                        1f to ringColor.copy(alpha = 0.95f),
                        center = c,
                    ),
                    startAngle = 0f,
                    sweepAngle = 300f,
                    useCenter = false,
                    topLeft = Offset(c.x - ringR, c.y - ringR),
                    size = androidx.compose.ui.geometry.Size(ringR * 2f, ringR * 2f),
                    style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
                )
            }

            // 5 — the sphere
            val lightSrc = Offset(c.x - sphereR * 0.30f, c.y - sphereR * 0.38f)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(
                        sphereTint.copy(alpha = 0.95f),
                        Color(0xFF232B36),
                        Color(0xFF0B1017),
                        Color(0xFF04070B),
                    ),
                    center = lightSrc,
                    radius = sphereR * 1.9f,
                ),
                radius = sphereR,
                center = c,
            )
            val spherePath = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c.x - sphereR, c.y - sphereR, c.x + sphereR, c.y + sphereR)) }
            clipPath(spherePath) {
                // inner shadow pooling at the bottom
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color.Black.copy(alpha = 0.52f), Color.Transparent),
                        center = Offset(c.x, c.y + sphereR * 0.72f),
                        radius = sphereR * 1.05f,
                    ),
                    radius = sphereR * 1.05f,
                    center = Offset(c.x, c.y + sphereR * 0.72f),
                )
                // specular highlight, top-left
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.30f), Color.Transparent),
                        center = Offset(c.x - sphereR * 0.34f, c.y - sphereR * 0.42f),
                        radius = sphereR * 0.42f,
                    ),
                    radius = sphereR * 0.42f,
                    center = Offset(c.x - sphereR * 0.34f, c.y - sphereR * 0.42f),
                )
            }
            // rim light — lit above, dark below
            drawArc(
                brush = Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.34f), Color.White.copy(alpha = 0.02f)),
                    startY = c.y - sphereR,
                    endY = c.y,
                ),
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(c.x - sphereR, c.y - sphereR),
                size = androidx.compose.ui.geometry.Size(sphereR * 2f, sphereR * 2f),
                style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round),
            )
            drawArc(
                brush = Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.10f), Color.Black.copy(alpha = 0.55f)),
                    startY = c.y,
                    endY = c.y + sphereR,
                ),
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(c.x - sphereR, c.y - sphereR),
                size = androidx.compose.ui.geometry.Size(sphereR * 2f, sphereR * 2f),
                style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round),
            )

            // 6 — the power glyph with a triple-stroke glow
            val pr = 30.dp.toPx()
            fun glyph(width: Float, alpha: Float) {
                drawArc(
                    color = glyphColor.copy(alpha = alpha),
                    startAngle = -30f,
                    sweepAngle = 240f,
                    useCenter = false,
                    topLeft = Offset(c.x - pr, c.y - pr + 8.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(pr * 2f, pr * 2f),
                    style = Stroke(width = width, cap = StrokeCap.Round),
                )
                drawLine(
                    color = glyphColor.copy(alpha = alpha),
                    start = Offset(c.x, c.y + 8.dp.toPx() - pr * 0.86f),
                    end = Offset(c.x, c.y + 8.dp.toPx() - pr * 0.30f),
                    strokeWidth = width,
                    cap = StrokeCap.Round,
                )
            }
            glyph(8.dp.toPx() * if (busy) breathe else 1.6f, 0.10f)
            glyph(7.dp.toPx(), 0.16f)
            glyph(4.6f.dp.toPx(), 1f)

            // 7 — busy spinner riding the ring
            if (busy) {
                rotate(spinner, pivot = c) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            0f to Color.Transparent,
                            1f to Honey.goldTop,
                            center = c,
                        ),
                        startAngle = 0f,
                        sweepAngle = 130f,
                        useCenter = false,
                        topLeft = Offset(c.x - ringR, c.y - ringR),
                        size = androidx.compose.ui.geometry.Size(ringR * 2f, ringR * 2f),
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
        }
    }
}
