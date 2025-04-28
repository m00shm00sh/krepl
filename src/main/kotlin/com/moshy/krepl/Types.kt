package com.moshy.krepl

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlin.reflect.KProperty

typealias InputReceiveChannel = ReceiveChannel<String>
typealias OutputSendChannel = SendChannel<Output>

typealias InputProducer = suspend SendChannel<String>.() -> Unit
typealias OutputConsumer = suspend ReceiveChannel<Output>.() -> Unit

typealias PromptSupplier = () -> String

sealed interface Output {
    @JvmInline value class Line(val value: String): Output
    @JvmInline value class Nonline(val value: String): Output
    object Heartbeat: Output
}
fun String.asLine(): Output = Output.Line(this)
fun String.asNonLine(): Output = Output.Nonline(this)
