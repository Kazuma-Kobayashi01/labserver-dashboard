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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeShared(intent)
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
