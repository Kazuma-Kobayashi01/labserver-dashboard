package com.labserver.lablink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerTest {
    @Test
    fun onlyTheWordsAreRead() {
        val md = "## Steps\n\n1. Open **settings**\n- see [the docs](https://x.y/z)\n\n```kotlin\nval a = 1\n```\nDone `now`."
        assertEquals("Steps\n\nOpen settings\nsee the docs\n\n \nDone now.", Speaker.plain(md))
    }

    @Test
    fun longTextIsSplitAtSentenceEnds() {
        val text = "第一句话。".repeat(50)
        val parts = Speaker.chunks(text, 60)
        assertTrue(parts.all { it.length <= 60 })
        assertEquals(text, parts.joinToString(""))
        assertTrue(parts.first().endsWith("。"))
    }
}
