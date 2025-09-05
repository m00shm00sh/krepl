package com.moshy.krepl

import com.moshy.krepl.test_internal.maximizeLoggingLevelForTesting
import com.moshy.krepl.test_internal.withTimeoutOneSecond
import org.junit.jupiter.api.Assertions.assertLinesMatch
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/* Tests level push/pop functionality.
 */
class ReplLevelTests {
    @Test
    fun `test enter and exit level`() = withTimeoutOneSecond {
        val (repl, lines) = IoRepl(listOf(
            "a",
            "b",
            "quit",
            "b"
        ), null)
        repl["a"] {
            handler = {
                push("a") { _, o ->
                    o.send("leave a".asLine())
                }
                this["b"] {
                    handler = {
                        it.outputChannel.send("B!".asLine())
                    }
                }
            }
        }
        repl.run()
        assertLinesMatch(lines(
            "B!",
            "leave a",
            "(E) unrecognized command: b"
        ), lines)
    }

    @Test
    fun `test show multiple levels`() = withTimeoutOneSecond {
        val (repl, lines) = IoRepl(listOf(
            "a",
            "b",
            "levels"
        ), null)
        repl["a"] {
            handler = {
                push("aa")
                this["b"] {
                    handler = {
                        push("bb")
                    }
                }
            }
        }
        repl.run()
        assertLinesMatch(lines(
            ":aa:bb",
        ), lines)
    }

    @Test
    fun `test pop multiple levels`() = withTimeoutOneSecond {
        val (repl, lines) = IoRepl(listOf(
            "a",
            "b",
            "c",
            "quit",
            "quit",
            "levels"
        ), null)
        repl["a"] {
            handler = {
                push("aa")
                this["b"] {
                    handler = {
                        push("bb")
                        this["c"] {
                            handler = {
                                push("")
                            }
                        }
                    }
                }
            }
        }
        repl.run()
        assertLinesMatch(lines(
            ":aa",
        ), lines)
    }

    @Test
    fun `test pop exception`() = withTimeoutOneSecond {
        val (repl, lines) = IoRepl(listOf(
            "a",
            "levels",
            "quit",
            "levels"
        ), null)
        repl["a"] {
            handler = {
                push("aa") { _, _ ->
                    throw IllegalArgumentException("a")
                }
            }
        }
        repl.run()
        assertLinesMatch(
            lines(":aa") +
                    listOf("\\(quit:E\\) .*IllegalArgumentException: .*\n") +
                    lines(":aa"),
            lines
        )
    }

    @Test
    fun `test rename`() = withTimeoutOneSecond {
        val (repl, lines)         = IoRepl(listOf(
            "a", "b"
        ))
        repl["a"] {
            handler = {
                push("a")
                val renamer = renamer()
                this["b"] {
                    handler = {
                        renamer.set("b")
                    }
                }
            }
        }
        repl.run()
        assertLinesMatch(listOf(
           " $ ",
            ":a $ ",
            ":b $ ",
        ), lines)
    }

    @Test
    fun `test rename nested`() = withTimeoutOneSecond {
        val (repl, lines)         = IoRepl(listOf(
            "a", "b", "c", "d"
        ))
        repl["a"] {
            handler = {
                push("a")
                val renamer = renamer()
                this["b"] {
                    handler = {
                        push("b")
                        val renamer = renamer()
                        this["d"] {
                            handler = {
                                renamer.set("d")
                            }
                        }
                    }
                }
                this["c"] {
                    handler = {
                        renamer.set("c")
                    }
                }
            }
        }
        repl.run()
        assertLinesMatch(listOf(
            " $ ",
            ":a $ ",
            ":a:b $ ",
            ":c:b $ ",
            ":c:d $ "
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
