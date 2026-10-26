package com.labserver.lablink.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labserver.lablink.R
import com.labserver.lablink.data.ChatMessage
import com.labserver.lablink.labApp
import com.labserver.lablink.net.BotEvent
import com.labserver.lablink.tunnel.TunnelState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(tunnel: TunnelState, onPill: () -> Unit, onOpenLink: () -> Unit) {
    val app = LocalContext.current.labApp
    val ui by app.chat.ui.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // newest message at index 0 (the list is reversed), so growing replies stay pinned to the bottom
    LaunchedEffect(ui.messages.size) { if (ui.messages.isNotEmpty()) listState.animateScrollToItem(0) }

    val send = {
        app.chat.send(input)
        input = ""
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        CenterAlignedTopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BotAvatar(30.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.bot_title))
                }
            },
            actions = {
                ConnectionPill(tunnel, onPill)
                IconButton(onClick = { confirmClear = true }, enabled = ui.messages.isNotEmpty()) {
                    Icon(Icons.Rounded.DeleteSweep, contentDescription = stringResource(R.string.bot_new_chat))
                }
            },
            windowInsets = WindowInsets(0),
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (ui.messages.isEmpty()) {
                EmptyState(onSuggest = { app.chat.send(it) })
            } else {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    val msgs = ui.messages
                    items(msgs.asReversed(), key = { it.id }) { m ->
                        val last = m.id == msgs.last().id
                        if (m.role == "user") UserBubble(m) else BotBubble(m, thinking = last && ui.streaming, working = last && ui.working)
                    }
                }
            }
        }

        ui.error?.let { err ->
            ErrorCard(err, onDismiss = app.chat::dismissError, onConnect = { app.tunnel.connect(); app.chat.dismissError() }, onOpenLink = { onOpenLink(); app.chat.dismissError() })
        }

        InputBar(
            value = input,
            onValueChange = { input = it },
            streaming = ui.streaming,
            onSend = send,
            onStop = app.chat::stop,
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.bot_new_chat)) },
            text = { Text(stringResource(R.string.bot_new_chat_confirm)) },
            confirmButton = {
                TextButton(onClick = { app.chat.clear(); confirmClear = false }) { Text(stringResource(R.string.common_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun EmptyState(onSuggest: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BotAvatar(104.dp)
        Spacer(Modifier.padding(8.dp))
        Text(stringResource(R.string.bot_hello), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.bot_hello_sub),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
        )
        listOf(R.string.bot_suggest_1, R.string.bot_suggest_2, R.string.bot_suggest_3).forEach { res ->
            val text = stringResource(res)
            SuggestionChip(onClick = { onSuggest(text) }, label = { Text(text) }, modifier = Modifier.padding(vertical = 3.dp))
        }
    }
}

@Composable
private fun UserBubble(m: ChatMessage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 6.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            SelectionContainer {
                Text(
                    m.content, color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun BotBubble(m: ChatMessage, thinking: Boolean, working: Boolean) {
    var showThoughts by rememberSaveable(m.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        BotAvatar(32.dp, Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(8.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(topStart = 6.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 24.dp),
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                if (m.reasoning.isNotBlank()) {
                    Row(
                        Modifier.padding(bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { showThoughts = !showThoughts }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                            Text(stringResource(R.string.bot_thought), style = MaterialTheme.typography.labelLarge)
                            Icon(if (showThoughts) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
                        }
                    }
                    AnimatedVisibility(showThoughts) {
                        Text(
                            m.reasoning,
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }
                when {
                    m.content.isNotBlank() -> SelectionContainer {
                        MarkdownText(m.content, MaterialTheme.colorScheme.onSurface)
                    }
                    thinking -> Text(
                        stringResource(if (working) R.string.bot_working else R.string.bot_thinking),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(err: BotEvent.Error, onDismiss: () -> Unit, onConnect: () -> Unit, onOpenLink: () -> Unit) {
    val text = when (err.kind) {
        BotEvent.Error.Kind.NotConnected -> stringResource(R.string.bot_need_connect)
        BotEvent.Error.Kind.NoKey -> stringResource(R.string.bot_need_key)
        BotEvent.Error.Kind.Auth -> stringResource(R.string.bot_error_auth)
        BotEvent.Error.Kind.Unreachable -> stringResource(R.string.bot_error_unreachable) + if (err.detail.isNotBlank()) " (${err.detail})" else ""
        BotEvent.Error.Kind.Other -> err.detail
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
            when (err.kind) {
                BotEvent.Error.Kind.NotConnected -> TextButton(onClick = onConnect) { Text(stringResource(R.string.link_connect)) }
                BotEvent.Error.Kind.NoKey, BotEvent.Error.Kind.Auth -> TextButton(onClick = onOpenLink) { Text(stringResource(R.string.tab_link)) }
                else -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_ok)) }
            }
        }
    }
}

@Composable
private fun InputBar(value: String, onValueChange: (String) -> Unit, streaming: Boolean, onSend: () -> Unit, onStop: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.bot_hint)) },
            shape = RoundedCornerShape(28.dp),
            maxLines = 5,
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        )
        Spacer(Modifier.width(8.dp))
        FilledIconButton(
            onClick = if (streaming) onStop else onSend,
            enabled = streaming || value.isNotBlank(),
            modifier = Modifier.padding(bottom = 4.dp),
        ) {
            Icon(
                if (streaming) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.Send,
                contentDescription = null,
            )
        }
    }
}
