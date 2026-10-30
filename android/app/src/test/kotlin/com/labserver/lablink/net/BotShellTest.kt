package com.labserver.lablink.net

import org.junit.Assert.assertEquals
import org.junit.Test

class BotShellTest {
    @Test
    fun shellUrlIsTheBotsManagerRoute() {
        assertEquals(
            "http://bot.labserver.internal/m/v1/bots/b0123456789/shell?cols=80&rows=24",
            BotShell.shellUrl("http://bot.labserver.internal/m/v1/", "b0123456789", 80, 24),
        )
        assertEquals("http://10.0.2.2:8650/m/v1/bots/labbot/shell?cols=51&rows=28", BotShell.shellUrl("http://10.0.2.2:8650/m/v1", "labbot", 51, 28))
    }
}
