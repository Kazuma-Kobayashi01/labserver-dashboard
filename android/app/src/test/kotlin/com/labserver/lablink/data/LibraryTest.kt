package com.labserver.lablink.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LibraryTest {
    private fun att(local: String, kind: AttachmentKind, id: String = local) =
        Attachment(id = id, name = "$local.x", mime = "x/y", size = 10, kind = kind, local = local)

    @Test
    fun newThingsGoToTheFrontInOrderAndKnownOnesMoveUp() {
        val a = att("a", AttachmentKind.Image); val b = att("b", AttachmentKind.File); val c = att("c", AttachmentKind.Text)
        val first = mergeSent(emptyList(), listOf(a, b), "bot1", 100)
        assertEquals(listOf("a", "b"), first.map { it.attachment.local })
        val second = mergeSent(first, listOf(c), "bot2", 200)
        assertEquals(listOf("c", "a", "b"), second.map { it.attachment.local })
        // sending b again (a new attachment id, the same stored file) moves it up and records the bot it went to
        val third = mergeSent(second, listOf(b.copy(id = "new")), "bot3", 300)
        assertEquals(listOf("b", "c", "a"), third.map { it.attachment.local })
        assertEquals(Triple("bot3", 300L, "new"), third[0].let { Triple(it.botId, it.sentAt, it.attachment.id) })
        assertEquals(3, third.size)
    }

    @Test
    fun theSameFileTwiceInOneMessageIsStoredOnce() {
        val a = att("a", AttachmentKind.Image)
        assertEquals(1, mergeSent(emptyList(), listOf(a, a.copy(id = "other")), "b", 1).size)
    }

    @Test
    fun nothingSentChangesNothing() {
        val lib = mergeSent(emptyList(), listOf(att("a", AttachmentKind.Image)), "b", 1)
        assertEquals(lib, mergeSent(lib, emptyList(), "b", 2))
    }

    @Test
    fun filtersSeparatePicturesFromEverythingElse() {
        val lib = mergeSent(emptyList(), listOf(att("i", AttachmentKind.Image), att("t", AttachmentKind.Text), att("f", AttachmentKind.File)), "b", 1)
        assertEquals(listOf("i"), lib.filtered(LibraryFilter.Images).map { it.attachment.local })
        assertEquals(listOf("t", "f"), lib.filtered(LibraryFilter.Files).map { it.attachment.local })
        assertEquals(3, lib.filtered(LibraryFilter.All).size)
    }

    @Test
    fun theLibrarySurvivesARestartAndABrokenFileIsEmpty() {
        val dir = java.nio.file.Files.createTempDirectory("lib").toFile()
        val store = LibraryStore(dir)
        assertTrue(store.load().isEmpty())
        val items = mergeSent(emptyList(), listOf(att("a", AttachmentKind.Image), att("b", AttachmentKind.File)), "bot", 5)
        store.save(items)
        assertEquals(items, LibraryStore(dir).load())
        File(dir, "library.json").writeText("not json")
        assertTrue(LibraryStore(dir).load().isEmpty())
        dir.deleteRecursively()
    }
}
