package com.beevpn.win.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font as DesktopFont
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/// ─────────────────────────────────────────────────────────────────────────
/// «Obsidian Hive» — the BEE-VPN Windows design system, v2.
///
/// Depth is the language: obsidian glass panels float over a living aurora,
/// edges catch a top light, presses physically sink, the orb casts real glow.
/// The palette keeps the honey DNA (iOS Theme.swift / Android hive gold) but
/// adds two cold counter-lights (teal + violet) so the gold reads as *light*
/// instead of paint.
/// ─────────────────────────────────────────────────────────────────────────
object Honey {
    // stage
    val canvas = Color(0xFF05070C)
    val canvasHi = Color(0xFF090D14)
    val surface = Color(0xFF0E141D)
    val surfaceHi = Color(0xFF141C27)
    val divider = Color(0xFF202B38)
    val track = Color(0xFF111924)

    // ink
    val ink = Color(0xFFF2F7F5)
    val muted = Color(0xFF93A6B0)

    // honey light
    val primary = Color(0xFFFFC107)
    val goldTop = Color(0xFFFFE082)
    val goldMid = Color(0xFFFFB300)
    val goldBot = Color(0xFFFF8F00)
    val glow = Color(0xFFFFB300)

    // signals
    val ok = Color(0xFF4CD98E)
    val danger = Color(0xFFFF5C72)
    val teal = Color(0xFF2DD4BF)
    val violet = Color(0xFF8B7CFF)

    // glass strokes
    val glassEdgeTop = Color(0x2EFFFFFF)
    val glassEdgeBot = Color(0x08FFFFFF)
    val onGold = Color(0xFF1A1200)
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
    val h0 = TextStyle(fontFamily = Vazir, fontWeight = FontWeight.Black, fontSize = 30.sp, color = Honey.ink)
    val h1 = TextStyle(fontFamily = Vazir, fontWeight = FontWeight.Black, fontSize = 26.sp, color = Honey.ink)
    val h2 = TextStyle(fontFamily = Vazir, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Honey.ink)
    val body = TextStyle(fontFamily = Vazir, fontWeight = FontWeight.Normal, fontSize = 14.5.sp, color = Honey.ink)
    val muted = TextStyle(fontFamily = Vazir, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, color = Honey.muted)
    val accent = TextStyle(fontFamily = Vazir, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Honey.primary)
    val mono = TextStyle(fontFamily = Vazir, fontWeight = FontWeight.Normal, fontSize = 13.sp, color = Honey.ink)
    val chip = TextStyle(fontFamily = Vazir, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Honey.ink)
}

// ------------------------------------------------------------- geometry

val CardShape = RoundedCornerShape(18.dp)
val FieldShape = RoundedCornerShape(13.dp)
val BtnShape = RoundedCornerShape(14.dp)
val ChipShape = RoundedCornerShape(11.dp)

private fun Modifier.hoverLine(onHover: (Boolean) -> Unit): Modifier =
    this.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                when (event.type) {
                    PointerEventType.Enter -> onHover(true)
                    PointerEventType.Exit -> onHover(false)
                }
            }
        }
    }

// ------------------------------------------------------------- aurora stage

/// The living backdrop: three cold-to-warm light blobs drifting on sine paths,
/// a honeycomb mesh etched at whisper alpha, and an obsidian vignette that
/// pulls the eye to the middle of the stage. One Canvas, redrawn cheaply.
@Composable
fun AuroraBackground(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "aurora")
    val p1 by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(19_000, easing = LinearEasing), RepeatMode.Reverse), label = "p1",
    )
    val p2 by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(27_000, easing = LinearEasing), RepeatMode.Reverse), label = "p2",
    )
    val p3 by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(35_000, easing = LinearEasing), RepeatMode.Reverse), label = "p3",
    )
    val meshA by t.animateFloat(
        0.028f, 0.05f, infiniteRepeatable(tween(11_000, easing = LinearEasing), RepeatMode.Reverse), label = "mesh",
    )

    Canvas(modifier) {
        val w = size.width
        val h = size.height

        // drifting light blobs
        fun blob(x: Float, y: Float, r: Float, color: Color, alpha: Float) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(color.copy(alpha = alpha), Color.Transparent),
                    center = Offset(x, y),
                    radius = r,
                ),
                radius = r,
                center = Offset(x, y),
            )
        }
        val drift1 = Offset(w * (0.86f - 0.10f * kotlin.math.sin(p1 * Math.PI.toFloat())), h * (0.10f + 0.07f * kotlin.math.cos(p1 * Math.PI.toFloat())))
        val drift2 = Offset(w * (0.06f + 0.08f * kotlin.math.cos(p2 * Math.PI.toFloat())), h * (0.88f - 0.08f * kotlin.math.sin(p2 * Math.PI.toFloat())))
        val drift3 = Offset(w * (0.42f - 0.14f * kotlin.math.sin(p3 * Math.PI.toFloat())), h * (0.42f + 0.18f * kotlin.math.cos(p3 * Math.PI.toFloat())))
        blob(drift1.x, drift1.y, h * 0.72f, Honey.glow, 0.115f)
        blob(drift2.x, drift2.y, h * 0.66f, Honey.teal, 0.10f)
        blob(drift3.x, drift3.y, h * 0.80f, Honey.violet, 0.075f)

        // honeycomb mesh — flat-top hexes tiled across the stage
        val r = 34.dp.toPx()
        val hw = r * kotlin.math.sqrt(3f) // hex width
        val vh = r * 1.5f                 // vertical stride
        val stroke = 1.dp.toPx()
        var row = 0
        var y = -r
        while (y < h + r) {
            val offset = if (row % 2 == 0) 0f else hw / 2f
            var x = -r + offset
            while (x < w + r) {
                drawHexAt(x, y, r, Honey.primary.copy(alpha = meshA), stroke)
                x += hw
            }
            y += vh
            row++
        }

        // obsidian vignette — edges sink into darkness
        drawRect(
            brush = Brush.radialGradient(
                listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                center = Offset(w * 0.5f, h * 0.42f),
                radius = maxOf(w, h) * 0.85f,
            ),
            size = size,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHexAt(
    cx: Float, cy: Float, r: Float, color: Color, stroke: Float,
) {
    val path = androidx.compose.ui.graphics.Path()
    for (i in 0..6) {
        val ang = Math.PI / 3.0 * i + Math.PI / 6.0
        val px = cx + (r * kotlin.math.cos(ang)).toFloat()
        val py = cy + (r * kotlin.math.sin(ang)).toFloat()
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
    drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
}

// ------------------------------------------------------------- glass card

/// The obsidian glass panel: vertical glass fill, a light-catching gradient
/// edge, a specular line under the top rim and a real drop shadow. On hover
/// it wakes: edge brightens, glow warms.
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = CardShape,
    content: @Composable () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    val wake by animateFloatAsState(if (hovered) 1f else 0.55f, tween(260), label = "wake")
    Box(
        modifier
            .hoverLine { hovered = it }
            .shadow(
                elevation = 16.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.60f),
                spotColor = Honey.glow.copy(alpha = 0.10f),
            )
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(Honey.surfaceHi.copy(alpha = 0.94f), Honey.surface.copy(alpha = 0.88f)),
                ),
            )
            .border(
                1.dp,
                Brush.verticalGradient(
                    listOf(
                        Honey.glassEdgeTop.copy(alpha = 0.65f + 0.35f * wake),
                        Honey.glassEdgeBot.copy(alpha = 1f),
                    ),
                ),
                shape,
            )
            .drawBehind {
                // specular line hugging the top rim
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.10f * wake), Color.Transparent),
                        startY = 0f,
                        endY = size.height * 0.35f,
                    ),
                    topLeft = Offset(0f, 0f),
                    size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.35f),
                )
            }
            .padding(16.dp),
    ) { content() }
}

/// The old honey card name — now glass.
@Composable
fun HoneyCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) = GlassCard(modifier, content = content)

// ------------------------------------------------------------- info rows

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

// ------------------------------------------------------------- buttons

/// The molten button. Filled = vertical honey gradient with a top specular
/// edge, warm spot-shadow and press physics (it physically sinks). Ghost =
/// obsidian glass that catches gold on hover.
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
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val live = enabled && !busy
    val scale by animateFloatAsState(
        if (pressed && live) 0.962f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "btnPress",
    )
    val hover = remember { mutableStateOf(false) }

    val alpha = if (enabled) 1f else 0.45f
    val btnShape = BtnShape

    val fill: Brush = when {
        danger -> Brush.verticalGradient(
            listOf(Honey.danger.copy(alpha = 0.20f * alpha), Honey.danger.copy(alpha = 0.10f * alpha)),
        )
        filled -> Brush.verticalGradient(
            listOf(
                Honey.goldTop.copy(alpha = alpha),
                Honey.goldMid.copy(alpha = alpha),
                Honey.goldBot.copy(alpha = alpha),
            ),
        )
        else -> Brush.verticalGradient(
            listOf(
                Honey.surfaceHi.copy(alpha = 0.95f * alpha),
                Honey.surface.copy(alpha = 0.90f * alpha),
            ),
        )
    }
    val fg = when {
        filled && !danger -> Honey.onGold.copy(alpha = alpha)
        danger -> Honey.danger.copy(alpha = alpha)
        else -> Honey.ink.copy(alpha = alpha)
    }
    val edge: Brush = when {
        filled && !danger -> Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.55f * alpha), Honey.goldBot.copy(alpha = 0.0f * alpha)),
        )
        danger -> Brush.verticalGradient(
            listOf(Honey.danger.copy(alpha = 0.55f * alpha), Honey.danger.copy(alpha = 0.12f * alpha)),
        )
        else -> Brush.verticalGradient(
            listOf(
                Honey.divider.copy(alpha = alpha),
                if (hover.value) Honey.primary.copy(alpha = 0.35f * alpha) else Honey.glassEdgeBot,
            ),
        )
    }

    Box(
        modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .hoverLine { hover.value = it }
            .height(48.dp)
            .shadow(
                elevation = if (filled && !danger && live) 16.dp else 8.dp,
                shape = btnShape,
                ambientColor = Color.Black.copy(alpha = 0.55f),
                spotColor = if (filled && !danger) Honey.glow.copy(alpha = 0.35f * alpha) else Color.Transparent,
            )
            .clip(btnShape)
            .background(fill)
            .border(1.dp, edge, btnShape)
            .drawBehind {
                // molten top light
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = if (filled && !danger) 0.30f * alpha else 0.08f), Color.Transparent),
                        startY = 0f,
                        endY = size.height * 0.45f,
                    ),
                    size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.45f),
                )
            }
            .then(
                if (live) {
                    Modifier.clickable(interactionSource = interaction, indication = null) { onClick() }
                } else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (busy) {
                Spinner(size = 17.dp, color = fg)
                Spacer(Modifier.width(9.dp))
            }
            Text(title, style = T.body.copy(color = fg, fontWeight = FontWeight.Bold, fontSize = 15.sp))
        }
    }
}

/// Small glowing spinner used everywhere — a 270° honey arc chasing itself.
@Composable
fun Spinner(size: androidx.compose.ui.unit.Dp, color: Color) {
    val t = rememberInfiniteTransition(label = "spinner")
    val spin by t.animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spin",
    )
    Canvas(Modifier.size(size)) {
        rotate(spin) {
            drawArc(
                color = color,
                startAngle = 0f,
                sweepAngle = 270f,
                useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 2.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                ),
            )
        }
    }
}

// ------------------------------------------------------------- chips & nav

/// The tab pill — a glass chip that lights from within when selected.
@Composable
fun NavItem(label: String, icon: String, selected: Boolean, onClick: () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    val bgAlpha by animateFloatAsState(
        if (selected) 0.20f else if (hovered) 0.09f else 0f,
        tween(220), label = "navBg",
    )
    val fg = if (selected) Honey.primary else Honey.muted
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .hoverLine { hovered = it }
            .clip(ChipShape)
            .background(
                Brush.horizontalGradient(
                    listOf(Honey.primary.copy(alpha = bgAlpha), Honey.primary.copy(alpha = bgAlpha * 0.35f)),
                ),
            )
            .border(
                1.dp,
                if (selected) Honey.primary.copy(alpha = 0.30f) else Color.Transparent,
                ChipShape,
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // the icon tile — a tiny glass square that glows when selected
        Box(
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(
                    if (selected) Brush.verticalGradient(
                        listOf(Honey.goldTop.copy(alpha = 0.30f), Honey.goldBot.copy(alpha = 0.22f)),
                    ) else Brush.verticalGradient(listOf(Honey.surfaceHi, Honey.surface)),
                )
                .border(
                    1.dp,
                    if (selected) Honey.primary.copy(alpha = 0.55f) else Honey.divider,
                    RoundedCornerShape(9.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(icon, fontSize = 14.sp, color = fg)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            style = T.body.copy(
                color = fg,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                shadow = if (selected) Shadow(Honey.glow.copy(alpha = 0.6f), blurRadius = 14f) else null,
            ),
        )
    }
}

/// Section header with the molten dot and a hairline that fades out.
@Composable
fun SectionHeader(title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp)) {
        Box(
            Modifier
                .size(8.dp)
                .shadow(6.dp, RoundedCornerShape(4.dp), spotColor = Honey.glow)
                .background(Brush.verticalGradient(listOf(Honey.goldTop, Honey.goldBot)), RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.width(9.dp))
        Text(title, style = T.accent)
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Honey.divider, Color.Transparent),
                    ),
                ),
        )
    }
}

/// A small glass pill with a glowing dot — used for latency, plan, status.
@Composable
fun GlassChip(text: String, color: Color = Honey.primary) {
    Box(
        Modifier
            .clip(ChipShape)
            .background(Honey.track.copy(alpha = 0.85f))
            .border(1.dp, color.copy(alpha = 0.28f), ChipShape)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(text, style = T.chip.copy(color = color, fontSize = 11.5.sp))
    }
}

@Composable
fun LinkText(label: String, url: String) {
    var hovered by remember { mutableStateOf(false) }
    Text(
        label,
        style = T.body.copy(
            color = if (hovered) Honey.goldTop else Honey.primary,
            fontSize = 13.sp,
            shadow = if (hovered) Shadow(Honey.glow.copy(alpha = 0.5f), blurRadius = 12f) else null,
        ),
        modifier = Modifier
            .hoverLine { hovered = it }
            .clickable {
                try {
                    java.awt.Desktop.getDesktop().browse(java.net.URI(url))
                } catch (_: Exception) {}
            },
    )
}
