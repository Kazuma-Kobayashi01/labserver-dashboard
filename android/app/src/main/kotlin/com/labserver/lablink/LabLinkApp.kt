package com.labserver.lablink

import android.app.Application
import android.content.Context
import com.labserver.lablink.data.Bot
import com.labserver.lablink.data.BotColors
import com.labserver.lablink.data.DEFAULT_BOT_ID
import com.labserver.lablink.data.Prefs
import com.labserver.lablink.net.BotClient
import com.labserver.lablink.net.ChatController
import com.labserver.lablink.net.ManagerClient
import com.labserver.lablink.net.ManagerEndpoint
import com.labserver.lablink.tunnel.TunnelController

class LabLinkApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var tunnel: TunnelController
        private set
    lateinit var chat: ChatController
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        tunnel = TunnelController(this, prefs)
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val manager = ManagerClient(
            ManagerEndpoint(
                baseUrl = { prefs.effectiveManagerUrl }, key = { prefs.managerKey }, proxyPort = { tunnel.proxyPort.value },
                direct = { debuggable && prefs.effectiveManagerUrl.contains("://10.0.2.2") },
            ),
        )
        chat = ChatController(filesDir, BotClient(prefs, tunnel), manager, prefs) {
            Bot(
                id = DEFAULT_BOT_ID,
                name = getString(R.string.bot_default_name),
                role = getString(R.string.bot_default_role),
                color = BotColors.first(),
            )
        }
    }
}

val Context.labApp: LabLinkApp get() = applicationContext as LabLinkApp
