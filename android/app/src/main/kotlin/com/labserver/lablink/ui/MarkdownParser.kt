package com.labserver.lablink.ui

/** The block structure of a (possibly still streaming) Markdown reply. No UI in here, so it can be tested on the JVM. */
internal sealed interface Block {
    data class Para(val text: String) : Block
    data class Heading(val level: Int, val text: String) : Block

    /** [depth] 0 is the top level; [task] is null for a plain item, else whether the box is ticked. */
    data class Item(val depth: Int, val marker: String, val text: String, val task: Boolean? = null) : Block
    data class Code(val lang: String, val text: String) : Block
    data class Quote(val blocks: List<Block>) : Block
    data class Table(val header: List<String>, val rows: List<List<String>>) : Block
    data object Rule : Block
}

private val fenceOpen = Regex("""^\s*(`{3,}|~{3,})\s*([^\s`]*).*$""")
private val heading = Regex("""^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$""")
private val bullet = Regex("""^(\s*)[-*+]\s+(.*)""")
private val numbered = Regex("""^(\s*)(\d+[.)])\s+(.*)""")
private val rule = Regex("""^\s{0,3}([-*_])(\s*\1){2,}\s*$""")
private val tableSep = Regex("""^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$""")
private val taskBox = Regex("""^\[([ xX])]\s+(.*)""", RegexOption.DOT_MATCHES_ALL)

private fun indentOf(line: String): Int = line.takeWhile { it == ' ' || it == '\t' }.sumOf { if (it == '\t') 4 else 1 as Int }

private fun cells(line: String): List<String> = line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

internal fun parseBlocks(src: String): List<Block> {
    val out = mutableListOf<Block>()
    val para = StringBuilder()
    fun flush() {
        if (para.isNotEmpty()) out += Block.Para(para.toString().trimEnd())
        para.setLength(0)
    }
    val lines = src.replace("\r\n", "\n").lines()
    var fence: String? = null          // the opening fence, e.g. "```" or "~~~~"
    var lang = ""
    val code = StringBuilder()
    val indents = ArrayList<Int>()     // indentation of the open list levels, to turn indents into depths
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val open = fence
        if (open != null) {
            val t = line.trim()
            if (t.startsWith(open[0].toString().repeat(open.length)) && t.all { it == open[0] }) {
                out += Block.Code(lang, code.toString().trimEnd('\n')); fence = null; code.setLength(0)
            } else code.appendLine(line)
            i++; continue
        }
        val f = fenceOpen.matchEntire(line)
        val h = heading.matchEntire(line)
        val b = bullet.matchEntire(line)
        val n = numbered.matchEntire(line)
        when {
            f != null -> { flush(); indents.clear(); fence = f.groupValues[1]; lang = f.groupValues[2] }
            h != null -> { flush(); indents.clear(); out += Block.Heading(h.groupValues[1].length, h.groupValues[2]) }
            rule.matches(line) -> { flush(); indents.clear(); out += Block.Rule }
            line.trimStart().startsWith(">") -> {
                flush(); indents.clear()
                val q = StringBuilder()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    q.appendLine(lines[i].trimStart().removePrefix(">").removePrefix(" ")); i++
                }
                out += Block.Quote(parseBlocks(q.toString())); continue
            }
            line.contains('|') && i + 1 < lines.size && tableSep.matches(lines[i + 1]) && lines[i + 1].contains('|') -> {
                flush(); indents.clear()
                val header = cells(line)
                val rows = mutableListOf<List<String>>()
                i += 2
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) { rows += cells(lines[i]); i++ }
                out += Block.Table(header, rows); continue
            }
            b != null || n != null -> {
                flush()
                val indent = indentOf(line)
                while (indents.isNotEmpty() && indents.last() > indent) indents.removeAt(indents.size - 1)
                if (indents.isEmpty() || indents.last() < indent) indents += indent
                val depth = indents.size - 1
                val rest = if (b != null) b.groupValues[2] else n!!.groupValues[3]
                val box = taskBox.matchEntire(rest)
                out += Block.Item(
                    depth, if (b != null) "•" else n!!.groupValues[2],
                    box?.groupValues?.get(2) ?: rest, box?.let { it.groupValues[1] != " " },
                )
            }
            line.isBlank() -> { flush(); if (lines.getOrNull(i + 1)?.let { bullet.matches(it) || numbered.matches(it) } != true) indents.clear() }
            // an indented line right under a list item continues that item
            indents.isNotEmpty() && para.isEmpty() && out.lastOrNull() is Block.Item && indentOf(line) > 0 -> {
                val last = out.removeAt(out.size - 1) as Block.Item
                out += last.copy(text = last.text + "\n" + line.trim())
            }
            else -> { indents.clear(); if (para.isNotEmpty()) para.append('\n'); para.append(line) }
        }
        i++
    }
    if (fence != null) out += Block.Code(lang, code.toString().trimEnd('\n')) // a reply still streaming inside a code block
    flush()
    return out
}
