package com.labserver.lablink

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * Reads replies aloud with the phone's text-to-speech engine. One reply at a time: [speaking] is the id of the message being
 * read (null when quiet). The engine is started on first use and kept for the life of the app.
 */
class Speaker(context: Context) {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var queued: Pair<String, String>? = null

    private val _speaking = MutableStateFlow<String?>(null)
    val speaking: StateFlow<String?> = _speaking

    /** Starts reading [text] as message [id], or stops when that message is already being read. False when there is no engine. */
    fun toggle(id: String, text: String, onUnavailable: () -> Unit) {
        if (_speaking.value == id) { stop(); return }
        val engine = tts
        if (engine == null) {
            queued = id to text
            _speaking.value = id
            tts = TextToSpeech(appContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (!ready) {
                    _speaking.value = null
                    queued = null
                    tts = null
                    onUnavailable()
                    return@TextToSpeech
                }
                tts?.setOnUtteranceProgressListener(listener)
                queued?.let { (i, t) -> speakNow(i, t) }
                queued = null
            }
            return
        }
        if (!ready) { queued = id to text; _speaking.value = id; return }
        speakNow(id, text)
    }

    fun stop() {
        queued = null
        tts?.stop()
        _speaking.value = null
    }

    private fun speakNow(id: String, markdown: String) {
        val engine = tts ?: return
        val text = plain(markdown)
        if (text.isBlank()) { _speaking.value = null; return }
        // Chinese voices read the odd English word well enough; English voices cannot read Chinese at all
        engine.language = if (text.any { it.code in 0x4E00..0x9FFF }) Locale.SIMPLIFIED_CHINESE else Locale.US
        engine.stop()
        _speaking.value = id
        val parts = chunks(text, TextToSpeech.getMaxSpeechInputLength() - 100)
        parts.forEachIndexed { i, part ->
            val uid = if (i == parts.lastIndex) "$id$LAST" else "$id#$i"
            engine.speak(part, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, uid)
        }
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) {
            if (utteranceId != null && utteranceId.endsWith(LAST) && _speaking.value == utteranceId.removeSuffix(LAST)) _speaking.value = null
        }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) { _speaking.value = null }
        override fun onStop(utteranceId: String?, interrupted: Boolean) = Unit
    }

    companion object {
        private const val LAST = "#last"

        /** What is worth hearing in a Markdown reply: the words, without code, links' addresses or formatting marks. */
        fun plain(markdown: String): String = markdown
            .replace(Regex("MEDIA:\\S+"), " ")
            .replace(Regex("```[\\s\\S]*?(```|$)"), " ")
            .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("`([^`]*)`"), "$1")
            .replace(Regex("(?m)^[ \\t]{0,3}(#{1,6}|>|[-*+]|\\d+\\.)\\s+"), "")
            .replace(Regex("[*_~|]+"), "")
            .replace(Regex("[ \\t]+"), " ")
            .trim()

        /** Splits [text] into pieces the engine accepts, at paragraph or sentence ends where it can. */
        fun chunks(text: String, max: Int): List<String> {
            val out = ArrayList<String>()
            var rest = text
            while (rest.length > max) {
                val window = rest.substring(0, max)
                val cut = listOf(window.lastIndexOf("\n"), window.lastIndexOf("。"), window.lastIndexOf(". "), window.lastIndexOf(" "))
                    .maxOrNull()?.takeIf { it > max / 2 } ?: max
                out += rest.substring(0, cut + 1).trim()
                rest = rest.substring(cut + 1)
            }
            if (rest.isNotBlank()) out += rest.trim()
            return out
        }
    }
}
