package com.labserver.lablink.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyPartsTest {
    private fun t(s: String) = ReplyPart.Text(s)
    private fun img(s: String, alt: String = "") = ReplyPart.Image(s, alt)

    @Test
    fun hermesMediaTokensBecomePictures() {
        assertEquals(
            listOf(t("修好了，这次没有方框了。给你——"), img("media:/home/u/.hermes/profiles/b/cache/scratch/test.png")),
            splitReply("修好了，这次没有方框了。给你——MEDIA:/home/u/.hermes/profiles/b/cache/scratch/test.png"),
        )
        assertEquals(
            listOf(t("Here:"), img("media:/workspace/a.jpg"), t("and"), img("media:/tmp/b.webp")),
            splitReply("Here:\nMEDIA:/workspace/a.jpg\nand MEDIA:/tmp/b.webp."),
        )
    }

    @Test
    fun markdownImagesBecomePicturesWithTheirCaption() {
        assertEquals(
            listOf(t("A chart"), img("https://x.y/c.png", "sales"), t("done")),
            splitReply("A chart\n\n![sales](https://x.y/c.png \"title\")\n\ndone"),
        )
    }

    @Test
    fun codeAndHalfStreamedPathsStayText() {
        val code = "```\nMEDIA:/a/b.png\n```"
        assertEquals(listOf(t(code)), splitReply(code))
        assertEquals(listOf(t("MEDIA:/home/u/cach")), splitReply("MEDIA:/home/u/cach"))
        assertEquals(listOf(t("plain")), splitReply("plain"))
    }

    @Test
    fun copiedTextLeavesThePicturesOut() {
        assertEquals("look\n\nnice", replyText("look MEDIA:/a/b.png\nnice"))
    }
}
