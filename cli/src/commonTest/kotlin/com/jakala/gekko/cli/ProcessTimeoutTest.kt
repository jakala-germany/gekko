package com.jakala.gekko.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProcessTimeoutTest {
    @Test
    fun aCallThatHangsOnceIsAskedAgain() {
        if (isWindowsHost) return
        val marker = "${tempDirectory()}/gekko-retry-test-${nowMillis()}"
        try {
            // The first call hangs (and is stopped by the time limit), the second one finds the marker and answers
            val result =
                runProcessRetrying(
                    listOf(
                        "sh",
                        "-c",
                        "if [ -e \"\$1\" ]; then echo again; else touch \"\$1\"; sleep 10; fi",
                        "sh",
                        marker,
                    ),
                    timeoutSeconds = 1,
                )

            assertEquals(0, result.exitCode)
            assertEquals("again", result.text.trim())
        } finally {
            removeRecursively(marker)
        }
    }

    @Test
    fun aCallThatHangsTwiceFails() {
        val hang = TestPrograms.sleep(10)
        val failure = assertFailsWith<InfrastructureException> { runProcessRetrying(hang, timeoutSeconds = 1) }

        assertEquals("${hang.first()} did not finish within 1s", failure.message)
    }

    @Test
    fun aCallThatAnswersIsNotRepeated() {
        val result = runProcessRetrying(TestPrograms.echo("once"), timeoutSeconds = 5)

        assertEquals("once", result.text.trim())
    }
}

/** Programs that every host has, for the tests that start real processes. */
internal object TestPrograms {
    fun sleep(seconds: Int): List<String> {
        return if (isWindowsHost) listOf("ping", "-n", "${seconds + 1}", "127.0.0.1") else listOf("sleep", "$seconds")
    }

    fun echo(text: String): List<String> {
        return if (isWindowsHost) listOf("cmd", "/c", "echo $text") else listOf("echo", text)
    }

    /** Prints what it reads on its standard input. */
    fun cat(): List<String> {
        return if (isWindowsHost) listOf("findstr", "^") else listOf("cat")
    }
}
