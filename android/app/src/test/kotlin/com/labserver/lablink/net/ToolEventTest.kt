package com.labserver.lablink.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolEventTest {
    @Test
    fun theManagersCommandDetailsAreRead() {
        val running = BotClient.parseToolDetail("""{"toolCallId":"c1","status":"running","command":"ls -la"}""")!!
        assertEquals(BotEvent.ToolDetail("c1", "ls -la", done = false), running)
        val done = BotClient.parseToolDetail("""{"toolCallId":"c1","status":"completed","command":"ls","output":"a\\nb","exitCode":2,"error":null,"durationMs":1500}""")!!
        assertEquals(BotEvent.ToolDetail("c1", "ls", done = true, output = "a\\nb", exitCode = 2, error = null, durationMs = 1500), done)
        assertNull(BotClient.parseToolDetail("""{"status":"running"}"""))
    }
}
