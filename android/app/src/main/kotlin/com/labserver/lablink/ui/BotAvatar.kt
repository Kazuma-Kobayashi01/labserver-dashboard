package com.labserver.lablink.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Colour of the default bot (and of the app icon). */
val OrbIndigo = Color(0xFF4B3FD8)

/** The orbit and its satellite keep one colour for every bot. */
private val OrbitAmber = Color(0xFFFFB547)

/** 1 = eyes open; squashes briefly while [thinking], like a blink. */
@Composable
private fun eyeOpen(thinking: Boolean): Float {
    val v by rememberInfiniteTransition(label = "blink").animateFloat(
        initialValue = 1f, targetValue = if (thinking) 0.3f else 1f,
        animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse),
        label = "eyes",
    )
    return v
}

/** A flat gradient orb with two slanted pill eyes, centred at [c] with radius [r]. */
private fun DrawScope.orb(c: Offset, r: Float, color: Color, open: Float) {
    drawCircle(
        Brush.linearGradient(
            colors = listOf(lerp(color, Color.White, 0.30f), lerp(color, Color.Black, 0.30f)),
            start = Offset(c.x - r * 0.7f, c.y - r * 0.8f),
            end = Offset(c.x + r * 0.7f, c.y + r * 0.9f),
        ),
        radius = r, center = c,
    )
    val d = r * 2
    val w = d * 0.115f
    val h = d * 0.33f * open
    for (fx in listOf(0.346f, 0.635f)) {
        val ec = Offset(c.x - r + d * fx, c.y - r + d * 0.423f)
        rotate(10f, pivot = ec) {
            drawRoundRect(Color.White, Offset(ec.x - w / 2, ec.y - h / 2), Size(w, h), CornerRadius(w / 2))
        }
    }
}

/** The bot's face: used for avatars in lists, the top bar and next to replies. */
@Composable
fun BotAvatar(size: Dp, modifier: Modifier = Modifier, color: Color = OrbIndigo, thinking: Boolean = false) {
    val open = eyeOpen(thinking)
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        orb(Offset(s / 2, s / 2), s / 2, color, open)
    }
}

/**
 * The full logo, for hero moments (empty chat, bot editor): the orb with its orbit ring and satellite. The satellite
 * travels round the orbit while the bot is [thinking]. Same art as res/drawable/ic_launcher_foreground.xml.
 */
@Composable
fun BotHero(size: Dp, modifier: Modifier = Modifier, color: Color = OrbIndigo, thinking: Boolean = false) {
    val open = eyeOpen(thinking)
    val spin by rememberInfiniteTransition(label = "orbit").animateFloat(
        initialValue = 0f, targetValue = if (thinking) (2 * PI).toFloat() else 0f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
        label = "satellite",
    )
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val c = Offset(s / 2, s * 0.51f)
        val rx = s * 0.38f
        val ry = s * 0.11f
        val stroke = s * 0.028f
        // parametric orbit; t in (pi, 2pi) is the far side, behind the orb
        fun point(t: Float) = Offset(c.x + rx * cos(t), c.y + ry * sin(t))
        fun arc(from: Float, to: Float) = Path().apply {
            val n = 40
            for (i in 0..n) {
                val p = point(from + (to - from) * i / n)
                if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
        }
        val sat = (0.35f + spin) % (2 * PI).toFloat() // idle: on the near side, to the right
        val satBehind = sat > PI
        rotate(-14f, pivot = c) {
            drawPath(arc(PI.toFloat(), (2 * PI).toFloat()), OrbitAmber.copy(alpha = 0.5f), style = Stroke(stroke, cap = StrokeCap.Round))
            if (satBehind) drawCircle(OrbitAmber.copy(alpha = 0.6f), s * 0.026f, point(sat))
        }
        orb(Offset(s / 2, s / 2), s * 0.26f, color, open)
        rotate(-14f, pivot = c) {
            drawPath(arc(0f, PI.toFloat()), OrbitAmber, style = Stroke(stroke, cap = StrokeCap.Round))
            if (!satBehind) drawCircle(OrbitAmber, s * 0.026f, point(sat))
        }
    }
}
