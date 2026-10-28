package com.labserver.lablink.data

/** A reply cut into what is read and what is looked at. */
sealed interface ReplyPart {
    data class Text(val markdown: String) : ReplyPart

    /** [src] is `media:<path on the server>` for a picture the bot made, or an http(s)/data URL. */
    data class Image(val src: String, val alt: String = "") : ReplyPart
}

private val MEDIA = Regex("""MEDIA:(\S+)""")
private val MD_IMAGE = Regex("""!\[([^\]]*)]\(\s*<?((?:https?://|data:image/)[^)\s>]+)>?(?:\s+"[^"]*")?\s*\)""")
private val FENCE = Regex("""^\s*(`{3,}|~{3,})""")
private val TRAILING = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '"', '\'', '。', '，', '；', '：', '！', '？', '）', '」', '`')

/**
 * Pulls the pictures out of a reply: Hermes' `MEDIA:/path` tokens and Markdown images `![alt](url)`. Everything else stays text,
 * in order; code blocks are left alone (a path in a code sample is not a picture to show).
 */
fun splitReply(content: String): List<ReplyPart> {
    if (!content.contains("MEDIA:") && !content.contains("![")) return listOf(ReplyPart.Text(content))
    val out = ArrayList<ReplyPart>()
    val text = StringBuilder()
    fun flush() {
        val t = text.toString().trim()
        // the full stop or comma left behind a picture is not worth a paragraph of its own
        if (t.any { !it.isWhitespace() && it !in TRAILING }) out += ReplyPart.Text(t)
        text.setLength(0)
    }
    var fence: String? = null
    for (line in content.split('\n')) {
        val f = FENCE.find(line)?.groupValues?.get(1)
        if (fence != null) {
            text.append(line).append('\n')
            if (f != null && f.first() == fence.first() && f.length >= fence.length) fence = null
            continue
        }
        if (f != null) { fence = f; text.append(line).append('\n'); continue }
        var rest = line
        while (true) {
            val m = MEDIA.find(rest)
            val i = MD_IMAGE.find(rest)
            val next = listOfNotNull(m, i).minByOrNull { it.range.first } ?: break
            text.append(rest, 0, next.range.first)
            if (next === m) {
                val raw = m.groupValues[1]
                val path = raw.trimEnd(*TRAILING)
                // a picture shows only once its path is complete (it has a file extension), not while it is still streaming in
                if (path.startsWith("/") && path.substringAfterLast('/').contains('.')) {
                    flush(); out += ReplyPart.Image("media:$path")
                    rest = raw.substring(path.length) + rest.substring(m.range.last + 1)
                } else {
                    text.append(m.value)
                    rest = rest.substring(m.range.last + 1)
                }
            } else {
                flush(); out += ReplyPart.Image(i!!.groupValues[2], i.groupValues[1])
                rest = rest.substring(i.range.last + 1)
            }
        }
        text.append(rest).append('\n')
    }
    flush()
    return out
}

/** The reply without its pictures (for copying and reading aloud). */
fun replyText(content: String): String =
    splitReply(content).filterIsInstance<ReplyPart.Text>().joinToString("\n\n") { it.markdown }
