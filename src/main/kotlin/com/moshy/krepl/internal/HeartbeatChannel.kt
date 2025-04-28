package com.moshy.krepl.internal

import com.moshy.krepl.Output
import kotlinx.coroutines.channels.SendChannel

/** Heartbeat SendChannel. [Output.Heartbeat] is sent after every line to check for stored cancellation. */
internal class HeartbeatOutputSendChannel(
    private val ch: SendChannel<Output>
): SendChannel<Output> by ch {
    override suspend fun send(element: Output) {
        ch.send(element)
        ch.send(Output.Heartbeat)
    }
}
