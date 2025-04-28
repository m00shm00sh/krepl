package com.moshy.krepl.internal

import org.slf4j.LoggerFactory
import kotlin.reflect.jvm.jvmName

internal fun logger(forName: String) =
    LoggerFactory.getLogger(forName)
internal inline fun <reified T: Any> logger() =
    LoggerFactory.getLogger(T::class.jvmName)
