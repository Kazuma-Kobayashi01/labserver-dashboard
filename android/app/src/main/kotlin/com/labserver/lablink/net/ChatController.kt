package com.labserver.lablink.net

import com.labserver.lablink.data.AttachException
import com.labserver.lablink.data.AttachProblem
import com.labserver.lablink.data.Attachment
import com.labserver.lablink.data.AttachmentKind
import com.labserver.lablink.data.AttachmentStore
import com.labserver.lablink.data.Bot
import com.labserver.lablink.data.Limits
import com.labserver.lablink.data.LibraryItem
import com.labserver.lablink.data.LibraryStore
import com.labserver.lablink.data.mergeSent
import com.labserver.lablink.data.Catalog
import com.labserver.lablink.data.CatalogCache
import com.labserver.lablink.data.ModelPick
import com.labserver.lablink.data.BotStore
import com.labserver.lablink.data.ChatMessage
import com.labserver.lablink.data.ChatStore
import com.labserver.lablink.data.Conversation
import com.labserver.lablink.data.ConversationStore
import com.labserver.lablink.data.titleFrom
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
import kotlinx.coroutines.cancelAndJoin
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
    /** The bot being talked to; [messages] is the open conversation with it. */
    val bot: Bot,
    val messages: List<ChatMessage> = emptyList(),
    /** The open conversation (a new one is not in [conversations] until its first message). */
    val conversationId: String = UUID.randomUUID().toString(),
    /** This bot's conversations: pinned first, then the most recent. */
    val conversations: List<Conversation> = emptyList(),
    /** Conversations of any bot whose reply is still coming in. */
    val busy: Set<String> = emptySet(),
    /** Guarded actions waiting for the user, by conversation (any bot); [approval] is the open conversation's. */
    val waiting: Map<String, ApprovalRequest> = emptyMap(),
    /** An answer to [approval] is on its way. */
    val answering: Boolean = false,
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
    /** Everything sent so far, newest first. */
    val library: List<LibraryItem> = emptyList(),
) {
    val canSend: Boolean get() = pending.none { it.state == Pending.State.Working }
    /** What the next message will use: the pick, else the bot's default (empty = the server's default). */
    val effectivePick: ModelPick get() = if (pick.isDefault) bot.pick else pick

    val conversation: Conversation? get() = conversations.firstOrNull { it.id == conversationId }
    val approval: ApprovalRequest? get() = waiting[conversationId]

    val sandboxCount: Int get() = bots.count { it.isSandbox }
    val full: Boolean get() = sandboxCount >= limit
}

/**
 * Owns the bots and their conversations so they survive screen changes. The default bot is local; the sandbox bots
 * (at most [Listing.limit]) are kept by the server's bot-manager and mirrored here. Each bot has any number of conversations.
 * Replies keep streaming when another conversation is opened; each is saved into its own conversation when it ends.
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
    /** Replies coming in, by conversation, and each conversation as it stands so far (shown again when it is reopened). */
    private val jobs = HashMap<String, Job>()
    private val live = HashMap<String, List<ChatMessage>>()

    private companion object {
        const val MAX_RETRIES = 2
        const val RETRY_DELAY_MS = 1500L
        const val UI_MS = 50L
    }

    private val botStore = BotStore(dir, makeDefault)
    private val cache = ListingCache(dir)
    private val catalogCache = CatalogCache(dir)
    private val libraryStore = LibraryStore(dir)

    /** The only local bot. Custom local bots from earlier versions are dropped (with their chats). */
    private var local: Bot = botStore.load().let { saved ->
        saved.filter { it.id != DEFAULT_BOT_ID }.forEach { ConversationStore(dir, it.id).deleteAll() }
        saved.firstOrNull { it.id == DEFAULT_BOT_ID } ?: makeDefault()
    }.also { botStore.save(listOf(it)) }

    private val initialListing = cache.load()
    private val initialBots = listOf(local) + initialListing.bots
    private val initialBot = initialBots.firstOrNull { it.id == prefs.currentBotId } ?: initialBots.first()
    private var convs = ConversationStore(dir, initialBot.id)
    private val initialConvs = convs.load()
    private val initialConv = initialConvs.firstOrNull { it.id == prefs.lastConversation(initialBot.id) }?.id ?: UUID.randomUUID().toString()
    private var store = convs.messages(initialConv)

    private val _ui = MutableStateFlow(
        ChatUi(
            initialBots, initialBot, store.load(), conversationId = initialConv, conversations = initialConvs,
            trash = initialListing.trash, limit = initialListing.limit, trashDays = initialListing.trashDays, running = initialListing.running,
            catalog = catalogCache.load(), pick = prefs.pick(initialBot.id), library = libraryStore.load(),
        ),
    )
    val ui: StateFlow<ChatUi> = _ui

    init {
        scope.launch(Dispatchers.IO) { sweepOrphans() }
    }

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
        if ((t.isEmpty() && attachments.isEmpty()) || cur.streaming) return
        val bot = cur.bot
        val convId = cur.conversationId
        val chatFile = store
        val index = convs
        val history = cur.messages + ChatMessage("user", t, attachments = attachments)
        if (attachments.isNotEmpty()) saveLibrary(mergeSent(cur.library, attachments, cur.bot.id, System.currentTimeMillis()))
        val reply = ChatMessage("assistant", "")
        // a sandbox bot keeps its own default on the server, so only an explicit pick is sent for it
        val sendPick = if (bot.isSandbox && cur.pick.isDefault) ModelPick() else cur.effectivePick
        live[convId] = history + reply
        // the conversation shows in the history from its first message on
        touch(index, bot.id, convId, history)
        prefs.setLastConversation(bot.id, convId)
        _ui.update { it.copy(messages = history + reply, streaming = true, working = false, error = null) }

        // started only once it is registered, so a reply that fails at once still finds and clears its own entry
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val content = StringBuilder()
            val reasoning = StringBuilder()
            var failedSend = false
            val started = android.os.SystemClock.elapsedRealtime()
            var answeredAt = 0L
            var lastUi = 0L
            // tokens can arrive far faster than a screen refreshes; the list is redrawn at most every UI_MS
            fun throttled(): Boolean {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastUi < UI_MS) return false
                lastUi = now
                return true
            }
            try {
                // a connection that drops before anything arrived (the phone changed network, the tunnel blinked) is retried
                var attempt = 0
                while (true) {
                    var dropped: BotEvent.Error? = null
                    client.stream(history, bot, sendPick).collect { ev ->
                        when (ev) {
                            is BotEvent.Content -> {
                                if (answeredAt == 0L) answeredAt = android.os.SystemClock.elapsedRealtime()
                                content.append(ev.text)
                                if (throttled()) update(convId, reply.id, content, reasoning, working = false)
                            }
                            is BotEvent.Reasoning -> {
                                reasoning.append(ev.text)
                                if (throttled()) update(convId, reply.id, content, reasoning, working = null)
                            }
                            BotEvent.Tool -> onCurrent(convId) { it.copy(working = true) }
                            is BotEvent.Approval -> {
                                // flush what came so far, so the question sits under the latest words
                                update(convId, reply.id, content, reasoning, working = true)
                                _ui.update { it.copy(waiting = it.waiting + (convId to ev.request.copy(botId = bot.id, botName = bot.name))) }
                            }
                            BotEvent.Done -> Unit
                            is BotEvent.Error ->
                                if (ev.kind == BotEvent.Error.Kind.Unreachable && content.isEmpty() && reasoning.isEmpty() && attempt < MAX_RETRIES) dropped = ev
                                else {
                                    failedSend = true
                                    onCurrent(convId) { it.copy(error = ev) }
                                }
                        }
                    }
                    if (dropped == null) break
                    attempt++
                    kotlinx.coroutines.delay(RETRY_DELAY_MS * attempt)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failedSend = true
                onCurrent(convId) { it.copy(error = BotEvent.Error(BotEvent.Error.Kind.Other, e.message ?: e.toString())) }
            } finally {
                withContext(NonCancellable) {
                    jobs.remove(convId)
                    live.remove(convId)
                    _ui.update { it.copy(waiting = it.waiting - convId, answering = false) }
                    // save into the conversation this reply belongs to, even if another one is open now
                    val thought = if (reasoning.isEmpty()) 0L else (if (answeredAt > 0) answeredAt else android.os.SystemClock.elapsedRealtime()) - started
                    val finalReply = reply.copy(content = content.toString(), reasoning = reasoning.toString(), thinkingMs = thought)
                    val empty = finalReply.content.isBlank() && finalReply.reasoning.isBlank()
                    // nothing came back after an error: the message did not get through
                    val finalList = if (!empty) history + finalReply
                    else if (failedSend) history.dropLast(1) + history.last().copy(failed = true)
                    else history
                    chatFile.save(finalList)
                    touch(index, bot.id, convId, finalList)
                    _ui.update { it.copy(busy = jobs.keys.toSet()) }
                    onCurrent(convId) { it.copy(messages = finalList, streaming = false, working = false) }
                }
            }
        }
        jobs[convId] = job
        _ui.update { it.copy(busy = jobs.keys.toSet()) }
        job.start()
    }

    /** Stops the reply coming in for the open conversation. */
    fun stop() {
        jobs[_ui.value.conversationId]?.cancel()
    }

    /**
     * Answers the open conversation's pending approval. On success the card goes and the reply carries on (or the bot is
     * told no); when Hermes no longer waits (it timed out) the card goes too and says so.
     */
    fun answerApproval(choice: String) {
        val cur = _ui.value
        val convId = cur.conversationId
        val req = cur.waiting[convId] ?: return
        if (cur.answering) return
        _ui.update { it.copy(answering = true) }
        scope.launch {
            val r = manager.answerApproval(prefs.profileOf(req.botId), req.runId, req.requestId, choice)
            _ui.update { st ->
                // a refusal other than "could not reach it" means Hermes no longer waits (it timed out, or the reply ended)
                val retry = setOf(ManagerResult.Kind.NotConnected, ManagerResult.Kind.Unreachable, ManagerResult.Kind.Auth, ManagerResult.Kind.NoKey, ManagerResult.Kind.BadUrl)
                val gone = r is ManagerResult.Ok || (r is ManagerResult.Fail && r.kind !in retry)
                st.copy(
                    answering = false,
                    waiting = if (gone && st.waiting[convId] === req) st.waiting - convId else st.waiting,
                    error = if (r is ManagerResult.Fail && st.conversationId == convId) BotEvent.Error(BotEvent.Error.Kind.Other, r.detail.ifBlank { r.kind.name }) else st.error,
                )
            }
        }
    }

    /** Records [messages] as the latest state of [convId]: its time, and its title until the user names it. */
    private fun touch(index: ConversationStore, botId: String, convId: String, messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        val old = index.load().firstOrNull { it.id == convId }
        val c = (old ?: Conversation(id = convId)).let { if (it.renamed) it else it.copy(title = titleFrom(messages)) }
            .copy(updated = System.currentTimeMillis())
        val list = index.upsert(c)
        _ui.update { if (it.bot.id == botId) it.copy(conversations = list) else it }
    }

    /** Saves the open conversation after the user changed it (retracted or regenerated). */
    private fun saveOpen(messages: List<ChatMessage>) {
        store.save(messages)
        val cur = _ui.value
        if (messages.isNotEmpty()) touch(convs, cur.bot.id, cur.conversationId, messages)
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

    /**
     * Takes back the user message [id] to edit it: it and everything after it leave the chat (a reply still coming in is stopped),
     * its pictures and files go back into the message being written, and [onText] gets its words for the input box.
     */
    fun retract(id: String, onText: (String) -> Unit) {
        scope.launch {
            jobs[_ui.value.conversationId]?.cancelAndJoin()   // its save must land before the chat is cut
            val cur = _ui.value
            val at = cur.messages.indexOfFirst { it.id == id && it.role == "user" }
            if (at < 0) return@launch
            val msg = cur.messages[at]
            val kept = cur.messages.take(at)
            saveOpen(kept)
            val back = msg.attachments.filter { a -> cur.pending.none { it.id == a.id } }.map { Pending(it.id, it.name, Pending.State.Ready, it) }
            _ui.update { it.copy(messages = kept, error = null, pending = back + it.pending) }
            onText(msg.content)
        }
    }

    /** Opens a fresh conversation with the current bot (the open one stays in the history). */
    fun newConversation() {
        val cur = _ui.value
        if (cur.messages.isEmpty() && !cur.streaming) return
        open(cur.bot, UUID.randomUUID().toString())
    }

    fun openConversation(id: String) {
        val cur = _ui.value
        if (id == cur.conversationId) return
        if (cur.conversations.none { it.id == id }) return
        open(cur.bot, id)
    }

    /** Shows conversation [convId] of [bot]; a reply still coming in elsewhere keeps going. */
    private fun open(bot: Bot, convId: String) {
        val cur = _ui.value
        if (bot.id != cur.bot.id) {
            dropPending()
            prefs.currentBotId = bot.id
            convs = ConversationStore(dir, bot.id)
        }
        store = convs.messages(convId)
        val list = if (bot.id != cur.bot.id) convs.load() else cur.conversations
        if (list.any { it.id == convId }) prefs.setLastConversation(bot.id, convId)
        val streaming = convId in jobs
        _ui.value = cur.copy(
            bot = bot, conversationId = convId, conversations = list,
            messages = live[convId] ?: store.load(), streaming = streaming, working = false, error = null,
            pick = if (bot.id != cur.bot.id) prefs.pick(bot.id) else cur.pick,
            pending = if (bot.id != cur.bot.id) emptyList() else cur.pending,
        )
    }

    fun renameConversation(id: String, title: String) {
        val t = title.trim()
        val c = _ui.value.conversations.firstOrNull { it.id == id } ?: return
        val list = convs.upsert(if (t.isEmpty()) c.copy(renamed = false, title = titleFrom(convs.messages(id).load())) else c.copy(title = t, renamed = true))
        _ui.update { it.copy(conversations = list) }
    }

    fun setPinned(id: String, pinned: Boolean) {
        val c = _ui.value.conversations.firstOrNull { it.id == id } ?: return
        val list = convs.upsert(c.copy(pinned = pinned))
        _ui.update { it.copy(conversations = list) }
    }

    /** Deletes a conversation for good (what was sent stays in the library). */
    fun deleteConversation(id: String) {
        jobs[id]?.cancel()
        scope.launch {
            jobs[id]?.join()
            val list = convs.delete(id)
            _ui.update { it.copy(conversations = list) }
            if (_ui.value.conversationId == id) open(_ui.value.bot, list.firstOrNull()?.id ?: UUID.randomUUID().toString())
        }
    }

    /** The messages of one of this bot's conversations (for sharing it). */
    fun messagesOf(id: String): List<ChatMessage> = live[id] ?: if (id == _ui.value.conversationId) _ui.value.messages else convs.messages(id).load()

    /** Whether a reply is coming in for any conversation of [botId]. */
    fun isBusy(botId: String): Boolean = jobs.isNotEmpty() && ConversationStore(dir, botId).load().any { it.id in jobs }

    /** The ids of this bot's conversations that mention [query] anywhere. */
    suspend fun search(query: String): Set<String> {
        val index = convs
        return withContext(Dispatchers.IO) { index.search(query) }
    }

    /** Opens conversation [convId] of bot [botId] (from a notification). */
    fun show(botId: String, convId: String) {
        if (_ui.value.bot.id != botId) select(botId)
        openConversation(convId)
    }

    fun select(id: String) {
        val cur = _ui.value
        if (id == cur.bot.id) return
        val bot = cur.bots.firstOrNull { it.id == id } ?: return
        val target = ConversationStore(dir, id)
        val last = prefs.lastConversation(id).takeIf { l -> target.load().any { it.id == l } }
        open(bot, last ?: UUID.randomUUID().toString())
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
        // every bot works in a sandbox now: a file goes into its attachments folder there
        return when (val r = manager.upload(prefs.profileOf(bot.id), a.name, files.file(a))) {
            is ManagerResult.Ok -> a.copy(remotePath = r.value)
            is ManagerResult.Fail -> throw AttachException(if (r.kind == ManagerResult.Kind.Invalid && r.detail.contains("MB")) AttachProblem.TooLarge else AttachProblem.UploadFailed, r.detail)
        }
    }

    fun removePending(id: String) {
        val p = _ui.value.pending.firstOrNull { it.id == id } ?: return
        p.attachment?.let(::discardUnsent)
        _ui.update { st -> st.copy(pending = st.pending.filterNot { it.id == id }) }
    }

    private fun dropPending() {
        _ui.value.pending.forEach { p -> p.attachment?.let(::discardUnsent) }
    }

    /** A file that was attached but never sent goes; one that is in the library (or in an earlier message) stays. */
    private fun discardUnsent(a: Attachment) {
        if (_ui.value.library.none { it.attachment.local == a.local }) files.delete(a)
    }

    private fun saveLibrary(list: List<LibraryItem>) {
        libraryStore.save(list)
        _ui.update { it.copy(library = list) }
    }

    /** Takes things out of the library for good: the files go too (older messages then show an empty thumbnail). */
    fun deleteFromLibrary(locals: Set<String>) {
        val gone = _ui.value.library.filter { it.attachment.local in locals }
        files.delete(gone.map { it.attachment })
        saveLibrary(_ui.value.library.filterNot { it.attachment.local in locals })
    }

    /** Attaches things from the library to the message being written, as if they had just been picked. */
    fun attachFromLibrary(locals: List<String>) {
        val bot = _ui.value.bot
        for (local in locals) {
            val item = _ui.value.library.firstOrNull { it.attachment.local == local } ?: continue
            val id = UUID.randomUUID().toString()
            val a = item.attachment.copy(id = id)
            _ui.update { it.copy(pending = it.pending + Pending(id, a.name)) }
            scope.launch {
                val result = try {
                    // the sandbox copy is reused when it is this bot's; another bot gets its own upload
                    val sameBot = item.botId == bot.id && a.remotePath.isNotEmpty()
                    val ready = withContext(Dispatchers.IO) { if (a.kind == AttachmentKind.File && sameBot) a else prepare(bot, a.copy(remotePath = "")) }
                    Pending(id, a.name, Pending.State.Ready, ready)
                } catch (e: AttachException) {
                    Pending(id, a.name, Pending.State.Failed, problem = e.problem)
                } catch (e: Exception) {
                    Pending(id, a.name, Pending.State.Failed, problem = AttachProblem.Unreadable)
                }
                _ui.update { st -> if (st.bot.id != bot.id) st else st.copy(pending = st.pending.map { if (it.id == id) result else it }) }
            }
        }
    }

    /** Files that nothing refers to (a failed upload, a removed chip) are cleaned away when the app starts. */
    private fun sweepOrphans() {
        val used = HashSet<String>()
        _ui.value.library.forEach { used += it.attachment.local }
        dir.listFiles { f -> (f.name.startsWith("chat") || f.name.startsWith("conv-")) && f.name.endsWith(".json") }?.forEach { f ->
            runCatching { kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<List<ChatMessage>>(f.readText()) }
                .getOrNull()?.forEach { m -> m.attachments.forEach { used += it.local } }
        }
        files.keepOnly(used)
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
            ConversationStore(dir, id).load().forEach { c -> jobs[c.id]?.cancel() }
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
            ConversationStore(dir, id).deleteAll()   // what was sent stays in the library
            refresh()
        }
        return r
    }

    private fun applyListing(l: Listing) {
        cache.save(l)
        val known = (l.bots.map { it.id } + l.trash.map { it.bot.id }).toSet()
        // bots that are gone on the server (removed after their time in the trash) leave no conversation behind
        dir.listFiles { f -> (f.name.startsWith("chat-") || f.name.startsWith("convs-")) && f.name.endsWith(".json") }?.forEach { f ->
            val id = f.name.removePrefix("chat-").removePrefix("convs-").removeSuffix(".json")
            if (isSandboxId(id) && id !in known) ConversationStore(dir, id).deleteAll()
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

    /** Applies [change] only while conversation [convId] is the one on screen. */
    private fun onCurrent(convId: String, change: (ChatUi) -> ChatUi) {
        _ui.update { if (it.conversationId == convId) change(it) else it }
    }

    private fun update(convId: String, id: String, content: CharSequence, reasoning: CharSequence, working: Boolean?) {
        live[convId]?.let { msgs -> live[convId] = msgs.map { if (it.id == id) it.copy(content = content.toString(), reasoning = reasoning.toString()) else it } }
        onCurrent(convId) { st ->
            st.copy(
                messages = st.messages.map { if (it.id == id) it.copy(content = content.toString(), reasoning = reasoning.toString()) else it },
                working = working ?: st.working,
            )
        }
    }
}
