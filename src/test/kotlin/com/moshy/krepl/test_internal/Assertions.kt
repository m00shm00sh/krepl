package com.moshy.krepl.test_internal

import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.assertThrows
import java.util.regex.PatternSyntaxException
import kotlin.test.fail

internal inline fun <reified E: Throwable> assertThrowsWithMessage(
    @Language("RegExp") exceptionMessage: String,
    executable: () -> Unit
) {
    assertMatches(exceptionMessage, assertThrows<E>(executable).message ?: "")
}

internal fun assertMatches(@Language("RegExp") expectedPattern: String, actual: String) {
    if (!actual.matches(expectedPattern))
        fail("expected exception message: `$expectedPattern`\n\tbut got: `$actual`")
}

/* This is same flavor of string matching as Junit assertLinesMatch, including the lack of caching regex compilations,
 * which performs the following three, in order of computational expense:
 * (1) simple string match
 * (2) substring match
 * (3) regex match
 * (Junit assertLinesMatch does (2) via (3) but we do a substring scan to avoid compiling an unnecessary regex)
 */
private fun String.matches(@Language("RegExp") pattern: String) =
     try {
        this == pattern ||
        contains(pattern) ||
        matches(Regex(pattern))
    } catch (_: PatternSyntaxException) {
        false
    }