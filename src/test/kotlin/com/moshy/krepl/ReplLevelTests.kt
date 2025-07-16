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
            "pop",
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
            "pop 2",
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
    fun `test invalid pop`() = withTimeoutOneSecond {
        val (repl, lines) = IoRepl(listOf(
            "a",
            "pop 2",
            "pop -1",
            "pop one",
            "levels"
        ), null)
        repl["a"] {
            handler = {
                push("aa")
            }
        }
        repl.run()
        assertLinesMatch(lines(
            "\\(pop:E\\).* must call exit.*",
            "\\(pop:E\\).* invalid pop count",
            "\\(pop:E\\).* invalid pop count",
            ":aa",
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
