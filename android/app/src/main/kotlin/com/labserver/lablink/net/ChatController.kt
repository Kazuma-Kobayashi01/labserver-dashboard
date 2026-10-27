package com.labserver.lablink.net

import com.labserver.lablink.data.AttachException
import com.labserver.lablink.data.AttachProblem
import com.labserver.lablink.data.Attachment
import com.labserver.lablink.data.AttachmentKind
import com.labserver.lablink.data.AttachmentStore
import com.labserver.lablink.data.Bot
import com.labserver.lablink.data.Limits
import com.labserver.lablink.data.Catalog
import com.labserver.lablink.data.CatalogCache
import com.labserver.lablink.data.ModelPick
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
import java.util.UUID

/** A file on its way into the next message: being copied or uploaded, ready, or failed. */
data class Pending(
    val id: String,
    val name: String,
    val state: State = State.Working,
    val attachment: Attachment? = null,
    val problem: AttachProblem? = null,
) {
    enum class State { Working, Ready, Failed }
}

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
    /** The models the server offers. */
    val catalog: Catalog = Catalog(),
    /** The model picked in the chat for the current bot; empty = the bot's own default. */
    val pick: ModelPick = ModelPick(),
    /** Files attached to the message being written. */
    val pending: List<Pending> = emptyList(),
) {
    val canSend: Boolean get() = pending.none { it.state == Pending.State.Working }
    /** What the next message will use: the pick, else the bot's default (empty = the server's default). */
    val effectivePick: ModelPick get() = if (pick.isDefault) bot.pick else pick

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
    private val files: AttachmentStore,
    private val prefs: Prefs,
    makeDefault: () -> Bot,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    private val botStore = BotStore(dir, makeDefault)
    private val cache = ListingCache(dir)
    private val catalogCache = CatalogCache(dir)

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
            catalog = catalogCache.load(), pick = prefs.pick(initialBot.id),
        ),
    )
    val ui: StateFlow<ChatUi> = _ui

    /** Sends [text] with the files attached so far. */
    fun send(text: String) {
        val cur = _ui.value
        if (!cur.canSend) return
        val ready = cur.pending.mapNotNull { it.attachment.takeIf { _ -> it.state == Pending.State.Ready } }
        _ui.update { it.copy(pending = emptyList()) }
        post(text, ready)
    }

    private fun post(text: String, attachments: List<Attachment>) {
        val t = text.trim()
        val cur = _ui.value
        if ((t.isEmpty() && attachments.isEmpty()) || cur.streaming) { files.delete(attachments.filter { _ -> cur.streaming }); return }
        val bot = cur.bot
        val chatFile = store
        val history = cur.messages + ChatMessage("user", t, attachments = attachments)
        val reply = ChatMessage("assistant", "")
        // a sandbox bot keeps its own default on the server, so only an explicit pick is sent for it
        val sendPick = if (bot.isSandbox && cur.pick.isDefault) ModelPick() else cur.effectivePick
        _ui.update { it.copy(messages = history + reply, streaming = true, working = false, error = null) }

        job = scope.launch {
            val content = StringBuilder()
            val reasoning = StringBuilder()
            try {
                client.stream(history, bot, sendPick).collect { ev ->
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
        val last = cur.messages[at]
        val kept = cur.messages.take(at)
        store.save(kept)
        _ui.update { it.copy(messages = kept, error = null) }
        post(last.content, last.attachments)
    }

    fun clear() {
        job?.cancel()
        _ui.value.messages.forEach { files.delete(it.attachments) }
        store.save(emptyList())
        _ui.update { it.copy(messages = emptyList(), streaming = false, working = false, error = null) }
    }

    fun select(id: String) {
        val cur = _ui.value
        if (id == cur.bot.id) return
        val bot = cur.bots.firstOrNull { it.id == id } ?: return
        job?.cancel()
        dropPending()
        prefs.currentBotId = id
        store = ChatStore(dir, id)
        _ui.value = cur.copy(bot = bot, messages = store.load(), streaming = false, working = false, error = null, pick = prefs.pick(id), pending = emptyList())
    }

    /** Edits the default (local) bot. Sandbox bots go through [updateBot]. */
    fun saveBot(bot: Bot) {
        if (bot.id != DEFAULT_BOT_ID) return
        local = bot
        botStore.save(listOf(bot))
        _ui.update { st -> st.copy(bots = st.bots.map { if (it.id == bot.id) bot else it }, bot = if (st.bot.id == bot.id) bot else st.bot) }
    }

    fun dismissError() = _ui.update { it.copy(error = null) }

    /** The model and thinking level for this chat (empty = the bot's default); kept until changed. */
    fun setPick(p: ModelPick) {
        prefs.setPick(_ui.value.bot.id, p)
        _ui.update { it.copy(pick = p) }
    }

    fun managerAvailable(): Boolean = manager.available

    // ---- files and pictures

    /**
     * Takes the files [uris] point at. Pictures are shrunk, text files are kept to be read inline, anything else is uploaded to
     * the bot's sandbox (sandbox bots only). Each shows up as a chip while it is worked on; a failure leaves a chip saying why.
     */
    fun attach(uris: List<android.net.Uri>) {
        val bot = _ui.value.bot
        for (uri in uris) {
            val id = UUID.randomUUID().toString()
            _ui.update { it.copy(pending = it.pending + Pending(id, uri.lastPathSegment?.substringAfterLast('/') ?: "file")) }
            scope.launch {
                val result = try {
                    val a = withContext(Dispatchers.IO) { files.import(uri) }
                    try { Pending(id, a.name, Pending.State.Ready, withContext(Dispatchers.IO) { prepare(bot, a) }) }
                    catch (e: AttachException) { files.delete(a); throw e }
                } catch (e: AttachException) {
                    Pending(id, _ui.value.pending.firstOrNull { it.id == id }?.name ?: "file", Pending.State.Failed, problem = e.problem)
                } catch (e: Exception) {
                    android.util.Log.w("LabLink", "could not attach $uri", e)
                    Pending(id, "file", Pending.State.Failed, problem = AttachProblem.Unreadable)
                }
                _ui.update { st ->
                    if (st.bot.id != bot.id) { result.attachment?.let(files::delete); st }
                    else st.copy(pending = st.pending.map { if (it.id == id) result else it })
                }
            }
        }
    }

    /** Checks the picture limit and, for a file, uploads it; returns what goes into the message. */
    private suspend fun prepare(bot: Bot, a: Attachment): Attachment {
        val taken = _ui.value.pending.count { it.attachment?.kind == AttachmentKind.Image }
        if (a.kind == AttachmentKind.Image && taken >= Limits.MAX_IMAGES) throw AttachException(AttachProblem.TooManyImages)
        if (a.kind != AttachmentKind.File) return a
        if (!bot.isSandbox) throw AttachException(AttachProblem.NoSandbox)
        return when (val r = manager.upload(bot.id, a.name, files.file(a))) {
            is ManagerResult.Ok -> a.copy(remotePath = r.value)
            is ManagerResult.Fail -> throw AttachException(if (r.kind == ManagerResult.Kind.Invalid && r.detail.contains("MB")) AttachProblem.TooLarge else AttachProblem.UploadFailed, r.detail)
        }
    }

    fun removePending(id: String) {
        val p = _ui.value.pending.firstOrNull { it.id == id } ?: return
        p.attachment?.let(files::delete)
        _ui.update { st -> st.copy(pending = st.pending.filterNot { it.id == id }) }
    }

    private fun dropPending() {
        _ui.value.pending.forEach { p -> p.attachment?.let(files::delete) }
    }

    /** Removes the stored pictures and files of a conversation. */
    private fun deleteChatFiles(botId: String) {
        files.delete(ChatStore(dir, botId).load().flatMap { it.attachments })
    }

    // ---- sandbox bots (the server is the source of truth)

    /** Asks the server for the lists. When it cannot be reached the cached ones stay and [ChatUi.syncError] says why. */
    suspend fun refresh() {
        _ui.update { it.copy(syncing = true) }
        when (val r = manager.list()) {
            is ManagerResult.Ok -> {
                applyListing(r.value)
                (manager.models() as? ManagerResult.Ok)?.let { m ->
                    catalogCache.save(m.value)
                    _ui.update { it.copy(catalog = m.value) }
                }
            }
            is ManagerResult.Fail -> _ui.update { it.copy(syncing = false, syncError = r.kind) }
        }
    }

    suspend fun createBot(bot: Bot): ManagerResult<Bot> {
        val r = manager.create(bot)
        if (r is ManagerResult.Ok) {
            refresh()
            select(r.value.id)
        }
        return r
    }

    suspend fun updateBot(bot: Bot): ManagerResult<Bot> {
        val r = manager.update(bot)
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
            deleteChatFiles(id)
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
            if (isSandboxId(id) && id !in known) { deleteChatFiles(id); f.delete() }
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
