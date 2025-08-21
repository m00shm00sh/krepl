package com.moshy.krepl.internal

import com.moshy.krepl.InputProducer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.yield

/** Creates a producer that reads a line from [reader] and sends it to a channel.
 *
 * NOTE: [splice] channel should have a capacity of 1 if the same coroutine is used to send to [splice] and
 *       receive from the spliced channel
 */
internal fun splicingLineProducer(splice: ReceiveChannel<List<String>>, other: ReceiveChannel<String>): InputProducer =
    {
        try {
            while (true) {
                select {
                    splice.onReceive { lines ->
                        logger.trace("receive splice of {} lines", lines.size)
                        for (line in lines)
                            send(line)
                    }
                    other.onReceive { line ->
                        logger.trace("receive from other")
                        send(line)
                        /* force a suspension so other coroutine has a chance to splice lines and
                         * we don't read greedily from [other]
                         */
                        yield()
                    }
                }
            }
        } catch (e: CancellationException) {
            logger.debug("cancellation received")
            currentCoroutineContext().cancel(e)
        } catch (e: ClosedReceiveChannelException) {
            logger.debug("close received")
            close(e)
        } catch (e: Throwable) {
            logger.debug("exceptional close received")
            close(e)
        }
    }

private val logger by lazy { logger("Repl.i.SplicingLineProducer") }
