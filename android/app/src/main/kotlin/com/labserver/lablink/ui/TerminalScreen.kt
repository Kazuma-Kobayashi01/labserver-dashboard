package com.labserver.lablink.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labserver.lablink.R
import com.labserver.lablink.data.Bot
import com.labserver.lablink.labApp
import com.labserver.lablink.net.BotShell
import com.labserver.lablink.data.isSandbox
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.rounded.Refresh
import org.connectbot.terminal.ModifierManager
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory

private val TermBg = Color(0xFF14141C)
private val TermFg = Color(0xFFE4E4F0)

/**
 * A terminal in the bot's own machine, opened from its ⋮: the bot's sandbox, or the Hermes container for the default bot.
 * It connects as soon as it opens, with no login of its own (the bot-manager lets the app in with the key it already
 * holds). A row of keys the phone keyboard lacks (Esc, Tab, Ctrl, arrows) sits above the keyboard.
 */
@Composable
fun TerminalScreen(bot: Bot, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.labApp
    val prefs = app.prefs
    val accent = Color(bot.color)
    val debuggable = ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0

    // the emulator and the shell feed each other: what the bot's machine prints is drawn, what is typed goes back
    val keys = remember { StickyKeys() }
    val holder = remember { arrayOfNulls<TerminalEmulator>(1) }
    val shell = remember(bot.id) {
        BotShell(
            prefs, prefs.profileOf(bot.id), proxyPort = { app.tunnel.awaitPort() },
            direct = { url -> debuggable && url.contains("://10.0.2.2") },
        ) { bytes -> try { holder[0]?.writeInput(bytes) } catch (_: IllegalStateException) {} }
    }
    val emulator = remember {
        TerminalEmulatorFactory.create(
            defaultForeground = TermFg, defaultBackground = TermBg,
            onKeyboardInput = { shell.send(it) },
            onResize = { d -> shell.resize(d.columns, d.rows) },
            onClipboardCopy = { text -> ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("terminal", text)) },
        ).also {
            // the panel's own colours for the default text and background (otherwise it paints pure black)
            it.setDefaultColors(TermFg.toArgb(), TermBg.toArgb())
            holder[0] = it
        }
    }
    DisposableEffect(Unit) {
        shell.connect()
        onDispose {
            shell.close()
            holder[0] = null
            emulator.close()
        }
    }
    val state by shell.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, null, onBack)
            BotAvatar(34.dp, Modifier.padding(start = 10.dp), color = accent)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(bot.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(7.dp).clip(CircleShape).background(
                            when (state) {
                                BotShell.State.Open -> Color(0xFF34C759)
                                BotShell.State.Connecting -> Color(0xFFFFB020)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            },
                        ),
                    )
                    Text(
                        stringResource(if (bot.isSandbox) R.string.shell_where_sandbox else R.string.shell_where_hermes),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            // start again after it ended, or end it now
            if (state is BotShell.State.Closed) GlassIconButton(Icons.Rounded.Refresh, stringResource(R.string.shell_reconnect), { shell.connect() })
            else GlassIconButton(Icons.Rounded.PowerSettingsNew, stringResource(R.string.ssh_disconnect), { shell.close(); onBack() })
        }

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
            when (val st = state) {
                BotShell.State.Connecting -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), strokeWidth = 2.5.dp, color = accent)
                is BotShell.State.Closed -> ClosedNote(st, Modifier.align(Alignment.BottomCenter)) { shell.connect() }
                else -> Unit
            }
        }
        ExtraKeys(keys, onSend = shell::send, onPaste = { paste(ctx, emulator) })
    }
}

/** Why the terminal is not running (it ended, no tunnel, no sandbox yet...), with a button to start it again. */
@Composable
private fun ClosedNote(st: BotShell.State.Closed, modifier: Modifier, onRetry: () -> Unit) {
    AnimatedVisibility(true, modifier = modifier, enter = fadeIn(Springs.soft()) + scaleIn(Springs.bouncy(), initialScale = 0.9f)) {
        Row(
            Modifier.padding(12.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF262633))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp)).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(
                    when (st.reason) {
                        BotShell.Reason.Ended -> R.string.shell_ended
                        BotShell.Reason.Offline -> R.string.bot_need_connect
                        BotShell.Reason.NoSandbox -> R.string.shell_no_sandbox
                        BotShell.Reason.NoTerminal -> R.string.shell_no_terminal
                        BotShell.Reason.Unauthorized -> R.string.shell_unauthorized
                        BotShell.Reason.NotFound -> R.string.shell_not_found
                        BotShell.Reason.Failed -> R.string.shell_failed
                    },
                ),
                color = TermFg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.shell_reconnect), color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF5546E0)).pressable(onRetry).padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

private fun paste(ctx: android.content.Context, emulator: TerminalEmulator) {
    val text = ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
    if (text.isNotEmpty()) emulator.pasteText(text)
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
