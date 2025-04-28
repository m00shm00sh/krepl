package com.moshy.krepl.internal

import com.moshy.krepl.Output
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlin.coroutines.cancellation.CancellationException

/** Non-cancellable ReceiveChannel. */
internal class NoncancellableInputReceiveChannel(
    private val ch: ReceiveChannel<String>
): ReceiveChannel<String> by ch {
    override fun cancel(cause: CancellationException?) = uoe()
    @Deprecated("", level = DeprecationLevel.HIDDEN)
    override fun cancel(cause: Throwable?): Boolean = uoe()
}

/** Non-cancellable SendChannel. */
internal class NoncloseableOutputSendChannel(
    private val ch: SendChannel<Output>
): SendChannel<Output> by ch {
    override fun close(cause: Throwable?): Boolean = uoe()
    override fun invokeOnClose(handler: (cause: Throwable?) -> Unit) = uoe()
}
