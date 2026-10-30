package com.labserver.lablink.net

import com.labserver.lablink.data.Bot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshEnterTest {
    @Test
    fun defaultBotEntersTheHermesContainer() {
        assertEquals("sudo incus exec hermes -t -- bash -l", SshSession.enterCommand(Bot(id = "labbot", name = "Lab bot")))
    }

    @Test
    fun sandboxBotEntersItsOwnSandbox() {
        val cmd = SshSession.enterCommand(Bot(id = "b0123456789", name = "Hel'per\"; rm -rf /"))
        assertTrue(cmd.contains("/profiles/b0123456789/"))
        assertTrue(cmd.contains("docker exec -it"))
        // the bot's name cannot break out of the quoting
        assertFalse(cmd.contains("Hel'per"))
        assertFalse(cmd.contains("rm -rf"))
    }
}
