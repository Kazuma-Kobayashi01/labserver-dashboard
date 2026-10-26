package com.labserver.lablink.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Colour of the default bot (and of the app icon). */
val OrbIndigo = Color(0xFF4B3FD8)

/**
 * What the orb is doing. The motion language:
 * - [Idle]: still; with `alive` it blinks now and then.
 * - [Thinking]: eyes drift up and around (pondering), the body breathes and a soft glow pulses.
 * - [Working] (using a tool, e.g. searching the web): eyes scan left and right, as if reading.
 * - [Answering]: eyes front and happy, a light bounce, and a squash-and-stretch each time new text arrives (`talk`).
 */
enum class OrbMood { Idle, Thinking, Working, Answering }

/** Picks the mood of a reply that is being produced. */
fun orbMood(streaming: Boolean, working: Boolean, hasText: Boolean): OrbMood = when {
    !streaming -> OrbMood.Idle
    working -> OrbMood.Working
    hasText -> OrbMood.Answering
    else -> OrbMood.Thinking
}

private class OrbPose(
    val gazeX: Float = 0f, // eye offset, in orb radii
    val gazeY: Float = 0f,
    val open: Float = 1f, // eye height factor (blinks, squints)
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val lift: Float = 0f, // vertical body offset, in orb radii (negative = up)
    val glow: Float = 0f, // halo alpha
    val shimmer: Float = 0f, // strength of the light sweeping round inside the orb ("processing")
    val shimmerAngle: Float = 0f, // radians
)

/** The orb: a flat gradient sphere with two slanted pill eyes; [pose] moves the eyes and body. */
private fun DrawScope.orb(center: Offset, r: Float, color: Color, pose: OrbPose) {
    if (pose.glow > 0.01f) {
        drawCircle(
            Brush.radialGradient(listOf(color.copy(alpha = pose.glow), Color.Transparent), center, r * 1.35f),
            radius = r * 1.35f, center = center,
        )
    }
    val c = Offset(center.x, center.y + pose.lift * r)
    withTransform({ scale(pose.scaleX, pose.scaleY, pivot = Offset(c.x, c.y + r)) }) {
        drawCircle(
            Brush.linearGradient(
                colors = listOf(lerp(color, Color.White, 0.30f), lerp(color, Color.Black, 0.30f)),
                start = Offset(c.x - r * 0.7f, c.y - r * 0.8f),
                end = Offset(c.x + r * 0.7f, c.y + r * 0.9f),
            ),
            radius = r, center = c,
        )
        if (pose.shimmer > 0.01f) {
            val sp = Offset(c.x + r * 0.5f * cos(pose.shimmerAngle), c.y + r * 0.5f * sin(pose.shimmerAngle))
            drawCircle(
                Brush.radialGradient(listOf(Color.White.copy(alpha = 0.42f * pose.shimmer), Color.Transparent), sp, r * 0.75f),
                radius = r, center = c,
            )
        }
        val d = r * 2
        val w = d * 0.115f
        val h = d * 0.33f * pose.open.coerceIn(0.08f, 1.1f)
        for (fx in listOf(0.346f, 0.635f)) {
            val ec = Offset(c.x - r + d * fx + pose.gazeX * r, c.y - r + d * 0.423f + pose.gazeY * r)
            rotate(10f, pivot = ec) {
                drawRoundRect(Color.White, Offset(ec.x - w / 2, ec.y - h / 2), Size(w, h), CornerRadius(w / 2))
            }
        }
    }
}

/** A quick blink near the end of each cycle: 1 = open. */
private fun blink(p: Float): Float {
    val d = abs(p - 0.96f)
    return if (d < 0.03f) 1f - 0.9f * (1f - d / 0.03f) else 1f
}

/**
 * The bot's face, used everywhere (top bar, replies, lists, empty state, editor). Each bot has its own [color].
 * [talk] should change as the reply grows (e.g. its length / 16): every change gives the orb a little "spoken" wobble.
 * [alive] lets an idle orb blink now and then; keep it off for avatars in lists, so idle screens draw no frames.
 */
@Composable
fun BotAvatar(
    size: Dp,
    modifier: Modifier = Modifier,
    color: Color = OrbIndigo,
    mood: OrbMood = OrbMood.Idle,
    talk: Int = 0,
    alive: Boolean = false,
) {
    if (mood == OrbMood.Idle && !alive) {
        Canvas(modifier.size(size)) { orb(Offset(this.size.width / 2, this.size.height / 2), this.size.minDimension * 0.44f, color, OrbPose()) }
        return
    }

    // how much of each mood is mixed in; springs make the hand-over between moods smooth
    val think by animateFloatAsState(if (mood == OrbMood.Thinking) 1f else 0f, spring(stiffness = 120f), label = "think")
    val work by animateFloatAsState(if (mood == OrbMood.Working) 1f else 0f, spring(stiffness = 120f), label = "work")
    val answer by animateFloatAsState(if (mood == OrbMood.Answering) 1f else 0f, spring(stiffness = 120f), label = "answer")

    val loop = rememberInfiniteTransition(label = "orb")
    val phase by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "phase")
    val slow by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "idle blink")
    val glowPulse by loop.animateFloat(0.55f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "glow")

    // squash-and-stretch kick each time more of the answer arrives
    val kick = remember { Animatable(0f) }
    LaunchedEffect(talk) {
        if (mood == OrbMood.Answering) {
            kick.snapTo(1f)
            kick.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 700f))
        }
    }

    val tau = (2 * PI).toFloat()
    val a = phase * tau
    val pose = OrbPose(
        // thinking: a slow loop up and around; working: brisk left-right scan, slightly down (reading)
        gazeX = think * 0.16f * sin(a) + work * 0.17f * sin(a * 2),
        gazeY = think * (-0.19f + 0.05f * cos(a * 2)) + work * 0.06f - answer * 0.03f,
        // thinking squints a little; answering is a happy, half-closed look
        open = (1f - 0.15f * think - 0.1f * work - 0.3f * answer) * (if (think > 0.5f || work > 0.5f) blink(phase) else blink(slow)),
        scaleX = 1f + 0.04f * think * sin(a) + 0.09f * kick.value,
        scaleY = 1f + 0.04f * think * sin(a) - 0.09f * kick.value,
        lift = -answer * 0.08f * abs(sin(a * 3)) - think * 0.03f * (1 + sin(a)),
        glow = (0.55f * think + 0.4f * work + 0.15f * answer) * glowPulse,
        shimmer = 0.9f * think + 0.7f * work,
        shimmerAngle = a * (if (work > think) 2f else 1f) - (PI / 2).toFloat(),
    )
    Canvas(modifier.size(size)) { orb(Offset(this.size.width / 2, this.size.height / 2), this.size.minDimension * 0.44f, color, pose) }
}

/** The big orb for hero moments (empty chat, bot editor, drawer header): always alive. */
@Composable
fun BotHero(size: Dp, modifier: Modifier = Modifier, color: Color = OrbIndigo, mood: OrbMood = OrbMood.Idle) {
    BotAvatar(size, modifier, color, mood, alive = true)
}
