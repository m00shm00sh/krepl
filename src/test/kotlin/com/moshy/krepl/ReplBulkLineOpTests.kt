package com.moshy.krepl

import com.moshy.krepl.test_internal.maximizeLoggingLevelForTesting
import com.moshy.krepl.test_internal.stream
import com.moshy.krepl.test_internal.throwingInputStream
import com.moshy.krepl.test_internal.withTimeoutOneSecond
import org.junit.jupiter.api.Assertions.assertLinesMatch
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.File
import java.io.InputStreamReader
import java.io.Reader

/* Tests operation of bulk line operations.
 * Namely, the collect-until-delimiter, collect-from-file, and clear-buffer built-ins.
 */
class ReplBulkLineOpTests {
    @Test
    fun `test consume`() = withTimeoutOneSecond {
        val (repl, lines) = IoRepl(listOf(
            "<< @@",
            "ab c",
            "d e=f g",
            "@@",
            ">"
        ), null)
        repl.run()
        assertLinesMatch(lines(
            "2 collected",
            "ab c",
            "d e=f g"
        ), lines)
    }

    @Test
    fun `test clear`() = withTimeoutOneSecond {
        val (repl, lines) = IoRepl(listOf(
            "<< @@",
            "@@",
            "!-",
            ">"
        ), null)
        repl.run()
        assertLinesMatch(lines(
            "0 collected",
            "(E) no collected lines"
        ), lines)
    }

    @Test
    fun `test consume append`() = withTimeoutOneSecond {
        val (repl, lines) = IoRepl(listOf(
            "<< @@",
            "ab c",
            "d e=f g",
            "@@",
            "+<< ##",
            "h i=j",
            "k",
            "##",
            ">"
        ), null)
        repl.run()
        assertLinesMatch(lines(
            "2 collected",
            "2 added (4 total)",
            "ab c",
            "d e=f g",
            "h i=j",
            "k"
        ), lines)
    }

    @Test
    fun `test file consume`() = withTimeoutOneSecond {
        val (repl, lines) = IoRepl(listOf(
            "< ab",
            ">"
        ), null)
        repl.fileReader = ::testingFileReaderSupplier
        repl.run()
        assertLinesMatch(lines(
            "2 collected",
            "a",
            "b"
        ), lines)
    }

    @Test
    fun `test file consume append`() = withTimeoutOneSecond {
        val (repl, lines) = IoRepl(listOf(
            "<< @@",
            "ab c",
            "d e=f g",
            "@@",
            "+< ab", ">"
        ), null)
        repl.fileReader = ::testingFileReaderSupplier
        repl.run()
        assertLinesMatch(lines(
            "2 collected",
            "2 added (4 total)",
            "ab c",
            "d e=f g",
            "a",
            "b"
        ), lines)
    }

    @Test
    fun `test file consume missing`() = withTimeoutOneSecond {
        val (repl, lines) = IoRepl(listOf(
            "<< @@",
            "ab c",
            "d e=f g",
            "@@",
            "+< z", ">"
        ), null)
        repl.fileReader = ::testingFileReaderSupplier
        repl.run()
        lines.forEach(::println)
        assertLinesMatch(lines(
            "2 collected",
            "\\(\\+<:E\\) .*\\.NoSuchFileException:.*",
            "ab c",
            "d e=f g"
        ), lines)
    }

    @Test
    fun `test file consume exception`() = withTimeoutOneSecond {
        val (repl, lines) = IoRepl(listOf(
            "<< @@",
            "ab c",
            "d e=f g",
            "@@",
            "+< thrower", ">"
        ), null)
        repl.fileReader = ::testingFileReaderSupplier
        repl.run()
        assertLinesMatch(lines(
            "2 collected",
            "\\(\\+<:E\\) .*: Throwing!",
            "ab c",
            "d e=f g"
        ), lines)
    }

    private companion object {
        @JvmStatic
        @BeforeAll
        fun initLogging() {
            maximizeLoggingLevelForTesting()
        }

        fun testingFileReaderSupplier(name: String): Reader =
            when (name) {
                "thrower" -> InputStreamReader(throwingInputStream())
                "ab" -> "a\nb".stream()
                else -> throw NoSuchFileException(File(name))
            }
    }
}
