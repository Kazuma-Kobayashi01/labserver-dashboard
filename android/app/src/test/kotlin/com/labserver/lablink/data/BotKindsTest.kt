package com.labserver.lablink.data

import com.labserver.lablink.net.ManagerClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotKindsTest {
    @Test
    fun onlyTheAdminBotAndSandboxBotsHaveATerminal() {
        assertTrue(Bot(id = DEFAULT_BOT_ID, name = "Lab bot").hasTerminal)
        assertTrue(Bot(id = "b0123456789", name = "Coder", sandboxed = true).hasTerminal)
        assertFalse(Bot(id = "b0123456789", name = "Writer", sandboxed = false).hasTerminal)
        assertFalse(Bot(id = "b0123456789", name = "Writer", sandboxed = false).hasSandbox)
        assertFalse(Bot(id = DEFAULT_BOT_ID, name = "Lab bot").hasSandbox)
    }

    @Test
    fun theServerSaysWhetherABotHasASandbox() {
        val base = """{"id":"b0123456789","name":"W","color":1"""
        assertFalse(ManagerClient.parseBot(JSONObject("$base,\"sandboxed\":false}")).sandboxed)
        assertTrue(ManagerClient.parseBot(JSONObject("$base,\"sandboxed\":true}")).sandboxed)
        // an older server does not say: its bots all have one
        assertTrue(ManagerClient.parseBot(JSONObject("$base}")).sandboxed)
        assertEquals("W", ManagerClient.parseBot(JSONObject("$base}")).name)
    }
}
