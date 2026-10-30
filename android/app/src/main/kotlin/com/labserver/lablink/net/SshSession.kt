package com.labserver.lablink.net

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.ProxyHTTP
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import com.labserver.lablink.data.Bot
import com.labserver.lablink.data.isSandbox
import com.labserver.lablink.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * One SSH login to the home server for the bot terminal, through the app's own tunnel (its loopback proxy, with CONNECT
 * to port 22). What the server prints goes to [onOutput]; [send] types into the shell. The server's key is remembered the
 * first time (trust on first use) and a changed key refuses the login before any password is sent.
 */
class SshSession(
    private val prefs: Prefs,
    private val proxyPort: suspend () -> Int,
    private val onOutput: (ByteArray, Int) -> Unit,
) {
    sealed interface State {
        data object Idle : State
        data object Connecting : State
        data object Open : State
        /** [hostKeyChanged]: the server is not the one seen before; [auth]: user name, password or key refused. */
        data class Closed(val message: String? = null, val auth: Boolean = false, val hostKeyChanged: Boolean = false) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outbox = Channel<ByteArray>(Channel.UNLIMITED)
    private var session: Session? = null
    private var shell: ChannelShell? = null
    private var size = 80 to 24

    /** Logs in with [password] (blank = this phone's key only) and opens a shell. */
    fun connect(user: String, password: String) {
        if (_state.value == State.Connecting || _state.value == State.Open) return
        _state.value = State.Connecting
        scope.launch {
            try {
                val port = proxyPort()
                if (port == 0) throw IllegalStateException(NOT_CONNECTED)
                val jsch = JSch()
                jsch.hostKeyRepository = Trust(prefs)
                ensureKey(prefs).let { jsch.addIdentity("lablink", it.toByteArray(), null, null) }
                val s = jsch.getSession(user, prefs.effectiveSshHost, prefs.sshPort)
                s.setProxy(ProxyHTTP("127.0.0.1", port))
                if (password.isNotEmpty()) s.setPassword(password)
                s.userInfo = Quiet(password)
                s.setConfig("StrictHostKeyChecking", "yes")
                s.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
                s.serverAliveInterval = 20_000
                s.connect(15_000)
                val ch = s.openChannel("shell") as ChannelShell
                ch.setPtyType("xterm-256color", size.first, size.second, 0, 0)
                ch.setEnv("LANG", "C.UTF-8")
                val input = ch.inputStream
                val output = ch.outputStream
                ch.connect(10_000)
                session = s
                shell = ch
                _state.value = State.Open
                launch { writer(output) }
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) onOutput(buf, n)
                }
                _state.value = State.Closed()
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                _state.value = State.Closed(
                    message = msg.ifBlank { e.javaClass.simpleName },
                    auth = msg.contains("Auth fail", ignoreCase = true) || msg.contains("Auth cancel", ignoreCase = true),
                    hostKeyChanged = e is com.jcraft.jsch.JSchChangedHostKeyException || msg.contains("HostKey has been changed", ignoreCase = true),
                )
            } finally {
                shell?.disconnect(); session?.disconnect()
                shell = null; session = null
            }
        }
    }

    private suspend fun writer(out: OutputStream) {
        for (bytes in outbox) {
            try { out.write(bytes); out.flush() } catch (_: Exception) { break }
        }
    }

    /** Types [bytes] into the shell (keys, pasted text); kept in order and written off the main thread. */
    fun send(bytes: ByteArray) {
        if (_state.value == State.Open) outbox.trySend(bytes)
    }

    fun send(text: String) = send(text.toByteArray())

    /** Tells the server the terminal's new size, in characters. */
    fun resize(cols: Int, rows: Int) {
        size = cols to rows
        val ch = shell ?: return
        scope.launch { try { ch.setPtySize(cols, rows, 0, 0) } catch (_: Exception) {} }
    }

    fun disconnect() {
        scope.launch { shell?.disconnect(); session?.disconnect() }
    }

    fun close() {
        disconnect()
        outbox.close()
        scope.launch { kotlinx.coroutines.delay(500); scope.cancel() }
    }

    /** Remembers each server's key the first time it is seen; a different key later is refused. */
    private class Trust(private val prefs: Prefs) : HostKeyRepository {
        private fun id(key: ByteArray): String =
            Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(key)).trimEnd('=')

        override fun check(host: String, key: ByteArray): Int {
            val seen = prefs.knownHost(host)
            val now = id(key)
            return when {
                seen.isEmpty() -> { prefs.setKnownHost(host, now); HostKeyRepository.OK }
                seen == now -> HostKeyRepository.OK
                else -> HostKeyRepository.CHANGED
            }
        }

        override fun add(hostkey: HostKey, ui: UserInfo?) {}
        override fun remove(host: String, type: String?) {}
        override fun remove(host: String, type: String?, key: ByteArray?) {}
        override fun getKnownHostsRepositoryID(): String = "lablink"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    /** Answers the server's password prompts (keyboard-interactive) with the password given, and asks nothing else. */
    private class Quiet(private val password: String) : UserInfo, com.jcraft.jsch.UIKeyboardInteractive {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String = password
        override fun promptPassword(message: String?) = password.isNotEmpty()
        override fun promptPassphrase(message: String?) = false
        override fun promptYesNo(message: String?) = false
        override fun showMessage(message: String?) {}
        override fun promptKeyboardInteractive(
            destination: String?, name: String?, instruction: String?, prompt: Array<out String>, echo: BooleanArray,
        ): Array<String>? = if (password.isEmpty()) null else Array(prompt.size) { password }
    }

    companion object {
        const val NOT_CONNECTED = "not connected"

        /** This phone's SSH key, made (ECDSA P-256) the first time; returns the private key in PEM. */
        @Synchronized
        fun ensureKey(prefs: Prefs): String {
            prefs.sshKey.takeIf { it.isNotBlank() && prefs.sshPublicKey.isNotBlank() }?.let { return it }
            val kp = KeyPair.genKeyPair(JSch(), KeyPair.ECDSA, 256)
            val priv = ByteArrayOutputStream().also { kp.writePrivateKey(it) }.toString()
            val pub = ByteArrayOutputStream().also { kp.writePublicKey(it, "lablink-phone") }.toString().trim()
            kp.dispose()
            prefs.sshKey = priv
            prefs.sshPublicKey = pub
            return priv
        }

        /**
         * What to type to step from the server into [bot]'s own machine: the Hermes container for the default bot, the
         * bot's Docker sandbox (found by the profile folder it mounts) for a sandbox bot.
         */
        fun enterCommand(bot: Bot): String {
            val name = bot.name.filter { it.isLetterOrDigit() || it == ' ' }.ifBlank { bot.id }
            return if (!bot.isSandbox) "sudo incus exec hermes -t -- bash -l"
            else "sudo incus exec hermes -t -- bash -c 'for c in \$(docker ps -q); do " +
                "if docker inspect -f \"{{range .Mounts}}{{.Source}} {{end}}\" \"\$c\" | grep -q \"/profiles/${bot.id}/\"; then " +
                "exec docker exec -it \"\$c\" sh -c \"command -v bash >/dev/null && exec bash -l || exec sh -l\"; fi; done; " +
                "echo \"$name: sandbox is not running\"'"
        }
    }
}
