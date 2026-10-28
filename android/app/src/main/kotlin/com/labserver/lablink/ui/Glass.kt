package com.labserver.lablink.ui

import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.isSpecified
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
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The app's own look on top of Material: an aurora of the bot's colour drifting behind everything, and translucent "glass"
 * surfaces with a hairline edge floating on it (as in visionOS, Arc and the newer ChatGPT and Claude apps). Glass is faked
 * with translucency over the aurora (no live blur), which keeps it cheap on every phone.
 */

/**
 * What liquid glass on this screen refracts: the recorded layer behind the floating controls (the aurora and the
 * messages). Null where nothing was recorded, and then glass falls back to plain translucency.
 */
val LocalBackdrop = androidx.compose.runtime.compositionLocalOf<com.kyant.backdrop.Backdrop?> { null }

/**
 * Which way the phone leans, in degrees (0 = upright), smoothed. Glass rims catch the light from that side, so tilting
 * the phone moves the shine round the edges as it does on iOS 26. Read only while drawing, so it costs no recomposition.
 */
val LocalTilt = androidx.compose.runtime.compositionLocalOf<androidx.compose.runtime.State<Float>> { androidx.compose.runtime.mutableFloatStateOf(0f) }

@Composable
fun rememberTilt(): androidx.compose.runtime.State<Float> {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val tilt = androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    androidx.compose.runtime.DisposableEffect(owner) {
        val sm = ctx.getSystemService(android.hardware.SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY) ?: sm?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        var smooth = 0f
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                val roll = Math.toDegrees(kotlin.math.atan2(e.values[0].toDouble(), e.values[1].toDouble().coerceAtLeast(0.5))).toFloat()
                smooth += (roll.coerceIn(-60f, 60f) - smooth) * 0.3f
                // only a change of a few degrees moves the shine: sensor jitter must not repaint the glass every frame
                val step = (smooth / 4f).roundToInt() * 4f
                if (step != tilt.floatValue) tilt.floatValue = step
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) = Unit
        }
        // listen only while the app is on screen (a sensor left on in the background drains the battery)
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> if (sensor != null) sm.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> sm?.unregisterListener(listener)
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); sm?.unregisterListener(listener) }
    }
    return tilt
}

/**
 * Apple's Liquid Glass (iOS 26) on Android: what lies behind is bent through a thick lens towards the rim (with the
 * slight colour fringes of real glass), barely blurred so the bending shows, made more vivid, lit along its edge from the
 * side the phone leans to, and lifted by a soft shadow. [press] (0..1) and [touch] make it react to a finger: it swells
 * and stretches towards the finger, bends more, and light gathers where it is pressed. Blur needs Android 12 and the lens
 * Android 13 (each skips itself below that); with no [backdrop] it is the plain translucent [glass].
 */
@Composable
fun Modifier.liquidGlass(
    shape: androidx.compose.foundation.shape.CornerBasedShape,
    backdrop: com.kyant.backdrop.Backdrop? = LocalBackdrop.current,
    tint: Float = 0.12f,
    blur: Dp = 2.dp,
    glow: Color = Color.Black,
    press: () -> Float = { 0f },
    touch: () -> Offset = { Offset.Unspecified },
): Modifier {
    if (backdrop == null || android.os.Build.VERSION.SDK_INT < 31) return glass(shape, elevation = 8.dp, strong = tint > 0.4f, glow = glow)
    val dark = isDark()
    val fill = if (dark) Color(0xFF1C1C24).copy(alpha = (tint + 0.14f).coerceAtMost(1f)) else Color.White.copy(alpha = tint)
    val shadow = if (dark) Color.Black.copy(alpha = 0.4f) else glow.copy(alpha = 0.12f).compositeOverBlack(0.10f)
    val tilt = LocalTilt.current
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(blur.toPx())
            val p = press()
            val m = size.minDimension
            // the lens may reach no deeper than the smallest corner is round (the library's rule)
            val corner = minOf(shape.topStart.toPx(size, this), shape.topEnd.toPx(size, this), shape.bottomStart.toPx(size, this), shape.bottomEnd.toPx(size, this))
            // a thick rim: the bending reaches a third of the way in and grows while pressed
            lens(
                refractionHeight = (m * 0.32f).coerceAtMost(m / 2f).coerceAtMost(corner),
                refractionAmount = (m * (0.6f + 0.35f * p)).coerceAtMost(m),
                depthEffect = true,
                // colour fringing samples the background three times per pixel; the bend reads as glass without it
                chromaticAberration = false,
            )
        },
        highlight = {
            com.kyant.backdrop.highlight.Highlight(
                width = 1.dp, alpha = (0.75f + 0.25f * press()).coerceAtMost(1f),
                style = com.kyant.backdrop.highlight.HighlightStyle.Default(angle = 45f + tilt.value * 1.4f, falloff = 1.2f),
            )
        },
        shadow = { com.kyant.backdrop.shadow.Shadow(radius = 18.dp + 10.dp * press(), color = shadow) },
        layerBlock = {
            // swell under the finger and lean towards it, like a drop of water being pushed
            val p = press()
            val t = touch()
            val dx = if (t.isSpecified && size.width > 0f) ((t.x / size.width) - 0.5f).coerceIn(-1f, 1f) * p else 0f
            val dy = if (t.isSpecified && size.height > 0f) ((t.y / size.height) - 0.5f).coerceIn(-1f, 1f) * p else 0f
            scaleX = 1f + 0.07f * p + kotlin.math.abs(dx) * 0.08f
            scaleY = 1f + 0.07f * p + kotlin.math.abs(dy) * 0.08f
            translationX = dx * size.width * 0.06f
            translationY = dy * size.height * 0.06f
        },
        onDrawSurface = { drawRect(fill) },
        onDrawFront = {
            // light gathers under the finger
            val p = press()
            val t = touch()
            if (p > 0.01f && t.isSpecified) {
                drawCircle(
                    Brush.radialGradient(listOf(Color.White.copy(alpha = 0.32f * p), Color.Transparent), center = t, radius = size.maxDimension * 0.75f),
                    radius = size.maxDimension * 0.75f, center = t,
                )
            }
        },
    )
}

/**
 * A pressable piece of liquid glass: [onClick] on release inside it, with the swell, stretch and light of a finger on it,
 * springing back with a wobble when let go.
 */
@Composable
fun Modifier.liquidButton(
    shape: androidx.compose.foundation.shape.CornerBasedShape,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Float = 0.12f,
    glow: Color = Color.Black,
): Modifier {
    val press = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    val touch = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(Offset.Unspecified) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val click by androidx.compose.runtime.rememberUpdatedState(onClick)
    return this
        .semantics { role = androidx.compose.ui.semantics.Role.Button; if (enabled) onClick { click(); true } else disabled() }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                touch.value = down.position
                scope.launch { press.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 600f)) }
                var released = false
                while (true) {
                    val ev = awaitPointerEvent()
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) { released = true; break }
                    touch.value = c.position
                }
                val inside = touch.value.x in -24f..(size.width + 24f) && touch.value.y in -24f..(size.height + 24f)
                scope.launch { press.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.32f, stiffness = 320f)) }
                if (released && inside) click()
            }
        }
        .liquidGlass(shape, tint = tint, glow = glow, press = { press.value }, touch = { touch.value })
}

private fun Color.compositeOverBlack(alpha: Float): Color = if (this.alpha >= alpha) this else this.copy(alpha = alpha)

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
            Modifier.size(size).liquidButton(CircleShape, onClick, enabled),
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

/** A bot's colour for icons and small text on the background: lifted towards white in dark mode, so deep colours stay legible. */
@Composable
fun accentInk(c: Color): Color = if (isDark()) lerp(c, Color.White, 0.42f) else c
