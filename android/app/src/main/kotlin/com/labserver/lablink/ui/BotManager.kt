package com.labserver.lablink.ui

import com.labserver.lablink.labApp
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.labserver.lablink.R
import com.labserver.lablink.data.Bot
import com.labserver.lablink.data.BotColors
import com.labserver.lablink.data.Catalog
import com.labserver.lablink.data.ModelPick

private class Preset(
    val color: Long,
    @StringRes val name: Int,
    @StringRes val role: Int,
    @StringRes val prompt: Int,
)

private val presets = listOf(
    Preset(0xFF3B9BE8, R.string.preset_translator_name, R.string.preset_translator_role, R.string.preset_translator_prompt),
    Preset(0xFF2B2B3A, R.string.preset_coder_name, R.string.preset_coder_role, R.string.preset_coder_prompt),
    Preset(0xFFF2724D, R.string.preset_writer_name, R.string.preset_writer_role, R.string.preset_writer_prompt),
    Preset(0xFF2FBF8F, R.string.preset_lab_name, R.string.preset_lab_role, R.string.preset_lab_prompt),
    Preset(0xFFE8589B, R.string.preset_buddy_name, R.string.preset_buddy_role, R.string.preset_buddy_prompt),
)

/** Switch between bots, or start a new one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotPickerSheet(
    bots: List<Bot>,
    currentId: String,
    sandboxCount: Int,
    limit: Int,
    running: Set<String>,
    onSelect: (Bot) -> Unit,
    onEdit: (Bot) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.bots_title) + "  ·  " + stringResource(R.string.bots_count, sandboxCount, limit), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
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
                            Text(bot.name + if (bot.id in running) "  ●" else "", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                Text(if (sandboxCount >= limit) stringResource(R.string.bots_new_full, sandboxCount, limit) else stringResource(R.string.bots_new))
            }
        }
    }
}

/** Create a bot (initial == null) or edit one: name, one-line role, system prompt and colour. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotEditorDialog(
    initial: Bot?,
    /** A sandbox bot: kept by the server, with the server's limits on the fields. */
    sandbox: Boolean,
    canDelete: Boolean,
    saving: Boolean,
    error: String?,
    trashDays: Int,
    catalog: Catalog,
    onSave: (Bot) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var role by remember { mutableStateOf(initial?.role ?: "") }
    var prompt by remember { mutableStateOf(initial?.systemPrompt ?: "") }
    var color by remember { mutableStateOf(initial?.color ?: BotColors.palette().random()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showError by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf(initial?.pick ?: ModelPick()) }
    var showModels by remember { mutableStateOf(false) }

    val presetStrings = presets.map { p -> Triple(p.color, stringResource(p.name) to stringResource(p.role), stringResource(p.prompt)) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.systemBarsPadding().imePadding()) {
                TopAppBar(
                    title = { Text(stringResource(if (initial == null) R.string.bot_editor_new else R.string.bot_editor_edit)) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_cancel)) } },
                    actions = {
                        TextButton(enabled = !saving, onClick = {
                            if (name.isBlank()) showError = true
                            else onSave((initial ?: Bot(name = name)).copy(name = name.trim(), role = role.trim(), systemPrompt = prompt.trim(), color = color, modelProvider = pick.provider, modelId = pick.model, reasoning = pick.reasoning))
                        }) { Text(stringResource(if (saving) R.string.bot_saving else R.string.common_save)) }
                    },
                    windowInsets = WindowInsets(0),
                )
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // live preview: the hero orb takes the colour as you pick it
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        BotHero(150.dp, color = Color(color))
                        Text(name.ifBlank { stringResource(R.string.bot_editor_new) }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (role.isNotBlank()) Text(role, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }

                    if (sandbox) {
                        Text(stringResource(R.string.bot_sandbox_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    error?.let {
                        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
                        }
                    }
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

                    if (initial == null) {
                        Text(stringResource(R.string.bot_presets), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        // templates as small bot cards, each with its own orb colour
                        presetStrings.chunked(2).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { (c, np, text) ->
                                    Surface(
                                        onClick = { name = np.first; role = np.second; prompt = text; color = c },
                                        shape = RoundedCornerShape(22.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                            BotAvatar(36.dp, color = Color(c))
                                            Column(Modifier.padding(start = 10.dp)) {
                                                Text(np.first, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(np.second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }

                    OutlinedTextField(
                        name, { name = if (sandbox) it.take(40) else it; showError = false },
                        label = { Text(stringResource(R.string.bot_name)) },
                        isError = showError, supportingText = if (showError) ({ Text(stringResource(R.string.bot_name_required)) }) else null,
                        singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        role, { role = if (sandbox) it.take(200) else it },
                        label = { Text(stringResource(R.string.bot_role)) },
                        singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        prompt, { prompt = if (sandbox) it.take(8000) else it },
                        label = { Text(stringResource(R.string.bot_prompt)) },
                        supportingText = { Text(stringResource(if (sandbox) R.string.bot_prompt_hint_sandbox else R.string.bot_prompt_hint)) },
                        minLines = 6, maxLines = 14, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
                    )

                    // the bot's own default model and thinking level (the chat can still change them for itself)
                    Surface(onClick = { showModels = true }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(stringResource(R.string.bot_default_model), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                if (pick.isDefault) stringResource(R.string.model_default_server) else pickLabel(catalog, pick),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }

                    // replies formatted in Markdown (headings, lists, tables, code); on for every bot unless switched off here
                    if (initial != null) {
                        val prefs = LocalContext.current.labApp.prefs
                        var markdown by remember { mutableStateOf(prefs.markdown(initial.id)) }
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.bot_markdown), style = MaterialTheme.typography.bodyLarge)
                                    Text(stringResource(R.string.bot_markdown_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                androidx.compose.material3.Switch(checked = markdown, onCheckedChange = { markdown = it; prefs.setMarkdown(initial.id, it) })
                            }
                        }
                    }

                    if (initial != null && canDelete) {
                        OutlinedButton(
                            onClick = { confirmDelete = true },
                            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            enabled = !saving,
                        ) { Text(stringResource(R.string.bot_delete)) }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }

    if (showModels) {
        ModelPickerSheet(
            catalog = catalog, current = pick, defaultSubtitle = stringResource(R.string.model_default_server),
            onPick = { pick = it }, onDismiss = { showModels = false },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.bot_delete)) },
            text = { Text(if (sandbox) stringResource(R.string.bot_delete_sandbox_confirm, trashDays) else stringResource(R.string.bot_delete_confirm)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(R.string.common_ok)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
