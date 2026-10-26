package com.labserver.lablink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.labserver.lablink.ui.AppRoot
import com.labserver.lablink.ui.LabLinkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { LabLinkTheme { AppRoot() } }
    }
}
