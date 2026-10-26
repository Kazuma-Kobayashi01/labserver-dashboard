package com.labserver.lablink.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.labserver.lablink.R
import com.labserver.lablink.data.Bot
import com.labserver.lablink.data.BotColors

private class Preset(
    @StringRes val name: Int,
    @StringRes val role: Int,
    @StringRes val prompt: Int,
)

private val presets = listOf(
    Preset(R.string.preset_translator_name, R.string.preset_translator_role, R.string.preset_translator_prompt),
    Preset(R.string.preset_coder_name, R.string.preset_coder_role, R.string.preset_coder_prompt),
    Preset(R.string.preset_writer_name, R.string.preset_writer_role, R.string.preset_writer_prompt),
    Preset(R.string.preset_lab_name, R.string.preset_lab_role, R.string.preset_lab_prompt),
    Preset(R.string.preset_buddy_name, R.string.preset_buddy_role, R.string.preset_buddy_prompt),
)

/** Switch between bots, or start a new one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotPickerSheet(
    bots: List<Bot>,
    currentId: String,
    onSelect: (Bot) -> Unit,
    onEdit: (Bot) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.bots_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
            bots.forEach { bot ->
                val selected = bot.id == currentId
                Surface(
                    onClick = { onSelect(bot) },
                    shape = MaterialTheme.shapes.large,
                    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        BotAvatar(46.dp, color = Color(bot.color))
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text(bot.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (bot.role.isNotBlank()) {
                                Text(bot.role, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        IconButton(onClick = { onEdit(bot) }) { Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.bots_edit)) }
                    }
                }
            }
            FilledTonalButton(onClick = onNew, modifier = Modifier.fillMaxWidth().height(52.dp).padding(top = 4.dp)) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.bots_new))
            }
        }
    }
}

/** Create a bot (initial == null) or edit one: name, one-line role, system prompt and colour. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotEditorDialog(initial: Bot?, canDelete: Boolean, onSave: (Bot) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var role by remember { mutableStateOf(initial?.role ?: "") }
    var prompt by remember { mutableStateOf(initial?.systemPrompt ?: "") }
    var color by remember { mutableStateOf(initial?.color ?: BotColors.palette().random()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showError by remember { mutableStateOf(false) }

    val presetStrings = presets.map { p ->
        Triple(stringResource(p.name), stringResource(p.name) to stringResource(p.role), stringResource(p.prompt))
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.systemBarsPadding().imePadding()) {
                TopAppBar(
                    title = { Text(stringResource(if (initial == null) R.string.bot_editor_new else R.string.bot_editor_edit)) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_cancel)) } },
                    actions = {
                        TextButton(onClick = {
                            if (name.isBlank()) showError = true
                            else onSave((initial ?: Bot(name = name)).copy(name = name.trim(), role = role.trim(), systemPrompt = prompt.trim(), color = color))
                        }) { Text(stringResource(R.string.common_save)) }
                    },
                    windowInsets = WindowInsets(0),
                )
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { BotAvatar(96.dp, color = Color(color)) }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        BotColors.palette().forEach { c ->
                            val sel = c == color
                            Box(
                                Modifier.size(34.dp)
                                    .background(Color(c), CircleShape)
                                    .then(if (sel) Modifier.border(BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface), CircleShape) else Modifier)
                                    .clickable { color = c },
                                contentAlignment = Alignment.Center,
                            ) { if (sel) Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp)) }
                        }
                    }

                    OutlinedTextField(
                        name, { name = it; showError = false },
                        label = { Text(stringResource(R.string.bot_name)) },
                        isError = showError, supportingText = if (showError) ({ Text(stringResource(R.string.bot_name_required)) }) else null,
                        singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        role, { role = it },
                        label = { Text(stringResource(R.string.bot_role)) },
                        singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        prompt, { prompt = it },
                        label = { Text(stringResource(R.string.bot_prompt)) },
                        supportingText = { Text(stringResource(R.string.bot_prompt_hint)) },
                        minLines = 6, maxLines = 14, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
                    )

                    if (initial == null) {
                        Text(stringResource(R.string.bot_presets), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowChips(presetStrings.map { it.first }) { i ->
                            val (_, np, text) = presetStrings[i]
                            name = np.first; role = np.second; prompt = text
                        }
                    }

                    if (initial != null && canDelete) {
                        OutlinedButton(
                            onClick = { confirmDelete = true },
                            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) { Text(stringResource(R.string.bot_delete)) }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.bot_delete)) },
            text = { Text(stringResource(R.string.bot_delete_confirm)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(R.string.common_ok)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlowChips(labels: List<String>, onClick: (Int) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEachIndexed { i, label -> AssistChip(onClick = { onClick(i) }, label = { Text(label) }) }
    }
}
