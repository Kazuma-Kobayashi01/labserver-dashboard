package com.labserver.lablink

import android.content.pm.ApplicationInfo
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.labserver.lablink.ui.AppRoot
import com.labserver.lablink.ui.LabLinkTheme
import com.labserver.lablink.ui.OrbPlayground

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // debug builds can open the orb motion playground: am start ... --ez orb_playground true
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val playground = debuggable && intent.getBooleanExtra("orb_playground", false)
        setContent { LabLinkTheme { if (playground) OrbPlayground() else AppRoot() } }
        takeShared(intent)
        takeApproval(intent)
        // approvals reach the user as notifications when the app is away; Android 13+ asks for that once
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val sp = getSharedPreferences("lablink", MODE_PRIVATE)
            if (!sp.getBoolean("askedNotifications", false)) {
                sp.edit().putBoolean("askedNotifications", true).apply()
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
    }

    /** A tap on an approval notification opens the conversation that waits. */
    private fun takeApproval(intent: Intent?) {
        val bot = intent?.getStringExtra(com.labserver.lablink.net.ReplyService.EXTRA_BOT) ?: return
        val conv = intent.getStringExtra(com.labserver.lablink.net.ReplyService.EXTRA_CONV) ?: return
        intent.removeExtra(com.labserver.lablink.net.ReplyService.EXTRA_BOT)
        labApp.chat.show(bot, conv)
        labApp.showChat()
        com.labserver.lablink.net.ReplyService.clearApproval(this)
    }

    override fun onStart() {
        super.onStart()
        labApp.tunnel.foreground = true
        labApp.tunnel.autoConnect()
    }

    override fun onStop() {
        labApp.tunnel.foreground = false
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeShared(intent)
        takeApproval(intent)
    }

    /** "Share to LabLink" from another app: the pictures and files become attachments of the current chat. */
    private fun takeShared(intent: Intent?) {
        if (intent == null || (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE)) return
        @Suppress("DEPRECATION")
        val uris: List<Uri> = if (intent.action == Intent.ACTION_SEND) {
            listOfNotNull(if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM))
        } else {
            (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)).orEmpty()
        }
        intent.action = null   // do not take the same share again after a rotation
        if (uris.isNotEmpty()) {
            labApp.chat.attach(uris)
            labApp.showChat()
        }
    }
}
