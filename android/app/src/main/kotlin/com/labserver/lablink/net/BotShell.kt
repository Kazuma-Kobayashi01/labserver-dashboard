package com.labserver.lablink.net

import com.labserver.lablink.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * A terminal in a bot's own machine (its sandbox, or the Hermes container for the default bot), through the bot-manager's
 * `/bots/{id}/shell` WebSocket over the tunnel. No login of its own: the manager key the app already holds lets it in.
 * What the shell prints goes to [onOutput]; [send] types into it; [resize] tells it the terminal's size.
 */
class BotShell(
    private val prefs: Prefs,
    private val profile: String,
    private val proxyPort: suspend () -> Int,
    private val direct: (String) -> Boolean = { false },
    private val onOutput: (ByteArray) -> Unit,
) {
    sealed interface State {
        data object Connecting : State
        data object Open : State
        data class Closed(val reason: Reason, val detail: String? = null) : State
    }

    enum class Reason { Ended, Offline, NoSandbox, NoTerminal, Unauthorized, NotFound, Failed }

    private val _state = MutableStateFlow<State>(State.Connecting)
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var size = 80 to 24

    fun connect() {
        if (_state.value == State.Open) return
        _state.value = State.Connecting
        scope.launch {
            val url = shellUrl(prefs.effectiveManagerUrl, profile, size.first, size.second)
            val viaProxy = !direct(url)
            val port = if (viaProxy) proxyPort() else 0
            if (viaProxy && port == 0) {
                _state.value = State.Closed(Reason.Offline)
                return@launch
            }
            val client = OkHttpClient.Builder()
                .apply { if (viaProxy) proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))) }
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .pingInterval(25, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder().url(url).header("Authorization", "Bearer ${prefs.managerKey}").build()
            socket = client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    _state.value = State.Open
                    // the size may have changed while it was connecting
                    sendSize(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) = onOutput(bytes.toByteArray())

                override fun onMessage(webSocket: WebSocket, text: String) = onOutput(text.toByteArray())

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    _state.value = State.Closed(Reason.Ended)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    _state.value = State.Closed(Reason.Ended)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val body = try { response?.body?.string().orEmpty() } catch (_: Exception) { "" }
                    _state.value = State.Closed(
                        when (response?.code) {
                            null -> if (_state.value == State.Open) Reason.Ended else Reason.Failed
                            401 -> Reason.Unauthorized
                            403 -> Reason.NoTerminal
                            404 -> Reason.NotFound
                            409 -> Reason.NoSandbox
                            else -> Reason.Failed
                        },
                        detail = response?.let { "HTTP ${it.code} ${body.take(200)}".trim() } ?: t.message,
                    )
                }
            })
        }
    }

    /** Types [bytes] into the shell (keys, pasted text). */
    fun send(bytes: ByteArray) {
        socket?.takeIf { _state.value == State.Open }?.send(bytes.toByteString())
    }

    fun send(text: String) = send(text.toByteArray())

    /** The terminal's size in characters; sent now when open, else with the next connection. */
    fun resize(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        size = cols to rows
        socket?.takeIf { _state.value == State.Open }?.let(::sendSize)
    }

    private fun sendSize(ws: WebSocket) {
        ws.send("""{"resize":[${size.first},${size.second}]}""")
    }

    fun close() {
        socket?.close(1000, null)
        socket = null
        scope.cancel()
    }

    companion object {
        /** `http://bot.<domain>/m/v1` + `/bots/<profile>/shell?cols=..&rows=..` (OkHttp speaks ws over http URLs). */
        fun shellUrl(managerUrl: String, profile: String, cols: Int, rows: Int): String =
            "${managerUrl.trimEnd('/')}/bots/$profile/shell?cols=$cols&rows=$rows"
    }
}
