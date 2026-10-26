package com.labserver.lablink

import android.app.Application
import android.content.Context
import com.labserver.lablink.data.Bot
import com.labserver.lablink.data.BotColors
import com.labserver.lablink.data.DEFAULT_BOT_ID
import com.labserver.lablink.data.Prefs
import com.labserver.lablink.net.BotClient
import com.labserver.lablink.net.ChatController
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
        chat = ChatController(filesDir, BotClient(prefs, tunnel), prefs) {
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
