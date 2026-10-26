package com.labserver.lablink

import android.app.Application
import android.content.Context
import com.labserver.lablink.data.ChatStore
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
        chat = ChatController(ChatStore(filesDir), BotClient(prefs, tunnel))
    }
}

val Context.labApp: LabLinkApp get() = applicationContext as LabLinkApp
