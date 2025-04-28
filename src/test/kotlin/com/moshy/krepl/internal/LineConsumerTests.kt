package com.moshy.krepl.internal

import com.moshy.krepl.Output
import com.moshy.krepl.asLine
import com.moshy.krepl.asNonLine
import com.moshy.krepl.test_internal.assertMatches
import com.moshy.krepl.test_internal.inputWriter
import com.moshy.krepl.test_internal.throwingOutputStream
import com.moshy.krepl.test_internal.withTimeoutOneSecond
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals

class LineConsumerTests {
    @Test
    fun `test consumer`() = withTimeoutOneSecond {
        val newStream = ByteArrayOutputStream()
        val toSend = listOf("a".asLine(), "b".asNonLine(), "c".asLine())
        val channel = Channel<Output>()
        val channelWriteJob = launch { inputWriter(toSend)(channel) }
        val streamWriteJob = launch { lineConsumer(newStream.writer())(channel) }
        channelWriteJob.join()
        streamWriteJob.join()
        val received = newStream.toString()
        assertEquals("a\nbc\n", received)
    }

    @Test
    fun `test consumer exceptions`() = withTimeoutOneSecond {
        val channel = Channel<Output>()
        val toSend = listOf("a", "b", "c").map(String::asLine)
        var caught: CancellationException? = null
        val streamWriteJob = launch { lineConsumer(throwingOutputStream().writer())(channel) }
        val channelWriteJob = launch(CoroutineName("Channel-Writer")) {
            try {
                for (line in toSend)
                    channel.send(line)
                channel.close()
            } catch (e: CancellationException) {
                caught = e
            }
        }
        channelWriteJob.join()
        streamWriteJob.join()
        assertMatches("IO exception: .*\\.IOE: Throwing!", caught?.message ?: "")
    }
}

private fun inputWriter(lines: List<Output>): suspend SendChannel<Output>.() -> Unit = {
    val ch = this
    withContext(CoroutineName("Input-Channel-Writer")) {
        try {
            for (line in lines)
                ch.send(line)
            ch.close()
        } catch (t: CancellationException) {
            cancel(t)
        }
    }
}
