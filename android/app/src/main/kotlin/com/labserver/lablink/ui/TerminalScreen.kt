package com.labserver.lablink.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labserver.lablink.R
import com.labserver.lablink.data.Bot
import com.labserver.lablink.labApp
import com.labserver.lablink.net.SshSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.connectbot.terminal.ModifierManager
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory

private val TermBg = Color(0xFF14141C)
private val TermFg = Color(0xFFE4E4F0)

/**
 * A terminal on the home server over SSH, opened from a bot's ⋮: log in (password, or this phone's own key once it is in
 * the server's authorized_keys), then one tap steps into the bot's own machine (the Hermes container, or the bot's
 * sandbox). A row of keys the phone keyboard lacks (Esc, Tab, Ctrl, arrows) sits above the keyboard.
 */
@Composable
fun TerminalScreen(bot: Bot, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.labApp
    val prefs = app.prefs
    val accent = Color(bot.color)

    // the emulator and the session feed each other: what the server prints is drawn, what is typed goes to the server
    val keys = remember { StickyKeys() }
    val holder = remember { arrayOfNulls<TerminalEmulator>(1) }
    val session = remember {
        SshSession(prefs, proxyPort = { app.tunnel.awaitPort() }) { buf, n -> try { holder[0]?.writeInput(buf, 0, n) } catch (_: IllegalStateException) {} }
    }
    val emulator = remember {
        TerminalEmulatorFactory.create(
            defaultForeground = TermFg, defaultBackground = TermBg,
            onKeyboardInput = { session.send(it) },
            onResize = { d -> session.resize(d.columns, d.rows) },
            onClipboardCopy = { text -> ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("terminal", text)) },
        ).also {
            // the panel's own colours for the default text and background (otherwise it paints pure black)
            it.setDefaultColors(TermFg.toArgb(), TermBg.toArgb())
            holder[0] = it
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            session.close()
            holder[0] = null
            emulator.close()
        }
    }
    val state by session.state.collectAsStateWithLifecycle()

    var user by rememberSaveable { mutableStateOf(prefs.sshUser) }
    var password by remember { mutableStateOf(prefs.sshPassword) }
    var keep by rememberSaveable { mutableStateOf(prefs.sshPassword.isNotEmpty()) }
    // a login started by itself shows the terminal at once; one from the form waits on the form
    var auto by remember { mutableStateOf(false) }
    val connect = {
        prefs.sshUser = user
        prefs.sshPassword = if (keep) password else ""
        session.connect(user, password)
    }
    // with a user saved (and a saved password, or this phone's key in authorized_keys) it logs straight in
    LaunchedEffect(Unit) { if (user.isNotBlank() && state == SshSession.State.Idle) { auto = true; connect() } }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, null, onBack)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(stringResource(R.string.ssh_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(7.dp).clip(CircleShape).background(
                            when (state) {
                                SshSession.State.Open -> Color(0xFF34C759)
                                SshSession.State.Connecting -> Color(0xFFFFB020)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            },
                        ),
                    )
                    Text(
                        "${user.ifBlank { "…" }}@${prefs.effectiveSshHost} · ${bot.name}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            if (state == SshSession.State.Open) {
                // step from the server into this bot's own machine
                Row(
                    Modifier.liquidButton(RoundedCornerShape(50), onClick = { session.send(SshSession.enterCommand(bot) + "\n") }, glow = accent)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Login, contentDescription = null, modifier = Modifier.size(17.dp), tint = accentInk(accent))
                    Text(stringResource(R.string.ssh_enter_bot), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
                }
                Spacer(Modifier.width(8.dp))
                GlassIconButton(Icons.Rounded.PowerSettingsNew, stringResource(R.string.ssh_disconnect), { session.disconnect() })
            }
        }

        AnimatedContent(
            targetState = state is SshSession.State.Open || state is SshSession.State.Connecting && auto,
            transitionSpec = { (fadeIn(Springs.soft()) + scaleIn(Springs.soft(), initialScale = 0.97f)) togetherWith fadeOut(Springs.snappy()) },
            modifier = Modifier.weight(1f),
            label = "terminal",
        ) { open ->
            if (open) Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp).clip(RoundedCornerShape(20.dp)).background(TermBg)
                        .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(20.dp)).padding(8.dp),
                ) {
                    Terminal(
                        emulator, Modifier.fillMaxSize(),
                        initialFontSize = 12.sp, backgroundColor = TermBg, foregroundColor = TermFg,
                        keyboardEnabled = true, modifierManager = keys,
                        onPasteRequest = { paste(ctx, emulator) },
                    )
                    if (state == SshSession.State.Connecting) CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), strokeWidth = 2.5.dp, color = accent)
                }
                ExtraKeys(keys, onSend = session::send, onPaste = { paste(ctx, emulator) })
            } else LoginCard(
                state = state, user = user, onUser = { user = it }, password = password, onPassword = { password = it },
                keep = keep, onKeep = { keep = it }, onConnect = { auto = false; connect() },
            )
        }
    }
}

private fun paste(ctx: android.content.Context, emulator: TerminalEmulator) {
    val text = ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
    if (text.isNotEmpty()) emulator.pasteText(text)
}

/** The login form: who to log in as, a password (or none, with this phone's key in authorized_keys), and why it failed. */
@Composable
private fun LoginCard(
    state: SshSession.State, user: String, onUser: (String) -> Unit, password: String, onPassword: (String) -> Unit,
    keep: Boolean, onKeep: (Boolean) -> Unit, onConnect: () -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = ctx.labApp.prefs
    var publicKey by remember { mutableStateOf(prefs.sshPublicKey) }
    LaunchedEffect(Unit) { if (publicKey.isBlank()) publicKey = withContext(Dispatchers.Default) { SshSession.ensureKey(prefs); prefs.sshPublicKey } }
    val copied = stringResource(R.string.link_copied)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().glass(RoundedCornerShape(28.dp), elevation = 6.dp, strong = true).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.ssh_login, prefs.effectiveSshHost), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
            }
            (state as? SshSession.State.Closed)?.let { c ->
                Text(
                    when {
                        c.hostKeyChanged -> stringResource(R.string.ssh_hostkey_changed)
                        c.auth -> stringResource(R.string.ssh_auth_failed)
                        c.message == SshSession.NOT_CONNECTED -> stringResource(R.string.bot_need_connect)
                        c.message != null -> stringResource(R.string.ssh_failed, c.message)
                        else -> stringResource(R.string.ssh_closed)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (c.message == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
            LabTextField(
                user, onUser, label = { Text(stringResource(R.string.ssh_user)) }, singleLine = true,
                // a login name: plain letters, no pinyin, no autocorrect or capitals
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false, capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None),
                modifier = Modifier.fillMaxWidth(),
            )
            LabTextField(
                password, onPassword, label = { Text(stringResource(R.string.ssh_password)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = { Text(stringResource(R.string.ssh_password_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ssh_remember), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                LabSwitch(checked = keep, onCheckedChange = onKeep)
            }
            PillButton(onClick = onConnect, kind = PillKind.Primary, enabled = user.isNotBlank() && state != SshSession.State.Connecting, modifier = Modifier.fillMaxWidth()) {
                if (state == SshSession.State.Connecting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                else Text(stringResource(R.string.ssh_connect))
            }
        }
        // this phone's key: once it is in the server's authorized_keys, no password is needed
        Column(
            Modifier.fillMaxWidth().glass(RoundedCornerShape(28.dp), elevation = 6.dp, strong = true).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.ssh_key_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp).weight(1f))
                GlassIconButton(Icons.Rounded.ContentCopy, stringResource(R.string.link_copy), {
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ssh public key", publicKey))
                    Toast.makeText(ctx, copied, Toast.LENGTH_SHORT).show()
                }, size = 38.dp)
            }
            Text(publicKey.ifBlank { "…" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.ssh_key_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Keys a phone keyboard lacks, in a scrolling row over it: Esc, Tab, a sticky Ctrl and Alt, arrows and a few symbols. */
@Composable
private fun ExtraKeys(keys: StickyKeys, onSend: (String) -> Unit, onPaste: () -> Unit) {
    val ctrl = keys.ctrl
    val alt = keys.alt
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Key("Esc") { onSend("\u001b") }
        Key("Tab") { onSend("\t") }
        Key("Ctrl", lit = ctrl) { keys.ctrl = !keys.ctrl }
        Key("Alt", lit = alt) { keys.alt = !keys.alt }
        Key("←") { onSend("\u001b[D") }
        Key("↓") { onSend("\u001b[B") }
        Key("↑") { onSend("\u001b[A") }
        Key("→") { onSend("\u001b[C") }
        for (s in listOf("|", "/", "-", "~", "*", "$")) Key(s) { onSend(s) }
        Key("^C") { onSend("\u0003") }
        Key("^D") { onSend("\u0004") }
        Box(
            Modifier.heightIn(min = 38.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
                .pressable(onPaste).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.ContentPaste, contentDescription = stringResource(R.string.ssh_paste), modifier = Modifier.size(17.dp)) }
    }
}

@Composable
private fun Key(label: String, lit: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 38.dp).widthIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
            .background(if (lit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
            .pressable(onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
            color = if (lit) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Ctrl and Alt from the key row: on for the next key typed on the keyboard, then off again. */
private class StickyKeys : ModifierManager {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)
    override fun isCtrlActive() = ctrl
    override fun isAltActive() = alt
    override fun isShiftActive() = false
    override fun clearTransients() { ctrl = false; alt = false }
}
