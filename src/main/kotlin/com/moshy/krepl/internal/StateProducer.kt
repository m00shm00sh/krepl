package com.moshy.krepl.internal

import com.moshy.krepl.InputReceiveChannel
import com.moshy.krepl.OutputSendChannel
import com.moshy.krepl.State

/** Convert line state and IO channels to callback state, where the channels are wrapped
 * with a non-closable delegate.
 *
 * @see TokenizedCommand
 * @see NoncancellableInputReceiveChannel
 * @see NoncloseableOutputSendChannel
 * @see State
 */
internal fun makeState(t: TokenizedCommand, i: InputReceiveChannel, o: OutputSendChannel): State =
    State(
        t.pos,
        t.kw,
        NoncancellableInputReceiveChannel(i),
        NoncloseableOutputSendChannel(o)
    )