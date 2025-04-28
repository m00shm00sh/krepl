package com.moshy.krepl.internal

import com.moshy.krepl.Output
import com.moshy.krepl.OutputConsumer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.Blocking
import java.io.IOException
import java.io.Writer

/** Creates a consumer that prints a line to [System.out].
 *
 * When a received string ends with \n\b, no newline is printed.
 *
 * Receiving a cancellation exception quietly quits the consumer.
 * Non-cancellation exceptions are propagated via channel cancellation.
 *
 * This producer is interruptible.
 *
 * The default buffering mode in use is line by line for prompt cancellation if IOException occurs.
 * [autoFlush] can be set to false for better performance when writing to file, in which case [DONT_PRINT_NEWLINE]
 * must be the string terminator for a flush to occur.
 */
internal fun lineConsumer(writer: Writer, autoFlush: Boolean = true): OutputConsumer =
    {
        @Blocking
        fun ensureWrite(w: Writer, line: String, forceFlush: Boolean = false) {
            if (line.isNotEmpty())
                w.write(line)
            if (autoFlush || forceFlush)
                w.flush()
        }

        val channel = this
        withContext(JvmIODispatcher + CoroutineName("IO-Write-Consumer")) {
            // is use{} necessary if we're already flushing after every write?
            writer.use {
                try {
                    for (line in channel) {
                        when (line) {
                            is Output.Heartbeat -> {}
                            is Output.Line -> {
                                val s = line.value
                                logger.trace("line of {} chars", s.length)
                                runInterruptible { ensureWrite(it, s + NEW_LINE) }
                            }
                            is Output.Nonline -> {
                                val s = line.value
                                logger.trace("non-line of {} chars", s.length)
                                runInterruptible { ensureWrite(it, s, true) }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    logger.debug("cancellation received")
                    cancel(e)
                } catch (e: IOException) {
                    logger.debug("IO exception received: {}", object {
                        override fun toString(): String = e.toString()
                    })
                    val canceller = CancellationException("IO exception: $e", e)
                    channel.cancel(canceller)
                    cancel(canceller)
                }
            }
        }
    }

private val logger by lazy { logger("Repl.i.LineConsumer") }
private val NEW_LINE = System.lineSeparator()