package com.labserver.lablink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Debug builds only: every orb mood side by side, plus one orb running through a whole reply, for designing the motion.
 * adb shell am start -n com.labserver.lablink/.MainActivity --ez orb_playground true
 */
@Composable
fun OrbPlayground() {
    // a fake reply: think, look something up, answer in bursts, rest
    var mood by remember { mutableStateOf(OrbMood.Idle) }
    var talk by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            mood = OrbMood.Thinking; delay(3000)
            mood = OrbMood.Working; delay(2200)
            mood = OrbMood.Answering
            repeat(30) { talk++; delay(60L + Random.nextLong(160)) }
            mood = OrbMood.Idle; delay(1800)
        }
    }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { tick++; delay(90L + Random.nextLong(120)) } }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).systemBarsPadding().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Lab bot · ${mood.name}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        BotAvatar(190.dp, mood = mood, talk = talk, alive = true)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Cell("Idle", OrbMood.Idle, 0, Color(0xFF4B3FD8))
            Cell("Thinking", OrbMood.Thinking, 0, Color(0xFF8E5CF6))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Cell("Working", OrbMood.Working, 0, Color(0xFF3B9BE8))
            Cell("Answering", OrbMood.Answering, tick, Color(0xFF2FBF8F))
        }
    }
}

@Composable
private fun Cell(label: String, mood: OrbMood, talk: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BotAvatar(120.dp, color = color, mood = mood, talk = talk, alive = true)
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
