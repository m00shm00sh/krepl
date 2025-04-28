package com.moshy.krepl.test_internal

import com.moshy.krepl.Output
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel

private class Counter {
    private val _c = atomic(0L)
    val value
        get() = _c.value

    fun inc() {
        _c.update { it + 1L }
    }
}
internal class ConsumeCounter {
    private val c = Counter()
    val value
        get() = c.value

    fun functionFactory(): suspend ReceiveChannel<Output>.() -> Unit = {
        while (true) {
            c.inc()
            receive()
        }
    }
}
internal class ProduceCounter {
    private val c = Counter()
    val value
        get() = c.value

    fun functionFactory(): suspend SendChannel<String>.() -> Unit = {
        while (true) {
            c.inc()
            send("")
        }
    }
}
