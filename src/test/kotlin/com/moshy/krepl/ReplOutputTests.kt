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
        repl["a"] {
            handler = { }
            usage = "a b"
        }
        repl["b"] = repl["a"]
        repl["c"] {
            handler = { }
        }
        repl["d"] {
            semantics = Repl.LineSemantics.CONSUME
            handler = { }
        }
        repl["e f"] {
            handler = { }
        }
        repl["g h"] = repl["e f"]
        repl.run()
        val B = "(builtin) "
        assertLinesMatch(lines(
            "Commands:",
            "\t${B}exit",
            "\t${B}quit alias for: exit",
            "\t${B}help [command]",
            "\t${B}? alias for: help",
            "\t${B}<< delimiter",
            ">>\t${B}clear>>",
            "\ta b",
            "\tb alias for: a",
            "\tc <no usage message>",
            "\td <requires 0\\+ collected lines>",
            "\t\"e f\" <no usage message>",
            "\t\"g h\" alias for: \"e f\"",
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
        repl["a"] {
            help = "aa\nbb"
            handler = { }
        }
        repl["b"] {
            handler = { }
        }
        repl["c"] = repl["b"]
        repl["g h"] {
            help = "i"
            handler = { }
        }
        repl["i j"] = repl["g h"]
        repl["i"] {
            usage = "j"
            help = "k"
            handler = { }
        }
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

    @Test
    fun `test multiline usage`() = withTimeoutOneSecond {
        val (repl, lines) = IoRepl(listOf("help", "help a"))
        repl["a"] {
            help = "h"
            usage = "a\nb"
            handler = {}
        }
        repl.run()
        assertLinesMatch(
            listOf(">> $ >>") +
            lines(
                "\ta",
                "\t\tb",
            ) +
            listOf(" $ ") +
            lines(
                "a",
                "\tb",
                "h"
            ) +
            listOf(" $ ")
        , lines)
    }

    private companion object {
        @JvmStatic
        @BeforeAll
        fun initLogging() {
            maximizeLoggingLevelForTesting()
        }
    }
}
