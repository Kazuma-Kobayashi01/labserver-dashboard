package com.labserver.lablink.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/*
 * The app's own look on top of Material: an aurora of the bot's colour drifting behind everything, and translucent "glass"
 * surfaces with a hairline edge floating on it (as in visionOS, Arc and the newer ChatGPT and Claude apps). Glass is faked
 * with translucency over the aurora (no live blur), which keeps it cheap on every phone.
 */

/** The colour of the bot on screen, for controls that glow in it (the send button). */
val LocalAccent = androidx.compose.runtime.compositionLocalOf { Color(0xFF5546E0) }

/** Whether the app is drawn dark (the theme follows the system). */
@Composable
fun isDark(): Boolean = isSystemInDarkTheme()

/** A colour next to [c] on the colour wheel: the second light of the aurora, so a bot's background is never one flat hue. */
fun neighbour(c: Color, shift: Float = 38f): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(android.graphics.Color.argb(255, (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt()), hsv)
    hsv[0] = (hsv[0] + shift + 360f) % 360f
    hsv[1] = (hsv[1] * 0.9f).coerceIn(0.25f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * The background of every screen: the base colour with three soft lights of [accent] (and its neighbours) drifting slowly.
 * The lights only move while [alive]; drawing them is a handful of radial gradients, no blur.
 */
@Composable
fun AuroraBackground(accent: Color, modifier: Modifier = Modifier, alive: Boolean = true, content: @Composable BoxScope.() -> Unit) {
    val dark = isDark()
    val base = MaterialTheme.colorScheme.surface
    val a by animateColorAsState(accent, tween(700), label = "aurora a")
    val b = remember(a) { neighbour(a, 42f) }
    val c = remember(a) { neighbour(a, -36f) }
    val strength = if (dark) 0.30f else 0.22f
    val t = if (alive) {
        val loop = rememberInfiniteTransition(label = "aurora")
        loop.animateFloat(0f, 1f, infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Restart), label = "drift").value
    } else 0f
    Box(modifier.background(base)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val tau = (Math.PI * 2).toFloat()
            fun light(color: Color, cx: Float, cy: Float, r: Float, alpha: Float) {
                drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)), center = Offset(cx, cy), radius = r), radius = r, center = Offset(cx, cy))
            }
            light(a, w * (0.15f + 0.10f * sin(t * tau)), h * (0.06f + 0.04f * cos(t * tau)), w * 0.95f, strength)
            light(b, w * (0.95f - 0.12f * cos(t * tau)), h * (0.22f + 0.06f * sin(t * tau * 2)), w * 0.80f, strength * 0.8f)
            light(c, w * (0.45f + 0.20f * sin(t * tau + 1.7f)), h * (0.92f + 0.04f * cos(t * tau)), w * 0.9f, strength * 0.55f)
        }
        content()
    }
}

/** The fill of a glass surface: the base colour, mostly opaque, so text stays easy to read over the aurora. */
@Composable
fun glassFill(strong: Boolean = false): Color {
    val s = MaterialTheme.colorScheme.surface
    return if (isDark()) lerp(s, Color.White, 0.06f).copy(alpha = if (strong) 0.92f else 0.72f)
    else Color.White.copy(alpha = if (strong) 0.92f else 0.68f)
}

/** The hairline round a glass surface: light on its top edge, fading down, as light catching a pane. */
@Composable
fun glassEdge(): Brush = if (isDark()) Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.04f)))
else Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.95f), Color.Black.copy(alpha = 0.06f)))

/** A glass surface: translucent fill, a hairline edge and a soft coloured shadow. */
@Composable
fun Modifier.glass(shape: Shape, elevation: Dp = 10.dp, strong: Boolean = false, glow: Color = Color.Black): Modifier {
    val fill = glassFill(strong)
    val edge = glassEdge()
    val shadowColor = if (isDark()) Color.Black.copy(alpha = 0.5f) else glow.copy(alpha = 0.16f)
    return this
        .shadow(elevation, shape, clip = false, ambientColor = shadowColor, spotColor = shadowColor)
        .clip(shape)
        .background(fill)
        .border(1.dp, edge, shape)
}

/** A round glass button for the floating bars. [dot] puts a small status light on its corner. */
@Composable
fun GlassIconButton(icon: ImageVector, contentDescription: String?, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, dot: Color? = null, size: Dp = 44.dp) {
    Box(modifier.size(size)) {
        Box(
            Modifier.size(size).pressable(if (enabled) onClick else ({})).glass(CircleShape, elevation = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon, contentDescription = contentDescription, modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.88f else 0.32f),
            )
        }
        // a status light on the corner, ringed in the background colour so it reads on any glass
        if (dot != null) Box(
            Modifier.align(Alignment.TopEnd).size(13.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.size(8.dp).clip(CircleShape).background(dot)) }
    }
}

/** Text painted with a gradient of [colors] (for greetings and hero titles). */
@Composable
fun GradientText(text: String, colors: List<Color>, style: TextStyle, modifier: Modifier = Modifier, textAlign: androidx.compose.ui.text.style.TextAlign? = null) {
    Text(text, style = style.merge(TextStyle(brush = Brush.linearGradient(colors))), modifier = modifier, textAlign = textAlign)
}

/** The bubble fill for the user's own messages: a vivid gradient of the bot's colour. */
fun bubbleBrush(c: Color, dark: Boolean): Brush {
    val from = if (dark) lerp(c, Color.Black, 0.10f) else c
    val to = neighbour(c, 24f).let { if (dark) lerp(it, Color.Black, 0.18f) else it }
    return Brush.linearGradient(listOf(from, to))
}

/** Text on a bot-coloured fill: white unless the colour is very light. */
fun onAccent(c: Color): Color = if ((0.299f * c.red + 0.587f * c.green + 0.114f * c.blue) > 0.72f) Color(0xFF1B1B21) else Color.White

/** Every bottom sheet: a white (or deep) card with big top corners and a slim handle. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LabSheet(onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val dark = isDark()
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = if (dark) lerp(MaterialTheme.colorScheme.surface, Color.White, 0.05f) else Color.White,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        dragHandle = {
            Box(
                androidx.compose.ui.Modifier.padding(top = 10.dp, bottom = 8.dp).size(width = 38.dp, height = 4.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)),
            )
        },
        content = content,
    )
}
