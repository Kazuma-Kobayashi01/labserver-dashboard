package com.labserver.lablink.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.labserver.lablink.R
import com.labserver.lablink.tunnel.TunnelState
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

val TunnelState.isUpOrBusy get() = this is TunnelState.On || this is TunnelState.Connecting

@Composable
fun tunnelDotColor(state: TunnelState): Color {
    val c by animateColorAsState(
        when (state) {
            TunnelState.Off -> Color(0xFF9E9E9E)
            is TunnelState.Connecting -> Color(0xFFFFB300)
            is TunnelState.On -> Color(0xFF34C759)
            is TunnelState.Failed -> Color(0xFFE5484D)
        },
        label = "tunnel dot",
    )
    return c
}

@Composable
fun tunnelTitle(state: TunnelState): String = when (state) {
    TunnelState.Off -> stringResource(R.string.status_off)
    is TunnelState.Connecting -> stringResource(R.string.link_connecting)
    is TunnelState.On -> stringResource(R.string.status_on)
    is TunnelState.Failed -> stringResource(R.string.status_failed)
}

/**
 * The connection, Tailscale style: one switch. [compact] is the drawer version; the full one also shows the details
 * (handshake, traffic, hints) and a diagnostics link when something is wrong.
 */
@Composable
fun ConnectionCard(state: TunnelState, onToggle: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false, onShowLog: (() -> Unit)? = null) {
    // ticks every second so the "still waiting" hint can appear
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(1000); value = System.currentTimeMillis() } }
    val waitingLong = state is TunnelState.Connecting && now - state.since > 15_000
    val detail = when (state) {
        TunnelState.Off -> if (compact) "" else stringResource(R.string.link_in_app_only)
        is TunnelState.Connecting -> if (waitingLong) stringResource(R.string.link_waiting) else ""
        is TunnelState.On -> stringResource(R.string.link_handshake, ago(state.handshakeAgo)) +
            if (compact) "" else "  ·  ↓ ${bytes(state.rx)}  ↑ ${bytes(state.tx)}"
        is TunnelState.Failed -> if (state.missingConfig) stringResource(R.string.link_missing) else state.message
    }
    // a glass card washed with the state's colour from its corner: violet when up, red when it failed
    val wash by animateColorAsState(
        when (state) {
            is TunnelState.On -> MaterialTheme.colorScheme.primary
            is TunnelState.Failed -> MaterialTheme.colorScheme.error
            is TunnelState.Connecting -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "connection wash",
    )
    androidx.compose.foundation.layout.Box(
        modifier.fillMaxWidth().glass(RoundedCornerShape(28.dp), elevation = 8.dp, strong = true)
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(wash.copy(alpha = 0.16f), Color.Transparent))),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = if (compact) 14.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(tunnelDotColor(state), CircleShape))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(stringResource(R.string.home_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        tunnelTitle(state),
                        style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Switch(checked = state.isUpOrBusy, onCheckedChange = { onToggle() })
            }
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (compact) 1 else 4)
            if (onShowLog != null && (state is TunnelState.Failed || waitingLong)) {
                TextButton(onClick = onShowLog) { Text(stringResource(R.string.link_diagnostics)) }
            }
        }
    }
}

/** Material 3 Expressive style "cookie": a circle with [bumps] soft scallops. Used for decorative icon containers. */
class CookieShape(private val bumps: Int = 8, private val depth: Float = 0.07f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = min(size.width, size.height) / 2
        val cx = size.width / 2
        val cy = size.height / 2
        val path = Path()
        val steps = 120
        for (i in 0..steps) {
            val t = 2 * PI * i / steps
            val rr = r * (1 - depth + depth * cos(bumps * t))
            val x = (cx + rr * cos(t)).toFloat()
            val y = (cy + rr * sin(t)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
    }
}

fun ago(s: Long): String = when {
    s < 60 -> "${s}s"
    s < 3600 -> "${s / 60}m"
    else -> "${s / 3600}h"
}

fun bytes(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "%.1f KB".format(n / 1024.0)
    else -> "%.1f MB".format(n / 1048576.0)
}
