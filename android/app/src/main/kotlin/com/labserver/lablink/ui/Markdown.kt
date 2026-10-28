package com.labserver.lablink.ui

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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

private val bareUrl = Regex("""https?://[^\s)<>\]]+""")
private const val ESCAPABLE = "\\`*_{}[]()#+-.!|~>"

private fun isWordChar(c: Char?) = c != null && (c.isLetterOrDigit())

/** Inline Markdown inside one run of text: **bold**, *italic*, ***both***, ~~strike~~, `code`, [links](url), <br>, escapes. */
private fun AnnotatedString.Builder.inlineInto(text: String, codeBg: Color, link: Color) {
    val linkStyle = TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline))
    var i = 0
    fun styled(style: SpanStyle, inner: String) { withStyle(style) { inlineInto(inner, codeBg, link) } }
    while (i < text.length) {
        val c = text[i]
        when {
            c == '\\' && i + 1 < text.length && text[i + 1] in ESCAPABLE -> { append(text[i + 1]); i += 2 }
            text.startsWith("<br>", i, true) -> { append('\n'); i += 4 }
            text.startsWith("<br/>", i, true) -> { append('\n'); i += 5 }
            c == '`' -> {
                var n = 0
                while (i + n < text.length && text[i + n] == '`') n++
                val fence = "`".repeat(n)
                val end = text.indexOf(fence, i + n)
                if (end > 0) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, background = codeBg)) { append(" " + text.substring(i + n, end).trim() + " ") }
                    i = end + n
                } else { append(fence); i += n }
            }
            text.startsWith("***", i) && text.indexOf("***", i + 3) > i + 3 -> {
                val end = text.indexOf("***", i + 3)
                styled(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic), text.substring(i + 3, end)); i = end + 3
            }
            (text.startsWith("**", i) || (text.startsWith("__", i) && !isWordChar(text.getOrNull(i - 1)))) && run {
                val m = text.substring(i, i + 2); text.indexOf(m, i + 2) > i + 2
            } -> {
                val m = text.substring(i, i + 2); val end = text.indexOf(m, i + 2)
                styled(SpanStyle(fontWeight = FontWeight.Bold), text.substring(i + 2, end)); i = end + 2
            }
            text.startsWith("~~", i) && text.indexOf("~~", i + 2) > i + 2 -> {
                val end = text.indexOf("~~", i + 2)
                styled(SpanStyle(textDecoration = TextDecoration.LineThrough), text.substring(i + 2, end)); i = end + 2
            }
            c == '*' && i + 1 < text.length && text[i + 1] != ' ' && text[i + 1] != '*' && run {
                var e = text.indexOf('*', i + 1); while (e > 0 && text.getOrNull(e + 1) == '*') e = text.indexOf('*', e + 2); e > i + 1
            } -> {
                var end = text.indexOf('*', i + 1); while (end > 0 && text.getOrNull(end + 1) == '*') end = text.indexOf('*', end + 2)
                styled(SpanStyle(fontStyle = FontStyle.Italic), text.substring(i + 1, end)); i = end + 1
            }
            c == '_' && !isWordChar(text.getOrNull(i - 1)) && i + 1 < text.length && text[i + 1] != ' ' && text[i + 1] != '_' && run {
                val e = text.indexOf('_', i + 1); e > i + 1 && !isWordChar(text.getOrNull(e + 1))
            } -> {
                val end = text.indexOf('_', i + 1)
                styled(SpanStyle(fontStyle = FontStyle.Italic), text.substring(i + 1, end)); i = end + 1
            }
            (c == '[' || (c == '!' && text.getOrNull(i + 1) == '[')) -> {
                val start = if (c == '!') i + 1 else i
                val close = text.indexOf("](", start)
                val end = if (close > 0) text.indexOf(')', close) else -1
                if (close > 0 && end > 0) {
                    val url = text.substring(close + 2, end).trim().substringBefore(' ')
                    val label = text.substring(start + 1, close)
                    withLink(LinkAnnotation.Url(url, linkStyle)) { inlineInto(if (c == '!') "🖼 " + label.ifBlank { url } else label, codeBg, link) }
                    i = end + 1
                } else { append(c); i++ }
            }
            c == '<' && text.startsWith("<http", i) && text.indexOf('>', i) > 0 -> {
                val end = text.indexOf('>', i); val url = text.substring(i + 1, end)
                withLink(LinkAnnotation.Url(url, linkStyle)) { append(url) }; i = end + 1
            }
            c == 'h' && !isWordChar(text.getOrNull(i - 1)) && bareUrl.matchAt(text, i) != null -> {
                val url = bareUrl.matchAt(text, i)!!.value.trimEnd('.', ',', ';', ':', '!', '?')
                withLink(LinkAnnotation.Url(url, linkStyle)) { append(url) }
                i += url.length
            }
            else -> { append(c); i++ }
        }
    }
}

private fun inline(text: String, codeBg: Color, link: Color): AnnotatedString = buildAnnotatedString { inlineInto(text, codeBg, link) }

/**
 * Markdown for chat: paragraphs, headings (all six levels), nested and task lists, quotes (which can hold anything), rules,
 * tables, fenced code with syntax colours and a copy button, and the inline styles above. Works while a reply is streaming.
 */
@Composable
fun MarkdownText(text: String, color: Color, modifier: Modifier = Modifier) {
    val codeBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f)
    val link = MaterialTheme.colorScheme.primary
    val blocks = remember(text) { parseBlocks(text) }
    BlockList(blocks, color, codeBg, link, modifier)
}

@Composable
private fun BlockList(blocks: List<Block>, color: Color, codeBg: Color, link: Color, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        var i = 0
        while (i < blocks.size) {
            val b = blocks[i]
            if (b is Block.Item) {
                // the items of one list sit closer together than separate paragraphs
                var j = i
                while (j < blocks.size && blocks[j] is Block.Item) j++
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (k in i until j) ListItem(blocks[k] as Block.Item, color, codeBg, link)
                }
                i = j; continue
            }
            when (b) {
                is Block.Para -> InlineText(b.text, color, codeBg, link, MaterialTheme.typography.bodyLarge)
                is Block.Heading -> InlineText(
                    b.text, color, codeBg, link,
                    when (b.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        3 -> MaterialTheme.typography.titleSmall
                        else -> MaterialTheme.typography.bodyLarge
                    },
                    Modifier.padding(top = 4.dp), FontWeight.ExtraBold,
                )
                is Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(2.dp)))
                    BlockList(b.blocks, MaterialTheme.colorScheme.onSurfaceVariant, codeBg, link, Modifier.padding(start = 12.dp))
                }
                Block.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                is Block.Table -> TableBlock(b, color, codeBg, link)
                is Block.Code -> CodeBlock(b.lang, b.text, color)
                is Block.Item -> Unit
            }
            i++
        }
    }
}

/** One run of inline Markdown; the styled text is built once per text, not on every redraw while a reply streams in. */
@Composable
private fun InlineText(
    text: String, color: Color, codeBg: Color, link: Color, style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier, weight: FontWeight? = null,
) {
    val styled = remember(text, codeBg, link) { inline(text, codeBg, link) }
    Text(styled, color = color, style = style, fontWeight = weight, modifier = modifier)
}

@Composable
private fun ListItem(b: Block.Item, color: Color, codeBg: Color, link: Color) {
    val marker = when {
        b.task == true -> "☑"
        b.task == false -> "☐"
        b.marker == "•" -> when (b.depth % 3) { 0 -> "•"; 1 -> "◦"; else -> "▪" }
        else -> b.marker
    }
    Row(Modifier.padding(start = 2.dp + 18.dp * b.depth)) {
        Text(
            marker, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(if (b.marker == "•" || b.task != null) 22.dp else 30.dp),
        )
        InlineText(
            b.text, if (b.task == true) MaterialTheme.colorScheme.onSurfaceVariant else color, codeBg, link,
            MaterialTheme.typography.bodyLarge, Modifier.weight(1f),
        )
    }
}

@Composable
private fun TableBlock(t: Block.Table, color: Color, codeBg: Color, link: Color) {
    val cols = t.header.size
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val widths = (0 until cols).map { c ->
        // Chinese characters are about twice as wide as Latin ones
        val longest = (listOf(t.header) + t.rows).maxOfOrNull { row ->
            (row.getOrNull(c) ?: "").sumOf { ch -> if (ch.code > 0x2E80) 2 else 1 as Int }
        } ?: 4
        (longest * 7.5f + 28f).coerceIn(64f, 190f)
    }.let { natural ->
        // a narrow table is stretched to the full width, a wide one scrolls sideways
        val total = natural.sum()
        val factor = if (total in 1f..maxWidth.value) maxWidth.value / total else 1f
        natural.map { (it * factor).dp }
    }
    // a light card with a hairline grid; the header row slightly tinted
    val line = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(glassFill(strong = true))
            .border(1.dp, line, RoundedCornerShape(16.dp)).horizontalScroll(rememberScrollState()),
    ) {
        for ((r, row) in (listOf(t.header) + t.rows).withIndex()) {
            Row(Modifier.then(if (r == 0) Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)) else Modifier)) {
                for (c in 0 until cols) {
                    InlineText(
                        row.getOrNull(c) ?: "", color, codeBg, link, MaterialTheme.typography.bodyMedium,
                        Modifier.width(widths[c]).padding(horizontal = 12.dp, vertical = 9.dp), if (r == 0) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
            if (r < t.rows.size) HorizontalDivider(color = line)
        }
    }
    }
}

/** A code block with a header: the language on the left, a copy button on the right (as in Gemini and ChatGPT). */
@Composable
private fun CodeBlock(lang: String, code: String, color: Color) {
    val Code = codePalette()
    val ctx = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    // an editor panel: night colours in dark mode, a white page in light mode
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Code.panel).border(1.dp, Code.border, RoundedCornerShape(16.dp))) {
        Row(Modifier.fillMaxWidth().background(Code.bar).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(lang.ifBlank { "code" }, style = MaterialTheme.typography.labelMedium, color = Code.muted, modifier = Modifier.weight(1f))
            IconButton(
                onClick = {
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("code", code))
                    copied = true
                },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy, contentDescription = null,
                    tint = if (copied) Code.added else Code.muted,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
        val shown = remember(code, lang, Code) {
            buildAnnotatedString {
                append(code)
                for (sp in tokenize(code, lang)) {
                    val style = when (sp.kind) {
                        Tok.Keyword -> SpanStyle(color = Code.keyword, fontWeight = FontWeight.SemiBold)
                        Tok.Type -> SpanStyle(color = Code.type)
                        Tok.Str -> SpanStyle(color = Code.string)
                        Tok.Number -> SpanStyle(color = Code.number)
                        Tok.Comment -> SpanStyle(color = Code.comment, fontStyle = FontStyle.Italic)
                        Tok.Added -> SpanStyle(color = Code.added)
                        Tok.Removed -> SpanStyle(color = Code.removed)
                        Tok.Plain -> null
                    }
                    if (style != null) addStyle(style, sp.start, sp.end)
                }
            }
        }
        Text(
            shown, color = Code.plain, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp),
        )
    }
}

/** The code panel's colours: a night editor in dark mode, a white page with GitHub-style colours in light mode. */
internal data class CodePalette(
    val panel: Color, val bar: Color, val border: Color, val plain: Color, val muted: Color, val keyword: Color,
    val type: Color, val string: Color, val number: Color, val comment: Color, val added: Color, val removed: Color,
)

private val NightCode = CodePalette(
    panel = Color(0xFF1B1B26), bar = Color(0xFF242433), border = Color(0x10FFFFFF), plain = Color(0xFFE4E4F0), muted = Color(0xFF9A9AB4),
    keyword = Color(0xFFC792EA), type = Color(0xFF82AAFF), string = Color(0xFFC3E88D), number = Color(0xFFF78C6C),
    comment = Color(0xFF6E7194), added = Color(0xFF4ED6A0), removed = Color(0xFFFF6B81),
)

private val DayCode = CodePalette(
    panel = Color.White, bar = Color(0xFFF4F4F8), border = Color(0xFFE4E4EC), plain = Color(0xFF24292F), muted = Color(0xFF6E7781),
    keyword = Color(0xFFCF222E), type = Color(0xFF8250DF), string = Color(0xFF0A3069), number = Color(0xFF0550AE),
    comment = Color(0xFF6E7781), added = Color(0xFF1A7F37), removed = Color(0xFFCF222E),
)

@Composable
internal fun codePalette(): CodePalette = if (isDark()) NightCode else DayCode
