package com.moshy.krepl

import com.moshy.krepl.internal.lineConsumer
import com.moshy.krepl.internal.lineProducer
import com.moshy.krepl.test_internal.ConsumeCounter
import com.moshy.krepl.test_internal.ProduceCounter
import com.moshy.krepl.test_internal.assertMatches
import com.moshy.krepl.test_internal.assertThrowsWithMessage
import com.moshy.krepl.test_internal.hardDelay
import com.moshy.krepl.test_internal.ignoreException
import com.moshy.krepl.test_internal.inputWriter
import com.moshy.krepl.test_internal.maximizeLoggingLevelForTesting
import com.moshy.krepl.test_internal.noop
import com.moshy.krepl.test_internal.outputReader
import com.moshy.krepl.test_internal.throwingInputStream
import com.moshy.krepl.test_internal.throwingOutputStream
import com.moshy.krepl.test_internal.withTimeoutOneSecond
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlin.test.assertEquals
import kotlin.test.fail
import org.junit.jupiter.api.Assertions.assertLinesMatch
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.EOFException
import java.io.IOException
import kotlin.coroutines.CoroutineContext

/* Testing pertaining to failure resilience.
 * Namely, exceptions and cancellations.
 * Command exception handling is also coverage tested here, including fatal and verbose-dump filters.
 */
class ReplFailureTests {
    @Test
    fun `test cancellation from upstream scope`() = withTimeoutOneSecond {
        val inC = ProduceCounter()
        val outC = ConsumeCounter()
        val repl = Repl(
            inC.functionFactory(),
            outC.functionFactory()
        )
        val job = launch { repl.run() }
        // spinlocky but easier to get right than wait points with synchronizers
        while (inC.value < 1L || outC.value < 1L) {
            hardDelay(1)
        }
        job.cancel(CancellationException("done"))
        job.join()
    }

    @Disabled("invalid usage")
    @Test
    fun `test cancellation from input worker job`() = withTimeoutOneSecond {
        val exc = CancellationException("1")
        val repl = Repl(
            { coroutineScope { cancel(exc) }},
            outputReader()
        )
        try {
            repl.run()
            fail("no cancellation")
        } catch (e: CancellationException) {
            assertEquals(exc.message, e.message)
        }
    }

    @Disabled("invalid usage")
    @Test
    fun `test cancellation from output worker job`() = withTimeoutOneSecond {
        val exc = CancellationException("1")
        val repl = Repl(
            noopProducer,
            { coroutineScope { cancel(exc) } }
        )
        try {
            repl.run()
            fail("no cancellation")
        } catch (e: CancellationException) {
            assertEquals(exc.message, e.message)
        }
    }

    @Test
    fun `test cancellation of parent from input worker job`() = withTimeoutOneSecond {
        val exc = CancellationException("1")
        var co: CoroutineContext? = null
        val repl = Repl(
            { co?.cancel(exc) },
            outputReader()
        )
        try {
            launch {
                co = currentCoroutineContext()
                repl.run()
                fail("no cancellation")
            }.join()
        } catch (e: CancellationException) {
            assertEquals(exc.message, e.message)
        }
    }
    @Test
    fun `test cancellation of parent from output worker job`() = withTimeoutOneSecond {
        val exc = CancellationException("1")
        var co: CoroutineContext? = null
        val repl = Repl(
            noopProducer,
            { co?.cancel(exc) }
        )
        try {
            launch {
                co = currentCoroutineContext()
                repl.run()
                fail("no cancellation")
            }.join()
        } catch (e: CancellationException) {
            assertEquals(exc.message, e.message)
        }
    }

    /* the consumer channel is not tied to the job (as if there was a consume{}) so we must check
     * cancellation from job instead of channel works as expected
     */
    @Test
    fun `test cancellation from output worker channel`() = withTimeoutOneSecond {
        val exc = CancellationException("1")
        val repl = Repl(
            noopProducer,
            { cancel(exc) }
        )
        try {
            repl.run()
            fail("no cancellation")
        } catch (e: CancellationException) {
            assertEquals(exc.message, e.message)
        }
    }

    @Test
    fun `test exception from input worker`() = withTimeoutOneSecond {
        val repl = Repl(
            lineProducer(throwingInputStream().reader()),
            outputReader(),
        )
        try {
            repl.run()
            fail("no cancellation")
        } catch (e: IOException) {
            assertMatches("Throwing!", e.message ?: "")
        }
    }

    @Test
    fun `test exception from output worker`() = withTimeoutOneSecond {
        val repl = Repl(
            inputWriter(emptyList()),
            lineConsumer(throwingOutputStream().writer())
        )
        try {
            repl.run()
            fail("no cancellation")
        } catch (e: CancellationException) {
            assertMatches("IO exception: .*: Throwing!", e.message ?: "")
        }
    }

    @Test
    fun `test reader producer cancelling with EOF`() = withTimeoutOneSecond {
        val repl = Repl(
            { close(EOFException()) },
            outputReader()
        )
        assertThrows<EOFException> { repl.run() }
    }

    @Test
    fun `test handling of command cancelling`() = withTimeoutOneSecond {
        val (repl, lines) = IoRepl(listOf("a"))
        repl.registerCommand("a") {
            coroutineScope { cancel() }
        }
        repl.run()
        assertLinesMatch(listOf(
            " $ ",
            "\\(a:E\\) .*JobCancellationException: .*\n",
            " $ "
        ), lines)
    }

    @Test
    fun `test handling of command throwing exception`() = withTimeoutOneSecond {
        val (repl, lines) = IoRepl(listOf("a"))
        repl.registerCommand("a") {
            require(false)
        }
        repl.run()
        assertLinesMatch(listOf(
            " $ ",
            "\\(a:E\\) .*IllegalArgumentException: .*\n",
            " $ "
        ), lines)
    }

    @Test
    fun `test fatal exception handler`() = withTimeoutOneSecond {
        val (repl, lines) = IoRepl(listOf("a"))
        open class E1: RuntimeException()
        class E2: E1() {
            override fun toString() = "\$E2"
        }
        repl.registerCommand("a") {
            throw E2()
        }
        repl.quitOnException<E1>()
        ignoreException { repl.run() }
        assertLinesMatch(
            listOf(
                " $ ",
                "\\(a:E\\) \\\$E2\n"
            ), lines
        )
    }

    @Test
    fun `test fatal exception handler via build`() = withTimeoutOneSecond {
        val (repl, lines) = IoRepl(listOf("a"))
        open class E1: RuntimeException()
        class E2: E1() {
            override fun toString() = "\$E2"
        }
        repl.build {
            registerCommand("a") {
                throw E2()
            }
            quitOnException<E1>()
        }
        ignoreException { repl.run() }
        assertLinesMatch(
            listOf(
                " $ ",
                "\\(a:E\\) \\\$E2\n"
            ), lines
        )
    }

    @Test
    fun `test fatal exception handler saving exception`() = withTimeoutOneSecond {
        val (repl, _) = IoRepl(listOf("a"))
        open class E1: RuntimeException()
        class E2: E1() {
            override fun toString() = "\$E2"
        }
        repl.registerCommand("a") {
            throw E2()
        }
        repl.quitOnException<E1>()
        assertThrows<E2>{ repl.run() }
    }

    @Test
    fun `test fatal exception handler invalid argument`() = withTimeoutOneSecond {
        val repl = NoopRepl()
        class E1: RuntimeException()
        repl.quitOnException<E1>()
        assertThrowsWithMessage<IllegalArgumentException>(".*class .*\\\$E1 already specified") {
            repl.quitOnException<E1>()
        }
    }

    @Test
    fun `test fatal exception handler works with multiple classes`() = withTimeoutOneSecond {
        val (repl, lines) = IoRepl(listOf("a"))
        open class E1: RuntimeException()
        class E2: E1() {
            override fun toString() = "\$E2"
        }
        class E3: RuntimeException()
        repl.registerCommand("a") {
            throw E2()
        }
        repl.quitOnException<E1>()
        repl.quitOnException<E3>()
        ignoreException { repl.run() }
        assertLinesMatch(listOf(
            " $ ",
            "\\(a:E\\) \\\$E2\n"
        ), lines)
    }

    @Test
    fun `test stack dump`() = withTimeoutOneSecond {
        val (repl, lines) = IoRepl(listOf("a"))
        repl.enableDumpingStacktrace()
        class E1: RuntimeException()
        repl.registerCommand("a") {
            throw E1()
        }
        repl.run()
        assertLinesMatch(listOf(
            " $ ",
            "\\(a:E\\) .*\\\$E1\n",
            /* we're in a suspend function so it's safe to assume there's an invokeSuspend somewhere
             * in the stack trace
             */
            "\\(a:E\\) \tat .*\\.invokeSuspend\\(.*\n",
            ">> >>",
            " $ "
        ), lines
        )
    }

    @Test
    fun `test stack dump enabled via build`() = withTimeoutOneSecond {
        val (repl, lines) = IoRepl(listOf("a"))
        repl.build {
            enableDumpingStacktrace()
        }
        class E1: RuntimeException()
        repl.registerCommand("a") {
            throw E1()
        }
        repl.run()
        assertLinesMatch(listOf(
                " $ ",
                "\\(a:E\\) .*\\\$E1\n",
                /* we're in a suspend function so it's safe to assume there's an invokeSuspend somewhere
                 * in the stack trace
                 */
                "\\(a:E\\) \tat .*\\.invokeSuspend\\(.*\n",
                ">> >>",
                " $ "
            ), lines
        )
    }

    @Test
    fun `test stack dump filtering`() = withTimeoutOneSecond {
        val (repl, lines) = IoRepl(listOf("a"))
        repl.enableDumpingStacktrace()
        open class E1: RuntimeException()
        class E2: E1()
        repl.registerCommand("a") {
            throw E2()
        }
        repl.filterFromStacktrace<E1>()
        repl.run()
        assertLinesMatch(
            listOf(
                " $ ",
                "\\(a:E\\) .*\\\$E2\n",
                " $ "
            ), lines
        )
    }

    @Test
    fun `test stack dump filtering via build`() = withTimeoutOneSecond {
        val (repl, lines) = IoRepl(listOf("a"))
        repl.build {
            enableDumpingStacktrace()
            open class E1 : RuntimeException()
            class E2 : E1()
            registerCommand("a") {
                throw E2()
            }
            filterFromStacktrace<E1>()
        }
        repl.run()
        assertLinesMatch(
            listOf(
                " $ ",
                "\\(a:E\\) .*\\\$E2\n",
                " $ "
            ), lines
        )
    }

    @Test
    fun `test stack dump filtering invalid argument`() = withTimeoutOneSecond {
        val repl = NoopRepl()
        class E1: RuntimeException()
        repl.filterFromStacktrace<E1>()
        assertThrowsWithMessage<IllegalArgumentException>(".*class .*\\\$E1 already specified") {
            repl.filterFromStacktrace<E1>()
        }
    }

    /* this test case tests the branch of Repl.checkExceptionClass;
     * there is no need to doing the same for fatal exception filter
     */
    @Test
    fun `test stack dump filtering invalid argument via base`() = withTimeoutOneSecond {
        val repl = NoopRepl()
        open class E1: RuntimeException()
        class E2: E1()
        repl.filterFromStacktrace<E1>()
        assertThrowsWithMessage<IllegalArgumentException>(
            ".*class .*\\\$E2 already specified via class .*\\\$E1"
        ) {
            repl.filterFromStacktrace<E2>()
        }
    }

    /* this test case tests the branch of Repl.checkExceptionClass;
     * there is no need to doing the same for fatal exception filter
     */
    @Test
    fun `test stack dump filtering invalid argument special classes`() = withTimeoutOneSecond {
        val repl = NoopRepl()
        assertThrowsWithMessage<IllegalArgumentException>(".*has special handling") {
            repl.filterFromStacktrace<CancellationException>()
        }
        assertThrowsWithMessage<IllegalArgumentException>(".*has special handling") {
            repl.filterFromStacktrace<EOFException>()
        }
    }

    @Test
    fun `test stack dump filtering invalid state`() = withTimeoutOneSecond {
        val repl = NoopRepl()
        class E1: RuntimeException()
        repl.filterFromStacktrace<E1>()
        assertThrowsWithMessage<IllegalArgumentException>(
            "stack trace filter must be empty if stacktrace dump is disabled"
        ) {
            repl.run()
        }
    }

    @Test
    fun `test invalid command`() = withTimeoutOneSecond {
        val (repl, lines) = IoRepl(listOf("aaa"), null)
        repl.run()
        assertLinesMatch(lines(
            "(E) unrecognized command: aaa",
        ), lines)
    }

    private companion object {
        @JvmStatic
        @BeforeAll
        fun initLogging() {
            maximizeLoggingLevelForTesting()
        }

        val noopProducer = SendChannel<String>::noop
    }
}
