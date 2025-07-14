package com.moshy.krepl

import com.moshy.krepl.test_internal.maximizeLoggingLevelForTesting
import com.moshy.krepl.test_internal.withTimeoutOneSecond
import org.junit.jupiter.api.Assertions.assertLinesMatch
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/* Tests dynamic modification of command registration.
 */
class ReplModifyTests {
    @Test
    fun `test add and remove command`() = withTimeoutOneSecond {
        val (repl, lines) = IoRepl(listOf(
            "a",
            "b",
            "a"
        ), null)
        repl["a"] {
            handler = {
                this["b"] {
                    handler = {
                        it.outputChannel.send("B!".asLine())
                        remove("a")
                    }
                }
            }
        }
        repl.run()
        assertLinesMatch(lines(
            "B!",
            "(E) unrecognized command: a"
        ), lines)
    }

    @Test
    fun `test add and clear commands`() = withTimeoutOneSecond {
        val (repl, lines) = IoRepl(listOf(
            "a",
        ), null)
        repl["a"] {
            handler = {
                clear()
            }
        }
        repl.run()
        val commands = repl.registeredCommands
        assertTrue(lines.isEmpty())
        assertTrue(commands.isEmpty())
    }

    private companion object {
        @JvmStatic
        @BeforeAll
        fun initLogging() {
            maximizeLoggingLevelForTesting()
        }
    }
}
