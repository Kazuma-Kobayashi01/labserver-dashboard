package com.labserver.lablink.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private sealed interface Block {
    data class Para(val text: String) : Block
    data class Heading(val level: Int, val text: String) : Block
    data class Item(val marker: String, val text: String) : Block
    data class Code(val lang: String, val text: String) : Block
    data class Quote(val text: String) : Block
    data class Table(val header: List<String>, val rows: List<List<String>>) : Block
    data object Rule : Block
}

private val bullet = Regex("""^\s*[-*+]\s+(.*)""")
private val numbered = Regex("""^\s*(\d+[.)])\s+(.*)""")
private val rule = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
private val tableSep = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")
private val bareUrl = Regex("""https?://[^\s)<>\]]+""")

private fun cells(line: String): List<String> = line.trim().trim('|').split('|').map { it.trim() }

private fun parseBlocks(src: String): List<Block> {
    val out = mutableListOf<Block>()
    val para = StringBuilder()
    fun flush() {
        if (para.isNotEmpty()) out += Block.Para(para.toString().trimEnd())
        para.setLength(0)
    }
    val lines = src.lines()
    var code: StringBuilder? = null
    var lang = ""
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trimStart()
        if (code != null) {
            if (t.startsWith("```")) { out += Block.Code(lang, code.toString().trimEnd('\n')); code = null } else code.appendLine(line)
            i++; continue
        }
        when {
            t.startsWith("```") -> { flush(); code = StringBuilder(); lang = t.removePrefix("```").trim() }
            t.startsWith("#") -> {
                flush()
                out += Block.Heading(t.takeWhile { it == '#' }.length.coerceAtMost(3), t.trimStart('#').trim())
            }
            rule.matches(line) -> { flush(); out += Block.Rule }
            t.startsWith(">") -> {
                flush()
                val q = StringBuilder()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) { q.appendLine(lines[i].trimStart().removePrefix(">").trim()); i++ }
                out += Block.Quote(q.toString().trimEnd()); continue
            }
            t.startsWith("|") && i + 1 < lines.size && tableSep.matches(lines[i + 1]) -> {
                flush()
                val header = cells(line)
                val rows = mutableListOf<List<String>>()
                i += 2
                while (i < lines.size && lines[i].trimStart().startsWith("|")) { rows += cells(lines[i]); i++ }
                out += Block.Table(header, rows); continue
            }
            bullet.matches(line) -> { flush(); out += Block.Item("•", bullet.matchEntire(line)!!.groupValues[1]) }
            numbered.matches(line) -> {
                flush()
                val m = numbered.matchEntire(line)!!
                out += Block.Item(m.groupValues[1], m.groupValues[2])
            }
            line.isBlank() -> flush()
            else -> { if (para.isNotEmpty()) para.append('\n'); para.append(line) }
        }
        i++
    }
    code?.let { out += Block.Code(lang, it.toString().trimEnd('\n')) } // a reply still streaming inside a code block
    flush()
    return out
}

/** **bold**, *italic*, `code`, [links](url) and bare URLs inside one run of text. */
private fun inline(text: String, codeBg: Color, link: Color): AnnotatedString = buildAnnotatedString {
    val linkStyle = TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline))
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else { append(c); i++ }
            }
            c == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > 0) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, background = codeBg)) { append(" " + text.substring(i + 1, end) + " ") }
                    i = end + 1
                } else { append(c); i++ }
            }
            c == '[' -> {
                val close = text.indexOf("](", i)
                val end = if (close > 0) text.indexOf(')', close) else -1
                if (close > 0 && end > 0) {
                    withLink(LinkAnnotation.Url(text.substring(close + 2, end), linkStyle)) { append(text.substring(i + 1, close)) }
                    i = end + 1
                } else { append(c); i++ }
            }
            c == 'h' && bareUrl.matchAt(text, i) != null -> {
                val url = bareUrl.matchAt(text, i)!!.value.trimEnd('.', ',', ';', ':', '!', '?')
                withLink(LinkAnnotation.Url(url, linkStyle)) { append(url) }
                i += url.length
            }
            c == '*' && i + 1 < text.length && text[i + 1] != ' ' -> {
                val end = text.indexOf('*', i + 1)
                if (end > 0) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else { append(c); i++ }
            }
            else -> { append(c); i++ }
        }
    }
}

/** Markdown subset: paragraphs, headings, lists, quotes, rules, tables, fenced code (with a copy button), inline styles and links. */
@Composable
fun MarkdownText(text: String, color: Color, modifier: Modifier = Modifier) {
    val codeBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f)
    val link = MaterialTheme.colorScheme.primary
    val blocks = parseBlocks(text)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (b in blocks) when (b) {
            is Block.Para -> Text(inline(b.text, codeBg, link), color = color, style = MaterialTheme.typography.bodyLarge)
            is Block.Heading -> Text(
                inline(b.text, codeBg, link), color = color, fontWeight = FontWeight.ExtraBold,
                style = when (b.level) {
                    1 -> MaterialTheme.typography.titleLarge
                    2 -> MaterialTheme.typography.titleMedium
                    else -> MaterialTheme.typography.titleSmall
                },
                modifier = Modifier.padding(top = 4.dp),
            )
            is Block.Item -> Row(Modifier.padding(start = 2.dp)) {
                Text(
                    b.marker, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(if (b.marker == "•") 18.dp else 28.dp),
                )
                Text(inline(b.text, codeBg, link), color = color, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            }
            is Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(2.dp)))
                Text(
                    inline(b.text, codeBg, link), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge,
                    fontStyle = FontStyle.Italic, modifier = Modifier.padding(start = 12.dp),
                )
            }
            Block.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            is Block.Table -> TableBlock(b, color, codeBg, link)
            is Block.Code -> CodeBlock(b.lang, b.text, color)
        }
    }
}

@Composable
private fun TableBlock(t: Block.Table, color: Color, codeBg: Color, link: Color) {
    val cols = t.header.size
    val widths = (0 until cols).map { c ->
        // Chinese characters are about twice as wide as Latin ones
        val longest = (listOf(t.header) + t.rows).maxOfOrNull { row ->
            (row.getOrNull(c) ?: "").sumOf { ch -> if (ch.code > 0x2E80) 2 else 1 as Int }
        } ?: 4
        (longest * 7.5f + 28f).coerceIn(64f, 190f).dp
    }
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp)).horizontalScroll(rememberScrollState()),
    ) {
        for ((r, row) in (listOf(t.header) + t.rows).withIndex()) {
            Row(Modifier.then(if (r == 0) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest) else Modifier)) {
                for (c in 0 until cols) {
                    Text(
                        inline(row.getOrNull(c) ?: "", codeBg, link), color = color,
                        style = MaterialTheme.typography.bodyMedium, fontWeight = if (r == 0) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.width(widths[c]).padding(horizontal = 12.dp, vertical = 9.dp),
                    )
                }
            }
            if (r < t.rows.size) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
    }
}

/** A code block with a header: the language on the left, a copy button on the right (as in Gemini and ChatGPT). */
@Composable
private fun CodeBlock(lang: String, code: String, color: Color) {
    val ctx = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(18.dp))) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(lang.ifBlank { "code" }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            IconButton(
                onClick = {
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("code", code))
                    copied = true
                },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy, contentDescription = null,
                    tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
        Text(
            code, color = color, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
        )
    }
}
