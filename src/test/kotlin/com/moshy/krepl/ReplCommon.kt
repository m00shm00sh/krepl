package com.moshy.krepl

import com.moshy.krepl.test_internal.inputWriter
import com.moshy.krepl.test_internal.noop
import com.moshy.krepl.test_internal.outputCollector
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel

/*
 * Factory functions for the Repl states that will be of use in testing
 */
@Suppress("TestFunctionName")
internal fun NoopRepl() = Repl(SendChannel<String>::noop, ReceiveChannel<Output>::noop)

@Suppress("TestFunctionName")
internal fun IoRepl(inputLines: List<String>, promptSupplier: (() -> String)? = { "" }) =
    outputCollector().run {
        Repl(inputWriter(inputLines), readerSupplier, promptSupplier) to collectedLines
    }

/** Terminates each line for more precise output checking. */
internal fun lines(vararg s: String) =
    s.map { "$it\n" }