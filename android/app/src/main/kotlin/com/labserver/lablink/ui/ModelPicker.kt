package com.labserver.lablink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.labserver.lablink.R
import com.labserver.lablink.data.Catalog
import com.labserver.lablink.data.EFFORT_ORDER
import com.labserver.lablink.data.ModelPick

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

/**
 * Choose a model and a thinking level, as in ChatGPT. Every tap is applied at once; levels the chosen model does not
 * offer are not shown. "Default" hands the choice back to the bot (or, in the bot editor, to the server).
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ModelPickerSheet(
    catalog: Catalog,
    current: ModelPick,
    defaultSubtitle: String,
    onPick: (ModelPick) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.model_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
            if (catalog.providers.isEmpty()) {
                Text(stringResource(R.string.model_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
            }
            ModelRow(selected = current.isDefault, title = stringResource(R.string.model_default), subtitle = defaultSubtitle) { onPick(ModelPick()) }
            catalog.providers.forEach { p ->
                Text(p.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, top = 10.dp))
                p.models.forEach { m ->
                    val sel = current.provider == p.id && current.model == m.id
                    ModelRow(selected = sel, title = m.name, subtitle = null) {
                        // keep the level when the new model has it too
                        onPick(ModelPick(p.id, m.id, current.reasoning.takeIf { it in m.efforts } ?: ""))
                    }
                    if (sel && m.efforts.isNotEmpty()) {
                        Text(stringResource(R.string.model_effort), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 2.dp))
                        FlowRow(Modifier.padding(start = 12.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = current.reasoning.isEmpty(), onClick = { onPick(current.copy(reasoning = "")) }, label = { Text(stringResource(R.string.model_effort_auto)) })
                            EFFORT_ORDER.filter { it in m.efforts }.forEach { e ->
                                FilterChip(selected = current.reasoning == e, onClick = { onPick(current.copy(reasoning = e)) }, label = { Text(effortLabel(e)) })
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            FilledTonalButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(stringResource(R.string.model_done)) }
        }
    }
}

@Composable
private fun ModelRow(selected: Boolean, title: String, subtitle: String?, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Column(Modifier.weight(1f).padding(start = 2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(4.dp))
        }
    }
}
