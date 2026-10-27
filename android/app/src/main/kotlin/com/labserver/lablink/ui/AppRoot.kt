package com.labserver.lablink.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
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

enum class Screen { Chat, HomeLab, Settings, Trash }

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
            ModalDrawerSheet {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 16.dp)) {
                    Row(Modifier.padding(start = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        BotHero(44.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    ConnectionCard(tunnel, onToggle = toggleTunnel, compact = true)

                    Text(
                        stringResource(R.string.bots_count, chat.sandboxCount, chat.limit),
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
                    )
                    chat.bots.forEach { bot ->
                        NavigationDrawerItem(
                            icon = { BotAvatar(30.dp, color = Color(bot.color)) },
                            label = { Text(bot.name, maxLines = 1) },
                            selected = screen == Screen.Chat && bot.id == chat.bot.id,
                            onClick = { app.chat.select(bot.id); go(Screen.Chat) },
                            badge = {
                                IconButton(onClick = { saveError = null; editing = bot; scope.launch { drawer.close() } }) {
                                    Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.bots_edit))
                                }
                            },
                        )
                    }
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                        label = { Text(if (chat.full) stringResource(R.string.bots_new_full, chat.sandboxCount, chat.limit) else stringResource(R.string.bots_new)) },
                        selected = false,
                        onClick = { newBot(); scope.launch { drawer.close() } },
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
            Box(Modifier.padding(inner).consumeWindowInsets(inner)) {
                when (screen) {
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
                    Screen.Trash -> TrashScreen(
                        connected = port != 0 || app.chat.managerAvailable(), onBack = { screen = Screen.Chat },
                        onMessage = { scope.launch { snackbar.showSnackbar(it) } },
                    )
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
