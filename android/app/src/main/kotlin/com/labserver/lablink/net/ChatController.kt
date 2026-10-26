package com.labserver.lablink.net

import com.labserver.lablink.data.Bot
import com.labserver.lablink.data.BotStore
import com.labserver.lablink.data.ChatMessage
import com.labserver.lablink.data.ChatStore
import com.labserver.lablink.data.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ChatUi(
    val bots: List<Bot>,
    /** The bot being talked to; [messages] is its conversation. */
    val bot: Bot,
    val messages: List<ChatMessage> = emptyList(),
    val streaming: Boolean = false,
    /** The agent is using a tool (searching the web...) before it answers. */
    val working: Boolean = false,
    val error: BotEvent.Error? = null,
)

/**
 * Owns the bots and their conversations so they survive screen changes. Each bot has its own chat file; the bot's
 * system prompt goes out with every request. One reply streams at a time; conversations are saved after every reply.
 */
class ChatController(
    private val dir: File,
    private val client: BotClient,
    private val prefs: Prefs,
    defaultBot: () -> Bot,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    private val botStore = BotStore(dir, defaultBot)
    private val initialBots = botStore.load()
    private val initialBot = initialBots.firstOrNull { it.id == prefs.currentBotId } ?: initialBots.first()
    private var store = ChatStore(dir, initialBot.id)

    private val _ui = MutableStateFlow(ChatUi(initialBots, initialBot, store.load()))
    val ui: StateFlow<ChatUi> = _ui

    fun send(text: String) {
        val t = text.trim()
        val cur = _ui.value
        if (t.isEmpty() || cur.streaming) return
        val bot = cur.bot
        val chatFile = store
        val history = cur.messages + ChatMessage("user", t)
        val reply = ChatMessage("assistant", "")
        _ui.update { it.copy(messages = history + reply, streaming = true, working = false, error = null) }

        job = scope.launch {
            val content = StringBuilder()
            val reasoning = StringBuilder()
            try {
                client.stream(history, bot.systemPrompt).collect { ev ->
                    when (ev) {
                        is BotEvent.Content -> {
                            content.append(ev.text)
                            update(bot.id, reply.id, content, reasoning, working = false)
                        }
                        is BotEvent.Reasoning -> {
                            reasoning.append(ev.text)
                            update(bot.id, reply.id, content, reasoning, working = null)
                        }
                        BotEvent.Tool -> onCurrent(bot.id) { it.copy(working = true) }
                        BotEvent.Done -> Unit
                        is BotEvent.Error -> onCurrent(bot.id) { it.copy(error = ev) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onCurrent(bot.id) { it.copy(error = BotEvent.Error(BotEvent.Error.Kind.Other, e.message ?: e.toString())) }
            } finally {
                withContext(NonCancellable) {
                    // save into the chat this reply belongs to, even if the user switched bots meanwhile
                    val finalReply = reply.copy(content = content.toString(), reasoning = reasoning.toString())
                    val finalList = if (finalReply.content.isBlank() && finalReply.reasoning.isBlank()) history else history + finalReply
                    chatFile.save(finalList)
                    onCurrent(bot.id) { it.copy(messages = finalList, streaming = false, working = false) }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    fun clear() {
        job?.cancel()
        store.save(emptyList())
        _ui.update { it.copy(messages = emptyList(), streaming = false, working = false, error = null) }
    }

    fun select(id: String) {
        val cur = _ui.value
        if (id == cur.bot.id) return
        val bot = cur.bots.firstOrNull { it.id == id } ?: return
        job?.cancel()
        prefs.currentBotId = id
        store = ChatStore(dir, id)
        _ui.value = cur.copy(bot = bot, messages = store.load(), streaming = false, working = false, error = null)
    }

    /** Adds the bot, or updates it when its id already exists. */
    fun saveBot(bot: Bot) {
        val list = _ui.value.bots
        val bots = if (list.any { it.id == bot.id }) list.map { if (it.id == bot.id) bot else it } else list + bot
        botStore.save(bots)
        _ui.update { it.copy(bots = bots, bot = if (it.bot.id == bot.id) bot else it.bot) }
    }

    /** The last remaining bot cannot be deleted. */
    fun deleteBot(id: String) {
        val bots = _ui.value.bots
        if (bots.size <= 1 || bots.none { it.id == id }) return
        val remaining = bots.filterNot { it.id == id }
        if (_ui.value.bot.id == id) select(remaining.first().id)
        ChatStore(dir, id).delete()
        botStore.save(remaining)
        _ui.update { it.copy(bots = remaining) }
    }

    fun dismissError() = _ui.update { it.copy(error = null) }

    /** Applies [change] only while [botId] is still the bot on screen. */
    private fun onCurrent(botId: String, change: (ChatUi) -> ChatUi) {
        _ui.update { if (it.bot.id == botId) change(it) else it }
    }

    private fun update(botId: String, id: String, content: CharSequence, reasoning: CharSequence, working: Boolean?) {
        onCurrent(botId) { st ->
            st.copy(
                messages = st.messages.map { if (it.id == id) it.copy(content = content.toString(), reasoning = reasoning.toString()) else it },
                working = working ?: st.working,
            )
        }
    }
}
