package com.labserver.lablink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labserver.lablink.R
import com.labserver.lablink.data.TrashedBot
import com.labserver.lablink.labApp
import com.labserver.lablink.net.ManagerResult
import kotlinx.coroutines.launch

/** Deleted sandbox bots: bring one back, or delete it for good. The server removes them by itself after a few days. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(connected: Boolean, onBack: () -> Unit, onMessage: (String) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.labApp
    val ui by app.chat.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var purging by remember { mutableStateOf<TrashedBot?>(null) }

    fun run(block: suspend () -> ManagerResult<*>) {
        scope.launch {
            busy = true
            val r = block()
            busy = false
            if (r is ManagerResult.Fail) onMessage(managerErrorText(ctx, r))
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent, scrolledContainerColor = glassFill(strong = true)),
            title = { Text(stringResource(R.string.trash_title), fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.web_back)) } },
            windowInsets = WindowInsets.statusBars,
        )
        if (!connected) {
            Text(stringResource(R.string.trash_offline), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        if (ui.trash.isEmpty()) {
            Text(
                stringResource(R.string.trash_empty, ui.trashDays),
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ui.trash, key = { it.bot.id }) { t ->
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            BotAvatar(44.dp, color = Color(t.bot.color))
                            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                Text(t.bot.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(expiryText(t), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(enabled = connected && !busy, onClick = { run { app.chat.restoreBot(t.bot.id) } }) {
                                Icon(Icons.Rounded.RestoreFromTrash, contentDescription = stringResource(R.string.trash_restore))
                            }
                            IconButton(enabled = connected && !busy, onClick = { purging = t }) {
                                Icon(Icons.Rounded.DeleteForever, contentDescription = stringResource(R.string.trash_purge), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    purging?.let { t ->
        AlertDialog(
            onDismissRequest = { purging = null },
            title = { Text(stringResource(R.string.trash_purge)) },
            text = { Text(stringResource(R.string.trash_purge_confirm, t.bot.name)) },
            confirmButton = { TextButton(onClick = { purging = null; run { app.chat.purgeBot(t.bot.id) } }) { Text(stringResource(R.string.trash_purge), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { purging = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun expiryText(t: TrashedBot): String {
    val at = t.expiresAt ?: return ""
    val days = Math.ceilDiv(at - System.currentTimeMillis() / 1000, 86400L).toInt()
    return if (days <= 0) stringResource(R.string.trash_expires_today) else stringResource(R.string.trash_expires_days, days)
}
