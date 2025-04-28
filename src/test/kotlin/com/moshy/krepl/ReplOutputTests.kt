package com.moshy.krepl

import com.moshy.krepl.test_internal.maximizeLoggingLevelForTesting
import com.moshy.krepl.test_internal.withTimeoutOneSecond
import org.junit.jupiter.api.Assertions.assertLinesMatch
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/* Verifies output of quit and help built-ins. */
class ReplOutputTests {
    @Test
    fun `test quit`() = withTimeoutOneSecond {
        val (repl, lines) = IoRepl(listOf("quit"), null)
        repl.run()
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `test implicit quit`() = withTimeoutOneSecond {
        val (repl, lines) = IoRepl(listOf(), null)
        repl.run()
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `test output of help`() = withTimeoutOneSecond {
        val (repl, lines) = IoRepl(listOf("help"), null)
        repl.registerCommand("a", "b", usage = "a b") { }
        repl.registerCommand("c") { }
        repl.registerCommand("d", semantics = Repl.LineSemantics.CONSUME) { }
        repl.registerCommand("e f", "g h") { }
        repl.run()
        assertLinesMatch(lines(
            "Commands:",
            "\texit",
            "\thelp \\[command\\]",
            "\t<< delimiter",
            ">>\tclear>>",
            "\ta b",
            "\tc <no usage message>",
            "\td <requires 0\\+ collected lines>",
            "\t\"e f\" <no usage message>",
            "Aliases:",
            "\texit: -q quit",
            "\thelp: -h \\?",
            ">>\t a: b>>",
            "\t\"e f\": \"g h\""
        ), lines)
    }

    @Test
    fun `test output of help with param`() = withTimeoutOneSecond {
        val (repl, lines) = IoRepl(listOf(
            "help a",
            "help c",
            "help d",
            "help e f",
            "help \"g \"h",
            "help i",
        ), null)
        repl.registerCommand("a", help = "aa\nbb") { }
        repl.registerCommand("b", "c") { }
        repl.registerCommand("g h", "i j", help = "i") { }
        repl.registerCommand("i", usage = "j", help = "k") { }
        repl.run()
        assertLinesMatch(lines(
            "a <no usage message>",
            "aa",
            "bb",
            "\\(help:E\\) .*: no help message for command c",
            "\\(help:E\\) .*: no match for command d",
            "\\(help:E\\) .*: too many arguments",
            "\"g h\" <no usage message>",
            "i",
            "j",
            "k",
        ), lines)
    }

    @Test
    fun `test empty command`() = withTimeoutOneSecond {
        val (repl, lines) = IoRepl(listOf("          "))
        repl.run()
        assertLinesMatch(listOf(
            " $ ",
            " $ "
        ), lines)

    }

    private companion object {
        @JvmStatic
        @BeforeAll
        fun initLogging() {
            maximizeLoggingLevelForTesting()
        }
    }
}
