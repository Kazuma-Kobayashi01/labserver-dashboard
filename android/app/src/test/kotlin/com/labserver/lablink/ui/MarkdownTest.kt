package com.labserver.lablink.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    private fun kinds(code: String, lang: String) = tokenize(code, lang).map { code.substring(it.start, it.end) to it.kind }

    @Test
    fun headingsParagraphsAndRules() {
        val b = parseBlocks("# One\n\n###### Six\ntext line 1\ntext line 2\n\n---\n\n#nospace")
        assertEquals(Block.Heading(1, "One"), b[0])
        assertEquals(Block.Heading(6, "Six"), b[1])
        assertEquals(Block.Para("text line 1\ntext line 2"), b[2])
        assertEquals(Block.Rule, b[3])
        assertEquals(Block.Para("#nospace"), b[4])   // a heading needs the space
    }

    @Test
    fun fencedCodeWithBackticksAndTildesAndWhileStreaming() {
        val b = parseBlocks("before\n```kotlin\nval x = 1\n```\n~~~python\nprint(1)\n~~~~\nafter")
        assertEquals(Block.Para("before"), b[0])
        assertEquals(Block.Code("kotlin", "val x = 1"), b[1])
        assertEquals(Block.Code("python", "print(1)"), b[2])
        assertEquals(Block.Para("after"), b[3])
        // a longer fence can contain a shorter one
        assertEquals(listOf(Block.Code("md", "```\ninner\n```")), parseBlocks("````md\n```\ninner\n```\n````"))
        // still streaming: the fence is not closed yet
        assertEquals(listOf(Block.Code("sh", "echo hi")), parseBlocks("```sh\necho hi"))
        // markdown inside code stays code
        assertEquals(listOf(Block.Code("", "# not a heading\n- not a list")), parseBlocks("```\n# not a heading\n- not a list\n```"))
    }

    @Test
    fun nestedAndTaskLists() {
        val b = parseBlocks("- a\n  - b\n    - c\n- d\n\n1. one\n2. two\n   continued\n\n- [ ] todo\n- [x] done")
        assertEquals(Block.Item(0, "•", "a"), b[0])
        assertEquals(Block.Item(1, "•", "b"), b[1])
        assertEquals(Block.Item(2, "•", "c"), b[2])
        assertEquals(Block.Item(0, "•", "d"), b[3])
        assertEquals(Block.Item(0, "1.", "one"), b[4])
        assertEquals(Block.Item(0, "2.", "two\ncontinued"), b[5])
        assertEquals(Block.Item(0, "•", "todo", false), b[6])
        assertEquals(Block.Item(0, "•", "done", true), b[7])
    }

    @Test
    fun quotesHoldOtherBlocks() {
        val b = parseBlocks("> note\n> - item\n> ```\n> code\n> ```")
        val q = b.single() as Block.Quote
        assertEquals(Block.Para("note"), q.blocks[0])
        assertEquals(Block.Item(0, "•", "item"), q.blocks[1])
        assertEquals(Block.Code("", "code"), q.blocks[2])
    }

    @Test
    fun tablesWithAndWithoutOuterPipes() {
        val withPipes = parseBlocks("| a | b |\n|---|:-:|\n| 1 | 2 |\n| 3 | 4 |\ntext")
        assertEquals(Block.Table(listOf("a", "b"), listOf(listOf("1", "2"), listOf("3", "4"))), withPipes[0])
        assertEquals(Block.Para("text"), withPipes[1])
        val bare = parseBlocks("a | b\n--- | ---\n1 | 2")
        assertEquals(Block.Table(listOf("a", "b"), listOf(listOf("1", "2"))), bare.single())
        assertEquals(Block.Para("a | b\nnot a table"), parseBlocks("a | b\nnot a table").single())
    }

    @Test
    fun windowsLineEndingsAndEmptyInput() {
        assertEquals(listOf(Block.Para("a\nb")), parseBlocks("a\r\nb"))
        assertTrue(parseBlocks("").isEmpty())
    }

    @Test
    fun pythonHighlighting() {
        val k = kinds("def f(x):  # note\n    return \"hi\" + 42", "python")
        assertTrue("def" to Tok.Keyword in k)
        assertTrue("# note" to Tok.Comment in k)
        assertTrue("return" to Tok.Keyword in k)
        assertTrue("\"hi\"" to Tok.Str in k)
        assertTrue("42" to Tok.Number in k)
    }

    @Test
    fun cLikeBlockCommentsStringsAndTypes() {
        val k = kinds("/* c */ val s: String = 'x' // end\nint n = 0x1F;", "kotlin")
        assertTrue("/* c */" to Tok.Comment in k)
        assertTrue("val" to Tok.Keyword in k)
        assertTrue("String" to Tok.Type in k)
        assertTrue("'x'" to Tok.Str in k)
        assertTrue("// end" to Tok.Comment in k)
        assertTrue("0x1F" to Tok.Number in k)
    }

    @Test
    fun anApostropheInsideTextDoesNotSwallowTheLine() {
        val k = kinds("it's fine // really", "js")
        assertTrue("// really" to Tok.Comment in k)
        assertTrue(k.none { it.second == Tok.Str })
    }

    @Test
    fun sqlIsCaseInsensitiveAndJsonHasLiterals() {
        assertTrue("SELECT" to Tok.Keyword in kinds("SELECT * FROM t WHERE a = 1 -- c", "sql"))
        assertTrue("-- c" to Tok.Comment in kinds("SELECT 1 -- c", "sql"))
        val j = kinds("{\"a\": true, \"b\": null, \"c\": 1.5}", "json")
        assertTrue("true" to Tok.Keyword in j && "null" to Tok.Keyword in j && "1.5" to Tok.Number in j)
        assertTrue("\"a\"" to Tok.Str in j)
    }

    @Test
    fun diffColoursAddedAndRemovedLines() {
        val k = kinds("--- a\n+++ b\n@@ -1 +1 @@\n-old\n+new\n same", "diff")
        assertTrue("-old" to Tok.Removed in k)
        assertTrue("+new" to Tok.Added in k)
        assertTrue("+++ b" to Tok.Keyword in k)
    }

    @Test
    fun anUnknownLanguageStillGetsStringsAndComments() {
        val k = kinds("x = \"a\"  # c\ny = 2 // d", "weird")
        assertTrue("\"a\"" to Tok.Str in k && "# c" to Tok.Comment in k && "2" to Tok.Number in k)
    }

    @Test
    fun anUnterminatedStringRunsToTheEndOfTheLine() {
        val k = kinds("say(\"open\nnext = 1", "js")
        assertTrue("\"open" to Tok.Str in k)
        assertTrue("1" to Tok.Number in k)
    }
}
