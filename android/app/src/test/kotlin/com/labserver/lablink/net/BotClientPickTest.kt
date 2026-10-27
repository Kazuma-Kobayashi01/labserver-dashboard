package com.labserver.lablink.net

import com.labserver.lablink.data.ModelPick
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotClientPickTest {
    private fun body() = JSONObject().put("model", "lab-bot")

    @Test
    fun nothingIsAddedForTheDefault() {
        val b = body()
        BotClient.applyPick(b, sandbox = true, pick = ModelPick())
        BotClient.applyPick(b, sandbox = false, pick = ModelPick("deepseek", "", "high"))   // a model is needed
        assertEquals(listOf("model"), b.keys().asSequence().toList())
    }

    @Test
    fun theManagerGetsPlainFields() {
        val b = body()
        BotClient.applyPick(b, sandbox = true, pick = ModelPick("openai-codex", "gpt-5.6-sol", "xhigh"))
        assertEquals("openai-codex", b.getString("modelProvider"))
        assertEquals("gpt-5.6-sol", b.getString("modelId"))
        assertEquals("xhigh", b.getString("reasoning"))
        assertFalse(b.has("provider"))
    }

    @Test
    fun theDefaultBotTalksToHermesWithAnExplicitProvider() {
        val b = body()
        BotClient.applyPick(b, sandbox = false, pick = ModelPick("deepseek", "deepseek-flash", "high"))
        assertEquals("deepseek", b.getString("provider"))
        assertEquals("deepseek-flash", b.getString("model"))
        val r = b.getJSONObject("model_options").getJSONObject("reasoning")
        assertTrue(r.getBoolean("enabled"))
        assertEquals("high", r.getString("effort"))
    }

    @Test
    fun offMeansReasoningDisabledAndAutoMeansNoOptions() {
        val off = body()
        BotClient.applyPick(off, sandbox = false, pick = ModelPick("deepseek", "deepseek-flash", "none"))
        assertFalse(off.getJSONObject("model_options").getJSONObject("reasoning").getBoolean("enabled"))
        val auto = body()
        BotClient.applyPick(auto, sandbox = false, pick = ModelPick("deepseek", "deepseek-flash", ""))
        assertFalse(auto.has("model_options"))
    }
}
