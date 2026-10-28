package com.labserver.lablink.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import com.labserver.lablink.data.Attachment
import com.labserver.lablink.data.Limits
import com.labserver.lablink.net.Pending
import com.labserver.lablink.data.isSandbox
import com.labserver.lablink.data.Bot
import com.labserver.lablink.data.ChatMessage
import com.labserver.lablink.data.ReplyPart
import com.labserver.lablink.data.replyText
import com.labserver.lablink.data.splitReply
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
    var chatMenu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val speaking by app.speaker.speaking.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }
    var showModel by remember { mutableStateOf(false) }
    var showAttach by remember { mutableStateOf(false) }
    var showLibrary by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Attachment?>(null) }
    val ctx = LocalContext.current
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(Limits.MAX_IMAGES)) { app.chat.attach(it) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { app.chat.attach(it) }
    var cameraFile by remember { mutableStateOf<Pair<java.io.File, android.net.Uri>?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        cameraFile?.let { (f, uri) -> if (ok) app.chat.attach(listOf(uri)) else f.delete() }
    }
    // speech to text through the phone's own recogniser; what it heard goes into the input box
    val voiceUnavailable = stringResource(R.string.voice_unavailable)
    val ttsUnavailable = stringResource(R.string.tts_unavailable)
    val listen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val heard = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        if (heard.isNotBlank()) input = if (input.isBlank()) heard else input.trimEnd() + " " + heard
    }
    val voice = {
        val i = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
        try { listen.launch(i) } catch (_: android.content.ActivityNotFoundException) { android.widget.Toast.makeText(ctx, voiceUnavailable, android.widget.Toast.LENGTH_SHORT).show() }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val botColor = Color(ui.bot.color)
    val surface = MaterialTheme.colorScheme.surface
    val tint = if (isSystemInDarkTheme()) 0.16f else 0.09f
    val haptic = LocalHapticFeedback.current
    val jumpVisible by remember { derivedStateOf { listState.firstVisibleItemIndex > 1 } }
    // the reply being written right now, if any: drives the orb's mood in the top bar
    val live = ui.messages.lastOrNull()?.takeIf { ui.streaming && it.role == "assistant" }
    val liveMood = orbMood(ui.streaming, ui.working, !live?.content.isNullOrBlank(), failed = ui.error != null)

    // newest message at index 0 (the list is reversed), so growing replies stay pinned to the bottom
    LaunchedEffect(ui.messages.size, ui.bot.id, ui.approval?.requestId) { if (ui.messages.isNotEmpty()) listState.animateScrollToItem(0) }

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
                    // the orb in the bar is alive too: it fidgets now and then while nothing is happening
                    val barOrb = rememberOrbController()
                    BotAvatar(28.dp, color = botColor, mood = liveMood, talk = (live?.content?.length ?: 0) / 16, alive = true, controller = barOrb)
                    Spacer(Modifier.width(8.dp))
                    Text(ui.bot.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
                    if (ui.bot.isSandbox) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.bot_sandbox_label), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                    Icon(Icons.Rounded.ExpandMore, contentDescription = stringResource(R.string.bots_title), modifier = Modifier.size(20.dp))
                }
            },
            actions = {
                // a new chat keeps this one in the history (the drawer), as in ChatGPT
                IconButton(onClick = app.chat::newConversation, enabled = ui.messages.isNotEmpty()) {
                    Icon(Icons.Rounded.EditNote, contentDescription = stringResource(R.string.bot_new_chat))
                }
                val open = ui.conversation
                if (open != null) Box {
                    IconButton(onClick = { chatMenu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.chat_more)) }
                    ConversationMenu(
                        chatMenu, open.pinned, onDismiss = { chatMenu = false },
                        onRename = { renaming = true }, onPin = { app.chat.setPinned(open.id, !open.pinned) },
                        onShare = { shareConversation(app, open, ui.bot.name) }, onDelete = { deleting = true },
                    )
                }
            },
            windowInsets = WindowInsets.statusBars,
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
                    // the question the bot is waiting on sits under its reply (the list runs bottom up)
                    ui.approval?.let { req ->
                        item(key = "approval-${req.requestId}-${req.runId}") {
                            ApprovalCard(req, busy = ui.answering, onAnswer = app.chat::answerApproval, modifier = Modifier.animateItem().padding(start = 34.dp))
                        }
                    }
                    items(msgs.asReversed(), key = { it.id }) { m ->
                        val last = m.id == msgs.last().id
                        if (m.role == "user") UserBubble(
                            m, botColor, { viewing = it },
                            onRetry = app.chat::regenerate,
                            onCopy = { copyText(ctx, m.content) },
                            onEdit = { app.chat.retract(m.id) { text -> input = if (input.isBlank()) text else text + "\n" + input } },
                            modifier = Modifier.animateItem(),
                        )
                        else BotReply(
                            m, ui.bot, thinking = last && ui.streaming, working = last && ui.working,
                            canRegenerate = last && !ui.streaming, onRegenerate = app.chat::regenerate,
                            speaking = speaking == m.id,
                            onSpeak = { app.speaker.toggle(m.id, m.content) { android.os.Handler(android.os.Looper.getMainLooper()).post { android.widget.Toast.makeText(ctx, ttsUnavailable, android.widget.Toast.LENGTH_SHORT).show() } } },
                            onShare = { shareText(ctx, ui.bot.name, m.content) },
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
            onVoice = voice,
            modelLabel = pickLabel(ui.catalog, ui.effectivePick),
            onModel = { showModel = true },
            pending = ui.pending, canSend = ui.canSend,
            onAttach = { showAttach = true },
            onRemove = app.chat::removePending,
        )
    }

    if (showAttach) {
        AttachSheet(
            onCamera = { showAttach = false; app.attachments.newCameraFile(ctx).also { cameraFile = it }.let { takePhoto.launch(it.second) } },
            onPhotos = { showAttach = false; pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onFiles = { showAttach = false; pickFiles.launch(arrayOf("*/*")) },
            onLibrary = { showAttach = false; showLibrary = true },
            onDismiss = { showAttach = false },
        )
    }
    viewing?.let { ImageViewer(it) { viewing = null } }
    if (showLibrary) LibraryPickerSheet(onAdd = app.chat::attachFromLibrary, onDismiss = { showLibrary = false })
    if (showModel) {
        ModelPickerSheet(
            catalog = ui.catalog, current = ui.pick,
            defaultSubtitle = if (ui.bot.pick.isDefault) stringResource(R.string.model_default_server) else pickLabel(ui.catalog, ui.bot.pick),
            onPick = app.chat::setPick, onDismiss = { showModel = false },
        )
    }

    if (showPicker) {
        BotPickerSheet(
            bots = ui.bots,
            currentId = ui.bot.id,
            sandboxCount = ui.sandboxCount, limit = ui.limit, running = ui.running,
            onSelect = { app.chat.select(it.id); showPicker = false },
            onEdit = { onEditBot(it); showPicker = false },
            onNew = { onNewBot(); showPicker = false },
            onDismiss = { showPicker = false },
        )
    }
    ui.conversation?.let { c ->
        if (renaming) RenameDialog(c.title, onDone = { app.chat.renameConversation(c.id, it); renaming = false }, onDismiss = { renaming = false })
        if (deleting) DeleteDialog(c.title, onConfirm = { app.chat.deleteConversation(c.id); deleting = false }, onDismiss = { deleting = false })
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
private fun UserBubble(m: ChatMessage, botColor: Color, onView: (Attachment) -> Unit, onEdit: () -> Unit, onRetry: () -> Unit, onCopy: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        if (m.failed) {
            // it never got through: a red dot with a white !, a tap sends it again
            val label = stringResource(R.string.bot_send_failed)
            Box(
                Modifier.padding(end = 8.dp).size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error)
                    .clickable(onClickLabel = label, onClick = onRetry),
                contentAlignment = Alignment.Center,
            ) { Text("!", color = Color.White, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge) }
        }
        Surface(
            color = lerp(MaterialTheme.colorScheme.surfaceContainerHigh, botColor, 0.12f),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 8.dp),
            modifier = Modifier.widthIn(max = 300.dp).padding(start = 48.dp),
        ) {
            Column(Modifier.padding(horizontal = if (m.attachments.isEmpty()) 16.dp else 10.dp, vertical = if (m.attachments.isEmpty()) 11.dp else 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // pictures and files first, then the words (as in Claude and ChatGPT)
                SentAttachments(m.attachments, onView)
                if (m.content.isNotBlank()) SelectionContainer {
                    // the user's own text takes Markdown too (a pasted code block shows as one)
                    MarkdownText(m.content, MaterialTheme.colorScheme.onSurface, Modifier.padding(horizontal = if (m.attachments.isEmpty()) 0.dp else 6.dp))
                }
            }
        }
    }
    Row {
        if (m.content.isNotBlank()) IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.bot_copy), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
        }
        // take it back: the message and what followed it go, and it returns to the input box to be changed and sent again
        IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.bot_retract), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
    }
    }
}

/** The bot's turn: no bubble, a page of text under its orb and name (as in Gemini and Claude). */
@Composable
private fun BotReply(
    m: ChatMessage, bot: Bot, thinking: Boolean, working: Boolean,
    canRegenerate: Boolean, onRegenerate: () -> Unit,
    speaking: Boolean, onSpeak: () -> Unit, onShare: () -> Unit, modifier: Modifier = Modifier,
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
                            Text(
                                if (m.thinkingMs >= 1000) stringResource(R.string.bot_thought_for, (m.thinkingMs / 1000).toInt()) else stringResource(R.string.bot_thought),
                                style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 6.dp),
                            )
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
                    ReplyBody(m.content, bot.id)
                    if (!thinking) ReplyActions(replyText(m.content), canRegenerate, onRegenerate, speaking, onSpeak, onShare)
                }
                thinking -> ShimmerText(stringResource(if (working) R.string.bot_working else R.string.bot_thinking))
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
        BotEvent.Error.Kind.Gone -> stringResource(R.string.bot_error_gone)
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
private fun Composer(
    hint: String, value: String, onValueChange: (String) -> Unit, streaming: Boolean, onSend: () -> Unit, onStop: () -> Unit, onVoice: () -> Unit,
    modelLabel: String, onModel: () -> Unit, pending: List<Pending>, canSend: Boolean, onAttach: () -> Unit, onRemove: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(30.dp),
        tonalElevation = 2.dp,
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f), RoundedCornerShape(30.dp)),
    ) {
      Column {
        // what is attached to the message being written, as in Claude and opencode
        androidx.compose.animation.AnimatedVisibility(
            pending.isNotEmpty(),
            enter = androidx.compose.animation.expandVertically(spring(dampingRatio = 0.8f, stiffness = 400f)) + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
        ) { PendingRow(pending, onRemove, Modifier.padding(top = 8.dp)) }
        Row(Modifier.padding(start = 4.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.Bottom) {
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
            // nothing typed: the button listens instead (as in ChatGPT)
            val listen = !streaming && value.isBlank() && pending.none { it.state == Pending.State.Ready }
            val enabled = streaming || listen || (canSend && (value.isNotBlank() || pending.any { it.state == Pending.State.Ready }))
            // circle -> rounded square, on a spring
            val corner by animateDpAsState(
                if (streaming) 14.dp else 22.dp,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
                label = "send shape",
            )
            Surface(
                onClick = when { streaming -> onStop; listen -> onVoice; else -> onSend },
                enabled = enabled,
                shape = RoundedCornerShape(corner),
                color = if (listen) MaterialTheme.colorScheme.surfaceContainerHigh else if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = if (listen) MaterialTheme.colorScheme.onSurface else if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp).size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    AnimatedContent(if (streaming) 0 else if (listen) 1 else 2, label = "send icon") { k ->
                        when (k) {
                            0 -> Box(Modifier.size(14.dp).background(MaterialTheme.colorScheme.onPrimary, RoundedCornerShape(3.dp)))
                            1 -> Icon(Icons.Rounded.Mic, contentDescription = stringResource(R.string.voice_input))
                            else -> Icon(Icons.Rounded.ArrowUpward, contentDescription = null)
                        }
                    }
                }
            }
        }
        // under the text: the attach button, and the model and thinking level of this chat as a small tag
        Row(Modifier.padding(start = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer).pressable(onAttach),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.attach_title), modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.width(8.dp))
            Row(
                Modifier.pressable(onModel).clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(modelLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
                Icon(Icons.Rounded.ExpandMore, contentDescription = stringResource(R.string.model_title), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** Small, quiet actions under a finished reply: copy, read aloud, share, or ask again. */
@Composable
private fun ReplyActions(content: String, canRegenerate: Boolean, onRegenerate: () -> Unit, speaking: Boolean, onSpeak: () -> Unit, onShare: () -> Unit) {
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
        IconButton(onClick = onSpeak, modifier = Modifier.size(38.dp)) {
            Icon(
                if (speaking) Icons.Rounded.StopCircle else Icons.AutoMirrored.Rounded.VolumeUp,
                contentDescription = stringResource(if (speaking) R.string.msg_stop_speaking else R.string.msg_speak),
                tint = if (speaking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(19.dp),
            )
        }
        IconButton(onClick = onShare, modifier = Modifier.size(38.dp)) {
            Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.msg_share), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        if (canRegenerate) {
            IconButton(onClick = onRegenerate, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.bot_regenerate), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(19.dp))
            }
        }
    }
}

private fun copyText(ctx: android.content.Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("message", text))
}

/** The reply as it is meant to be seen: Markdown for the words, the pictures in place. */
@Composable
private fun ReplyBody(content: String, botId: String) {
    val parts = remember(content) { splitReply(content) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (p in parts) when (p) {
            is ReplyPart.Text -> SelectionContainer { MarkdownText(p.markdown, MaterialTheme.colorScheme.onSurface) }
            is ReplyPart.Image -> ReplyImage(p.src, botId, p.alt)
        }
    }
}

/** "Thinking…" with a sheen running across it, as in ChatGPT. */
@Composable
private fun ShimmerText(text: String) {
    val base = MaterialTheme.colorScheme.onSurfaceVariant
    val shine = MaterialTheme.colorScheme.onSurface
    val loop = androidx.compose.animation.core.rememberInfiniteTransition(label = "shimmer")
    val x by loop.animateFloat(
        -1f, 2f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1600, easing = androidx.compose.animation.core.LinearEasing)),
        label = "sheen",
    )
    androidx.compose.foundation.layout.BoxWithConstraints {
        val w = constraints.maxWidth.toFloat().coerceAtMost(600f)
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge.merge(
                androidx.compose.ui.text.TextStyle(
                    brush = Brush.linearGradient(
                        listOf(base, shine, base),
                        start = androidx.compose.ui.geometry.Offset(w * x - 120f, 0f),
                        end = androidx.compose.ui.geometry.Offset(w * x + 120f, 0f),
                    ),
                ),
            ),
        )
    }
}
