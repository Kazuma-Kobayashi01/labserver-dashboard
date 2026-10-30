package com.labserver.lablink.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDetailTest {
    @Test
    fun unknownOrMissingNameIsStandard() {
        assertEquals(ChatDetail.Standard, ChatDetail.of(null))
        assertEquals(ChatDetail.Standard, ChatDetail.of("Verbose"))
        assertEquals(ChatDetail.Minimal, ChatDetail.of("Minimal"))
    }

    @Test
    fun levelsShowLessFromFullToMinimal() {
        assertTrue(ChatDetail.Full.thoughtsOpen && ChatDetail.Full.toolStaysOpen)
        assertFalse(ChatDetail.Standard.thoughtsOpen)
        assertTrue(ChatDetail.Standard.toolOpenWhileRunning && !ChatDetail.Standard.toolStaysOpen)
        assertFalse(ChatDetail.Compact.toolOpenWhileRunning)
        assertTrue(ChatDetail.Compact.showThoughts)
        assertFalse(ChatDetail.Minimal.showThoughts)
        assertTrue(ChatDetail.Minimal.groupTools)
        val folds = ChatDetail.entries.map { it.codeFoldLines }
        assertEquals(folds.sortedDescending(), folds)
    }
}
