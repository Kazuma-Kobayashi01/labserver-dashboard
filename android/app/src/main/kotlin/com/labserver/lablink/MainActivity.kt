package com.labserver.lablink

import android.content.pm.ApplicationInfo
import android.os.Bundle
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
    }
}
