package com.moshy.krepl.internal

import com.moshy.krepl.Output
import com.moshy.krepl.test_internal.withTimeoutOneSecond
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ChannelTests {
    @Test
    fun `test SendChannel`() = withTimeoutOneSecond {
        val channel = Channel<Output>()
        val channelReader = launch { channel.receive() }
        assertThrows<UnsupportedOperationException> {
            NoncloseableOutputSendChannel(channel).close(CancellationException())
        }
        channelReader.cancelAndJoin()
    }

    @Test
    fun `test SendChannel invoker`() = withTimeoutOneSecond {
        val channel = Channel<Output>()
        val channelReader = launch { channel.receive() }
        assertThrows<UnsupportedOperationException> {
            NoncloseableOutputSendChannel(channel).invokeOnClose {  }
        }
        channelReader.cancelAndJoin()
    }

    @Test
    fun `test ReceiveChannel`() = withTimeoutOneSecond {
        val channel = Channel<String>()
        val channelWriter = launch { channel.send("") }
        assertThrows<UnsupportedOperationException> {
            NoncancellableInputReceiveChannel(channel).cancel(CancellationException())
        }
        channelWriter.cancelAndJoin()
    }
}
