package com.moshy.krepl.internal

import com.moshy.krepl.test_internal.assertThrowsWithMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import kotlin.test.assertEquals

class TokenizerTests {
    @Test
    fun testPositional() {
        val line = "a b\"c d\"e f\\\" \"g \""
        val toks = line.tokenizeLine()
        assertAll(
            { assertEquals("a", toks.cmd) },
            { assertEquals(listOf("bc de", "f\"", "g "), toks.pos) }
        )
    }

    @Test
    fun testKeyword() {
        val line = "a b=c"
        val toks = line.tokenizeLine()
        assertAll(
            { assertEquals(mapOf("b" to "c"), toks.kw) },
        )
    }

    @Test
    fun testEscaping() {
        val line ="a \\\"b \\c"
        val toks = line.tokenizeLine()
        assertAll(
            { assertEquals(listOf("\"b", "\\c"), toks.pos) }
        )
    }

    @Test
    fun testEscapeKeyword() {
        val line = "a =b=c"
        val toks = line.tokenizeLine()
        assertEquals(listOf("b=c"), toks.pos)
    }

    @Test
    fun testDupKeyword() {
        val line = "a b=c b=d"
        assertThrowsWithMessage<IllegalArgumentException>(".*specified twice") {
            line.tokenizeLine()
        }
    }

    @Test
    fun testEscapedKeyKeyword() {
        val line = "a b\\=c=d=e"
        val toks = line.tokenizeLine()
        assertEquals(mapOf("b=c" to "d=e"), toks.kw)
    }

    @Test
    fun testTrim() {
        val line = " a b "
        val toks = line.tokenizeLine()
        assertAll(
            { assertEquals("a", toks.cmd) },
            { assertEquals(listOf("b"), toks.pos) }
        )
    }
}
