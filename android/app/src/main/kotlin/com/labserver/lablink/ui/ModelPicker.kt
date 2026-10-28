package com.labserver.lablink.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labserver.lablink.R
import com.labserver.lablink.data.Catalog
import com.labserver.lablink.data.EFFORT_ORDER
import com.labserver.lablink.data.ModelInfo
import com.labserver.lablink.data.ModelPick
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun effortLabel(effort: String): String = when (effort) {
    "none" -> stringResource(R.string.effort_none)
    "low" -> stringResource(R.string.effort_low)
    "medium" -> stringResource(R.string.effort_medium)
    "high" -> stringResource(R.string.effort_high)
    "xhigh" -> stringResource(R.string.effort_xhigh)
    "max" -> stringResource(R.string.effort_max)
    else -> stringResource(R.string.model_effort_auto)
}

/** "GPT-5.6 Sol · High", or "Default" when nothing specific is chosen. */
@Composable
fun pickLabel(catalog: Catalog, pick: ModelPick): String {
    if (pick.isDefault) return stringResource(R.string.model_default)
    val name = catalog.find(pick.provider, pick.model)?.name ?: pick.model
    return if (pick.reasoning.isEmpty()) name else "$name · ${effortLabel(pick.reasoning)}"
}

private val UltraPink = Color(0xFFF06BA8)
private val UltraPurple = Color(0xFF9B5CF6)
private val UltraViolet = Color(0xFFC77DFF)

/** Each thinking level has its colour, from cool and quick to hot and deep; the top one is the ultra gradient. */
private fun effortColor(effort: String, muted: Color): Color = when (effort) {
    "none" -> Color(0xFF8A94A6)
    "low" -> Color(0xFF4FB3E8)
    "medium" -> UltraPink
    "high" -> Color(0xFFFF8A5B)
    "xhigh" -> Color(0xFFE85D75)
    "max" -> UltraPurple
    else -> muted
}

/**
 * Choose a model and how hard it thinks, as in ChatGPT: a card with the level on top, the model under it (tap for the list),
 * and a stepped slider. The top level glows and sparkles. Every change applies at once; "reset" hands the choice back to the
 * bot (or, in the bot editor, to the server).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    catalog: Catalog,
    current: ModelPick,
    defaultSubtitle: String,
    onPick: (ModelPick) -> Unit,
    onDismiss: () -> Unit,
) {
    var listing by remember { mutableStateOf(false) }
    LabSheet(onDismiss) {
        AnimatedContent(
            listing,
            transitionSpec = {
                if (targetState) (slideInHorizontally { it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 } + fadeOut())
                else (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { it / 4 } + fadeOut())
            },
            label = "picker page",
        ) { list ->
            if (list) ModelList(catalog, current, defaultSubtitle, onBack = { listing = false }) { onPick(it); listing = false }
            else LevelCard(catalog, current, defaultSubtitle, onPick, onModels = { listing = true })
        }
    }
}

@Composable
private fun LevelCard(catalog: Catalog, current: ModelPick, defaultSubtitle: String, onPick: (ModelPick) -> Unit, onModels: () -> Unit) {
    val model: ModelInfo? = if (current.isDefault) null else catalog.find(current.provider, current.model)
    // "Auto" first, then the levels this model takes, in order
    val steps = listOf("") + EFFORT_ORDER.filter { model != null && it in model.efforts }
    val index = steps.indexOf(current.reasoning).coerceAtLeast(0)
    val level = steps[index]
    val ultra = level == "max"
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val tint by animateColorAsState(effortColor(level, muted), label = "level colour")
    val haptic = LocalHapticFeedback.current

    Surface(
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
    ) {
        Column(Modifier.padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // quickest: the lowest level this model has
                IconButton(onClick = { if (steps.size > 1) onPick(current.copy(reasoning = steps[1])) }, enabled = steps.size > 1) {
                    Icon(Icons.Rounded.Bolt, contentDescription = stringResource(R.string.model_fastest), tint = muted)
                }
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable(onClick = onModels).padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val title = if (current.isDefault) stringResource(R.string.model_default) else effortLabel(level)
                    Text(
                        title,
                        style = if (ultra) MaterialTheme.typography.titleLarge.merge(TextStyle(brush = Brush.linearGradient(listOf(UltraViolet, UltraPurple))))
                        else MaterialTheme.typography.titleLarge.copy(color = tint),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            model?.name ?: if (current.isDefault) defaultSubtitle else current.model,
                            style = MaterialTheme.typography.bodyMedium, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = stringResource(R.string.model_change), tint = muted, modifier = Modifier.size(18.dp))
                    }
                }
                IconButton(onClick = { onPick(ModelPick()) }, enabled = !current.isDefault) {
                    Icon(Icons.Rounded.RestartAlt, contentDescription = stringResource(R.string.model_reset), tint = muted)
                }
            }
            Spacer(Modifier.height(10.dp))
            EffortSlider(
                steps = steps.size, index = index, color = tint, ultra = ultra, enabled = steps.size > 1,
                onChange = { i ->
                    if (i != index) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onPick(current.copy(reasoning = steps[i]))
                    }
                },
                modifier = Modifier.padding(horizontal = 10.dp),
            )
        }
    }
}

/**
 * A stepped track: dots mark the levels, the filled part runs up to a white knob. Tap a spot or drag; it snaps to the
 * nearest level. At the top level the fill turns into a moving gradient with light sweeping across and sparks drifting.
 */
@Composable
private fun EffortSlider(steps: Int, index: Int, color: Color, ultra: Boolean, enabled: Boolean, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val pos by animateFloatAsState(
        if (steps <= 1) 0f else index / (steps - 1f),
        spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "knob",
    )
    val glow by animateFloatAsState(if (ultra) 1f else 0f, tween(450), label = "ultra")
    val change by rememberUpdatedState(onChange)
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val dot = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val loop = rememberInfiniteTransition(label = "ultra loop")
    val t by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "flow")
    val sparks = remember { List(22) { Spark(it) } }

    fun nearest(x: Float, width: Float, r: Float): Int {
        if (steps <= 1) return 0
        val f = ((x - r) / (width - 2 * r)).coerceIn(0f, 1f)
        return (f * (steps - 1)).roundToInt()
    }

    Canvas(
        modifier.fillMaxWidth().height(40.dp)
            .pointerInput(steps, enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { change(nearest(it.x, size.width.toFloat(), size.height / 2f)) }
            }
            .pointerInput(steps, enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures { c, _ -> change(nearest(c.position.x, size.width.toFloat(), size.height / 2f)) }
            },
    ) {
        val h = size.height
        val r = h / 2
        val corner = CornerRadius(r, r)
        drawRoundRect(track, cornerRadius = corner)
        val knobX = r + (size.width - 2 * r) * pos
        val fillW = (knobX + r).coerceAtMost(size.width)
        val fill = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, fillW, h, corner)) }

        // the fill: the level's colour, or (blending in) the flowing ultra gradient
        drawPath(fill, color.copy(alpha = if (enabled) 1f else 0.35f))
        if (glow > 0f) clipPath(fill) {
            val shift = t * size.width * 2
            drawRect(
                Brush.linearGradient(
                    listOf(UltraPurple, UltraPink, UltraViolet, UltraPurple, UltraPink),
                    start = Offset(-size.width * 2 + shift, 0f), end = Offset(shift, h),
                    tileMode = androidx.compose.ui.graphics.TileMode.Repeated,
                ),
                alpha = glow,
            )
            // a band of light sweeping left to right
            val sweep = (t * 1.6f - 0.3f) * fillW
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.35f * glow), Color.Transparent), startX = sweep - h * 1.5f, endX = sweep + h * 1.5f),
                topLeft = Offset(sweep - h * 1.5f, 0f), size = Size(h * 3f, h),
            )
            // sparks drifting right and twinkling
            for (s in sparks) {
                val x = ((s.x + t * s.speed) % 1f) * fillW
                val y = h * (0.2f + 0.6f * s.y) + sin((t * s.speed * 6 + s.phase) * 2 * PI).toFloat() * h * 0.08f
                val a = (0.35f + 0.65f * abs(sin((t * 3 + s.phase) * 2 * PI)).toFloat()) * glow
                drawCircle(Color.White.copy(alpha = a), radius = s.size * h, center = Offset(x, y))
            }
        }

        // level dots: faint on the track, lighter on the fill; none where the knob sits
        if (steps > 1) for (i in 0 until steps) {
            val x = r + (size.width - 2 * r) * i / (steps - 1f)
            if (abs(x - knobX) < r * 0.9f) continue
            drawCircle(if (x < knobX) Color.White.copy(alpha = 0.55f) else dot, radius = h * 0.06f, center = Offset(x, r))
        }
        // the knob, with a soft shadow
        drawCircle(Color.Black.copy(alpha = 0.18f), radius = r - 2.dp.toPx(), center = Offset(knobX, r + 1.5.dp.toPx()))
        drawCircle(Color.White, radius = r - 3.dp.toPx(), center = Offset(knobX, r))
    }
}

/** One spark of the ultra fill: where it starts, how fast it drifts and how big it is (fractions of the track). */
private class Spark(seed: Int) {
    private val rnd = java.util.Random(seed * 7919L + 17)
    val x = rnd.nextFloat()
    val y = rnd.nextFloat()
    val speed = 0.25f + rnd.nextFloat() * 0.6f
    val phase = rnd.nextFloat()
    val size = 0.025f + rnd.nextFloat() * 0.035f
}

/** The models, flat as in ChatGPT; with more than one provider each group gets its name. */
@Composable
private fun ModelList(catalog: Catalog, current: ModelPick, defaultSubtitle: String, onBack: () -> Unit, onPick: (ModelPick) -> Unit) {
    Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(start = 8.dp, end = 8.dp, bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null) }
            Text(stringResource(R.string.model_select), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (catalog.providers.isEmpty()) {
            Text(stringResource(R.string.model_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }
        ModelItem(stringResource(R.string.model_default), defaultSubtitle, current.isDefault) { onPick(ModelPick()) }
        val grouped = catalog.providers.size > 1
        catalog.providers.forEach { p ->
            if (grouped) Text(p.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp))
            p.models.forEach { m ->
                ModelItem(m.name, null, current.provider == p.id && current.model == m.id) {
                    // keep the level when the new model has it too
                    onPick(ModelPick(p.id, m.id, current.reasoning.takeIf { it in m.efforts } ?: ""))
                }
            }
        }
    }
}

@Composable
private fun ModelItem(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = if (subtitle != null) 10.dp else 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (subtitle != null || selected) FontWeight.SemiBold else FontWeight.Normal)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        else Spacer(Modifier.width(20.dp))
    }
}
