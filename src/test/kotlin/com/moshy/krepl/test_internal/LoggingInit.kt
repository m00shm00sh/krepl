package com.moshy.krepl.test_internal

import ch.qos.logback.classic.Level
import org.slf4j.Logger
import org.slf4j.LoggerFactory

internal fun maximizeLoggingLevelForTesting() {
    val rootLogger =
        LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
    rootLogger.level = Level.TRACE
}