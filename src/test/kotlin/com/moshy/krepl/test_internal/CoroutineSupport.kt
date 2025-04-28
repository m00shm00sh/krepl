package com.moshy.krepl.test_internal

import com.moshy.krepl.InputProducer
import com.moshy.krepl.Output
import com.moshy.krepl.OutputConsumer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal fun inputWriter(lines: List<String>): InputProducer = {
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

internal fun outputReader(mLines: MutableList<String>? = null): OutputConsumer = {
    val ch = this
    withContext(CoroutineName("Output-Channel-Reader")) {
        for (line in ch) {
            when {
                mLines == null -> { }
                line is Output.Heartbeat -> { }
                line is Output.Line -> { mLines.add("${line.value}\n") }
                line is Output.Nonline -> { mLines.add(line.value) }
            }
        }
    }
}

internal data class OutputCollector(
    val readerSupplier: OutputConsumer,
    val collectedLines: List<String>
)

internal fun outputCollector(): OutputCollector =
    mutableListOf<String>().run {
        OutputCollector(outputReader(this), this)
    }

internal suspend fun SendChannel<String>.noop() { }
internal suspend fun ReceiveChannel<Output>.noop() { }

internal fun withTimeoutOneSecond(block: suspend TestScope.() -> Unit) =
    runTest(timeout = 1.0.seconds) { block() }

// Forces a delay by switching to default CoroutineContext. Used to convert delay from a yield back to a wait.
internal suspend fun hardDelay(millis: Long) = withContext(Dispatchers.Default) { delay(millis) }

internal suspend fun assertBlockTimesOut(millis: Long, message: String? = null, block: suspend () -> Unit) =
    try {
        withTimeout(10.milliseconds) {
            block()
            fail(message ?: "no timeout")
        }
    } catch (_: TimeoutCancellationException) {
    }

