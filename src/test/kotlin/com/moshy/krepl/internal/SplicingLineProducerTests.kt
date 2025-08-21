package com.moshy.krepl.internal

import com.moshy.krepl.test_internal.withTimeoutOneSecond
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class SplicingLineProducerTests {
    @Test
    fun `test from string stream`() = withTimeoutOneSecond {
        val stream1 = produce {
            send("1")
            send("2")
            send("3")
            close()
        }
        val splicerChannel = Channel<List<String>>(1)
        val spliceProducer = splicingLineProducer(splicerChannel, stream1)
        val splicedChannel = Channel<String>()

        val lines = mutableListOf<String>()
        val splicerJob = launch { spliceProducer(splicedChannel) }
        lines.add(splicedChannel.receive())
        splicerChannel.send(listOf("a", "b"))
        (0..<3).forEach { _ ->
            lines.add(splicedChannel.receive())
        }
        splicerChannel.send(listOf("c", "d"))
        (0..<3).forEach { _ ->
            lines.add(splicedChannel.receive())
        }
        splicerChannel.close()
        splicerJob.join()
        assertEquals(listOf("1", "a", "b", "2", "c", "d", "3"), lines)
    }

    @Test
    fun `test exception propagation`() = withTimeoutOneSecond {
        class RE : RuntimeException()
        val stream1 = produce<String> {
            close(RE())
        }
        val splicerChannel = Channel<List<String>>(1)
        val spliceProducer = splicingLineProducer(splicerChannel, stream1)
        val splicedChannel = Channel<String>()

        launch { spliceProducer(splicedChannel) }
        assertThrows<RE> { splicedChannel.receive() }
        currentCoroutineContext().cancelChildren()
    }
}
