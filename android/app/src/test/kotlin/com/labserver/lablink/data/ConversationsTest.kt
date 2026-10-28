package com.labserver.lablink.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ConversationsTest {
    private fun dir() = Files.createTempDirectory("convs").toFile()

    @Test
    fun theTitleIsTheFirstLineOfWhatTheUserSaidCutToFit() {
        assertEquals("Plan a trip to Kyoto", titleFrom(listOf(ChatMessage("user", "  # Plan a trip   to Kyoto\nwith kids"), ChatMessage("assistant", "Sure"))))
        val long = titleFrom(listOf(ChatMessage("user", "x".repeat(80))))
        assertEquals(40, long.length)
        assertTrue(long.endsWith("…"))
        assertEquals("", titleFrom(listOf(ChatMessage("assistant", "hello"))))
    }

    @Test
    fun theOldSingleChatBecomesTheFirstConversation() {
        val d = dir()
        val old = listOf(ChatMessage("user", "hi there", time = 1000), ChatMessage("assistant", "hello", time = 2000))
        ChatStore(ChatStore.legacyFile(d, DEFAULT_BOT_ID)).save(old)
        val store = ConversationStore(d, DEFAULT_BOT_ID)
        val list = store.load()
        assertEquals(1, list.size)
        assertEquals("hi there", list[0].title)
        assertEquals(2000L, list[0].updated)
        assertEquals(old, store.messages(list[0].id).load())
        assertFalse(ChatStore.legacyFile(d, DEFAULT_BOT_ID).exists())
        // read again: the index is used, nothing is migrated twice
        assertEquals(list, store.load())
    }

    @Test
    fun pinnedComeFirstThenTheMostRecent() {
        val d = dir()
        val s = ConversationStore(d, "sb-1")
        s.upsert(Conversation(id = "a", updated = 100))
        s.upsert(Conversation(id = "b", updated = 300))
        s.upsert(Conversation(id = "c", updated = 200, pinned = true))
        assertEquals(listOf("c", "b", "a"), s.load().map { it.id })
        assertEquals(listOf("c", "a"), s.delete("b").map { it.id })
    }

    @Test
    fun searchLooksInTitlesAndMessages() {
        val d = dir()
        val s = ConversationStore(d, DEFAULT_BOT_ID)
        s.upsert(Conversation(id = "a", title = "Kyoto trip"))
        s.upsert(Conversation(id = "b", title = "Taxes"))
        s.messages("b").save(listOf(ChatMessage("assistant", "The deadline is in April")))
        assertEquals(setOf("a"), s.search("kyoto"))
        assertEquals(setOf("b"), s.search("APRIL"))
        assertEquals(emptySet<String>(), s.search("  "))
    }

    @Test
    fun deletingABotTakesAllItsChats() {
        val d = dir()
        val s = ConversationStore(d, "sb-2")
        s.upsert(Conversation(id = "x"))
        s.messages("x").save(listOf(ChatMessage("user", "hi")))
        s.deleteAll()
        assertTrue(d.listFiles()!!.isEmpty())
    }

    @Test
    fun chatsAreGroupedByWhenTheyWereLastUsed() {
        val now = java.util.Calendar.getInstance().apply { set(2026, 9, 28, 15, 0, 0) }.timeInMillis
        val h = 60 * 60 * 1000L
        assertEquals(Period.Today, periodOf(Conversation(updated = now - 2 * h), now))
        assertEquals(Period.Yesterday, periodOf(Conversation(updated = now - 20 * h), now))
        assertEquals(Period.Week, periodOf(Conversation(updated = now - 4 * 24 * h), now))
        assertEquals(Period.Month, periodOf(Conversation(updated = now - 20 * 24 * h), now))
        assertEquals(Period.Older, periodOf(Conversation(updated = now - 90 * 24 * h), now))
        assertEquals(Period.Pinned, periodOf(Conversation(updated = 0, pinned = true), now))
    }

    @Test
    fun exportNamesWhoSaidWhat() {
        val md = exportMarkdown("Trip", "Lab bot", listOf(ChatMessage("user", "hi"), ChatMessage("assistant", "**hello**")), "You")
        assertEquals("# Trip\n\n**You**\n\nhi\n\n**Lab bot**\n\n**hello**\n", md)
    }
}
