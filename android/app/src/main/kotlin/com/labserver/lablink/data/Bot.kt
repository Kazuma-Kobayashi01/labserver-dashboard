package com.labserver.lablink.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * The default `Lab` bot is local: a name, a role line, a system prompt and a colour, talking to the restricted Hermes
 * `labbot` profile (the prompt is sent with every request). Sandbox bots (see [isSandbox]) are kept by the server's
 * bot-manager, which also holds their prompt; the phone keeps only their conversations.
 */
@Serializable
data class Bot(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val role: String = "",
    val systemPrompt: String = "",
    /** ARGB of the orb. */
    val color: Long = BotColors.first(),
    /** The bot's own default model and thinking level; empty = the server's default. */
    val modelProvider: String = "",
    val modelId: String = "",
    val reasoning: String = "",
    /**
     * A server bot made with a sandbox: it may run commands and use files there, and has a terminal. Chosen once, when it
     * is made; bots from before the choice existed all have one.
     */
    val sandboxed: Boolean = true,
) {
    val pick: ModelPick get() = ModelPick(modelProvider, modelId, reasoning)
}

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

/** Sandbox bots live on the server; their id is the one the server gave them. */
private val SANDBOX_ID = Regex("^b[0-9a-f]{10}$")
fun isSandboxId(id: String) = SANDBOX_ID.matches(id)
val Bot.isSandbox: Boolean get() = isSandboxId(id)

/** A server bot that has its own sandbox (commands, files). */
val Bot.hasSandbox: Boolean get() = isSandbox && sandboxed

/** Who gets a terminal: the default bot (the server's admin) and the bots with a sandbox. */
val Bot.hasTerminal: Boolean get() = id == DEFAULT_BOT_ID || hasSandbox

/** A bot in the server's trash; [expiresAt] (epoch seconds) is when it will be removed for good. */
@Serializable
data class TrashedBot(val bot: Bot, val trashedAt: Long, val expiresAt: Long? = null)

/** What the server last told us, kept so the lists show something while the tunnel is down. */
@Serializable
data class Listing(
    val bots: List<Bot> = emptyList(),
    val running: Set<String> = emptySet(),
    val trash: List<TrashedBot> = emptyList(),
    val limit: Int = 5,
    val trashLimit: Int = 20,
    val trashDays: Int = 7,
)

class ListingCache(dir: File) {
    private val file = File(dir, "remote.json")
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): Listing = try {
        if (file.exists()) json.decodeFromString(file.readText()) else Listing()
    } catch (_: Exception) {
        Listing()
    }

    fun save(l: Listing) {
        try {
            file.writeText(json.encodeToString(l))
        } catch (_: Exception) {
        }
    }
}

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
