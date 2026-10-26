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
)

/** One running conversation per bot, kept as a small JSON file in the app's private storage. */
class ChatStore(dir: File, botId: String = DEFAULT_BOT_ID) {
    // the default bot keeps the original file name, so chats from before multiple bots are not lost
    private val file = File(dir, if (botId == DEFAULT_BOT_ID) "chat.json" else "chat-$botId.json")

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
            file.writeText(json.encodeToString(messages))
        } catch (_: Exception) {
        }
    }
}
