package com.labserver.lablink.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/**
 * A tap target that gives under the finger: it shrinks a little while pressed and springs back with a small overshoot.
 * Used on the small round buttons, chips and cards so every press feels the same.
 */
fun Modifier.pressable(onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.92f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    this.graphicsLayer { scaleX = scale; scaleY = scale }.clickable(interactionSource = source, indication = null, onClick = onClick)
}

/**
 * The app's springs, so motion feels like one physical world: [Soft] for things that arrive or leave (a little give),
 * [Snappy] for things that open and close, [Bouncy] for small things that pop.
 */
object Springs {
    fun <T> soft() = androidx.compose.animation.core.spring<T>(dampingRatio = 0.82f, stiffness = 320f)
    fun <T> snappy() = androidx.compose.animation.core.spring<T>(dampingRatio = 0.86f, stiffness = 520f)
    fun <T> bouncy() = androidx.compose.animation.core.spring<T>(dampingRatio = 0.55f, stiffness = 520f)
}

/** Opening and closing a block (folding text, a command window): its height and opacity follow a spring. */
val springExpand = androidx.compose.animation.expandVertically(Springs.snappy()) + androidx.compose.animation.fadeIn(Springs.snappy())
val springShrink = androidx.compose.animation.shrinkVertically(Springs.snappy()) + androidx.compose.animation.fadeOut(Springs.snappy())
