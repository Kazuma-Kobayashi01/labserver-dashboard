package com.labserver.lablink.ui

import androidx.compose.ui.graphics.graphicsLayer
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.lifecycle.repeatOnLifecycle
import com.labserver.lablink.R
import com.labserver.lablink.data.Bot
import com.labserver.lablink.data.isSandbox
import com.labserver.lablink.data.hasSandbox
import com.labserver.lablink.net.ManagerResult
import com.labserver.lablink.labApp
import com.labserver.lablink.tunnel.TunnelState
import kotlinx.coroutines.launch

enum class Screen { Chat, HomeLab, Settings, Trash, Library, Terminal }

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
    // the bot whose machine the SSH terminal was opened from
    var terminalBot by remember { mutableStateOf<com.labserver.lablink.data.Bot?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // a share from another app lands in the chat
    val shared by app.chatRequested.collectAsStateWithLifecycle()
    LaunchedEffect(shared) { if (shared > 0) screen = Screen.Chat }

    // the sandbox bots live on the server: read them when the tunnel comes up and whenever the drawer opens
    LaunchedEffect(port) { if (app.chat.managerAvailable()) app.chat.refresh() }
    LaunchedEffect(drawer.isOpen) { if (drawer.isOpen && app.chat.managerAvailable()) app.chat.refresh() }
    // chats continued on another device (the Mac) show up here: on switching bots, and every 20 s while the app is open
    val openBot = app.chat.ui.collectAsStateWithLifecycle().value.bot.id
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(port, openBot) {
        // only while the app is on screen: nothing runs in the background for this
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            while (true) {
                if (app.chat.managerAvailable()) app.chat.syncChats()
                kotlinx.coroutines.delay(20_000)
            }
        }
    }

    // WebViews in this app go through the tunnel's loopback proxy whenever it is up (and nowhere otherwise)
    LaunchedEffect(port) { WebProxy.apply(ctx, port) }
    BackHandler(enabled = screen != Screen.Chat && !drawer.isOpen) { screen = Screen.Chat }

    val toggleTunnel = { if (tunnel.isUpOrBusy) app.tunnel.disconnect() else app.tunnel.connect() }
    fun go(to: Screen) {
        screen = to
        scope.launch { drawer.animateTo(DrawerValue.Closed, Springs.soft()) }
    }
    val needConnect = stringResource(R.string.panels_need_connect)
    val fullMsg = stringResource(R.string.bots_full, chat.sandboxCount, chat.limit)
    fun newBot() {
        if (chat.full) scope.launch { snackbar.showSnackbar(fullMsg) } else { saveError = null; creating = true }
    }
    fun failed(r: ManagerResult<*>) {
        if (r is ManagerResult.Fail) saveError = managerErrorText(ctx, r)
    }

    // what the sidebar's glass shows through: the whole app behind it
    val appBackdrop = rememberLayerBackdrop()
    // the app is recorded for the sidebar's glass only while the sidebar is open or moving
    // from the first pixel it moves (not only once it has decided to open), so the glass does not switch on mid-drag
    val drawerWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) { 312.dp.toPx() }
    val drawerShown by remember { androidx.compose.runtime.derivedStateOf { drawer.currentOffset.let { !it.isNaN() && it > -drawerWidthPx + 1f } } }
    ModalNavigationDrawer(
        drawerState = drawer,
        scrimColor = Color.Black.copy(alpha = 0.18f),
        gesturesEnabled = (!panelOpen && screen != Screen.Terminal) || drawer.isOpen,
        drawerContent = {
            // about four fifths of a phone, so the chat stays in view beside it (as in ChatGPT); the open bot's aurora shows
            // through, the bots are cards, and the places (library, trash, home lab, settings) sit in a bar at the bottom
            // a floating sidebar (iOS 26): a liquid glass panel held off the screen's edges, blurring the chat behind it
            ModalDrawerSheet(
                Modifier.width(312.dp),
                drawerContainerColor = Color.Transparent,
                drawerShape = RoundedCornerShape(0.dp),
            ) {
                Box(
                    Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(start = 10.dp, top = 6.dp, bottom = 10.dp)
                        .liquidGlass(RoundedCornerShape(34.dp), backdrop = appBackdrop.takeIf { drawerShown }, tint = 0.62f, blur = 22.dp, glow = Color(chat.bot.color)),
                ) {
                // the content trails the panel on an eased curve: it slides in a little and fades up as the panel comes out
                Column(Modifier.fillMaxSize().graphicsLayer {
                    val f = drawer.currentOffset.let { if (it.isNaN()) 0f else (1f + it / drawerWidthPx).coerceIn(0f, 1f) }
                    val e = androidx.compose.animation.core.FastOutSlowInEasing.transform(f)
                    translationX = (1f - e) * -48.dp.toPx()
                    alpha = androidx.compose.animation.core.LinearOutSlowInEasing.transform((f * 1.4f - 0.2f).coerceIn(0f, 1f))
                }) {
                    Row(Modifier.padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        BotHero(40.dp, interactive = false)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                    }
                    ConnectionPill(tunnel, onToggle = toggleTunnel, modifier = Modifier.padding(horizontal = 14.dp))

                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(Modifier.padding(start = 8.dp, top = 12.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.bots_title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            Text("${chat.sandboxCount}/${chat.limit}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // each bot with its own chats: the open bot unfolds into its history, a tap on another one switches to it
                        chat.bots.forEach { bot ->
                            val open = bot.id == chat.bot.id
                            val c = Color(bot.color)
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                    .pressable { if (open) go(Screen.Chat) else app.chat.select(bot.id) }
                                    .then(if (open) Modifier.glass(RoundedCornerShape(22.dp), elevation = 6.dp, strong = true, glow = c) else Modifier.clip(RoundedCornerShape(22.dp)))
                                    .padding(start = 10.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                BotAvatar(36.dp, color = c)
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(bot.name, style = MaterialTheme.typography.titleSmall, fontWeight = if (open) FontWeight.ExtraBold else FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        bot.role.ifBlank { stringResource(if (bot.hasSandbox) R.string.bot_sandbox_label else R.string.bot_hello_sub) },
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (!open && chat.busy.isNotEmpty() && app.chat.isBusy(bot.id)) {
                                    Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                                }
                                IconButton(onClick = { saveError = null; editing = bot; scope.launch { drawer.animateTo(DrawerValue.Closed, Springs.soft()) } }, modifier = Modifier.size(38.dp)) {
                                    Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.bots_edit), modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            androidx.compose.animation.AnimatedVisibility(open, enter = springExpand, exit = springShrink) {
                                Column(Modifier.padding(start = 14.dp, top = 4.dp, bottom = 8.dp)) {
                                    ConversationSection(
                                        conversations = chat.conversations, currentId = chat.conversationId, busy = chat.busy, botName = chat.bot.name,
                                        onNew = { app.chat.newConversation(); go(Screen.Chat) },
                                        onOpen = { app.chat.openConversation(it); go(Screen.Chat) },
                                    )
                                }
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(22.dp)).pressable { newBot(); scope.launch { drawer.animateTo(DrawerValue.Closed, Springs.soft()) } }
                                .padding(start = 10.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(36.dp).clip(CircleShape).border(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Text(
                                if (chat.full) stringResource(R.string.bots_new_full, chat.sandboxCount, chat.limit) else stringResource(R.string.bots_new),
                                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }

                    // the places, one tap away at the bottom
                    Row(
                        Modifier.fillMaxWidth().padding(10.dp)
                            .clip(RoundedCornerShape(26.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)).padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        PlaceButton(Icons.Rounded.PhotoLibrary, stringResource(R.string.nav_library), screen == Screen.Library) { go(Screen.Library) }
                        PlaceButton(Icons.Rounded.Delete, stringResource(R.string.nav_trash), screen == Screen.Trash, badge = chat.trash.size) { go(Screen.Trash) }
                        PlaceButton(Icons.Rounded.Apps, stringResource(R.string.nav_home_lab), screen == Screen.HomeLab) { go(Screen.HomeLab) }
                        PlaceButton(Icons.Rounded.Settings, stringResource(R.string.nav_settings), screen == Screen.Settings) { go(Screen.Settings) }
                    }
                }
                }
            }
        },
    ) {
        // the chat paints its own aurora, so this one is left out under it (it would be painted and then covered)
        AuroraBackground(Color(chat.bot.color), Modifier.fillMaxSize().then(if (drawerShown) Modifier.layerBackdrop(appBackdrop) else Modifier), paint = screen != Screen.Chat) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) { LabSnackbar(it) } }, containerColor = Color.Transparent) { inner ->
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
                    // springs throughout: the page arrives with a little momentum and settles, nothing runs on a fixed clock
                    if (opening) {
                        (slideInHorizontally(Springs.soft()) { it / 4 } + fadeIn(Springs.soft())) togetherWith (fadeOut(Springs.snappy()) + scaleOut(Springs.soft(), targetScale = 0.94f))
                    } else {
                        (fadeIn(Springs.soft()) + scaleIn(Springs.soft(), initialScale = 0.94f)) togetherWith (slideOutHorizontally(Springs.soft()) { it / 4 } + fadeOut(Springs.snappy()))
                    }
                },
                label = "screen",
            ) { current ->
                Box(Modifier.fillMaxSize()) {
                when (current) {
                    Screen.Chat -> ChatScreen(
                        tunnel,
                        onOpenDrawer = { scope.launch { drawer.animateTo(DrawerValue.Open, Springs.soft()) } },
                        onConnect = { app.tunnel.connect() },
                        onOpenSettings = { screen = Screen.Settings },
                        onNewBot = { newBot() },
                        onEditBot = { saveError = null; editing = it },
                        onOpenTerminal = { terminalBot = it; screen = Screen.Terminal },
                    )
                    Screen.Terminal -> TerminalScreen(terminalBot ?: app.chat.ui.value.bot, onBack = { screen = Screen.Chat })
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

/** One place in the drawer's bottom bar: an icon over a small label, lit when it is the screen on show. */
@Composable
private fun PlaceButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, badge: Int = 0, onClick: () -> Unit) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.clip(RoundedCornerShape(16.dp)).pressable(onClick).padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            if (badge > 0) Box(
                Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = (-5).dp).size(15.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error),
                contentAlignment = Alignment.Center,
            ) { Text(badge.coerceAtMost(9).toString(), style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold) }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
    }
}

/** The tunnel in the drawer: a glass pill with a status light, what it is doing, and its switch. */
@Composable
private fun ConnectionPill(state: TunnelState, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp), elevation = 4.dp, strong = true).padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(tunnelDotColor(state)))
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(stringResource(R.string.home_title), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(tunnelTitle(state), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        LabSwitch(checked = state.isUpOrBusy, onCheckedChange = { onToggle() })
    }
}
