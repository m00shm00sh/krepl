package com.moshy.krepl.internal

import org.slf4j.LoggerFactory
import kotlin.reflect.jvm.jvmName

internal fun lazyString(messageSupplier: () -> String) =
    object { override fun toString() = messageSupplier() }