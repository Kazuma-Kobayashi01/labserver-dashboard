package com.labserver.lablink.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.labserver.lablink.R
import com.labserver.lablink.net.ApprovalRequest
import kotlinx.coroutines.delay

/**
 * The bot asks before it downloads, installs or deletes (as Claude and ChatGPT ask before a risky action): what it wants
 * to do, the exact command, and the answers. Nothing runs until one is picked; Hermes refuses on its own when the time
 * runs out, which the countdown shows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ApprovalCard(req: ApprovalRequest, busy: Boolean, onAnswer: (String) -> Unit, modifier: Modifier = Modifier) {
    val (icon, title) = when (req.kind) {
        "download" -> Icons.Rounded.Download to R.string.approval_title_download
        "install" -> Icons.Rounded.InstallMobile to R.string.approval_title_install
        "delete" -> Icons.Rounded.DeleteOutline to R.string.approval_title_delete
        else -> Icons.Rounded.Shield to R.string.approval_title_other
    }
    val danger = req.kind == "delete" || req.kind == "danger"
    val accent = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val left by produceState(remaining(req)) { while (value > 0) { delay(1000); value = remaining(req) } }

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = if (isDark()) MaterialTheme.colorScheme.surfaceContainerLow else Color.White,
        modifier = modifier.fillMaxWidth().border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(22.dp)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Icon(icon as ImageVector, contentDescription = null, tint = accent, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(title, req.botName.ifBlank { "Bot" }), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (left > 0) stringResource(R.string.approval_expires, "%d:%02d".format(left / 60, left % 60)) else stringResource(R.string.approval_hint),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // the exact command, as it would run
            Box(
                Modifier.fillMaxWidth().heightIn(max = 140.dp).clip(RoundedCornerShape(12.dp))
                    .background(codePalette().bar)
                    .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(req.command.ifBlank { "—" }, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if ("once" in req.choices) PillButton(onClick = { onAnswer("once") }, enabled = !busy, kind = if (danger) PillKind.Danger else PillKind.Primary) { Text(stringResource(R.string.approval_once)) }
                if ("session" in req.choices) PillButton(onClick = { onAnswer("session") }, enabled = !busy, kind = PillKind.Tonal) { Text(stringResource(R.string.approval_session)) }
                PillButton(onClick = { onAnswer("deny") }, enabled = !busy, kind = PillKind.Quiet) { Text(stringResource(R.string.approval_deny)) }
                if ("always" in req.choices) PillButton(onClick = { onAnswer("always") }, enabled = !busy, kind = PillKind.Quiet) { Text(stringResource(R.string.approval_always)) }
            }
        }
    }
}

private fun remaining(req: ApprovalRequest): Int = (((req.at + req.timeoutMs) - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0)
