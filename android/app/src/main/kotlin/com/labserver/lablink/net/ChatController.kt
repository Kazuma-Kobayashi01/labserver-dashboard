package com.labserver.lablink.net

import com.labserver.lablink.data.Bot
import com.labserver.lablink.data.BotStore
import com.labserver.lablink.data.ChatMessage
import com.labserver.lablink.data.ChatStore
import com.labserver.lablink.data.DEFAULT_BOT_ID
import com.labserver.lablink.data.Listing
import com.labserver.lablink.data.ListingCache
import com.labserver.lablink.data.Prefs
import com.labserver.lablink.data.TrashedBot
import com.labserver.lablink.data.isSandbox
import com.labserver.lablink.data.isSandboxId
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
    /** The default bot first, then the sandbox bots the server lists. */
    val bots: List<Bot>,
    /** The bot being talked to; [messages] is its conversation. */
    val bot: Bot,
    val messages: List<ChatMessage> = emptyList(),
    val streaming: Boolean = false,
    /** The agent is using a tool (searching the web...) before it answers. */
    val working: Boolean = false,
    val error: BotEvent.Error? = null,
    val trash: List<TrashedBot> = emptyList(),
    /** How many sandbox bots the server allows. */
    val limit: Int = 5,
    val trashDays: Int = 7,
    /** Sandbox bots whose sandbox is running right now. */
    val running: Set<String> = emptySet(),
    val syncing: Boolean = false,
    /** Why the last refresh failed (the lists then show what was cached). */
    val syncError: ManagerResult.Kind? = null,
) {
    val sandboxCount: Int get() = bots.count { it.isSandbox }
    val full: Boolean get() = sandboxCount >= limit
}

/**
 * Owns the bots and their conversations so they survive screen changes. The default bot is local; the sandbox bots
 * (at most [Listing.limit]) are kept by the server's bot-manager and mirrored here. Each bot has its own chat file.
 * One reply streams at a time; conversations are saved after every reply.
 */
class ChatController(
    private val dir: File,
    private val client: BotClient,
    private val manager: ManagerClient,
    private val prefs: Prefs,
    makeDefault: () -> Bot,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    private val botStore = BotStore(dir, makeDefault)
    private val cache = ListingCache(dir)

    /** The only local bot. Custom local bots from earlier versions are dropped (with their chats). */
    private var local: Bot = botStore.load().let { saved ->
        saved.filter { it.id != DEFAULT_BOT_ID }.forEach { ChatStore(dir, it.id).delete() }
        saved.firstOrNull { it.id == DEFAULT_BOT_ID } ?: makeDefault()
    }.also { botStore.save(listOf(it)) }

    private val initialListing = cache.load()
    private val initialBots = listOf(local) + initialListing.bots
    private val initialBot = initialBots.firstOrNull { it.id == prefs.currentBotId } ?: initialBots.first()
    private var store = ChatStore(dir, initialBot.id)

    private val _ui = MutableStateFlow(
        ChatUi(
            initialBots, initialBot, store.load(),
            trash = initialListing.trash, limit = initialListing.limit, trashDays = initialListing.trashDays, running = initialListing.running,
        ),
    )
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
                client.stream(history, bot).collect { ev ->
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

    /** Throws the last reply away and asks again with the last thing the user said. */
    fun regenerate() {
        val cur = _ui.value
        if (cur.streaming) return
        val at = cur.messages.indexOfLast { it.role == "user" }
        if (at < 0) return
        val text = cur.messages[at].content
        val kept = cur.messages.take(at)
        store.save(kept)
        _ui.update { it.copy(messages = kept, error = null) }
        send(text)
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

    /** Edits the default (local) bot. Sandbox bots go through [updateBot]. */
    fun saveBot(bot: Bot) {
        if (bot.id != DEFAULT_BOT_ID) return
        local = bot
        botStore.save(listOf(bot))
        _ui.update { st -> st.copy(bots = st.bots.map { if (it.id == bot.id) bot else it }, bot = if (st.bot.id == bot.id) bot else st.bot) }
    }

    fun dismissError() = _ui.update { it.copy(error = null) }

    fun managerAvailable(): Boolean = manager.available

    // ---- sandbox bots (the server is the source of truth)

    /** Asks the server for the lists. When it cannot be reached the cached ones stay and [ChatUi.syncError] says why. */
    suspend fun refresh() {
        _ui.update { it.copy(syncing = true) }
        when (val r = manager.list()) {
            is ManagerResult.Ok -> applyListing(r.value)
            is ManagerResult.Fail -> _ui.update { it.copy(syncing = false, syncError = r.kind) }
        }
    }

    suspend fun createBot(name: String, role: String, prompt: String, color: Long): ManagerResult<Bot> {
        val r = manager.create(name, role, prompt, color)
        if (r is ManagerResult.Ok) {
            refresh()
            select(r.value.id)
        }
        return r
    }

    suspend fun updateBot(bot: Bot): ManagerResult<Bot> {
        val r = manager.update(bot.id, bot.name, bot.role, bot.systemPrompt, bot.color)
        if (r is ManagerResult.Ok) refresh()
        return r
    }

    /** Moves a sandbox bot to the server's trash. Its conversation stays on the phone until it is removed for good. */
    suspend fun trashBot(id: String): ManagerResult<Unit> {
        val r = manager.trash(id)
        if (r is ManagerResult.Ok) {
            if (_ui.value.bot.id == id) select(DEFAULT_BOT_ID)
            refresh()
        }
        return r
    }

    suspend fun restoreBot(id: String): ManagerResult<Bot> {
        val r = manager.restore(id)
        if (r is ManagerResult.Ok) refresh()
        return r
    }

    suspend fun purgeBot(id: String): ManagerResult<Unit> {
        val r = manager.purge(id)
        if (r is ManagerResult.Ok) {
            ChatStore(dir, id).delete()
            refresh()
        }
        return r
    }

    private fun applyListing(l: Listing) {
        cache.save(l)
        val known = (l.bots.map { it.id } + l.trash.map { it.bot.id }).toSet()
        // bots that are gone on the server (removed after their time in the trash) leave no conversation behind
        dir.listFiles { f -> f.name.startsWith("chat-") && f.name.endsWith(".json") }?.forEach { f ->
            val id = f.name.removePrefix("chat-").removeSuffix(".json")
            if (isSandboxId(id) && id !in known) f.delete()
        }
        val bots = listOf(local) + l.bots
        val current = _ui.value.bot
        _ui.update {
            it.copy(
                bots = bots, bot = bots.firstOrNull { b -> b.id == current.id } ?: it.bot,
                trash = l.trash, limit = l.limit, trashDays = l.trashDays, running = l.running, syncing = false, syncError = null,
            )
        }
        if (bots.none { it.id == current.id }) select(DEFAULT_BOT_ID)
    }

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
