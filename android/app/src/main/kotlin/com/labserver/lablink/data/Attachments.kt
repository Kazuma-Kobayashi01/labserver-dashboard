package com.labserver.lablink.data

import kotlinx.serialization.Serializable
import org.json.JSONArray
import org.json.JSONObject

enum class AttachmentKind { Image, Text, File }

/**
 * Something sent along with a message. The bytes live in the app's private storage ([local], a file name inside the
 * attachments folder); the chat file only holds this description.
 */
@Serializable
data class Attachment(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val kind: AttachmentKind,
    val local: String,
    /** Where the bot's sandbox sees an uploaded [AttachmentKind.File]; empty until it is uploaded. */
    val remotePath: String = "",
)

object Limits {
    const val MAX_IMAGES = 4
    const val IMAGE_EDGE = 1568
    const val MAX_TEXT_BYTES = 128 * 1024
    const val MAX_FILE_BYTES = 25L * 1024 * 1024
    /** Only the latest messages carry their pictures; earlier ones are mentioned by name so a long chat stays light. */
    const val IMAGE_TURNS = 2
}

private val textExtensions = setOf(
    "txt", "md", "markdown", "rst", "log", "csv", "tsv", "json", "jsonl", "yaml", "yml", "toml", "ini", "cfg", "conf", "env", "xml", "html",
    "htm", "css", "scss", "js", "mjs", "ts", "tsx", "jsx", "kt", "kts", "java", "c", "h", "cpp", "hpp", "cc", "cs", "go", "rs", "py", "rb",
    "php", "swift", "sh", "bash", "zsh", "fish", "ps1", "bat", "sql", "gradle", "properties", "tex", "diff", "patch", "dockerfile", "makefile",
    "lua", "pl", "r", "dart", "vue", "svelte", "proto", "tf", "service", "desktop",
)
private val imageMimes = setOf("image/png", "image/jpeg", "image/webp", "image/gif", "image/heic", "image/heif", "image/bmp")

fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

/** Image, text the model can simply read inline, or a file that has to go to the bot's sandbox. [looksLikeText] is a check of the first bytes. */
fun classify(name: String, mime: String, size: Long, looksLikeText: Boolean): AttachmentKind {
    val m = mime.lowercase()
    if (m in imageMimes || (m.startsWith("image/") && extensionOf(name) in setOf("png", "jpg", "jpeg", "webp", "gif", "heic", "heif", "bmp"))) return AttachmentKind.Image
    val textual = m.startsWith("text/") || m in setOf("application/json", "application/xml", "application/x-yaml", "application/javascript", "application/x-sh") ||
        extensionOf(name) in textExtensions || name.lowercase() in setOf("dockerfile", "makefile")
    return if (textual && looksLikeText && size <= Limits.MAX_TEXT_BYTES) AttachmentKind.Text else AttachmentKind.File
}

/**
 * True when the bytes are valid UTF-8 without NUL characters (a binary file in disguise fails this). With [truncated] set, a
 * character cut off at the end of [head] (it is only the first bytes of a bigger file) does not count against it.
 */
fun looksLikeText(head: ByteArray, truncated: Boolean = false): Boolean {
    if (head.any { it == 0.toByte() }) return false
    val decoder = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
    return !decoder.decode(java.nio.ByteBuffer.wrap(head), java.nio.CharBuffer.allocate(head.size + 1), !truncated).isError
}

fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
    else -> "%.1f MB".format(bytes / 1048576.0)
}

/**
 * The content of one message as the model should see it: the typed text, then the inlined text files, then notes naming the
 * files that sit in the sandbox, and the pictures as inline data URLs. Plain text stays a plain string.
 *
 * @param withImages false for older messages: their pictures are only named
 */
fun buildContent(text: String, attachments: List<Attachment>, withImages: Boolean, readBytes: (Attachment) -> ByteArray?): Any {
    val sb = StringBuilder(text)
    val images = JSONArray()
    for (a in attachments) when (a.kind) {
        AttachmentKind.Text -> {
            val body = readBytes(a)?.toString(Charsets.UTF_8) ?: "(the file could not be read)"
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("<file name=\"").append(a.name).append("\">\n").append(body.trimEnd('\n')).append("\n</file>")
        }
        AttachmentKind.File -> {
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("[attached file: ").append(a.name).append(" (").append(humanSize(a.size)).append(")")
            if (a.remotePath.isNotEmpty()) sb.append(", in your sandbox at ").append(a.remotePath)
            sb.append("]")
        }
        AttachmentKind.Image -> {
            val bytes = if (withImages) readBytes(a) else null
            if (bytes != null) {
                images.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:${a.mime};base64,${java.util.Base64.getEncoder().encodeToString(bytes)}")))
            } else {
                if (sb.isNotEmpty()) sb.append("\n\n")
                sb.append("[an image was attached here: ").append(a.name).append("]")
            }
        }
    }
    if (images.length() == 0) return sb.toString()
    val parts = JSONArray()
    if (sb.isNotEmpty()) parts.put(JSONObject().put("type", "text").put("text", sb.toString()))
    for (i in 0 until images.length()) parts.put(images.get(i))
    return parts
}
