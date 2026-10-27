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
 * Debug builds only: all ten orb moves, each in its own cell (the six that need a touch play on their own every few seconds),
 * plus one orb running through a whole reply. adb shell am start -n com.labserver.lablink/.MainActivity --ez orb_playground true
 */
@Composable
fun OrbPlayground() {
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

    val smile = rememberOrbController(); val roll = rememberOrbController(); val jelly = rememberOrbController(); val hop = rememberOrbController()
    LaunchedEffect(Unit) {
        while (true) {
            smile.pressDown(); delay(1400); smile.pressUp(); delay(900)
            roll.play(OrbFx.Roll); delay(1300)
            jelly.play(OrbFx.Jelly); delay(1500)
            hop.play(OrbFx.Hop); delay(1200)
        }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).systemBarsPadding().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Lab bot · ${mood.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        BotAvatar(120.dp, mood = mood, talk = talk, alive = true)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Cell("1 Idle", OrbMood.Idle, 0, Color(0xFF4B3FD8))
            Cell("2 Thinking", OrbMood.Thinking, 0, Color(0xFF8E5CF6))
            Cell("3 Searching", OrbMood.Working, 0, Color(0xFF3B9BE8))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Cell("4 Answering", OrbMood.Answering, tick, Color(0xFF2FBF8F))
            Cell("5 Squint-smile", OrbMood.Idle, 0, Color(0xFFE8589B), smile)
            Cell("6 Roll", OrbMood.Idle, 0, Color(0xFFF2724D), roll)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Cell("7 Jelly", OrbMood.Idle, 0, Color(0xFFF2B63D), jelly)
            Cell("8 Hop", OrbMood.Idle, 0, Color(0xFF2B2B3A), hop)
            Cell("9 Sleepy", OrbMood.Sleepy, 0, Color(0xFF4B3FD8))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Cell("10 Shake no", if (tick % 40 < 20) OrbMood.Error else OrbMood.Idle, 0, Color(0xFF8E5CF6))
        }
    }
}

@Composable
private fun Cell(label: String, mood: OrbMood, talk: Int, color: Color, controller: OrbController? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BotAvatar(92.dp, color = color, mood = mood, talk = talk, alive = true, controller = controller)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
