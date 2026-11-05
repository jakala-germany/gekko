package com.jakala.gekko.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProcessTimeoutTest {
    @Test
    fun aCallThatHangsOnceIsAskedAgain() {
        val marker = "/tmp/gekko-retry-test-${nowMillis()}"
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
            runProcess(listOf("rm", "-f", marker))
        }
    }

    @Test
    fun aCallThatHangsTwiceFails() {
        val failure =
            assertFailsWith<InfrastructureException> { runProcessRetrying(listOf("sleep", "10"), timeoutSeconds = 1) }

        assertEquals("sleep did not finish within 1s", failure.message)
    }

    @Test
    fun aCallThatAnswersIsNotRepeated() {
        val result = runProcessRetrying(listOf("echo", "once"), timeoutSeconds = 5)

        assertEquals("once", result.text.trim())
    }
}
