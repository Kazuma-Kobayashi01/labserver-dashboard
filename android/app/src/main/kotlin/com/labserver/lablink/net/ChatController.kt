package com.labserver.lablink.net

import com.labserver.lablink.data.ChatMessage
import com.labserver.lablink.data.ChatStore
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

data class ChatUi(
    val messages: List<ChatMessage> = emptyList(),
    val streaming: Boolean = false,
    /** The agent is using a tool (searching the web...) before it answers. */
    val working: Boolean = false,
    val error: BotEvent.Error? = null,
)

/** Owns the conversation so it survives screen changes; one chat at a time, saved after every reply. */
class ChatController(private val store: ChatStore, private val client: BotClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    private val _ui = MutableStateFlow(ChatUi(messages = store.load()))
    val ui: StateFlow<ChatUi> = _ui

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || _ui.value.streaming) return
        val history = _ui.value.messages + ChatMessage("user", t)
        val reply = ChatMessage("assistant", "")
        _ui.value = ChatUi(history + reply, streaming = true)

        job = scope.launch {
            val content = StringBuilder()
            val reasoning = StringBuilder()
            try {
                client.stream(history).collect { ev ->
                    when (ev) {
                        is BotEvent.Content -> {
                            content.append(ev.text)
                            update(reply.id, content, reasoning, working = false)
                        }
                        is BotEvent.Reasoning -> {
                            reasoning.append(ev.text)
                            update(reply.id, content, reasoning, working = _ui.value.working)
                        }
                        BotEvent.Tool -> _ui.update { it.copy(working = true) }
                        BotEvent.Done -> Unit
                        is BotEvent.Error -> _ui.update { it.copy(error = ev) }
                    }
                }
            } finally {
                withContext(NonCancellable) { finish(reply.id) }
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    fun clear() {
        job?.cancel()
        _ui.value = ChatUi()
        store.save(emptyList())
    }

    fun dismissError() = _ui.update { it.copy(error = null) }

    private fun update(id: String, content: CharSequence, reasoning: CharSequence, working: Boolean) {
        _ui.update { st ->
            st.copy(
                messages = st.messages.map { if (it.id == id) it.copy(content = content.toString(), reasoning = reasoning.toString()) else it },
                working = working,
            )
        }
    }

    /** Drops an empty reply (nothing came back) and saves the conversation. */
    private fun finish(id: String) {
        _ui.update { st ->
            val kept = st.messages.filterNot { it.id == id && it.content.isBlank() && it.reasoning.isBlank() }
            st.copy(messages = kept, streaming = false, working = false)
        }
        store.save(_ui.value.messages)
    }
}
