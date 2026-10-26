package com.labserver.lablink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labserver.lablink.R
import com.labserver.lablink.labApp
import com.labserver.lablink.tunnel.TunnelState
import kotlinx.coroutines.launch

@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    val app = ctx.labApp
    val tunnel by app.tunnel.state.collectAsStateWithLifecycle()
    val port by app.tunnel.proxyPort.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // WebViews in this app go through the tunnel's loopback proxy whenever it is up (and nowhere otherwise)
    LaunchedEffect(port) { WebProxy.apply(ctx, port) }

    val onPill = {
        if (tunnel is TunnelState.On || tunnel is TunnelState.Connecting) tab = 2 else app.tunnel.connect()
    }
    val needConnect = stringResource(R.string.panels_need_connect)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                val items = listOf(
                    R.string.tab_bot to Icons.Rounded.SmartToy,
                    R.string.tab_panels to Icons.Rounded.Apps,
                    R.string.tab_link to Icons.Rounded.Hub,
                )
                items.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(stringResource(label)) },
                    )
                }
            }
        },
    ) { inner ->
        Box(Modifier.padding(inner).consumeWindowInsets(inner)) {
            when (tab) {
                0 -> ChatScreen(tunnel, onPill, onOpenLink = { tab = 2 })
                1 -> PanelsScreen(
                    tunnel, onPill,
                    onBlocked = { scope.launch { snackbar.showSnackbar(needConnect) } },
                )
                else -> LinkScreen(tunnel)
            }
        }
    }
}

/** Small status chip shown in every top bar: tap to connect, or to jump to the Link tab when connected. */
@Composable
fun ConnectionPill(state: TunnelState, onClick: () -> Unit) {
    val (label, color) = when (state) {
        TunnelState.Off -> stringResource(R.string.status_off) to Color(0xFF9E9E9E)
        is TunnelState.Connecting -> stringResource(R.string.status_connecting) to Color(0xFFFFB300)
        is TunnelState.On -> stringResource(R.string.status_on) to Color(0xFF34C759)
        is TunnelState.Failed -> stringResource(R.string.status_failed) to Color(0xFFE5484D)
    }
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Box(Modifier.size(10.dp).background(color, CircleShape)) },
    )
}
