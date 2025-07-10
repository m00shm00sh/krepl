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
    fun `test run lock`() {
        val repl = NoopRepl()
        val t = Thread {
            val disp = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            runBlocking(disp) { repl.run() }
        }.apply { start() }
        repl.waitForRunSema()
        assertRunLockIsHeld {
            runBlocking {
                repl.run()
            }
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
