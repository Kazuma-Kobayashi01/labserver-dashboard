package com.labserver.lablink.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
    /** Expressive shapes, mixed on purpose (Material 3 Expressive: "use a variety of shapes"). */
    val shape: Shape,
)

private val panels = listOf(
    Panel("dash", R.string.panel_dash, R.string.panel_dash_sub, Icons.Rounded.Dashboard, "", Color(0xFFF2724D), CookieShape(9)),
    Panel("incus", R.string.panel_incus, R.string.panel_incus_sub, Icons.Rounded.Inventory2, "incus", Color(0xFF4D8CF2), RoundedCornerShape(30)),
    Panel("mihomo", R.string.panel_mihomo, R.string.panel_mihomo_sub, Icons.Rounded.Bolt, "mihomo", Color(0xFF8C66E6), CircleShape),
    Panel("git", R.string.panel_git, R.string.panel_git_sub, Icons.Rounded.AccountTree, "git", Color(0xFFF29A26), CookieShape(6, 0.09f)),
    Panel("nc", R.string.panel_nc, R.string.panel_nc_sub, Icons.Rounded.Cloud, "nextcloud", Color(0xFF33A6D9), RoundedCornerShape(50, 50, 18, 50)),
    Panel("hermes", R.string.panel_hermes, R.string.panel_hermes_sub, Icons.Rounded.AutoAwesome, "hermes", Color(0xFFE65999), CookieShape(12, 0.05f)),
)

/** Home lab: the connection switch on top, then the panels as a grid. Panels only open inside the app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeLabScreen(tunnel: TunnelState, onBack: () -> Unit, onToggle: () -> Unit, onBlocked: () -> Unit) {
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

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Column(Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection)) {
        LargeTopAppBar(
            title = { Text(stringResource(R.string.nav_home_lab), fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.web_back)) } },
            scrollBehavior = scroll,
            windowInsets = WindowInsets(0),
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(2) }) {
                ConnectionCard(tunnel, onToggle = onToggle, modifier = Modifier.padding(bottom = 8.dp))
            }
            items(panels, key = { it.id }) { p ->
                Surface(
                    onClick = { if (up) openId = p.id else onBlocked() },
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.heightIn(min = 150.dp).alpha(if (up) 1f else 0.6f),
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(color = p.tint, contentColor = Color.White, shape = p.shape, modifier = Modifier.size(56.dp)) {
                            Box(contentAlignment = Alignment.Center) { Icon(p.icon, contentDescription = null) }
                        }
                        Column {
                            Text(stringResource(p.title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(p.sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}
