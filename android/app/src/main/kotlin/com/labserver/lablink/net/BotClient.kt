package com.labserver.lablink.net

import com.labserver.lablink.data.Bot
import com.labserver.lablink.data.ChatMessage
import com.labserver.lablink.data.Prefs
import com.labserver.lablink.data.isSandbox
import com.labserver.lablink.tunnel.TunnelController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

sealed interface BotEvent {
    data class Content(val text: String) : BotEvent
    data class Reasoning(val text: String) : BotEvent

    /** The agent started using a tool (web search...). */
    data object Tool : BotEvent
    data object Done : BotEvent
    data class Error(val kind: Kind, val detail: String = "") : BotEvent {
        enum class Kind { NotConnected, NoKey, BadUrl, Auth, Unreachable, Gone, Other }
    }
}

/**
 * Talks to the Lab bot (Hermes' OpenAI-compatible API, `labbot` profile) through the in-app tunnel's loopback proxy.
 * The reply is streamed as server-sent events: `reasoning_content` deltas, `content` deltas and tool-progress frames.
 */
class BotClient(private val prefs: Prefs, private val tunnel: TunnelController) {

    /**
     * A sandbox bot is reached through the bot-manager (its prompt lives on the server, the manager key authorises);
     * the default bot goes straight to the `labbot` profile and sends its prompt as the first message.
     */
    fun stream(history: List<ChatMessage>, bot: Bot): Flow<BotEvent> = callbackFlow {
        val sandbox = bot.isSandbox
        val url = if (sandbox) "${prefs.effectiveManagerUrl}/bots/${bot.id}/chat/completions" else prefs.effectiveBotUrl + "/chat/completions"
        val key = if (sandbox) prefs.managerKey else prefs.botKey
        val systemPrompt = if (sandbox) "" else bot.systemPrompt
        val port = tunnel.proxyPort.value
        var call: Call? = null
        when {
            port == 0 -> {
                trySend(BotEvent.Error(BotEvent.Error.Kind.NotConnected))
                close()
            }
            key.isBlank() -> {
                trySend(BotEvent.Error(BotEvent.Error.Kind.NoKey))
                close()
            }
            else -> {
                val client = OkHttpClient.Builder()
                    .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS) // the server sends a keepalive every 10 s
                    .build()
                val req = try {
                    request(history, url, key, systemPrompt)
                } catch (e: IllegalArgumentException) {
                    null // an address OkHttp cannot parse
                }
                if (req == null) {
                    trySend(BotEvent.Error(BotEvent.Error.Kind.BadUrl, url))
                    close()
                    return@callbackFlow awaitClose {}
                }
                val c = client.newCall(req)
                call = c
                launch(Dispatchers.IO) {
                    try {
                        c.execute().use { resp ->
                            if (!resp.isSuccessful) {
                                val kind = when (resp.code) {
                                    401, 403 -> BotEvent.Error.Kind.Auth
                                    404 -> if (sandbox) BotEvent.Error.Kind.Gone else BotEvent.Error.Kind.Unreachable
                                    502, 503, 504 -> BotEvent.Error.Kind.Unreachable
                                    else -> BotEvent.Error.Kind.Other
                                }
                                trySend(BotEvent.Error(kind, "HTTP ${resp.code}"))
                            } else {
                                readEvents(resp.body.source()) { trySend(it) }
                                trySend(BotEvent.Done)
                            }
                        }
                    } catch (e: IOException) {
                        if (!c.isCanceled()) trySend(BotEvent.Error(BotEvent.Error.Kind.Unreachable, e.message ?: ""))
                    } finally {
                        close()
                    }
                }
            }
        }
        awaitClose { call?.cancel() }
    }

    private fun request(history: List<ChatMessage>, url: String, key: String, systemPrompt: String): Request {
        val messages = JSONArray()
        if (systemPrompt.isNotBlank()) messages.put(JSONObject().put("role", "system").put("content", systemPrompt.trim()))
        history.filter { it.content.isNotBlank() }.takeLast(MAX_HISTORY).forEach {
            messages.put(JSONObject().put("role", it.role).put("content", it.content))
        }
        val body = JSONObject().put("model", "lab-bot").put("stream", true).put("messages", messages)
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun readEvents(source: okio.BufferedSource, emit: (BotEvent) -> Unit) {
        var event = ""
        val data = StringBuilder()
        while (true) {
            val line = source.readUtf8Line() ?: break
            when {
                line.isEmpty() -> {
                    if (data.isNotEmpty()) {
                        if (dispatch(event, data.toString(), emit)) return
                        data.setLength(0)
                    }
                    event = ""
                }
                line.startsWith("event:") -> event = line.substring(6).trim()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substring(5).trimStart())
                }
                // ": keepalive" comment lines are ignored
            }
        }
    }

    /** Returns true when the stream is finished. */
    private fun dispatch(event: String, data: String, emit: (BotEvent) -> Unit): Boolean {
        if (data == "[DONE]") return true
        if (event == "hermes.tool.progress") {
            emit(BotEvent.Tool)
            return false
        }
        val delta = try {
            JSONObject(data).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
        } catch (_: Exception) {
            null
        } ?: return false
        delta.optString("reasoning_content").takeIf { it.isNotEmpty() }?.let { emit(BotEvent.Reasoning(it)) }
        delta.optString("content").takeIf { it.isNotEmpty() }?.let { emit(BotEvent.Content(it)) }
        return false
    }

    private companion object {
        const val MAX_HISTORY = 24
    }
}
