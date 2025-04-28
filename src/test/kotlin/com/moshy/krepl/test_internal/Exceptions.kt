package com.moshy.krepl.test_internal

internal inline fun ignoreException(block: () -> Unit) =
    try {
        block()
    } catch (_: Throwable) {
    }
