package com.moshy.krepl.internal

import com.moshy.krepl.Output
import kotlinx.coroutines.channels.SendChannel
import org.slf4j.Logger

/** Heartbeat SendChannel. [Output.Heartbeat] is sent after every line to check for stored cancellation. */
internal class HeartbeatOutputSendChannel(
    private val ch: SendChannel<Output>,
    private val logger: Logger? = null,
): SendChannel<Output> by ch {
    override suspend fun send(element: Output) {
        ch.send(element)
        logger?.apply {
            if (isTraceEnabled) {
                val what = when (element) {
                    is Output.Line -> "line of ${element.value.length} chars"
                    is Output.Nonline -> "nonline of ${element.value.length} chars"
                    else -> "heartbeat"
                }
                trace("sent {}", what)
            }
        }
        ch.send(Output.Heartbeat)
        logger?.apply {
            trace("sent heartbeat")
        }
    }
}
