package com.moshy.krepl

import com.moshy.krepl.test_internal.assertBlockTimesOut
import com.moshy.krepl.test_internal.maximizeLoggingLevelForTesting
import com.moshy.krepl.test_internal.withTimeoutOneSecond
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.yield
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/* Testing pertaining to concurrent modification.
 */
class ReplConcurrentModificationTests {
    @Test
    fun `test run lock for non-exception parts`() = withTimeoutOneSecond {
        val repl = NoopRepl()
        val job = launch { repl.run() }
        yield()
        supervisorScope {
            assertBlockTimesOut(10L, "registerCommand") {
                repl.registerCommand("") { }
            }
            assertBlockTimesOut(10L, "atExit") {
                repl.atExit { }
            }
            assertBlockTimesOut(10L) {
                repl.run()
            }
            assertBlockTimesOut(10L) {
                repl.build { }
            }
        }
        job.cancelAndJoin()
    }

    /* it may be tricky to get Repl.run to yield back to us inside the exception handler
     * but these functions should only need to be locked when inside run's exception handler
     */
    @Test
    fun `test run lock for exception parts`() = withTimeoutOneSecond {
        val repl = NoopRepl()
        val job = launch { repl.run() }
        yield()
        supervisorScope {
            assertBlockTimesOut(10L, "fatal exception filter") {
                repl.quitOnException<IllegalArgumentException>()
            }
            assertBlockTimesOut(10L, "verbose exception filter") {
                repl.filterFromStacktrace<IllegalArgumentException>()
            }
            assertBlockTimesOut(10L, "enable-stacktrace") {
                repl.enableDumpingStacktrace()
            }
            assertBlockTimesOut(10L, "disable-stacktrace") {
                repl.disableDumpingStacktrace()
            }
        }
        job.cancelAndJoin()
    }

    private companion object {
        @JvmStatic
        @BeforeAll
        fun initLogging() {
            maximizeLoggingLevelForTesting()
        }
    }
}
