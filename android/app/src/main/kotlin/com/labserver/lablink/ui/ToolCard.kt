package com.labserver.lablink.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.labserver.lablink.R
import com.labserver.lablink.data.ChatDetail
import com.labserver.lablink.data.ToolRun

/** The command window's colours: a night terminal in dark mode, a white one in light mode. */
private data class TermPalette(val panel: Color, val bar: Color, val border: Color, val text: Color, val muted: Color, val prompt: Color, val ok: Color, val bad: Color)

private val NightTerm = TermPalette(Color(0xFF1B1B26), Color(0xFF22222F), Color(0x10FFFFFF), Color(0xFFE4E4F0), Color(0xFF9A9AB4), Color(0xFF82AAFF), Color(0xFF4ED6A0), Color(0xFFFF6B81))
private val DayTerm = TermPalette(Color.White, Color(0xFFF4F4F8), Color(0xFFE4E4EC), Color(0xFF24292F), Color(0xFF57606A), Color(0xFF0969DA), Color(0xFF1A7F37), Color(0xFFCF222E))

/**
 * A command the bot ran, as a terminal window in the reply (as Claude Code shows them): what it is about, then the
 * command after a `$` and what it printed. Open while it runs; once done it folds to one line with how it ended, and a
 * tap opens it again. Other tools (a web search) are a slim chip.
 */
@Composable
fun ToolCard(run: ToolRun, modifier: Modifier = Modifier) {
    val Term = if (isDark()) NightTerm else DayTerm
    if (run.tool != "terminal" && run.command.isEmpty()) {
        ToolChip(run, modifier)
        return
    }
    val ctx = LocalContext.current
    val detail = LocalChatDetail.current
    var open by rememberSaveable(run.id, detail) { mutableStateOf(if (run.done) detail.toolStaysOpen else detail.toolOpenWhileRunning) }
    // fold away by itself when it finishes (the reply carries on below it), unless everything is shown
    LaunchedEffect(run.done) { if (run.done && !detail.toolStaysOpen) open = false }
    val failed = run.done && ((run.exitCode ?: 0) != 0 || !run.error.isNullOrBlank())
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Term.panel)
            .border(1.dp, Term.border, RoundedCornerShape(16.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().background(Term.bar).pressable { open = !open }.padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                when {
                    !run.done -> CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = Term.prompt)
                    failed -> Icon(Icons.Rounded.Close, contentDescription = null, tint = Term.bad, modifier = Modifier.size(17.dp))
                    else -> Icon(Icons.Rounded.Check, contentDescription = null, tint = Term.ok, modifier = Modifier.size(17.dp))
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Rounded.Terminal, contentDescription = null, tint = Term.muted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                run.label.takeIf { it.isNotBlank() && it != "terminal" } ?: stringResource(R.string.tool_command),
                color = Term.text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            val tail = buildString {
                if (failed && run.exitCode != null && run.exitCode != 0) append(ctx.getString(R.string.tool_exit, run.exitCode)).append("  ")
                run.durationMs?.let { append(if (it < 1000) "${it}ms" else "%.1fs".format(it / 1000f)) }
            }
            if (tail.isNotBlank()) Text(tail.trim(), color = if (failed) Term.bad else Term.muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp))
            Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = Term.muted, modifier = Modifier.size(20.dp).rotate(turn))
        }
        AnimatedVisibility(open, enter = springExpand, exit = springShrink) {
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 12.dp)) {
                if (run.command.isNotBlank()) Row(verticalAlignment = Alignment.Top) {
                    Text(
                        "$ ", color = Term.prompt, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold,
                    )
                    Text(
                        run.command, color = Term.text, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 18.sp,
                        modifier = Modifier.weight(1f).heightIn(max = 140.dp).verticalScroll(rememberScrollState()),
                    )
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).pressable {
                            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("command", run.command))
                        },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.tool_copy), tint = Term.muted, modifier = Modifier.size(15.dp)) }
                }
                val out = run.output.trimEnd().ifEmpty { run.error.orEmpty() }
                when {
                    !run.done && out.isEmpty() -> Text(stringResource(R.string.tool_running), color = Term.muted, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp))
                    out.isNotEmpty() -> Text(
                        out, color = if (failed) Term.bad.copy(alpha = 0.9f) else Term.muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp,
                        modifier = Modifier.padding(top = 6.dp, end = 6.dp).fillMaxWidth().heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
                    )
                    else -> Text(stringResource(R.string.tool_no_output), color = Term.muted.copy(alpha = 0.6f), fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

/** How much of a bot's work replies show (Settings → Conversation display). */
val LocalChatDetail = androidx.compose.runtime.compositionLocalOf { ChatDetail.Standard }

/**
 * The tools of one stretch of a reply gathered into a single line ("Ran 3 commands"), for the minimal display: a spinner
 * while any of them runs, a tick or a cross after; a tap lists them.
 */
@Composable
fun ToolGroup(runs: List<ToolRun>, modifier: Modifier = Modifier) {
    if (runs.size == 1 && runs[0].tool != "terminal" && runs[0].command.isEmpty()) {
        ToolChip(runs[0], modifier)
        return
    }
    var open by rememberSaveable(runs.first().id) { mutableStateOf(false) }
    val busy = runs.any { !it.done }
    val failed = runs.any { it.done && ((it.exitCode ?: 0) != 0 || !it.error.isNullOrBlank()) }
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                .pressable { open = !open }.padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                busy -> CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.8.dp)
                failed -> Icon(Icons.Rounded.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(15.dp))
                else -> Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Rounded.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                androidx.compose.ui.res.pluralStringResource(if (busy) R.plurals.tool_group_running else R.plurals.tool_group_done, runs.size, runs.size),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp).size(18.dp).rotate(turn))
        }
        AnimatedVisibility(open, enter = springExpand, exit = springShrink) {
            // opened, the commands show as they would at the standard level
            androidx.compose.runtime.CompositionLocalProvider(LocalChatDetail provides ChatDetail.Standard) {
                Column(Modifier.padding(top = 10.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                    runs.forEach { ToolCard(it) }
                }
            }
        }
    }
}

/** A tool that is not a command (searching the web, reading a page): its name, and a spinner or a tick. */
@Composable
private fun ToolChip(run: ToolRun, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!run.done) CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.8.dp)
        else Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Rounded.Language, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(run.label.ifBlank { run.tool }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
