package com.labserver.lablink.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentsTest {
    private fun att(name: String, kind: AttachmentKind, size: Long = 10, remote: String = "", mime: String = "image/jpeg") =
        Attachment(id = name, name = name, mime = mime, size = size, kind = kind, local = name, remotePath = remote)

    @Test
    fun imagesAreRecognisedByMimeOrExtension() {
        assertEquals(AttachmentKind.Image, classify("a.jpg", "image/jpeg", 100, false))
        assertEquals(AttachmentKind.Image, classify("shot.HEIC", "image/heic", 100, false))
        assertEquals(AttachmentKind.Image, classify("x.png", "image/png", 100, false))
        assertEquals(AttachmentKind.File, classify("logo.svg", "image/svg+xml", 100, false))   // not a picture the model can read
    }

    @Test
    fun smallReadableTextIsInlinedEverythingElseIsAFile() {
        assertEquals(AttachmentKind.Text, classify("main.py", "text/x-python", 2000, true))
        assertEquals(AttachmentKind.Text, classify("notes.md", "application/octet-stream", 2000, true))
        assertEquals(AttachmentKind.Text, classify("Dockerfile", "application/octet-stream", 300, true))
        assertEquals(AttachmentKind.Text, classify("data.json", "application/json", 2000, true))
        assertEquals(AttachmentKind.File, classify("big.log", "text/plain", Limits.MAX_TEXT_BYTES + 1L, true))
        assertEquals(AttachmentKind.File, classify("report.pdf", "application/pdf", 2000, false))
        assertEquals(AttachmentKind.File, classify("renamed.txt", "text/plain", 2000, false))     // binary in disguise
        assertEquals(AttachmentKind.File, classify("a.zip", "application/zip", 100, false))
        assertEquals(AttachmentKind.File, classify("noext", "application/octet-stream", 100, true))
    }

    @Test
    fun textDetectionHandlesUtf8NulAndCutOffCharacters() {
        assertTrue(looksLikeText("héllo 你好\n".toByteArray()))
        assertFalse(looksLikeText(byteArrayOf(0x50, 0x4B, 0x00, 0x04)))
        assertFalse(looksLikeText(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41)))
        val cut = "你好".toByteArray().copyOf(5)              // the second character is cut in two
        assertFalse(looksLikeText(cut))
        assertTrue(looksLikeText(cut, truncated = true))
    }

    @Test
    fun sizesAreShownForHumans() {
        assertEquals("999 B", humanSize(999))
        assertEquals("2 KB", humanSize(2048))
        assertEquals("1.5 MB", humanSize(1572864))
    }

    @Test
    fun plainMessagesStayPlainStrings() {
        assertEquals("hello", buildContent("hello", emptyList(), true) { null })
    }

    @Test
    fun textFilesAreInlinedInOrder() {
        val files = listOf(att("a.py", AttachmentKind.Text, mime = "text/x-python"), att("b.txt", AttachmentKind.Text, mime = "text/plain"))
        val out = buildContent("look", files, true) { if (it.name == "a.py") "print(1)\n".toByteArray() else "second".toByteArray() } as String
        assertEquals("look\n\n<file name=\"a.py\">\nprint(1)\n</file>\n\n<file name=\"b.txt\">\nsecond\n</file>", out)
    }

    @Test
    fun uploadedFilesAreNamedWithTheirSandboxPath() {
        val out = buildContent("", listOf(att("r.pdf", AttachmentKind.File, 2048, "/root/.hermes/attachments/r.pdf", "application/pdf")), true) { null } as String
        assertEquals("[attached file: r.pdf (2 KB), in your sandbox at /root/.hermes/attachments/r.pdf]", out)
    }

    @Test
    fun imagesBecomeInlineDataUrlsAfterTheText() {
        val parts = buildContent("what is this", listOf(att("p.jpg", AttachmentKind.Image)), true) { byteArrayOf(1, 2, 3) } as JSONArray
        assertEquals(2, parts.length())
        assertEquals("text", parts.getJSONObject(0).getString("type"))
        assertEquals("what is this", parts.getJSONObject(0).getString("text"))
        assertEquals("data:image/jpeg;base64,AQID", parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
    }

    @Test
    fun anImageOnlyMessageHasNoEmptyTextPart() {
        val parts = buildContent("", listOf(att("p.jpg", AttachmentKind.Image)), true) { byteArrayOf(1) } as JSONArray
        assertEquals(1, parts.length())
        assertEquals("image_url", parts.getJSONObject(0).getString("type"))
    }

    @Test
    fun olderPicturesAreOnlyNamed() {
        val out = buildContent("earlier", listOf(att("old.jpg", AttachmentKind.Image)), false) { byteArrayOf(1) }
        assertEquals("earlier\n\n[an image was attached here: old.jpg]", out)
    }

    @Test
    fun anUnreadableImageFallsBackToItsName() {
        val out = buildContent("", listOf(att("gone.jpg", AttachmentKind.Image)), true) { null }
        assertEquals("[an image was attached here: gone.jpg]", out)
    }
}
