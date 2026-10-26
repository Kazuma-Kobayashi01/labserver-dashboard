package com.labserver.lablink.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * A bot is a personality, nothing more: a name, a short role line, a system prompt and a colour. They all talk to the
 * same restricted Hermes profile on the server; the system prompt is sent with every request.
 */
@Serializable
data class Bot(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val role: String = "",
    val systemPrompt: String = "",
    /** ARGB of the orb. */
    val color: Long = BotColors.first(),
)

object BotColors {
    private val all = listOf(
        0xFF4B3FD8, // indigo
        0xFF8E5CF6, // violet
        0xFFE8589B, // pink
        0xFFF2724D, // coral
        0xFFF2B63D, // amber
        0xFF2FBF8F, // mint
        0xFF3B9BE8, // sky
        0xFF2B2B3A, // graphite
    )
    fun first() = all.first()
    fun palette() = all
}

const val DEFAULT_BOT_ID = "lab"

/** The list of bots, kept as a small JSON file next to the chats. Never empty: there is always a default bot. */
class BotStore(dir: File, private val defaultBot: () -> Bot) {
    private val file = File(dir, "bots.json")
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<Bot> {
        val saved = try {
            if (file.exists()) json.decodeFromString<List<Bot>>(file.readText()) else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        return saved.ifEmpty { listOf(defaultBot()) }
    }

    fun save(bots: List<Bot>) {
        try {
            file.writeText(json.encodeToString(bots))
        } catch (_: Exception) {
        }
    }
}
