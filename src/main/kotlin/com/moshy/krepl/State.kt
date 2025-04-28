package com.moshy.krepl

import kotlinx.coroutines.channels.Channel

/** Line state for command.
 *
 * Note: input and output channels are [Channel]s with closing behavior disabled.
 *       Throw an exception if closing a channel is desired.
 * */
data class State(
    val positionalOrLines: List<String>,
    val keywords: Map<String, String>,
    val inputChannel: InputReceiveChannel,
    val outputChannel: OutputSendChannel,
)