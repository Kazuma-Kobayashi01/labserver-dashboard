package com.labserver.lablink.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Calendar
import java.util.UUID

/** One chat with a bot. Its messages live in their own file; the list of a bot's chats is its index. */
@Serializable
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    /** Empty until the first message names it (or the user renames it). */
    val title: String = "",
    val created: Long = System.currentTimeMillis(),
    val updated: Long = System.currentTimeMillis(),
    val pinned: Boolean = false,
    /** True once the user named it: later messages then leave the title alone. */
    val renamed: Boolean = false,
)

/** A short title from the first thing said: its first line, squeezed and cut to a phone's width. */
fun titleFrom(messages: List<ChatMessage>): String {
    val first = messages.firstOrNull { it.role == "user" } ?: return ""
    val line = first.content.lineSequence().map { it.trim().trimStart('#', '>', '-', '*', ' ') }.firstOrNull { it.isNotBlank() }
        ?: return first.attachments.firstOrNull()?.name.orEmpty()
    val squeezed = line.replace(Regex("\\s+"), " ")
    return if (squeezed.length <= TITLE_MAX) squeezed else squeezed.take(TITLE_MAX - 1).trimEnd() + "…"
}

private const val TITLE_MAX = 40

/** Where a chat falls in the history list, newest first. */
enum class Period { Pinned, Today, Yesterday, Week, Month, Older }

fun periodOf(c: Conversation, now: Long = System.currentTimeMillis()): Period {
    if (c.pinned) return Period.Pinned
    val day = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val dayMs = 24 * 60 * 60 * 1000L
    return when {
        c.updated >= day -> Period.Today
        c.updated >= day - dayMs -> Period.Yesterday
        c.updated >= day - 7 * dayMs -> Period.Week
        c.updated >= day - 30 * dayMs -> Period.Month
        else -> Period.Older
    }
}

/** Pinned first, then the most recently used. */
fun List<Conversation>.ordered(): List<Conversation> = sortedWith(compareByDescending<Conversation> { it.pinned }.thenByDescending { it.updated })

/**
 * A bot's chats: the index (`convs[-botId].json`) and one message file per chat (`conv-<id>.json`). The single chat a bot had
 * before there were several becomes its first chat the first time the index is read.
 */
class ConversationStore(private val dir: File, private val botId: String) {
    private val index = File(dir, if (botId == DEFAULT_BOT_ID) "convs.json" else "convs-$botId.json")
    private val json = Json { ignoreUnknownKeys = true }

    fun messages(id: String) = ChatStore(messageFile(dir, id))

    fun load(): List<Conversation> {
        if (index.exists()) {
            return try { json.decodeFromString<List<Conversation>>(index.readText()).ordered() } catch (_: Exception) { emptyList() }
        }
        val legacy = ChatStore.legacyFile(dir, botId)
        val old = ChatStore(legacy).load()
        if (old.isEmpty()) { legacy.delete(); return emptyList() }
        val c = Conversation(title = titleFrom(old), created = old.first().time, updated = old.last().time)
        messages(c.id).save(old)
        legacy.delete()
        save(listOf(c))
        return listOf(c)
    }

    fun save(list: List<Conversation>) {
        try {
            val tmp = File(dir, index.name + ".tmp")
            tmp.writeText(json.encodeToString(list))
            if (!tmp.renameTo(index)) { index.writeText(tmp.readText()); tmp.delete() }
        } catch (_: Exception) {
        }
    }

    /** Adds or replaces [c] and returns the new list. */
    fun upsert(c: Conversation): List<Conversation> {
        val list = (load().filterNot { it.id == c.id } + c).ordered()
        save(list)
        return list
    }

    fun delete(id: String): List<Conversation> {
        messageFile(dir, id).delete()
        val list = load().filterNot { it.id == id }
        save(list)
        return list
    }

    /** Every chat of this bot goes (the bot was removed for good). */
    fun deleteAll() {
        load().forEach { messageFile(dir, it.id).delete() }
        index.delete()
        ChatStore.legacyFile(dir, botId).delete()
    }

    /** The chats whose title or any message contains [query] (ignoring case). */
    fun search(query: String): Set<String> {
        val q = query.trim()
        if (q.isEmpty()) return emptySet()
        return load().filter { c ->
            c.title.contains(q, ignoreCase = true) ||
                messages(c.id).load().any { it.content.contains(q, ignoreCase = true) || it.attachments.any { a -> a.name.contains(q, ignoreCase = true) } }
        }.map { it.id }.toSet()
    }

    companion object {
        fun messageFile(dir: File, id: String) = File(dir, "conv-$id.json")
    }
}

/** A chat as Markdown, for sharing or saving elsewhere. */
fun exportMarkdown(title: String, botName: String, messages: List<ChatMessage>, you: String): String = buildString {
    append("# ").append(title.ifBlank { botName }).append("\n\n")
    for (m in messages) {
        append("**").append(if (m.role == "user") you else botName).append("**\n\n")
        m.attachments.forEach { append("📎 ").append(it.name).append('\n') }
        if (m.attachments.isNotEmpty()) append('\n')
        append(m.content.trim()).append("\n\n")
    }
}.trimEnd() + "\n"
