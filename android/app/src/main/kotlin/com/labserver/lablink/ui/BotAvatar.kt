package com.labserver.lablink.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Colour of the default bot (and of the app icon). */
val OrbIndigo = Color(0xFF4B3FD8)

/**
 * The orb's ten moves.
 *
 * What it is doing (set by the chat, see [orbMood]):
 *  1. **Idle**: still, with a blink now and then.
 *  2. **Thinking**: eyes drift up and around, the body breathes, a glow pulses, light sweeps round inside.
 *  3. **Working** (a tool, e.g. searching the web): eyes scan left and right as if reading.
 *  4. **Answering**: happy half-closed eyes, a light bounce, a squash-and-stretch each time new text arrives (`talk`).
 *  9. **Sleepy**: a big orb nobody has touched for a while dozes off: slit eyes, slow breathing, little z's rising.
 * 10. **Error**: a quick shake of the head, eyes squinting ("no").
 *
 * What touching it does (a hero orb with an [OrbController]):
 *  5. **Squint-smile**: while pressed the eyes turn into happy arcs, cheeks blush, the body squashes a little.
 *  6. **Roll**: a full somersault, the light on its surface turning with it.
 *  7. **Jelly**: wobbles tall and flat like a jelly, on a loose spring.
 *  8. **Hop**: crouches, jumps and lands with a squash.
 * A tap plays 6, 7 or 8 in turn; touching a sleeping orb wakes it with a hop.
 */
enum class OrbMood { Idle, Thinking, Working, Answering, Sleepy, Error }

enum class OrbFx { Roll, Jelly, Hop }

/** Picks the mood of a reply that is being produced; a failed one shakes its head. */
fun orbMood(streaming: Boolean, working: Boolean, hasText: Boolean, failed: Boolean = false): OrbMood = when {
    failed && !streaming -> OrbMood.Error
    !streaming -> OrbMood.Idle
    working -> OrbMood.Working
    hasText -> OrbMood.Answering
    else -> OrbMood.Thinking
}

/** Holds the touch animations of one orb; made with [rememberOrbController] and given to [BotAvatar]. */
class OrbController(private val scope: CoroutineScope) {
    internal val smile = Animatable(0f)
    internal val squash = Animatable(0f)
    internal val roll = Animatable(0f)       // degrees
    internal val jelly = Animatable(0f)
    internal val hop = Animatable(0f)        // 1 = at the top of the jump
    internal val crouch = Animatable(0f)
    internal val sleep = Animatable(0f)
    internal var lastTouch by mutableLongStateOf(0L)
    private var turn = 0
    private val jobs = HashMap<String, Job>()
    private val order = listOf(OrbFx.Jelly, OrbFx.Hop, OrbFx.Roll, OrbFx.Jelly, OrbFx.Roll, OrbFx.Hop)

    private fun run(key: String, block: suspend () -> Unit) {
        jobs[key]?.cancel()
        jobs[key] = scope.launch { block() }
    }

    private fun touched() { lastTouch = System.nanoTime() }

    fun pressDown() {
        touched()
        val waking = sleep.value > 0.3f
        run("smile") { smile.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 500f)) }
        run("squash") { squash.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 600f)) }
        if (waking) play(OrbFx.Hop)
    }

    fun pressUp() {
        run("smile") { smile.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 180f)) }
        run("squash") { squash.animateTo(0f, spring(dampingRatio = 0.3f, stiffness = 520f)) }
    }

    fun tap() {
        touched()
        play(order[turn++ % order.size])
    }

    /**
     * Something on its own, now and then, as a pet would: a wobble, a hop, a roll or a smile. It does not count as a touch,
     * so an orb left alone still dozes off in the end.
     */
    fun fidget() {
        when (kotlin.random.Random.nextInt(5)) {
            0 -> play(OrbFx.Jelly, byUser = false)
            1 -> play(OrbFx.Hop, byUser = false)
            2 -> play(OrbFx.Roll, byUser = false)
            else -> run("smile") {
                smile.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 300f))
                kotlinx.coroutines.delay(900)
                smile.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 180f))
            }
        }
    }

    fun play(fx: OrbFx, byUser: Boolean = true) {
        if (byUser) touched()
        when (fx) {
            OrbFx.Roll -> run("roll") {
                roll.snapTo(0f)
                scope.launch { hop.animateTo(0.45f, tween(260, easing = FastOutSlowInEasing)); hop.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = 500f)) }
                roll.animateTo(360f, tween(780, easing = FastOutSlowInEasing))
                roll.snapTo(0f)
            }
            OrbFx.Jelly -> run("jelly") {
                jelly.snapTo(1f)
                jelly.animateTo(0f, spring(dampingRatio = 0.22f, stiffness = 260f))
            }
            OrbFx.Hop -> run("hop") {
                crouch.animateTo(1f, tween(110))
                crouch.animateTo(0f, tween(90))
                hop.animateTo(1f, tween(230, easing = FastOutSlowInEasing))
                hop.animateTo(0f, tween(210))
                crouch.snapTo(1f)
                crouch.animateTo(0f, spring(dampingRatio = 0.3f, stiffness = 480f))
            }
        }
    }

    internal suspend fun fallAsleep() = sleep.animateTo(1f, tween(1600))
    internal suspend fun wake() = sleep.animateTo(0f, tween(300))
}

@Composable
fun rememberOrbController(): OrbController {
    val scope = rememberCoroutineScope()
    return remember { OrbController(scope) }
}

private class OrbPose(
    val gazeX: Float = 0f, // eye offset, in orb radii
    val gazeY: Float = 0f,
    val open: Float = 1f, // eye height factor (blinks, squints)
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val lift: Float = 0f, // vertical body offset, in orb radii (negative = up)
    val shiftX: Float = 0f, // sideways body offset, in orb radii
    val rotation: Float = 0f, // degrees
    val smile: Float = 0f, // 0 = pill eyes, 1 = happy arcs, with blushing cheeks
    val sleep: Float = 0f, // 0..1: little z's float up
    val zPhase: Float = 0f,
    val glow: Float = 0f, // halo alpha
    val shimmer: Float = 0f, // strength of the light sweeping round inside the orb ("processing")
    val shimmerAngle: Float = 0f, // radians
)

/** The orb: a flat gradient sphere with two slanted pill eyes; [pose] moves the eyes and body. */
private fun DrawScope.orb(center: Offset, r: Float, color: Color, pose: OrbPose) {
    val c = Offset(center.x + pose.shiftX * r, center.y + pose.lift * r)
    if (pose.glow > 0.01f) {
        drawCircle(
            Brush.radialGradient(listOf(color.copy(alpha = pose.glow), Color.Transparent), c, r * 1.35f),
            radius = r * 1.35f, center = c,
        )
    }
    withTransform({
        scale(pose.scaleX, pose.scaleY, pivot = Offset(c.x, c.y + r))
        rotate(pose.rotation, pivot = c)
    }) {
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
            if (pose.smile < 0.98f) {
                rotate(10f, pivot = ec) {
                    drawRoundRect(Color.White.copy(alpha = 1f - pose.smile), Offset(ec.x - w / 2, ec.y - h / 2), Size(w, h), CornerRadius(w / 2))
                }
            }
            if (pose.smile > 0.02f) {
                // a happy eye: an arch, ^
                val aw = d * 0.085f
                val path = Path().apply {
                    moveTo(ec.x - aw, ec.y + aw * 0.55f)
                    quadraticTo(ec.x, ec.y - aw * 1.1f, ec.x + aw, ec.y + aw * 0.55f)
                }
                drawPath(path, Color.White.copy(alpha = pose.smile), style = Stroke(width = w * 0.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawOval(Color(0xFFFF8FB3).copy(alpha = 0.38f * pose.smile), Offset(ec.x - aw * 1.15f, ec.y + d * 0.1f), Size(aw * 2.3f, aw * 1.2f))
            }
        }
    }
    // dozing: z's drift up and to the right, fading as they go
    if (pose.sleep > 0.02f) {
        for (i in 0 until 3) {
            val t = ((pose.zPhase + i / 3f) % 1f)
            val s = r * (0.10f + 0.12f * t)
            val x = c.x + r * (0.62f + 0.26f * t)
            val y = c.y - r * (0.50f + 0.62f * t)
            val a = pose.sleep * (if (t < 0.15f) t / 0.15f else 1f - (t - 0.15f) / 0.85f).coerceIn(0f, 1f)
            val z = Path().apply { moveTo(x, y); lineTo(x + s, y); lineTo(x, y + s); lineTo(x + s, y + s) }
            drawPath(z, Color.White.copy(alpha = a * 0.9f), style = Stroke(width = r * 0.045f, cap = StrokeCap.Round, join = StrokeJoin.Round))
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
 * With a [controller] the orb also answers touch and dozes off when left alone (see [OrbMood]).
 */
@Composable
fun BotAvatar(
    size: Dp,
    modifier: Modifier = Modifier,
    color: Color = OrbIndigo,
    mood: OrbMood = OrbMood.Idle,
    talk: Int = 0,
    alive: Boolean = false,
    controller: OrbController? = null,
) {
    if (mood == OrbMood.Idle && !alive && controller == null) {
        Canvas(modifier.size(size)) { orb(Offset(this.size.width / 2, this.size.height / 2), this.size.minDimension * 0.44f, color, OrbPose()) }
        return
    }

    // how much of each mood is mixed in; springs make the hand-over between moods smooth
    val think by animateFloatAsState(if (mood == OrbMood.Thinking) 1f else 0f, spring(stiffness = 120f), label = "think")
    val work by animateFloatAsState(if (mood == OrbMood.Working) 1f else 0f, spring(stiffness = 120f), label = "work")
    val answer by animateFloatAsState(if (mood == OrbMood.Answering) 1f else 0f, spring(stiffness = 120f), label = "answer")
    val err by animateFloatAsState(if (mood == OrbMood.Error) 1f else 0f, spring(stiffness = 200f), label = "error")
    val dozeMood by animateFloatAsState(if (mood == OrbMood.Sleepy) 1f else 0f, spring(stiffness = 60f), label = "doze")

    val loop = rememberInfiniteTransition(label = "orb")
    val phase by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "phase")
    val slow by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "idle blink")
    val zz by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "z")
    val glowPulse by loop.animateFloat(0.55f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "glow")

    // squash-and-stretch kick each time more of the answer arrives
    val kick = remember { Animatable(0f) }
    LaunchedEffect(talk) {
        if (mood == OrbMood.Answering) {
            kick.snapTo(1f)
            kick.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 700f))
        }
    }
    // a shake of the head, once, when something went wrong
    val shake = remember { Animatable(0f) }
    LaunchedEffect(mood) {
        if (mood == OrbMood.Error) {
            shake.snapTo(1f)
            shake.animateTo(0f, tween(950, easing = LinearEasing))
        }
    }
    // an orb that is left alone dozes off; any touch (or a change of mood) wakes it
    LaunchedEffect(controller?.lastTouch, mood) {
        val c = controller ?: return@LaunchedEffect
        if (mood == OrbMood.Idle) {
            delay(20_000)
            c.fallAsleep()
        } else {
            c.wake()
        }
    }
    LaunchedEffect(controller?.lastTouch) { controller?.let { if (it.sleep.value > 0f) it.wake() } }
    // left alone and awake, it does something every so often, without being touched
    LaunchedEffect(controller, mood) {
        val c = controller ?: return@LaunchedEffect
        if (mood != OrbMood.Idle) return@LaunchedEffect
        while (true) {
            delay(kotlin.random.Random.nextLong(5_000, 12_000))
            if (c.sleep.value < 0.3f) c.fidget()
        }
    }

    val tau = (2 * PI).toFloat()
    Canvas(modifier.size(size)) {
        val a = phase * tau
        val c = controller
        val smile = c?.smile?.value ?: 0f
        val squash = c?.squash?.value ?: 0f
        val jelly = c?.jelly?.value ?: 0f
        val hopUp = c?.hop?.value ?: 0f
        val crouch = c?.crouch?.value ?: 0f
        val sleep = maxOf(c?.sleep?.value ?: 0f, dozeMood)
        val shakeX = sin((1f - shake.value) * tau * 3.5f) * shake.value * 0.15f
        val breathe = sin(slow * tau * 2f)
        val pose = OrbPose(
            // thinking: a slow loop up and around; working: brisk left-right scan, slightly down (reading)
            gazeX = think * 0.16f * sin(a) + work * 0.17f * sin(a * 2),
            gazeY = think * (-0.19f + 0.05f * cos(a * 2)) + work * 0.06f - answer * 0.03f + sleep * 0.08f,
            // thinking squints a little; answering is a happy, half-closed look; dozing is nearly shut; "no" squints hard
            open = (1f - 0.15f * think - 0.1f * work - 0.3f * answer - 0.45f * err - 0.82f * sleep) *
                (if (think > 0.5f || work > 0.5f) blink(phase) else if (sleep > 0.5f) 1f else blink(slow)),
            scaleX = 1f + 0.04f * think * sin(a) + 0.09f * kick.value +
                0.07f * squash + 0.12f * jelly + 0.14f * crouch - 0.05f * hopUp - 0.02f * sleep * breathe,
            scaleY = 1f + 0.04f * think * sin(a) - 0.09f * kick.value - 0.17f * jelly - 0.07f * squash - 0.16f * crouch + 0.1f * hopUp + 0.035f * sleep * breathe,
            lift = -answer * 0.08f * abs(sin(a * 3)) - think * 0.03f * (1 + sin(a)) - hopUp * 0.34f + sleep * 0.04f,
            shiftX = shakeX,
            rotation = (c?.roll?.value ?: 0f) + shakeX * 40f,
            smile = smile,
            sleep = sleep,
            zPhase = zz,
            glow = (0.55f * think + 0.4f * work + 0.15f * answer + 0.18f * smile) * glowPulse,
            shimmer = 0.9f * think + 0.7f * work,
            shimmerAngle = a * (if (work > think) 2f else 1f) - (PI / 2).toFloat(),
        )
        orb(Offset(this.size.width / 2, this.size.height / 2), this.size.minDimension * 0.44f, color, pose)
    }
}

/**
 * The big orb for hero moments (empty chat, bot editor): always alive, and it plays with you: press and hold for a smile,
 * tap for a roll, a jelly wobble or a hop; leave it alone and it dozes off.
 */
@Composable
fun BotHero(size: Dp, modifier: Modifier = Modifier, color: Color = OrbIndigo, mood: OrbMood = OrbMood.Idle, interactive: Boolean = true) {
    val controller = rememberOrbController()
    if (!interactive) {
        BotAvatar(size, modifier, color, mood, alive = true, controller = controller)
        return
    }
    BotAvatar(
        size,
        modifier.pointerInput(controller) {
            detectTapGestures(
                onPress = { controller.pressDown(); tryAwaitRelease(); controller.pressUp() },
                onTap = { controller.tap() },
            )
        },
        color, mood, alive = true, controller = controller,
    )
}
