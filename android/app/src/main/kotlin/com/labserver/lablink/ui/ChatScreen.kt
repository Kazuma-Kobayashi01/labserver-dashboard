package com.labserver.lablink.ui

import androidx.compose.ui.layout.onSizeChanged
import com.kyant.backdrop.backdrops.layerBackdrop
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Settings
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
import com.labserver.lablink.data.hasSandbox
import com.labserver.lablink.data.hasTerminal
import com.labserver.lablink.data.Bot
import com.labserver.lablink.data.ChatMessage
import com.labserver.lablink.data.ReplyPart
import com.labserver.lablink.data.ToolRun
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
    onOpenTerminal: (Bot) -> Unit,
) {
    val app = LocalContext.current.labApp
    val ui by app.chat.ui.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var chatMenu by remember { mutableStateOf(false) }
    var listMenu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<com.labserver.lablink.data.Conversation?>(null) }
    var deleting by remember { mutableStateOf<com.labserver.lablink.data.Conversation?>(null) }
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

    // every bot lights the screen with its own colour. The aurora and the messages are one layer, recorded as the
    // backdrop; the top bar and the composer float over it as liquid glass that blurs and bends what scrolls beneath
    val backdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
    val density = androidx.compose.ui.platform.LocalDensity.current
    var topPx by remember { mutableStateOf(0) }
    var bottomPx by remember { mutableStateOf(0) }
    val topPad = with(density) { topPx.toDp() }
    val bottomPad = with(density) { bottomPx.toDp() }
    val detail by app.prefs.chatDetail.collectAsStateWithLifecycle()
    androidx.compose.runtime.CompositionLocalProvider(LocalAccent provides botColor, LocalBackdrop provides backdrop, LocalTilt provides rememberTilt(), LocalChatDetail provides detail) {
    Box(Modifier.fillMaxSize().imePadding()) {
        // the lights hold still: anything moving in this layer would make all the glass above it re-render every frame
        AuroraBackground(botColor, Modifier.fillMaxSize().layerBackdrop(backdrop), alive = false) {
            Box(Modifier.fillMaxSize()) {
                if (ui.messages.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(top = topPad, bottom = bottomPad)) { EmptyState(ui.bot, onSuggest = { app.chat.send(it) }) }
                } else {
                    LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topPad + 8.dp, bottom = bottomPad + 12.dp),
                    ) {
                        val msgs = ui.messages
                        // the question the bot is waiting on sits under its reply (the list runs bottom up)
                        ui.approval?.let { req ->
                            item(key = "approval-${req.requestId}-${req.runId}") {
                                ApprovalCard(req, busy = ui.answering, onAnswer = app.chat::answerApproval, modifier = Modifier.animateItem(fadeInSpec = Springs.soft(), placementSpec = Springs.soft(), fadeOutSpec = Springs.snappy()).padding(start = 34.dp))
                            }
                        }
                        items(msgs.asReversed(), key = { it.id }) { m ->
                            val last = m.id == msgs.last().id
                            if (m.role == "user") UserBubble(
                                m, botColor, { viewing = it },
                                onRetry = app.chat::regenerate,
                                onCopy = { copyText(ctx, m.content) },
                                onEdit = { app.chat.retract(m.id) { text -> input = if (input.isBlank()) text else text + "\n" + input } },
                                modifier = Modifier.animateItem(fadeInSpec = Springs.soft(), placementSpec = Springs.soft(), fadeOutSpec = Springs.snappy()),
                            )
                            else BotReply(
                                m, ui.bot, thinking = last && ui.streaming, working = last && ui.working,
                                canRegenerate = last && !ui.streaming, onRegenerate = app.chat::regenerate,
                                speaking = speaking == m.id,
                                onSpeak = { app.speaker.toggle(m.id, m.content) { android.os.Handler(android.os.Looper.getMainLooper()).post { android.widget.Toast.makeText(ctx, ttsUnavailable, android.widget.Toast.LENGTH_SHORT).show() } } },
                                onShare = { shareText(ctx, ui.bot.name, m.content) },
                                modifier = Modifier.animateItem(fadeInSpec = Springs.soft(), placementSpec = Springs.soft(), fadeOutSpec = Springs.snappy()),
                            )
                        }
                    }
                }
            }

        }

        // scroll edge effect (as in iOS 26): what slides under the bars fades into the background instead of showing
        // through in pieces between the buttons; the glass itself still refracts the unfaded layer
        val edge = MaterialTheme.colorScheme.surface
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { topPx = it.height }
                .background(Brush.verticalGradient(0f to edge.copy(alpha = 0.97f), 0.72f to edge.copy(alpha = 0.9f), 1f to edge.copy(alpha = 0f)))
                .padding(bottom = 18.dp),
        ) {
            // the top bar floats: a round menu button, the bot capsule in the middle, the chat's actions on the right
        // one row: the menu, the bot capsule in the space left between the buttons (never under them), the actions
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(Icons.Rounded.Menu, null, onOpenDrawer, dot = tunnelDotColor(tunnel))
            Box(Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                Row(
                    Modifier.widthIn(max = 230.dp).liquidButton(RoundedCornerShape(50), onClick = { showPicker = true }, glow = botColor)
                        .padding(start = 6.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // the orb in the bar is alive too: it fidgets now and then while nothing is happening
                    val barOrb = rememberOrbController()
                    BotAvatar(32.dp, color = botColor, mood = liveMood, talk = (live?.content?.length ?: 0) / 16, alive = false, controller = barOrb)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f, fill = false)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(ui.bot.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (ui.bot.hasSandbox) {
                                Spacer(Modifier.width(5.dp))
                                Box(Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary))
                            }
                        }
                        // the open chat's title, or what the bot is (as ChatGPT shows the model under its name)
                        Text(
                            ui.conversation?.title?.takeIf { it.isNotBlank() } ?: stringResource(if (ui.bot.hasSandbox) R.string.bot_sandbox_label else R.string.bot_new_chat),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(Icons.Rounded.ExpandMore, contentDescription = stringResource(R.string.bots_title), modifier = Modifier.padding(start = 2.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // this bot's chats: start a new one, open, rename, pin, share or delete one
                Box {
                    GlassIconButton(Icons.Outlined.Forum, stringResource(R.string.chats_list), { listMenu = true })
                    ConversationListMenu(
                        listMenu, ui.conversations, ui.conversationId, ui.busy, onDismiss = { listMenu = false },
                        onNew = app.chat::newConversation, onOpen = app.chat::openConversation,
                        onRename = { renaming = it }, onPin = { app.chat.setPinned(it.id, !it.pinned) },
                        onShare = { shareConversation(app, it, ui.bot.name) }, onDelete = { deleting = it },
                    )
                }
                Box {
                    GlassIconButton(Icons.Rounded.MoreVert, stringResource(R.string.chat_more), { chatMenu = true })
                    BotMoreMenu(
                        chatMenu, onDismiss = { chatMenu = false },
                        onTerminal = if (ui.bot.hasTerminal) ({ onOpenTerminal(ui.bot) }) else null, onEditBot = { onEditBot(ui.bot) }, onSettings = onOpenSettings,
                    )
                }
            }
        }

            AnimatedVisibility(tunnel !is TunnelState.On, enter = springExpand, exit = springShrink) {
                OfflineBanner(tunnel, onConnect)
            }

        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { bottomPx = it.height }
                .background(Brush.verticalGradient(0f to edge.copy(alpha = 0f), 0.35f to edge.copy(alpha = 0.7f), 1f to edge.copy(alpha = 0.9f)))
                .padding(top = 18.dp),
        ) {
                // jump back to the newest message after scrolling up
                androidx.compose.animation.AnimatedVisibility(
                    jumpVisible, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 6.dp),
                    enter = fadeIn(Springs.snappy()) + scaleIn(Springs.bouncy(), initialScale = 0.4f), exit = fadeOut(Springs.snappy()) + scaleOut(Springs.snappy(), targetScale = 0.6f),
                ) {
                    GlassIconButton(Icons.Rounded.ArrowDownward, null, { scope.launch { listState.animateScrollToItem(0) } }, size = 42.dp)
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
    }
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
    renaming?.let { c -> RenameDialog(c.title, onDone = { app.chat.renameConversation(c.id, it); renaming = null }, onDismiss = { renaming = null }) }
    deleting?.let { c -> DeleteDialog(c.title, onConfirm = { app.chat.deleteConversation(c.id); deleting = null }, onDismiss = { deleting = null }) }
}

/** The top bar's ⋮: a terminal on the bot's machine, the bot's own settings, and the app's settings. */
@Composable
private fun BotMoreMenu(expanded: Boolean, onDismiss: () -> Unit, onTerminal: (() -> Unit)?, onEditBot: () -> Unit, onSettings: () -> Unit) {
    androidx.compose.material3.DropdownMenu(
        expanded = expanded, onDismissRequest = onDismiss,
        shape = RoundedCornerShape(22.dp), containerColor = if (isDark()) Color(0xFF26262F) else Color.White, shadowElevation = 18.dp, tonalElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        modifier = Modifier.padding(horizontal = 6.dp),
    ) {
        // only the admin bot and the bots with a sandbox have a terminal
        if (onTerminal != null) androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.ssh_open)) },
            leadingIcon = { Icon(Icons.Rounded.Terminal, contentDescription = null) },
            onClick = { onDismiss(); onTerminal() },
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.bot_editor_edit)) },
            leadingIcon = { Icon(Icons.Rounded.Tune, contentDescription = null) },
            onClick = { onDismiss(); onEditBot() },
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.nav_settings)) },
            leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
            onClick = { onDismiss(); onSettings() },
        )
    }
}

@Composable
private fun OfflineBanner(tunnel: TunnelState, onConnect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).liquidGlass(RoundedCornerShape(50), tint = 0.45f)
            .padding(start = 16.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulseDot(tunnelDotColor(tunnel), pulsing = tunnel is TunnelState.Connecting)
        Text(
            if (tunnel is TunnelState.Connecting) stringResource(R.string.link_connecting) else stringResource(R.string.bot_need_connect),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
        )
        if (tunnel !is TunnelState.Connecting) {
            Text(
                stringResource(R.string.link_connect), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.pressable(onConnect).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else Spacer(Modifier.height(34.dp))
    }
}

/** A status light; while something is under way it breathes. */
@Composable
private fun PulseDot(color: Color, pulsing: Boolean) {
    val loop = androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse")
    val k by loop.animateFloat(
        0f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1200)), label = "pulse",
    )
    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
        if (pulsing) Box(Modifier.size(8.dp + 10.dp * k).clip(CircleShape).background(color.copy(alpha = 0.35f * (1f - k))))
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
    }
}

/**
 * Hero moment: the bot's orb inside soft rings that breathe out from it, the greeting painted in the bot's colours, and
 * four glass suggestion tiles that rise in one after another.
 */
@Composable
private fun EmptyState(bot: Bot, onSuggest: (String) -> Unit) {
    val botColor = Color(bot.color)
    val cards: List<Pair<ImageVector, Int>> = listOf(
        Icons.Rounded.WavingHand to R.string.bot_suggest_1,
        Icons.Rounded.AutoAwesome to R.string.bot_suggest_2,
        Icons.Rounded.Lightbulb to R.string.bot_suggest_3,
        Icons.Rounded.Event to R.string.bot_suggest_4,
    )
    // the rings breathe out a few times when the screen opens, then rest (an endless loop would keep the GPU busy)
    val ringAnim = remember(bot.id) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(bot.id) {
        repeat(3) {
            ringAnim.snapTo(0f)
            ringAnim.animateTo(1f, androidx.compose.animation.core.tween(3600, easing = androidx.compose.animation.core.LinearEasing))
        }
    }
    val ring = ringAnim.value
    var shown by remember(bot.id) { mutableStateOf(false) }
    LaunchedEffect(bot.id) { shown = true }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(250.dp), contentAlignment = Alignment.Center) {
            // three rings leave the orb in turn and fade as they widen
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                for (k in 0 until 3) {
                    val f = (ring + k / 3f) % 1f
                    drawCircle(botColor.copy(alpha = 0.22f * (1f - f)), radius = size.minDimension * (0.28f + 0.22f * f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
                }
                drawCircle(Brush.radialGradient(listOf(botColor.copy(alpha = 0.30f), Color.Transparent)), radius = size.minDimension * 0.5f)
            }
            BotHero(128.dp, color = botColor)
        }
        GradientText(
            stringResource(R.string.bot_hello_named, cjkSpaced(bot.name)),
            listOf(MaterialTheme.colorScheme.onSurface, accentInk(lerp(botColor, MaterialTheme.colorScheme.onSurface, 0.15f))),
            MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
            textAlign = TextAlign.Center,
        )
        Text(
            bot.role.ifBlank { stringResource(R.string.bot_hello_sub) },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp, bottom = 28.dp),
        )
        cards.chunked(2).forEachIndexed { r, row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEachIndexed { c, (icon, res) ->
                    val label = stringResource(res)
                    val n = r * 2 + c
                    // each tile rises in a little after the one before
                    val rise by androidx.compose.animation.core.animateFloatAsState(
                        if (shown) 1f else 0f,
                        androidx.compose.animation.core.tween(520, delayMillis = 90 * n, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                        label = "tile",
                    )
                    Column(
                        Modifier.weight(1f).heightIn(min = 96.dp)
                            .graphicsLayer { alpha = rise; translationY = (1f - rise) * 40f }
                            .pressable { onSuggest(label) }
                            .glass(RoundedCornerShape(24.dp), elevation = 6.dp, glow = botColor)
                            .padding(14.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Box(Modifier.size(34.dp).clip(CircleShape).background(accentInk(botColor).copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                            Icon(icon, contentDescription = null, tint = accentInk(botColor), modifier = Modifier.size(19.dp))
                        }
                        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, modifier = Modifier.padding(top = 12.dp))
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
        // your words in a vivid bubble of the bot's colour, its tail corner towards the edge
        val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 6.dp)
        val dark = isDark()
        Box(
            Modifier.padding(start = 48.dp).widthIn(max = 300.dp)
                .shadow(8.dp, shape, ambientColor = botColor.copy(alpha = 0.35f), spotColor = botColor.copy(alpha = 0.35f))
                .clip(shape).background(bubbleBrush(botColor, dark)),
        ) {
            Column(Modifier.padding(horizontal = if (m.attachments.isEmpty()) 16.dp else 10.dp, vertical = if (m.attachments.isEmpty()) 11.dp else 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // pictures and files first, then the words (as in Claude and ChatGPT)
                SentAttachments(m.attachments, onView)
                if (m.content.isNotBlank()) SelectionContainer {
                    // the user's own text takes Markdown too (a pasted code block shows as one)
                    MarkdownText(m.content, onAccent(botColor), Modifier.padding(horizontal = if (m.attachments.isEmpty()) 0.dp else 6.dp))
                }
            }
        }
    }
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    Row(Modifier.padding(top = 2.dp)) {
        if (m.content.isNotBlank()) IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.bot_copy), tint = quiet, modifier = Modifier.size(15.dp))
        }
        // take it back: the message and what followed it go, and it returns to the input box to be changed and sent again
        IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.bot_retract), tint = quiet, modifier = Modifier.size(15.dp))
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
    val detail = LocalChatDetail.current
    var showThoughts by rememberSaveable(m.id, detail) { mutableStateOf(detail.thoughtsOpen) }
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BotAvatar(26.dp, color = Color(bot.color), mood = orbMood(thinking, working, m.content.isNotBlank()), talk = m.content.length / 16)
            Text(
                bot.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Column(Modifier.padding(start = 34.dp, top = 6.dp)) {
            if (m.reasoning.isNotBlank() && detail.showThoughts) {
                Box(
                    Modifier.padding(bottom = 10.dp).pressable { showThoughts = !showThoughts }
                        .glass(RoundedCornerShape(18.dp), elevation = 0.dp),
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Psychology, contentDescription = null, modifier = Modifier.size(18.dp), tint = accentInk(Color(bot.color)))
                            Text(
                                if (m.thinkingMs >= 1000) stringResource(R.string.bot_thought_for, (m.thinkingMs / 1000).toInt()) else stringResource(R.string.bot_thought),
                                style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 6.dp),
                            )
                            Icon(if (showThoughts) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        AnimatedVisibility(showThoughts, enter = springExpand, exit = springShrink) {
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
                m.content.isNotBlank() || m.tools.isNotEmpty() -> {
                    ReplyBody(m.content, bot.id, m.tools)
                    // between a command and the next words, say it is still at work
                    if (thinking && m.content.length <= (m.tools.maxOfOrNull { it.at } ?: -1)) {
                        Box(Modifier.padding(top = 10.dp)) { ShimmerText(stringResource(if (working) R.string.bot_working else R.string.bot_thinking)) }
                    }
                    if (!thinking && m.content.isNotBlank()) ReplyActions(replyText(m.content), canRegenerate, onRegenerate, speaking, onSpeak, onShare)
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
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).liquidGlass(RoundedCornerShape(20.dp), tint = 0.6f)
            .height(androidx.compose.foundation.layout.IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(MaterialTheme.colorScheme.error))
        Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 12.dp).size(20.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(start = 10.dp, top = 10.dp, bottom = 10.dp))
        when (err.kind) {
            BotEvent.Error.Kind.NotConnected -> PillButton(onClick = onConnect) { Text(stringResource(R.string.link_connect)) }
            BotEvent.Error.Kind.NoKey, BotEvent.Error.Kind.BadUrl, BotEvent.Error.Kind.Auth -> PillButton(onClick = onOpenSettings) { Text(stringResource(R.string.nav_settings)) }
            else -> PillButton(onClick = onDismiss) { Text(stringResource(R.string.common_ok)) }
        }
    }
}

/** One big rounded container, as in Grok and Gemini; the send button morphs into a stop square while streaming. */
@Composable
private fun Composer(
    hint: String, value: String, onValueChange: (String) -> Unit, streaming: Boolean, onSend: () -> Unit, onStop: () -> Unit, onVoice: () -> Unit,
    modelLabel: String, onModel: () -> Unit, pending: List<Pending>, canSend: Boolean, onAttach: () -> Unit, onRemove: (String) -> Unit,
) {
    val accent = LocalAccent.current
    Box(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)
            .liquidGlass(RoundedCornerShape(28.dp), tint = 0.42f, blur = 4.dp, glow = accent),
    ) {
      // every round control is 40 dp and sits 8 dp from the edges, so its outline runs along the 28 dp corner it is in
      // (8 + 20 = 28): send in the top right corner, attach in the bottom left, the microphone beside it
      Column(Modifier.padding(8.dp)) {
        // what is attached to the message being written, as in Claude and opencode
        androidx.compose.animation.AnimatedVisibility(
            pending.isNotEmpty(),
            enter = androidx.compose.animation.expandVertically(spring(dampingRatio = 0.8f, stiffness = 400f)) + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
        ) { PendingRow(pending, onRemove, Modifier.padding(bottom = 4.dp)) }
        Row(verticalAlignment = Alignment.Top) {
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
            // send, or stop while a reply streams; with nothing to send it rests, dimmed
            val enabled = streaming || (canSend && (value.isNotBlank() || pending.any { it.state == Pending.State.Ready }))
            // circle -> rounded square, on a spring
            val corner by animateDpAsState(
                if (streaming) 12.dp else 20.dp,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
                label = "send shape",
            )
            val shape = RoundedCornerShape(corner)
            val dark = isDark()
            Box(
                Modifier.size(Control).pressable { if (enabled) (if (streaming) onStop else onSend)() }
                    .then(if (enabled) Modifier.shadow(10.dp, shape, ambientColor = accent, spotColor = accent) else Modifier)
                    .clip(shape)
                    .background(if (enabled) bubbleBrush(accent, dark) else androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))),
                contentAlignment = Alignment.Center,
            ) {
                val ink = if (enabled) onAccent(accent) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                AnimatedContent(streaming, label = "send icon") { stop ->
                    if (stop) Box(Modifier.size(14.dp).background(ink, RoundedCornerShape(3.dp)))
                    else Icon(Icons.Rounded.ArrowUpward, contentDescription = null, tint = ink)
                }
            }
        }
        // the tools: attach, speak, and the model and thinking level of this chat
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToolButton(Icons.Rounded.Add, stringResource(R.string.attach_title), onAttach)
            ToolButton(Icons.Rounded.Mic, stringResource(R.string.voice_input), onVoice)
            Row(
                Modifier.height(Control).pressable(onModel).clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)).padding(start = 14.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(modelLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
                Icon(Icons.Rounded.ExpandMore, contentDescription = stringResource(R.string.model_title), modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
      }
    }
    Spacer(Modifier.height(0.dp))
}

/** The size of the composer's round controls (see the note on their placement in [Composer]). */
private val Control = 40.dp

/** A quiet round tool in the composer. */
@Composable
private fun ToolButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(Control).pressable(onClick).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = label, modifier = Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
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
private fun ReplyBody(content: String, botId: String, tools: List<ToolRun> = emptyList()) {
    // the words, with each command window between the words it came between
    val group = LocalChatDetail.current.groupTools
    val pieces = remember(content, tools, group) { interleave(content, tools).let { if (group) gatherTools(it) else it } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (piece in pieces) when (piece) {
            is ToolRun -> ToolCard(piece)
            is ToolBatch -> ToolGroup(piece.runs)
            is ReplyPart.Text -> SelectionContainer { MarkdownText(piece.markdown, MaterialTheme.colorScheme.onSurface) }
            is ReplyPart.Image -> ReplyImage(piece.src, botId, piece.alt)
        }
    }
}

/** Tool runs that came one after another, shown as one line in the minimal display. */
private class ToolBatch(val runs: List<ToolRun>)

/** Folds each run of consecutive [ToolRun]s in [pieces] into one [ToolBatch]. */
private fun gatherTools(pieces: List<Any>): List<Any> {
    val out = ArrayList<Any>()
    var batch = ArrayList<ToolRun>()
    fun flush() { if (batch.isNotEmpty()) { out.add(ToolBatch(batch)); batch = ArrayList() } }
    for (p in pieces) if (p is ToolRun) batch.add(p) else { flush(); out.add(p) }
    flush()
    return out
}

/** The reply cut at the places its tools ran: text parts (and pictures) and tool runs, in order. */
private fun interleave(content: String, tools: List<ToolRun>): List<Any> {
    if (tools.isEmpty()) return splitReply(content)
    val out = ArrayList<Any>()
    var from = 0
    for (t in tools.sortedBy { it.at }) {
        val to = t.at.coerceIn(from, content.length)
        if (to > from) out.addAll(splitReply(content.substring(from, to)).filter { it !is ReplyPart.Text || it.markdown.isNotBlank() })
        out.add(t)
        from = to
    }
    if (from < content.length) out.addAll(splitReply(content.substring(from)).filter { it !is ReplyPart.Text || it.markdown.isNotBlank() })
    return out
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
