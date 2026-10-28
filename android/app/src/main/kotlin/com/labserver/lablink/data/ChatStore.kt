package com.labserver.lablink.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class ChatMessage(
    val role: String, // "user" | "assistant"
    val content: String,
    val reasoning: String = "",
    val id: String = UUID.randomUUID().toString(),
    val time: Long = System.currentTimeMillis(),
    val attachments: List<Attachment> = emptyList(),
    /** A user message that never reached the bot (no reply came back); shown with a red mark and sent again on a tap. */
    val failed: Boolean = false,
    /** How long the bot thought before it started answering (0 = it did not show its thinking). */
    val thinkingMs: Long = 0,
    /** The tools the bot used while answering (commands with their output), in order. */
    val tools: List<ToolRun> = emptyList(),
)

/**
 * One tool the bot used during a reply. For a command: what ran, what it printed and how it ended. [at] is where in the
 * reply's text it happened, so it is shown between the words it came between.
 */
@Serializable
data class ToolRun(
    val id: String,
    val tool: String = "terminal",
    val label: String = "",
    val command: String = "",
    val output: String = "",
    val exitCode: Int? = null,
    val error: String? = null,
    val durationMs: Long? = null,
    val done: Boolean = false,
    val at: Int = 0,
)

/** The messages of one conversation, kept as a small JSON file in the app's private storage. */
class ChatStore(private val file: File) {
    companion object {
        /** Before conversations: the one chat each bot had (the default bot kept the original name). */
        fun legacyFile(dir: File, botId: String) = File(dir, if (botId == DEFAULT_BOT_ID) "chat.json" else "chat-$botId.json")
    }

    fun delete() {
        file.delete()
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<ChatMessage> = try {
        if (file.exists()) json.decodeFromString(file.readText()) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    fun save(messages: List<ChatMessage>) {
        try {
            // written aside and moved over, so a crash mid-write never leaves half a conversation
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(messages))
            if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        } catch (_: Exception) {
        }
    }
}
