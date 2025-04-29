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
    fun `test atexit via build`() = withTimeoutOneSecond {
        val (repl, lines) = IoRepl(emptyList(), null)
        repl.build {
            atExit { o ->
                o.send("1".asLine())
            }
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
        repl.registerCommand("a", "b") { (_, _, _, o) -> o.send("b".asLine()) }
        repl.run()
        assertLinesMatch(lines("b"), lines)
        assertThrowsWithMessage<IllegalArgumentException>("another command has claimed a") {
            repl.registerCommand("A") { }
        }
        assertThrowsWithMessage<IllegalArgumentException>("command a has claimed alias b") {
            repl.registerCommand("__", "B") { }
        }
        assertThrowsWithMessage<IllegalArgumentException>("command a has claimed b") {
            repl.registerCommand("B") { }
        }
    }

    @Test
    fun `test command registration via build`() = withTimeoutOneSecond {
        val (repl, lines) = IoRepl(listOf("a"), null)
        repl.build {
            registerCommand("a", "b") { (_, _, _, o) -> o.send("b".asLine()) }
        }
        repl.run()
        assertLinesMatch(lines("b"), lines)
        repl.build {
            assertThrowsWithMessage<IllegalArgumentException>("another command has claimed a") {
                registerCommand("A") { }
            }
            assertThrowsWithMessage<IllegalArgumentException>("command a has claimed alias b") {
                registerCommand("__", "B") { }
            }
            assertThrowsWithMessage<IllegalArgumentException>("command a has claimed b") {
                registerCommand("B") { }
            }
        }
    }

    private companion object {
        @JvmStatic
        @BeforeAll
        fun initLogging() {
            maximizeLoggingLevelForTesting()
        }
    }
}
