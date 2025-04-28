package com.moshy.krepl.internal

internal data class TokenizedCommand(
    val cmd: String,
    val pos: List<String>,
    val kw: Map<String, String>,
)

/** Tokenize a line to command, positional, and keyword arguments. If a non-command argument starts with '=',
 * the rest of it is a positional token.
 */
internal fun String.tokenizeLine(): TokenizedCommand {
    val toks = splitWithEscape()
    val cmd = toks.removeFirst().lowercase()
    val pos = mutableListOf<String>()
    val kw = mutableMapOf<String, String>()
    for (t in toks) {
        val posEq = t.indexOf('=')
        if (posEq < 0) {
            pos += t
            continue
        }
        // start token with '=' to exclude it from kw processing
        if (posEq < 1) {
            pos += t.substring(1)
            continue
        }
        val k = t.substring(0, posEq)
        val v = t.substring(posEq+1)
        require(kw.putIfAbsent(k, v) == null) {
            "key $k specified twice"
        }
    }
    return TokenizedCommand(cmd, pos, kw)
}

private fun String.splitWithEscape(): MutableList<String> {
    var inQuote = false
    var skip = 0
    val buf = StringBuilder()
    return mutableListOf<String>().apply {
        fun clearBuf() {
            if (buf.isNotEmpty()) {
                add(buf.toString())
                buf.clear()
            }
        }

        // slow and simple state machine
        for (ch in this@splitWithEscape) {
            when {
                skip > 0 -> {
                    if (ch != QUOT)
                        buf.append(ESC)
                    buf.append(ch)
                    --skip
                }
                ch == '\\' -> {
                    skip = 1
                }
                ch == QUOT -> { inQuote = !inQuote }
                ch.isWhitespace() && inQuote -> { buf.append(ch) }
                ch.isWhitespace() && !inQuote -> { clearBuf() }
                !ch.isWhitespace() -> { buf.append(ch) }
            }
        }
        clearBuf()
    }
}

private const val QUOT = '"'
private const val ESC = '\\'
