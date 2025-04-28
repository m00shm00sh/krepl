package com.moshy.krepl.internal

import com.moshy.krepl.InputProducer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.Reader

/** Creates a producer that reads a line from [reader] and sends it to a channel.
 */
internal fun lineProducer(reader: Reader): InputProducer =
    {
        val channel = this
        withContext(JvmIODispatcher + CoroutineName("IO-Read-Producer")) {
            reader.buffered().use {
                try {
                    while (true) {
                        val line: String? = runInterruptible { it.readLine() }
                        if (line == null) {
                            logger.trace("EOF")
                            channel.close()
                            return@use
                        }
                        logger.trace("line of {} chars", line.length)
                        channel.send(line)
                    }
                } catch (e: CancellationException) { // channel being closed from consumer or upstream
                    logger.debug("cancellation received")
                    cancel(e)
                } catch (e: IOException) {
                    logger.debug("IO exception received: {}", object { override fun toString(): String = e.toString() })
                    /* send the exception directly to the channel because it will wrap it in a
                     * CancellationException with cause
                     */
                    channel.close(e)
                }
            }
        }
    }

private val logger by lazy { logger("Repl.i.LineProducer") }
