package com.moshy.krepl

import com.moshy.krepl.test_internal.stream
import com.moshy.krepl.test_internal.withTimeoutOneSecond
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals

/* Verifies output of quit and help built-ins. */
class ReplStreamTests {
    @Test
    fun `test stream`() = withTimeoutOneSecond {
        val inputProducerStream = "quit".stream()
        val outputConsumerStream = ByteArrayOutputStream()
        val repl = Repl(inputProducerStream, outputConsumerStream)
        repl.run()
        val lines = outputConsumerStream.toString()
        assertEquals(" $ ", lines)
    }
}
