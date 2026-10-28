package com.labserver.lablink.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApprovalTest {
    @Test
    fun ourPolicyNamesTheKindAndTheCommand() {
        val r = ApprovalRequest.parse(
            """{"event":"approval.request","run_id":"chatcmpl-1","timestamp":1.0,"request_id":"r7","pattern_key":"plugin_rule:lablink-download",
               "description":"curl -O https://x.y/a.zip","command":"<terminal> (plugin approval rule)","choices":["once","session","always","deny"]}""",
        )!!
        assertEquals(listOf("chatcmpl-1", "r7", "download", "curl -O https://x.y/a.zip"), listOf(r.runId, r.requestId, r.kind, r.command))
        assertEquals(listOf("once", "session", "always", "deny"), r.choices)
    }

    @Test
    fun hermesOwnCheckShowsTheCommand() {
        val r = ApprovalRequest.parse("""{"run_id":"c2","pattern_key":"recursive delete","command":"rm -rf /x","description":"recursive delete","choices":["once","deny"]}""")!!
        assertEquals(listOf("danger", "rm -rf /x"), listOf(r.kind, r.command))
    }

    @Test
    fun withoutARunThereIsNothingToAnswer() {
        assertNull(ApprovalRequest.parse("""{"pattern_key":"x"}"""))
        assertNull(ApprovalRequest.parse("not json"))
    }
}
