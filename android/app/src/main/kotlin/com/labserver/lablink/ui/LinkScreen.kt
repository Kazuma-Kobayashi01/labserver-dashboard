package com.labserver.lablink.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.labserver.lablink.R
import com.labserver.lablink.data.Prefs
import com.labserver.lablink.data.parseWgQuick
import com.labserver.lablink.labApp
import com.labserver.lablink.tunnel.TunnelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkScreen(state: TunnelState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.labApp
    val prefs = app.prefs

    var address by remember { mutableStateOf(prefs.address) }
    var endpoint by remember { mutableStateOf(prefs.endpoint) }
    var hubKey by remember { mutableStateOf(prefs.hubPublicKey) }
    var psk by remember { mutableStateOf(prefs.presharedKey) }
    var botUrl by remember { mutableStateOf(prefs.botBaseUrl.takeIf { Prefs.isHttpUrl(it) } ?: "") }
    var botUrlError by remember { mutableStateOf(false) }
    var botKey by remember { mutableStateOf(prefs.botKey) }
    var domain by remember { mutableStateOf(prefs.domain) }
    var tunnelHost by remember { mutableStateOf(prefs.tunnelHost) }
    var allowedIPs by remember { mutableStateOf(prefs.allowedIPs) }
    var advanced by remember { mutableStateOf(false) }
    var pub by remember { mutableStateOf("") }
    var confirmRegen by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { pub = withContext(Dispatchers.IO) { app.tunnel.publicKey() } }

    fun save(): Boolean {
        if (botUrl.isNotBlank() && !Prefs.isHttpUrl(botUrl)) {
            botUrlError = true
            return false
        }
        botUrlError = false
        prefs.address = address
        prefs.endpoint = endpoint
        prefs.hubPublicKey = hubKey
        prefs.presharedKey = psk
        prefs.botBaseUrl = botUrl
        prefs.botKey = botKey
        prefs.domain = domain
        prefs.tunnelHost = tunnelHost
        prefs.allowedIPs = allowedIPs
        return true
    }

    val copiedMsg = stringResource(R.string.link_copied)
    val savedMsg = stringResource(R.string.link_saved)
    val importDone = stringResource(R.string.link_import_done)
    val importEmpty = stringResource(R.string.link_import_empty)

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Column(Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection)) {
        LargeTopAppBar(
            title = { Text(stringResource(R.string.nav_settings), fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.web_back)) } },
            scrollBehavior = scroll,
            windowInsets = WindowInsets(0),
        )
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ConnectionCard(state, onToggle = {
                if (state.isUpOrBusy) app.tunnel.disconnect()
                else { if (save()) app.tunnel.connect() }
            }, onShowLog = { showLog = true })

            Section(stringResource(R.string.link_device)) {
                Text(stringResource(R.string.link_device_key), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SelectionContainer(Modifier.weight(1f)) {
                        Text(pub.ifEmpty { "…" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                    }
                    IconButton(onClick = {
                        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("lablink public key", pub))
                        Toast.makeText(ctx, copiedMsg, Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.link_copy)) }
                }
                TextButton(onClick = { confirmRegen = true }) { Text(stringResource(R.string.link_regen)) }
            }

            Section(stringResource(R.string.link_hub)) {
                Field(address, { address = it }, stringResource(R.string.link_address))
                Field(endpoint, { endpoint = it }, stringResource(R.string.link_endpoint))
                Field(hubKey, { hubKey = it }, stringResource(R.string.link_hub_key))
                Field(psk, { psk = it }, stringResource(R.string.link_psk), secret = true)
                TextButton(onClick = {
                    val clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                    val p = parseWgQuick(clip)
                    if (p.isEmpty) {
                        Toast.makeText(ctx, importEmpty, Toast.LENGTH_SHORT).show()
                    } else {
                        p.address?.let { address = it }
                        p.endpoint?.let { endpoint = it }
                        p.hubPublicKey?.let { hubKey = it }
                        p.presharedKey?.let { psk = it }
                        p.allowedIPs?.let { allowedIPs = it }
                        p.privateKey?.let { prefs.privateKey = it; pub = app.tunnel.publicKey() }
                        Toast.makeText(ctx, importDone, Toast.LENGTH_SHORT).show()
                    }
                }) { Text(stringResource(R.string.link_import)) }

                TextButton(onClick = { advanced = !advanced }) {
                    Text(stringResource(R.string.link_advanced))
                    Icon(if (advanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
                }
                if (advanced) {
                    Field(domain, { domain = it }, stringResource(R.string.link_domain))
                    Field(tunnelHost, { tunnelHost = it }, stringResource(R.string.link_tunnel_host))
                    Field(allowedIPs, { allowedIPs = it }, stringResource(R.string.link_allowed_ips))
                }
            }

            Section(stringResource(R.string.link_bot)) {
                Field(
                    botUrl, { botUrl = it; botUrlError = false }, stringResource(R.string.link_bot_url),
                    placeholder = "http://bot.$domain/p/labbot/v1",
                    error = if (botUrlError) stringResource(R.string.bot_error_url) else null,
                )
                Field(botKey, { botKey = it }, stringResource(R.string.link_bot_key), secret = true)
            }

            Button(
                onClick = { if (save()) Toast.makeText(ctx, savedMsg, Toast.LENGTH_SHORT).show() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(stringResource(R.string.link_save)) }
        }
    }

    if (confirmRegen) {
        AlertDialog(
            onDismissRequest = { confirmRegen = false },
            title = { Text(stringResource(R.string.link_regen)) },
            text = { Text(stringResource(R.string.link_regen_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    app.tunnel.disconnect()
                    app.tunnel.regenerateKey()
                    pub = app.tunnel.publicKey()
                    confirmRegen = false
                }) { Text(stringResource(R.string.common_ok)) }
            },
            dismissButton = { TextButton(onClick = { confirmRegen = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    if (showLog) {
        AlertDialog(
            onDismissRequest = { showLog = false },
            title = { Text(stringResource(R.string.link_diagnostics)) },
            text = {
                SelectionContainer {
                    Text(app.tunnel.log().ifBlank { "(empty)" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.verticalScroll(rememberScrollState()))
                }
            },
            confirmButton = { TextButton(onClick = { showLog = false }) { Text(stringResource(R.string.common_ok)) } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, secret: Boolean = false, placeholder: String? = null, error: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
    )
}
