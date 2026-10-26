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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp

/** Colour of the default bot (and of the app icon). */
val OrbIndigo = Color(0xFF4B3FD8)

/**
 * The bot's face: a glossy round orb with two slanted pill-shaped eyes, tinted with the bot's own colour. The eyes
 * blink gently while the bot is [thinking]. The same shapes are in res/drawable/ic_launcher_foreground.xml.
 */
@Composable
fun BotAvatar(size: Dp, modifier: Modifier = Modifier, color: Color = OrbIndigo, thinking: Boolean = false) {
    // 1 = eyes fully open; the animation squashes them briefly, like a blink
    val open by rememberInfiniteTransition(label = "blink").animateFloat(
        initialValue = 1f, targetValue = if (thinking) 0.35f else 1f,
        animationSpec = infiniteRepeatable(tween(650, easing = LinearEasing), RepeatMode.Reverse),
        label = "eyes",
    )
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val r = s / 2
        val light = lerp(color, Color.White, 0.28f)
        val dark = lerp(color, Color.Black, 0.55f)

        // body
        drawCircle(
            Brush.radialGradient(
                colors = listOf(light, color, dark),
                center = Offset(s * 0.36f, s * 0.30f),
                radius = s * 0.95f,
            ),
            radius = r,
        )
        // soft gloss, top left
        rotate(-35f, pivot = Offset(s * 0.33f, s * 0.24f)) {
            drawOval(Color.White.copy(alpha = 0.22f), Offset(s * 0.20f, s * 0.17f), Size(s * 0.26f, s * 0.15f))
        }
        // rim light, bottom right
        drawArc(
            Color(0xFF5CE1E6).copy(alpha = 0.65f), startAngle = 20f, sweepAngle = 50f, useCenter = false,
            topLeft = Offset(s * 0.07f, s * 0.07f), size = Size(s * 0.86f, s * 0.86f),
            style = Stroke(width = s * 0.025f, cap = StrokeCap.Round),
        )
        // eyes: two slanted pills
        val eyeW = s * 0.11f
        val eyeH = s * 0.30f * open
        val cy = s * 0.50f
        for (cx in listOf(s * 0.38f, s * 0.62f)) {
            rotate(12f, pivot = Offset(cx, cy)) {
                drawRoundRect(
                    Color.White,
                    topLeft = Offset(cx - eyeW / 2, cy - eyeH / 2),
                    size = Size(eyeW, eyeH),
                    cornerRadius = CornerRadius(eyeW / 2),
                )
            }
        }
    }
}
