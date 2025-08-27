package com.moshy.krepl

import com.moshy.krepl.test_internal.maximizeLoggingLevelForTesting
import com.moshy.krepl.test_internal.withTimeoutOneSecond
import org.junit.jupiter.api.Assertions.assertLinesMatch
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/* Tests paginator functionality.
 */
class ReplPaginatorTests {
    @Test
    fun `test pagination`() = withTimeoutOneSecond {
        val data = listOf("a", "b", "c", "d")
        val (repl, lines) = IoRepl(listOf(
            "a",
            "c 1",
            "p",
            "c 4",
            "n",
            "q"
        ))
        repl["a"] {
            handler = { (_, _, _, o) ->
                paginate(data, o, 2)
            }
        }
        repl.run()
        assertLinesMatch(
            listOf(
                " $ "
            ) + lines(
                "a",
                "b",
            ) + listOf(
                ":\"paginate 2/4@2\" $ ",
                ":\"paginate 2/4@1\" $ "
            ) + lines(
                "b",
            ) + listOf(
                ":\"paginate 2/4@1\" $ ",
                ":\"paginate 2/4@4\" $ "
            ) + lines(
            "c",
            "d"
            ) + listOf(
                ":\"paginate <END>/4@4\" $ ",
                " $ "
            )
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
