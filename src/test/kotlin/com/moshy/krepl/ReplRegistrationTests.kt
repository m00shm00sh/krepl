package com.moshy.krepl

import com.moshy.krepl.test_internal.assertThrowsWithMessage
import com.moshy.krepl.test_internal.maximizeLoggingLevelForTesting
import com.moshy.krepl.test_internal.withTimeoutOneSecond
import org.junit.jupiter.api.Assertions.assertLinesMatch
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

/* Tests registration of commands.
 * Registration related to exception handling is tested in ReplFailureTests
 */
class ReplRegistrationTests {
    @Test
    fun `test atexit`() = withTimeoutOneSecond {
        val (repl, lines) = IoRepl(emptyList(), null)
        repl.atExit { o ->
            o.send("1".asLine())
        }
        repl.run()
        assertEquals(lines("1"), lines)
    }

    @Test
    fun `test atexit invalid argument`() = withTimeoutOneSecond {
        val repl = NoopRepl()
        repl.atExit { }
        assertThrowsWithMessage<IllegalArgumentException>("at-exit handler already set") {
            repl.atExit {  }
        }
    }

    @Test
    fun `test prompt supplier evaluates every time`() = withTimeoutOneSecond {
        val v = AtomicInteger(0)
        val (repl, lines) = IoRepl(listOf("")) { "${v.incrementAndGet()}" }
        repl.run()
        assertEquals(listOf(
            "1 $ ",
            "2 $ "
        ), lines)
    }

    @Test
    fun `test command registration`() = withTimeoutOneSecond {
        val (repl, lines) = IoRepl(listOf("a"), null)
        repl["a"] {
            handler = { (_, _, _, o) -> o.send("b".asLine()) }
        }
        repl["b"] = repl["a"]
        repl.run()
        assertLinesMatch(lines("b"), lines)
        assertThrowsWithMessage<IllegalArgumentException>("another command or alias has claimed a") {
            repl["A"] {
            }
        }
        assertThrowsWithMessage<IllegalArgumentException>("command a has claimed alias b") {
            repl["B"] = repl["A"]
        }
        val expBuiltins = setOf(
            "exit", "quit",
            "help", "?",
            "collect", "collect-lines", "<<",
            "collect-more", "collect-more-lines", "+<<",
            "collect-from-file", "<",
            "collect-more-from-file", "+<",
            "peek", "peek-collection-buffer", "?<",
            "clear", "clear-collection-buffer", "!<"
        )
        val expRegistered = setOf("a", "b")
        assertEquals(expBuiltins, repl.builtinCommands)
        assertEquals(expRegistered, repl.registeredCommands)
    }

    private companion object {
        @JvmStatic
        @BeforeAll
        fun initLogging() {
            maximizeLoggingLevelForTesting()
        }
    }
}
