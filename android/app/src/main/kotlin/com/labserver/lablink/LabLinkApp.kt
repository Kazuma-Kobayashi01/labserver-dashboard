package com.labserver.lablink

import android.app.Application
import android.content.Context
import com.labserver.lablink.data.AttachmentStore
import com.labserver.lablink.data.Bot
import com.labserver.lablink.data.BotColors
import com.labserver.lablink.data.DEFAULT_BOT_ID
import com.labserver.lablink.data.Prefs
import com.labserver.lablink.net.BotClient
import com.labserver.lablink.net.ChatController
import com.labserver.lablink.net.ManagerClient
import com.labserver.lablink.net.ManagerEndpoint
import com.labserver.lablink.net.ReplyService
import com.labserver.lablink.tunnel.TunnelController
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class LabLinkApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var tunnel: TunnelController
        private set
    lateinit var chat: ChatController
        private set
    lateinit var attachments: AttachmentStore
        private set

    /** The pictures in replies, fetched once and kept. */
    val media: com.labserver.lablink.net.MediaLoader by lazy {
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        com.labserver.lablink.net.MediaLoader(this, prefs, tunnel, { url -> debuggable && url.contains("://10.0.2.2") })
    }

    /** Reads replies aloud; the engine starts on first use. */
    val speaker: Speaker by lazy { Speaker(this) }

    /** Ticks when something outside the chat screen wants the chat shown (a share from another app). */
    private val chatRequests = kotlinx.coroutines.flow.MutableStateFlow(0)
    val chatRequested: kotlinx.coroutines.flow.StateFlow<Int> = chatRequests
    fun showChat() { chatRequests.value += 1 }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        attachments = AttachmentStore(this)
        tunnel = TunnelController(this, prefs)
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val manager = ManagerClient(
            ManagerEndpoint(
                baseUrl = { prefs.effectiveManagerUrl }, key = { prefs.managerKey }, proxyPort = { tunnel.proxyPort.value },
                direct = { debuggable && prefs.effectiveManagerUrl.contains("://10.0.2.2") },
            ),
        )
        chat = ChatController(filesDir, BotClient(prefs, tunnel, { url -> debuggable && url.contains("://10.0.2.2") }, attachments::read), manager, attachments, prefs) {
            Bot(
                id = DEFAULT_BOT_ID,
                name = getString(R.string.bot_default_name),
                role = getString(R.string.bot_default_role),
                color = BotColors.first(),
            )
        }
        // a reply that is still streaming must survive the app leaving the screen
        kotlinx.coroutines.MainScope().launch {
            chat.ui.map { it.busy.isNotEmpty() }.distinctUntilChanged().collect { ReplyService.set(this@LabLinkApp, it) }
        }
        // a bot waiting for an answer while the app is away: say so (it refuses on its own after a few minutes)
        kotlinx.coroutines.MainScope().launch {
            var seen = emptySet<String>()
            chat.ui.map { it.waiting }.distinctUntilChanged().collect { waiting ->
                val fresh = waiting.filterKeys { it !in seen }
                seen = waiting.keys
                if (waiting.isEmpty()) ReplyService.clearApproval(this@LabLinkApp)
                if (!tunnel.foreground) fresh.forEach { (conv, req) -> ReplyService.notifyApproval(this@LabLinkApp, req.botId, conv, req.botName, req.command) }
            }
        }
    }
}

val Context.labApp: LabLinkApp get() = applicationContext as LabLinkApp
