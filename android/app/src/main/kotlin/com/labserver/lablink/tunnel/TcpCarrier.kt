package com.labserver.lablink.tunnel

import android.content.Context
import com.labserver.lablink.data.Prefs
import java.io.File

/**
 * The way the tunnel reaches the relay: the relay's UDP replies do not arrive on the networks this is used on, so the
 * WireGuard packets travel over TCP. A local wstunnel client (bundled as libwstunnel.so) listens on [PORT] and forwards
 * them over a WebSocket; the tunnel's endpoint is that local port. It lives and dies with the tunnel, inside this app only.
 */
class TcpCarrier(context: Context, private val prefs: Prefs) {
    private val binary = File(context.applicationInfo.nativeLibraryDir, "libwstunnel.so")
    private var process: Process? = null
    private val lines = ArrayDeque<String>()

    val enabled: Boolean
        get() = binary.canExecute() && prefs.tcpUrl.startsWith("wss://") && prefs.tcpPrefix.isNotBlank()

    /** The endpoint WireGuard should use while the carrier is on. */
    val endpoint: String get() = "127.0.0.1:$PORT"

    @Synchronized
    fun start() {
        stop()
        val p = ProcessBuilder(
            binary.path, "client",
            "-L", "udp://127.0.0.1:$PORT:127.0.0.1:51820?timeout_sec=0",
            "--http-upgrade-path-prefix", prefs.tcpPrefix, prefs.tcpUrl,
        ).redirectErrorStream(true).start()
        process = p
        Thread {
            p.inputStream.bufferedReader().forEachLine { l ->
                synchronized(lines) { lines.addLast(l.take(300)); while (lines.size > 40) lines.removeFirst() }
            }
        }.apply { isDaemon = true }.start()
        Thread.sleep(300)
        check(p.isAlive) { "wstunnel exited: " + log().lines().lastOrNull { it.isNotBlank() }.orEmpty() }
    }

    @Synchronized
    fun stop() {
        process?.destroy()
        process = null
    }

    fun log(): String = synchronized(lines) { lines.joinToString("\n") }

    companion object {
        const val PORT = 51821
    }
}
