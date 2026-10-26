package com.labserver.lablink.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labserver.lablink.R
import com.labserver.lablink.labApp
import com.labserver.lablink.tunnel.TunnelState

private data class Panel(
    val id: String,
    @StringRes val title: Int,
    @StringRes val sub: Int,
    val icon: ImageVector,
    /** Sub-domain of the home domain; empty = the domain itself. */
    val host: String,
    val tint: Color,
)

private val panels = listOf(
    Panel("dash", R.string.panel_dash, R.string.panel_dash_sub, Icons.Rounded.Dashboard, "", Color(0xFFF2724D)),
    Panel("incus", R.string.panel_incus, R.string.panel_incus_sub, Icons.Rounded.Inventory2, "incus", Color(0xFF4D8CF2)),
    Panel("mihomo", R.string.panel_mihomo, R.string.panel_mihomo_sub, Icons.Rounded.Bolt, "mihomo", Color(0xFF8C66E6)),
    Panel("git", R.string.panel_git, R.string.panel_git_sub, Icons.Rounded.AccountTree, "git", Color(0xFFF29A26)),
    Panel("nc", R.string.panel_nc, R.string.panel_nc_sub, Icons.Rounded.Cloud, "nextcloud", Color(0xFF33A6D9)),
    Panel("hermes", R.string.panel_hermes, R.string.panel_hermes_sub, Icons.Rounded.AutoAwesome, "hermes", Color(0xFFE65999)),
)

/** Home panels. They only open inside the app (a WebView routed through the tunnel), never in the phone's browser. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PanelsScreen(tunnel: TunnelState, onPill: () -> Unit, onBlocked: () -> Unit) {
    val prefs = LocalContext.current.labApp.prefs
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val open = panels.firstOrNull { it.id == openId }
    val up = tunnel is TunnelState.On

    if (open != null) {
        val domain = prefs.domain
        PanelWebView(
            url = "http://" + (if (open.host.isEmpty()) domain else "${open.host}.$domain") + "/",
            domain = domain,
            tunnelHost = prefs.tunnelHost,
            title = stringResource(open.title),
            onClose = { openId = null },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = { Text(stringResource(R.string.panels_title)) },
            actions = { ConnectionPill(tunnel, onPill) },
            windowInsets = WindowInsets(0),
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(panels, key = { it.id }) { p ->
                Card(
                    onClick = { if (up) openId = p.id else onBlocked() },
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth().alpha(if (up) 1f else 0.55f),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(52.dp).background(p.tint, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(p.icon, contentDescription = null, tint = Color.White)
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                            Text(stringResource(p.title), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(p.sub),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
