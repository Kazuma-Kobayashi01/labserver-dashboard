package com.labserver.lablink.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

/**
 * Lab bot's round, smiling face. Placeholder art drawn in code: when the real icon exists, replace the body of this
 * composable with it (and the two ic_launcher drawables).
 */
@Composable
fun BotAvatar(size: Dp, modifier: Modifier = Modifier) {
    val body = MaterialTheme.colorScheme.primary
    val antenna = MaterialTheme.colorScheme.tertiary
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val ink = Color(0xFF2B2B3A)
        val cx = s / 2
        drawCircle(body, radius = s / 2)
        drawCircle(Color.White, radius = s * 0.30f, center = Offset(cx, s * 0.56f))
        // eyes
        drawCircle(ink, s * 0.045f, Offset(cx - s * 0.12f, s * 0.54f))
        drawCircle(ink, s * 0.045f, Offset(cx + s * 0.12f, s * 0.54f))
        // cheeks
        val blush = Color(0xFFFF8FB1).copy(alpha = 0.5f)
        drawCircle(blush, s * 0.045f, Offset(cx - s * 0.19f, s * 0.62f))
        drawCircle(blush, s * 0.045f, Offset(cx + s * 0.19f, s * 0.62f))
        // smile
        drawArc(
            ink, startAngle = 20f, sweepAngle = 140f, useCenter = false,
            topLeft = Offset(cx - s * 0.07f, s * 0.575f), size = Size(s * 0.14f, s * 0.10f),
            style = Stroke(width = s * 0.022f, cap = StrokeCap.Round),
        )
        // antenna
        drawLine(antenna, Offset(cx, s * 0.26f), Offset(cx, s * 0.17f), strokeWidth = s * 0.03f, cap = StrokeCap.Round)
        drawCircle(antenna, s * 0.045f, Offset(cx, s * 0.15f))
    }
}
