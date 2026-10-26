package com.labserver.lablink.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WavingHand
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labserver.lablink.R
import com.labserver.lablink.data.Bot
import com.labserver.lablink.data.ChatMessage
import com.labserver.lablink.labApp
import com.labserver.lablink.net.BotEvent
import com.labserver.lablink.tunnel.TunnelState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    tunnel: TunnelState,
    onOpenDrawer: () -> Unit,
    onConnect: () -> Unit,
    onOpenSettings: () -> Unit,
    onNewBot: () -> Unit,
    onEditBot: (Bot) -> Unit,
) {
    val app = LocalContext.current.labApp
    val ui by app.chat.ui.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val botColor = Color(ui.bot.color)
    val surface = MaterialTheme.colorScheme.surface
    val tint = if (isSystemInDarkTheme()) 0.16f else 0.09f
    val haptic = LocalHapticFeedback.current
    val jumpVisible by remember { derivedStateOf { listState.firstVisibleItemIndex > 1 } }
    // the reply being written right now, if any: drives the orb's mood in the top bar
    val live = ui.messages.lastOrNull()?.takeIf { ui.streaming && it.role == "assistant" }
    val liveMood = orbMood(ui.streaming, ui.working, !live?.content.isNullOrBlank())

    // newest message at index 0 (the list is reversed), so growing replies stay pinned to the bottom
    LaunchedEffect(ui.messages.size, ui.bot.id) { if (ui.messages.isNotEmpty()) listState.animateScrollToItem(0) }

    val send = {
        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
        app.chat.send(input)
        input = ""
    }
    // a light tick when a reply is finished
    var wasStreaming by remember { mutableStateOf(false) }
    LaunchedEffect(ui.streaming) {
        if (wasStreaming && !ui.streaming && ui.error == null) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        wasStreaming = ui.streaming
    }

    // every bot washes the screen in its own colour, very softly (as Pi does with its warm backgrounds)
    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(lerp(surface, botColor, tint), surface), endY = 1400f))
            .imePadding(),
    ) {
        CenterAlignedTopAppBar(
            navigationIcon = {
                IconButton(onClick = onOpenDrawer) {
                    Box {
                        Icon(Icons.Rounded.Menu, contentDescription = null)
                        // connection status, always in sight
                        Box(
                            Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-2).dp).size(9.dp)
                                .background(MaterialTheme.colorScheme.surface, CircleShape).padding(1.5.dp)
                                .background(tunnelDotColor(tunnel), CircleShape),
                        )
                    }
                }
            },
            title = {
                // bot switcher, Gemini style: the name with a chevron
                Row(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f))
                        .clickable { showPicker = true }
                        .padding(start = 8.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BotAvatar(28.dp, color = botColor, mood = liveMood, talk = (live?.content?.length ?: 0) / 16, alive = true)
                    Spacer(Modifier.width(8.dp))
                    Text(ui.bot.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
                    Icon(Icons.Rounded.ExpandMore, contentDescription = stringResource(R.string.bots_title), modifier = Modifier.size(20.dp))
                }
            },
            actions = {
                IconButton(onClick = { confirmClear = true }, enabled = ui.messages.isNotEmpty() && !ui.streaming) {
                    Icon(Icons.Rounded.EditNote, contentDescription = stringResource(R.string.bot_new_chat))
                }
            },
            windowInsets = WindowInsets(0),
            colors = androidx.compose.material3.TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
        )

        AnimatedVisibility(tunnel !is TunnelState.On, enter = expandVertically(), exit = shrinkVertically()) {
            OfflineBanner(tunnel, onConnect)
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (ui.messages.isEmpty()) {
                EmptyState(ui.bot, onSuggest = { app.chat.send(it) })
            } else {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                ) {
                    val msgs = ui.messages
                    items(msgs.asReversed(), key = { it.id }) { m ->
                        val last = m.id == msgs.last().id
                        if (m.role == "user") UserBubble(m, botColor, Modifier.animateItem())
                        else BotReply(
                            m, ui.bot, thinking = last && ui.streaming, working = last && ui.working,
                            canRegenerate = last && !ui.streaming, onRegenerate = app.chat::regenerate,
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
            // jump back to the newest message after scrolling up
            androidx.compose.animation.AnimatedVisibility(
                jumpVisible, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut(),
            ) {
                Surface(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    shape = CircleShape, shadowElevation = 4.dp, tonalElevation = 3.dp,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.size(42.dp),
                ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.ArrowDownward, contentDescription = null, modifier = Modifier.size(20.dp)) } }
            }
        }

        ui.error?.let { err ->
            ErrorStrip(
                err,
                onDismiss = app.chat::dismissError,
                onConnect = { onConnect(); app.chat.dismissError() },
                onOpenSettings = { onOpenSettings(); app.chat.dismissError() },
            )
        }

        Composer(
            hint = stringResource(R.string.bot_hint, cjkSpaced(ui.bot.name)),
            value = input,
            onValueChange = { input = it },
            streaming = ui.streaming,
            onSend = send,
            onStop = app.chat::stop,
        )
    }

    if (showPicker) {
        BotPickerSheet(
            bots = ui.bots,
            currentId = ui.bot.id,
            onSelect = { app.chat.select(it.id); showPicker = false },
            onEdit = { onEditBot(it); showPicker = false },
            onNew = { onNewBot(); showPicker = false },
            onDismiss = { showPicker = false },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.bot_new_chat)) },
            text = { Text(stringResource(R.string.bot_new_chat_confirm)) },
            confirmButton = { TextButton(onClick = { app.chat.clear(); confirmClear = false }) { Text(stringResource(R.string.common_ok)) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun OfflineBanner(tunnel: TunnelState, onConnect: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(tunnelDotColor(tunnel), CircleShape))
            Text(
                if (tunnel is TunnelState.Connecting) stringResource(R.string.link_connecting) else stringResource(R.string.bot_need_connect),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            )
            if (tunnel is TunnelState.Connecting) {
                CircularProgressIndicator(Modifier.padding(12.dp).size(18.dp), strokeWidth = 2.dp)
            } else {
                FilledTonalButton(onClick = onConnect, contentPadding = PaddingValues(horizontal = 16.dp)) { Text(stringResource(R.string.link_connect)) }
            }
        }
    }
}

/** Hero moment: the bot's orb on a soft glow of its colour, the greeting in big type, and four suggestion cards. */
@Composable
private fun EmptyState(bot: Bot, onSuggest: (String) -> Unit) {
    val botColor = Color(bot.color)
    val cards: List<Pair<ImageVector, Int>> = listOf(
        Icons.Rounded.WavingHand to R.string.bot_suggest_1,
        Icons.Rounded.AutoAwesome to R.string.bot_suggest_2,
        Icons.Rounded.Lightbulb to R.string.bot_suggest_3,
        Icons.Rounded.Event to R.string.bot_suggest_4,
    )
    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(300.dp).background(Brush.radialGradient(listOf(botColor.copy(alpha = 0.22f), Color.Transparent))))
            BotHero(132.dp, color = botColor)
        }
        Text(
            stringResource(R.string.bot_hello_named, cjkSpaced(bot.name)),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            bot.role.ifBlank { stringResource(R.string.bot_hello_sub) },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, bottom = 26.dp),
        )
        for (row in cards.chunked(2)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (icon, res) ->
                    val label = stringResource(res)
                    Surface(
                        onClick = { onSuggest(label) },
                        color = lerp(MaterialTheme.colorScheme.surfaceContainerHigh, botColor, 0.10f),
                        shape = RoundedCornerShape(26.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 92.dp),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                            Icon(icon, contentDescription = null, tint = botColor, modifier = Modifier.size(22.dp))
                            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, modifier = Modifier.padding(top = 14.dp))
                        }
                    }
                }
            }
        }
    }
}

/** The user's turn: a soft tonal bubble on the right. */
@Composable
private fun UserBubble(m: ChatMessage, botColor: Color, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = lerp(MaterialTheme.colorScheme.surfaceContainerHigh, botColor, 0.12f),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 8.dp),
            modifier = Modifier.widthIn(max = 300.dp).padding(start = 48.dp),
        ) {
            SelectionContainer {
                Text(
                    m.content,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                )
            }
        }
    }
}

/** The bot's turn: no bubble, a page of text under its orb and name (as in Gemini and Claude). */
@Composable
private fun BotReply(
    m: ChatMessage, bot: Bot, thinking: Boolean, working: Boolean,
    canRegenerate: Boolean, onRegenerate: () -> Unit, modifier: Modifier = Modifier,
) {
    var showThoughts by rememberSaveable(m.id) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BotAvatar(26.dp, color = Color(bot.color), mood = orbMood(thinking, working, m.content.isNotBlank()), talk = m.content.length / 16)
            Text(
                bot.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Column(Modifier.padding(start = 34.dp, top = 6.dp)) {
            if (m.reasoning.isNotBlank()) {
                Surface(
                    onClick = { showThoughts = !showThoughts },
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Psychology, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(stringResource(R.string.bot_thought), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 6.dp))
                            Icon(if (showThoughts) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        AnimatedVisibility(showThoughts) {
                            Text(
                                m.reasoning,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
            when {
                m.content.isNotBlank() -> {
                    SelectionContainer { MarkdownText(m.content, MaterialTheme.colorScheme.onSurface) }
                    if (!thinking) ReplyActions(m.content, canRegenerate, onRegenerate)
                }
                thinking -> Text(
                    stringResource(if (working) R.string.bot_working else R.string.bot_thinking),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ErrorStrip(err: BotEvent.Error, onDismiss: () -> Unit, onConnect: () -> Unit, onOpenSettings: () -> Unit) {
    val text = when (err.kind) {
        BotEvent.Error.Kind.NotConnected -> stringResource(R.string.bot_need_connect)
        BotEvent.Error.Kind.NoKey -> stringResource(R.string.bot_need_key)
        BotEvent.Error.Kind.BadUrl -> stringResource(R.string.bot_error_url)
        BotEvent.Error.Kind.Auth -> stringResource(R.string.bot_error_auth)
        BotEvent.Error.Kind.Unreachable -> stringResource(R.string.bot_error_unreachable) + if (err.detail.isNotBlank()) " (${err.detail})" else ""
        BotEvent.Error.Kind.Other -> err.detail
    }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            when (err.kind) {
                BotEvent.Error.Kind.NotConnected -> TextButton(onClick = onConnect) { Text(stringResource(R.string.link_connect)) }
                BotEvent.Error.Kind.NoKey, BotEvent.Error.Kind.BadUrl, BotEvent.Error.Kind.Auth -> TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.nav_settings)) }
                else -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_ok)) }
            }
        }
    }
}

/** One big rounded container, as in Grok and Gemini; the send button morphs into a stop square while streaming. */
@Composable
private fun Composer(hint: String, value: String, onValueChange: (String) -> Unit, streaming: Boolean, onSend: () -> Unit, onStop: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(30.dp),
        tonalElevation = 2.dp,
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f), RoundedCornerShape(30.dp)),
    ) {
        Row(Modifier.padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(hint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                maxLines = 6,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                ),
            )
            val enabled = streaming || value.isNotBlank()
            // circle -> rounded square, on a spring
            val corner by animateDpAsState(
                if (streaming) 14.dp else 22.dp,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
                label = "send shape",
            )
            Surface(
                onClick = if (streaming) onStop else onSend,
                enabled = enabled,
                shape = RoundedCornerShape(corner),
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp).size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    AnimatedContent(streaming, label = "send icon") { s ->
                        if (s) Box(Modifier.size(14.dp).background(MaterialTheme.colorScheme.onPrimary, RoundedCornerShape(3.dp)))
                        else Icon(Icons.Rounded.ArrowUpward, contentDescription = null)
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(0.dp))
}

/**
 * Chinese strings put the name right after Chinese text ("我是%1$s"); a Latin name ("Lab bot") reads better with a space
 * on both sides, as Chinese typography usually adds. English strings already have their own spaces.
 */
@Composable
private fun cjkSpaced(name: String): String {
    val zh = LocalContext.current.resources.configuration.locales[0].language == "zh"
    return if (zh && name.firstOrNull()?.let { it.code < 0x2E80 } == true) " $name " else name
}

/** Small, quiet actions under a finished reply: copy it, or ask again. */
@Composable
private fun ReplyActions(content: String, canRegenerate: Boolean, onRegenerate: () -> Unit) {
    val ctx = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1500); copied = false } }
    Row(Modifier.padding(top = 2.dp).offset(x = (-10).dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("reply", content))
                copied = true
            },
            modifier = Modifier.size(38.dp),
        ) {
            Icon(
                if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                contentDescription = stringResource(R.string.bot_copy),
                tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        if (canRegenerate) {
            IconButton(onClick = onRegenerate, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.bot_regenerate), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(19.dp))
            }
        }
    }
}
