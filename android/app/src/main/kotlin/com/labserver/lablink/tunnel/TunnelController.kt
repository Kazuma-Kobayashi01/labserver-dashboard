package com.labserver.lablink.tunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.labserver.lablink.data.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import labtunnel.Labtunnel
import org.json.JSONObject

sealed interface TunnelState {
    data object Off : TunnelState
    data class Connecting(val since: Long) : TunnelState
    data class On(val handshakeAgo: Long, val rx: Long, val tx: Long) : TunnelState
    data class Failed(val message: String, val missingConfig: Boolean = false) : TunnelState
}

/**
 * The in-app WireGuard tunnel (see ../tunnel in the repo). It runs in this process only: no VpnService, so nothing else on
 * the phone is routed through it. The app reaches home through a loopback HTTP proxy whose port is [proxyPort].
 */
class TunnelController(context: Context, private val prefs: Prefs) {
    private val appContext = context.applicationContext
    private val carrier = TcpCarrier(context, prefs)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var ignoreNetUntil = 0L

    private val _state = MutableStateFlow<TunnelState>(TunnelState.Off)
    val state: StateFlow<TunnelState> = _state

    /** 0 while the tunnel is down. */
    private val _port = MutableStateFlow(0)
    val proxyPort: StateFlow<Int> = _port

    /** This device's public key; the private key is generated on first use and never leaves the phone. */
    fun publicKey(): String {
        ensureKey()
        return Labtunnel.publicKeyOf(prefs.privateKey)
    }

    fun regenerateKey() {
        prefs.privateKey = Labtunnel.generatePrivateKey()
    }

    private fun ensureKey() {
        if (prefs.privateKey.isBlank()) prefs.privateKey = Labtunnel.generatePrivateKey()
    }

    fun connect() {
        if (job?.isActive == true) return
        job = scope.launch {
            val since = System.currentTimeMillis()
            _state.value = TunnelState.Connecting(since)
            try {
                if (!carrier.enabled || prefs.hubPublicKey.isBlank()) {
                    _state.value = TunnelState.Failed("", missingConfig = true)
                    return@launch
                }
                ensureKey()
                val port = withContext(Dispatchers.IO) {
                    carrier.start()
                    Labtunnel.start(configJson()).toInt()
                }
                _port.value = port
                watchNetwork()
                while (isActive) {
                    val s = JSONObject(Labtunnel.stats())
                    val ago = s.optLong("handshakeAgo", -1)
                    _state.value = if (ago >= 0) {
                        TunnelState.On(ago, s.optLong("rx"), s.optLong("tx"))
                    } else {
                        TunnelState.Connecting(since)
                    }
                    delay(1000)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                teardown()
                _state.value = TunnelState.Failed(e.message ?: e.toString())
            }
        }
    }

    fun disconnect() {
        job?.cancel()
        job = null
        teardown()
        _state.value = TunnelState.Off
    }

    fun log(): String = Labtunnel.log() + carrier.log().let { if (it.isBlank()) "" else "\n--- wstunnel ---\n$it" }

    private fun teardown() {
        unwatchNetwork()
        Labtunnel.stop()
        carrier.stop()
        _port.value = 0
    }

    private fun configJson(): String = JSONObject().apply {
        put("privateKey", prefs.privateKey)
        put("address", prefs.address)
        put("peerPublicKey", prefs.hubPublicKey)
        put("presharedKey", prefs.presharedKey)
        put("endpoint", carrier.endpoint)
        put("allowedIPs", prefs.allowedIPs)
        put("keepalive", 25)
        put("mtu", 1280)
        put("allowedHosts", prefs.domain)
        put("tunnelHost", prefs.tunnelHost)
    }.toString()

    /** After Wi-Fi <-> mobile data switches the old UDP socket is useless: reopen it. */
    private fun watchNetwork() {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        unwatchNetwork()
        ignoreNetUntil = System.currentTimeMillis() + 3000 // the callback fires once right away on registration
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (System.currentTimeMillis() > ignoreNetUntil) {
                    // the TCP connection of the old network is gone: reconnect it before the tunnel reopens its socket
                    runCatching { carrier.start() }
                    Labtunnel.rebind()
                }
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        netCallback = cb
    }

    private fun unwatchNetwork() {
        val cb = netCallback ?: return
        netCallback = null
        runCatching { appContext.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
    }
}
