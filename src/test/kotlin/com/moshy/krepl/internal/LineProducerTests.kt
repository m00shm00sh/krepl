package com.moshy.krepl.internal

import com.moshy.krepl.lines
import com.moshy.krepl.test_internal.assertMatches
import com.moshy.krepl.test_internal.stream
import com.moshy.krepl.test_internal.throwingInputStream
import com.moshy.krepl.test_internal.withTimeoutOneSecond
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals

class LineProducerTests {
    @Test
    fun `test from string stream`() = withTimeoutOneSecond {
        val stream = """
            a
            b
        """.trimIndent().stream()
        val (consumerSupplier, lines) = outputCollector()
        val channel = Channel<String>()
        val channelReadJob = launch { consumerSupplier(channel) }
        val streamReadJob = launch { lineProducer(stream.reader())(channel) }
        streamReadJob.join()
        channelReadJob.join()
        assertEquals(lines("a", "b"), lines)
    }

    @Test
    fun `test with exceptions`() = withTimeoutOneSecond {
        val stream = throwingInputStream().reader()
        val lines = mutableListOf<String>()
        val channel = Channel<String>()
        var caught: IOException? = null
        val channelReadJob = launch(CoroutineName("Channel-Reader")) {
            try {
                for (line in channel)
                    lines.add(line)
            } catch (e: IOException) {
                caught = e
            }
        }
        val streamReadJob = launch { lineProducer(stream)(channel) }
        streamReadJob.join()
        channelReadJob.join()
        assertMatches(".*\\.IOE: Throwing!", caught?.toString() ?: "")
    }
}

private fun outputReader(mLines: MutableList<String>? = null): suspend ReceiveChannel<String>.() -> Unit = {
    val ch = this
    withContext(CoroutineName("Output-Channel-Reader")) {
        for (line in ch) {
            when {
                mLines == null -> { }
                else -> { mLines.add(line + "\n") }
            }
        }
    }
}

private data class OutputCollector(
    val readerSupplier: suspend ReceiveChannel<String>.() -> Unit,
    val collectedLines: List<String>
)

private fun outputCollector(): OutputCollector =
    mutableListOf<String>().run {
        OutputCollector(outputReader(this), this)
    }
