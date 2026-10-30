package com.labserver.lablink.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatWireTest {
    @Test
    fun aChatGoesOutAndComesBackTheSame() {
        val c = Conversation(id = "c1", title = "状态", created = 1, updated = 2, pinned = true)
        val msgs = listOf(
            ChatMessage(role = "user", content = "hi", id = "m1", time = 10,
                attachments = listOf(Attachment("a1", "p.png", "image/png", 3, AttachmentKind.Image, "/data/p.png"))),
            ChatMessage(role = "assistant", content = "ok", reasoning = "hmm", id = "m2", time = 11, thinkingMs = 900,
                tools = listOf(ToolRun("t1", command = "ls", output = "x", exitCode = 0, done = true, at = 2))),
        )
        val wire = ChatWire.encode(c, msgs)
        val att = wire.getJSONArray("messages").getJSONObject(0).getJSONArray("attachments").getJSONObject(0)
        // the shared form: lower-case kinds, no path from this device
        assertEquals("image", att.getString("kind"))
        assertFalse(att.has("local"))
        val (back, list) = ChatWire.decode(JSONObject(wire.toString()))
        assertEquals(c, back)
        assertEquals("hmm", list[1].reasoning)
        assertEquals("ls", list[1].tools[0].command)
        assertEquals(AttachmentKind.Image, list[0].attachments[0].kind)
        assertEquals("", list[0].attachments[0].local)
    }

    @Test
    fun aChatFromTheMacReadsToo() {
        val mac = """{"conversation":{"id":"ABC-1","title":"t","created":1,"updated":5,"pinned":false,"renamed":true},
            "messages":[{"id":"x","role":"user","content":"yo","reasoning":"","time":1793345360361.123,"attachments":[{"id":"q","name":"a.txt","mime":"text/plain","size":4,"kind":"text","remotePath":""}],"failed":false,"thinkingMs":0,"tools":[]}]}"""
        val (c, list) = ChatWire.decode(JSONObject(mac))
        assertTrue(c.renamed)
        assertEquals(AttachmentKind.Text, list[0].attachments[0].kind)
        assertEquals(1793345360361L, list[0].time)
    }
}
