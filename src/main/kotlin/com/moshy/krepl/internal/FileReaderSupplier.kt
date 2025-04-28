package com.moshy.krepl.internal

import java.io.File
import java.io.Reader

// there is zero benefit in coverage testing this because all it does is defer to runtime default
internal fun fileReader(name: String): Reader = File(name).reader()
