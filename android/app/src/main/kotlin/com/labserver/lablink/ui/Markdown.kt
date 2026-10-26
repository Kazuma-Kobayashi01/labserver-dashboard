package com.labserver.lablink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private sealed interface Block {
    data class Para(val text: String) : Block
    data class Heading(val level: Int, val text: String) : Block
    data class Item(val marker: String, val text: String) : Block
    data class Code(val text: String) : Block
}

private val bullet = Regex("""^\s*[-*+]\s+(.*)""")
private val numbered = Regex("""^\s*(\d+[.)])\s+(.*)""")

private fun parseBlocks(src: String): List<Block> {
    val out = mutableListOf<Block>()
    val para = StringBuilder()
    fun flush() {
        if (para.isNotEmpty()) out += Block.Para(para.toString().trimEnd()); para.setLength(0)
    }
    var code: StringBuilder? = null
    for (line in src.lines()) {
        if (code != null) {
            if (line.trimStart().startsWith("```")) { out += Block.Code(code.toString().trimEnd('\n')); code = null }
            else code.appendLine(line)
            continue
        }
        val t = line.trimStart()
        when {
            t.startsWith("```") -> { flush(); code = StringBuilder() }
            t.startsWith("#") -> {
                flush()
                val level = t.takeWhile { it == '#' }.length.coerceAtMost(3)
                out += Block.Heading(level, t.trimStart('#').trim())
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
    }
    code?.let { out += Block.Code(it.toString().trimEnd('\n')) } // a reply still streaming inside a code block
    flush()
    return out
}

/** **bold**, *italic* and `code` inside one line of text. */
private fun inline(text: String, codeBg: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else { append(text[i]); i++ }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > 0) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            text[i] == '*' && i + 1 < text.length && text[i + 1] != ' ' -> {
                val end = text.indexOf('*', i + 1)
                if (end > 0) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            else -> { append(text[i]); i++ }
        }
    }
}

/** A small Markdown subset: paragraphs, headings, lists, fenced code, bold / italic / inline code. */
@Composable
fun MarkdownText(text: String, color: Color, modifier: Modifier = Modifier) {
    val codeBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val blocks = parseBlocks(text)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (b in blocks) when (b) {
            is Block.Para -> Text(inline(b.text, codeBg), color = color, style = MaterialTheme.typography.bodyLarge)
            is Block.Heading -> Text(
                inline(b.text, codeBg), color = color, fontWeight = FontWeight.Bold,
                style = when (b.level) {
                    1 -> MaterialTheme.typography.titleLarge
                    2 -> MaterialTheme.typography.titleMedium
                    else -> MaterialTheme.typography.titleSmall
                },
            )
            is Block.Item -> Row(Modifier.padding(start = 4.dp)) {
                Text(b.marker, color = color, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 8.dp))
                Text(inline(b.text, codeBg), color = color, style = MaterialTheme.typography.bodyLarge)
            }
            is Block.Code -> Text(
                b.text, color = color, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(codeBg, RoundedCornerShape(14.dp))
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            )
        }
    }
}
