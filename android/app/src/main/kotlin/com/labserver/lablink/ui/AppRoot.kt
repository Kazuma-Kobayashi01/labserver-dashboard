package com.labserver.lablink.ui

import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labserver.lablink.R
import com.labserver.lablink.data.Bot
import com.labserver.lablink.data.isSandbox
import com.labserver.lablink.net.ManagerResult
import com.labserver.lablink.labApp
import com.labserver.lablink.tunnel.TunnelState
import kotlinx.coroutines.launch

enum class Screen { Chat, HomeLab, Settings, Trash, Library }

/**
 * Chat is home (as in Gemini / ChatGPT / Claude); everything else lives in the navigation drawer: the connection
 * switch, the bots, the home-lab panels and settings.
 */
@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    val app = ctx.labApp
    val tunnel by app.tunnel.state.collectAsStateWithLifecycle()
    val port by app.tunnel.proxyPort.collectAsStateWithLifecycle()
    val chat by app.chat.ui.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.Chat) }
    var editing by remember { mutableStateOf<Bot?>(null) }
    var creating by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    // a panel's WebView needs the edge swipes for itself, so the drawer gesture is off while one is open
    var panelOpen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // a share from another app lands in the chat
    val shared by app.chatRequested.collectAsStateWithLifecycle()
    LaunchedEffect(shared) { if (shared > 0) screen = Screen.Chat }

    // the sandbox bots live on the server: read them when the tunnel comes up and whenever the drawer opens
    LaunchedEffect(port) { if (app.chat.managerAvailable()) app.chat.refresh() }
    LaunchedEffect(drawer.isOpen) { if (drawer.isOpen && app.chat.managerAvailable()) app.chat.refresh() }

    // WebViews in this app go through the tunnel's loopback proxy whenever it is up (and nowhere otherwise)
    LaunchedEffect(port) { WebProxy.apply(ctx, port) }
    BackHandler(enabled = screen != Screen.Chat && !drawer.isOpen) { screen = Screen.Chat }

    val toggleTunnel = { if (tunnel.isUpOrBusy) app.tunnel.disconnect() else app.tunnel.connect() }
    fun go(to: Screen) {
        screen = to
        scope.launch { drawer.close() }
    }
    val needConnect = stringResource(R.string.panels_need_connect)
    val fullMsg = stringResource(R.string.bots_full, chat.sandboxCount, chat.limit)
    fun newBot() {
        if (chat.full) scope.launch { snackbar.showSnackbar(fullMsg) } else { saveError = null; creating = true }
    }
    fun failed(r: ManagerResult<*>) {
        if (r is ManagerResult.Fail) saveError = managerErrorText(ctx, r)
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = !panelOpen || drawer.isOpen,
        drawerContent = {
            // about four fifths of a phone, so the chat stays in view beside it (as in ChatGPT)
            ModalDrawerSheet(Modifier.width(296.dp)) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 16.dp)) {
                    Row(Modifier.padding(start = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        BotHero(44.dp, interactive = false)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    ConnectionCard(tunnel, onToggle = toggleTunnel, compact = true)

                    Text(
                        stringResource(R.string.bots_count, chat.sandboxCount, chat.limit),
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
                    )
                    // each bot with its own chats: the open bot unfolds into its history, a tap on another one switches to it
                    chat.bots.forEach { bot ->
                        val open = bot.id == chat.bot.id
                        NavigationDrawerItem(
                            icon = { BotAvatar(30.dp, color = Color(bot.color)) },
                            label = { Text(bot.name, maxLines = 1, fontWeight = if (open) FontWeight.SemiBold else FontWeight.Normal) },
                            selected = false,
                            onClick = { if (open) go(Screen.Chat) else app.chat.select(bot.id) },
                            badge = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (!open && chat.busy.isNotEmpty() && app.chat.isBusy(bot.id)) {
                                        androidx.compose.foundation.layout.Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape))
                                    }
                                    IconButton(onClick = { saveError = null; editing = bot; scope.launch { drawer.close() } }) {
                                        Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.bots_edit))
                                    }
                                    Icon(
                                        if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp),
                                    )
                                }
                            },
                        )
                        androidx.compose.animation.AnimatedVisibility(open) {
                            Column(Modifier.padding(start = 20.dp, bottom = 8.dp)) {
                                ConversationSection(
                                    conversations = chat.conversations, currentId = chat.conversationId, busy = chat.busy, botName = chat.bot.name,
                                    onNew = { app.chat.newConversation(); go(Screen.Chat) },
                                    onOpen = { app.chat.openConversation(it); go(Screen.Chat) },
                                )
                            }
                        }
                    }
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                        label = { Text(if (chat.full) stringResource(R.string.bots_new_full, chat.sandboxCount, chat.limit) else stringResource(R.string.bots_new)) },
                        selected = false,
                        onClick = { newBot(); scope.launch { drawer.close() } },
                    )
                    HorizontalDivider(Modifier.padding(vertical = 12.dp, horizontal = 16.dp))
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Rounded.PhotoLibrary, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_library)) },
                        selected = screen == Screen.Library,
                        onClick = { go(Screen.Library) },
                    )
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_trash) + if (chat.trash.isNotEmpty()) " (${chat.trash.size})" else "") },
                        selected = screen == Screen.Trash,
                        onClick = { go(Screen.Trash) },
                    )

                    HorizontalDivider(Modifier.padding(vertical = 12.dp, horizontal = 16.dp))
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Rounded.Apps, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_home_lab)) },
                        selected = screen == Screen.HomeLab,
                        onClick = { go(Screen.HomeLab) },
                    )
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_settings)) },
                        selected = screen == Screen.Settings,
                        onClick = { go(Screen.Settings) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        },
    ) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { inner ->
            val ld = androidx.compose.ui.platform.LocalLayoutDirection.current
            fun contentPadding(p: androidx.compose.foundation.layout.PaddingValues) =
                androidx.compose.foundation.layout.PaddingValues(start = p.calculateStartPadding(ld), end = p.calculateEndPadding(ld), bottom = p.calculateBottomPadding())
            // screens ease into each other: a page opening slides in from the right over the chat and the chat settles back
            AnimatedContent(
                targetState = screen,
                // the top is left to each screen's own bar, so its colour runs up behind the status bar
                modifier = Modifier.padding(contentPadding(inner)).consumeWindowInsets(contentPadding(inner)),
                transitionSpec = {
                    val opening = targetState != Screen.Chat
                    val spring = spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = 0.9f, stiffness = 380f)
                    if (opening) {
                        (slideInHorizontally(spring) { it / 5 } + fadeIn(tween(220))) togetherWith (fadeOut(tween(160)) + scaleOut(tween(220), targetScale = 0.97f))
                    } else {
                        (fadeIn(tween(240)) + scaleIn(tween(240), initialScale = 0.97f)) togetherWith (slideOutHorizontally(spring) { it / 5 } + fadeOut(tween(160)))
                    }
                },
                label = "screen",
            ) { current ->
                Box(Modifier.fillMaxSize()) {
                when (current) {
                    Screen.Chat -> ChatScreen(
                        tunnel,
                        onOpenDrawer = { scope.launch { drawer.open() } },
                        onConnect = { app.tunnel.connect() },
                        onOpenSettings = { screen = Screen.Settings },
                        onNewBot = { newBot() },
                        onEditBot = { saveError = null; editing = it },
                    )
                    Screen.HomeLab -> HomeLabScreen(
                        tunnel,
                        onBack = { screen = Screen.Chat },
                        onToggle = toggleTunnel,
                        onBlocked = { scope.launch { snackbar.showSnackbar(needConnect) } },
                        onPanelOpen = { panelOpen = it },
                    )
                    Screen.Settings -> LinkScreen(tunnel, onBack = { screen = Screen.Chat })
                    Screen.Library -> LibraryScreen(
                        onBack = { screen = Screen.Chat },
                        onSendAgain = { app.chat.attachFromLibrary(it); screen = Screen.Chat },
                    )
                    Screen.Trash -> TrashScreen(
                        connected = port != 0 || app.chat.managerAvailable(), onBack = { screen = Screen.Chat },
                        onMessage = { scope.launch { snackbar.showSnackbar(it) } },
                    )
                }
                }
            }
        }
    }

    if (creating) {
        BotEditorDialog(
            initial = null, sandbox = true, canDelete = false, saving = saving, error = saveError, trashDays = chat.trashDays, catalog = chat.catalog,
            onSave = { b ->
                scope.launch {
                    saving = true; saveError = null
                    val r = app.chat.createBot(b)
                    saving = false
                    if (r is ManagerResult.Ok) { creating = false; screen = Screen.Chat } else failed(r)
                }
            },
            onDelete = {},
            onDismiss = { if (!saving) creating = false },
        )
    }
    editing?.let { bot ->
        BotEditorDialog(
            initial = bot, sandbox = bot.isSandbox, canDelete = bot.isSandbox, saving = saving, error = saveError, trashDays = chat.trashDays, catalog = chat.catalog,
            onSave = { b ->
                if (!bot.isSandbox) { app.chat.saveBot(b); editing = null } else scope.launch {
                    saving = true; saveError = null
                    val r = app.chat.updateBot(b)
                    saving = false
                    if (r is ManagerResult.Ok) editing = null else failed(r)
                }
            },
            onDelete = {
                scope.launch {
                    saving = true; saveError = null
                    val r = app.chat.trashBot(bot.id)
                    saving = false
                    if (r is ManagerResult.Ok) editing = null else failed(r)
                }
            },
            onDismiss = { if (!saving) editing = null },
        )
    }
}
