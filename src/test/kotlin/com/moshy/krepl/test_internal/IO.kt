package com.moshy.krepl.test_internal

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.Reader

internal fun String.stream(): Reader = toByteArray().let(::ByteArrayInputStream).reader()

private class ThrowingInputStream: InputStream() {
    override fun read(): Int = throw IOE()
}

private class ThrowingOutputStream: OutputStream() {
    override fun write(p0: Int) = throw IOE()
}

private class IOE: IOException("Throwing!")

internal fun throwingInputStream(): InputStream = ThrowingInputStream()
internal fun throwingOutputStream(): OutputStream = ThrowingOutputStream()
