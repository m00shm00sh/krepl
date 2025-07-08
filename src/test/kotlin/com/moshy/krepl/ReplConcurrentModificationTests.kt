package com.moshy.krepl

import com.moshy.krepl.test_internal.assertRunLockIsHeld
import com.moshy.krepl.test_internal.maximizeLoggingLevelForTesting
import com.moshy.krepl.test_internal.waitForRunSema
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors

/* Testing pertaining to concurrent modification.
 */
class ReplConcurrentModificationTests {
    @Test
    fun `test run lock for non-exception parts`() {
        val repl = NoopRepl()
        val t = Thread {
            val disp = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            runBlocking(disp) { repl.run() }
        }.apply { start() }
        repl.waitForRunSema()
        assertRunLockIsHeld("registerCommand") {
            repl.registerCommand("") { }
        }
        assertRunLockIsHeld("atExit") {
            repl.atExit { }
        }
        assertRunLockIsHeld {
            runBlocking {
                repl.run()
            }
        }
        assertRunLockIsHeld {
            repl.build { }
        }
        t.interrupt()
        t.join()
    }

    /* it may be tricky to get Repl.run to yield back to us inside the exception handler
     * but these functions should only need to be locked when inside run's exception handler
     */
    @Test
    fun `test run lock for exception parts`() {
        val repl = NoopRepl()
        val t = Thread {
            val disp = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            runBlocking(disp) {
                repl.run()
            }
        }.apply { start() }
        repl.waitForRunSema()
        assertRunLockIsHeld("fatal exception filter") {
            repl.quitOnException<IllegalArgumentException>()
        }
        assertRunLockIsHeld("verbose exception filter") {
            repl.filterFromStacktrace<IllegalArgumentException>()
        }
        assertRunLockIsHeld("enable-stacktrace") {
            repl.enableDumpingStacktrace()
        }
        assertRunLockIsHeld("disable-stacktrace") {
            repl.disableDumpingStacktrace()
        }
        t.interrupt()
        t.join()
    }

    private companion object {
        @JvmStatic
        @BeforeAll
        fun initLogging() {
            maximizeLoggingLevelForTesting()
        }
    }
}
