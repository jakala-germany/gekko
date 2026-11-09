package com.jakala.gekko.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real processes: what the host layer does with arguments, input, environment, output and the time limit. */
class ProcessTest {
    @Test
    fun aProgramThatIsNotThereIsAnExitCode127WithTheReason() {
        val result = runProcess(listOf("gekko-no-such-program-anywhere", "--version"))

        assertEquals(127, result.exitCode)
        assertTrue(result.text.startsWith("cannot run 'gekko-no-such-program-anywhere'"), result.text)
    }

    @Test
    fun theOutputAndTheExitCodeComeBack() {
        val result = runProcess(TestPrograms.echo("hello world"))

        assertEquals(0, result.exitCode)
        assertEquals("hello world", result.text)
    }

    @Test
    fun theInputReachesTheProgramAndNothingElseDoes() {
        val result = runProcess(TestPrograms.cat(), stdin = "from the input".encodeToByteArray())

        assertEquals("from the input", result.text)
    }

    @Test
    fun aProgramWithoutInputSeesAnEmptyOne() {
        val result = runProcess(TestPrograms.cat())

        assertEquals("", result.text)
    }

    @Test
    fun aBigInputAndAnAnswerToItDoNotBlockEachOther() {
        val line = "x".repeat(99) + "\n"
        val input = line.repeat(20_000)

        val result = runProcess(TestPrograms.cat(), stdin = input.encodeToByteArray())

        // findstr on Windows ends its lines with CR LF
        assertEquals(input.trimEnd(), result.stdout.decodeToString().replace("\r", "").trimEnd())
    }

    @Test
    fun aProgramThatFailsReportsItsExitCode() {
        val command = if (isWindowsHost) listOf("cmd", "/c", "exit 7") else listOf("sh", "-c", "exit 7")

        assertEquals(7, runProcess(command).exitCode)
    }

    @Test
    fun theEnvironmentIsAddedForTheProgramOnly() {
        val command =
            if (isWindowsHost) listOf("cmd", "/c", "echo %GEKKO_TEST_VALUE%")
            else listOf("sh", "-c", "printf %s \"\$GEKKO_TEST_VALUE\"")

        val result = runProcess(command, environment = mapOf("GEKKO_TEST_VALUE" to "some value"))

        assertEquals("some value", result.text)
        assertEquals(null, env("GEKKO_TEST_VALUE"))
    }

    @Test
    fun argumentsArriveExactlyAsTheyWereGiven() {
        // Spaces, quotes, backslashes and a trailing backslash are what a command line on Windows gets wrong
        val tricky = listOf("a b", "say \"hi\"", "back\\slash", "ends with\\", "100%", "semi;colon&and|pipe")
        val directory = createPrivateDirectory("gekko-args-test")
        try {
            val dump =
                if (isWindowsHost) {
                    writeTextFile(
                        "$directory/dump.ps1",
                        "foreach (\$a in \$args) { [Console]::Out.WriteLine('[' + \$a + ']') }",
                    )
                    listOf("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "$directory/dump.ps1")
                } else {
                    listOf("sh", "-c", "for a in \"\$@\"; do printf '[%s]\\n' \"\$a\"; done", "sh")
                }

            val result = runProcess(dump + tricky)

            assertEquals(tricky.map { "[$it]" }, result.text.lines().map { it.trimEnd('\r') })
        } finally {
            removeRecursively(directory)
        }
    }

    @Test
    fun standardErrorIsMergedOrDropped() {
        if (isWindowsHost) {
            val merged = runProcess(listOf("cmd", "/c", "echo out& echo err 1>&2"))
            assertTrue(merged.text.contains("err"), merged.text)
            val dropped = runProcess(listOf("cmd", "/c", "echo out& echo err 1>&2"), mergeStderr = false)
            assertFalse(dropped.text.contains("err"), dropped.text)
            return
        }
        val command = listOf("sh", "-c", "printf out; printf err >&2")

        assertEquals("outerr", runProcess(command).text)
        assertEquals("out", runProcess(command, mergeStderr = false).text)
    }

    @Test
    fun theProgramRunsInTheWorkingDirectoryItIsGiven() {
        val directory = createPrivateDirectory("gekko-cwd-test")
        try {
            val command = if (isWindowsHost) listOf("cmd", "/c", "cd") else listOf("pwd")

            val printed = runProcess(command, workingDirectory = directory).text

            // /tmp is a link on macOS, so compare the last part only
            assertEquals(directory.substringAfterLast('/'), slashes(printed).substringAfterLast('/'))
        } finally {
            removeRecursively(directory)
        }
    }

    @Test
    fun aProgramThatRunsTooLongIsStopped() {
        val started = nowMillis()

        val failure =
            assertFailsWith<InfrastructureException> { runProcess(TestPrograms.sleep(30), timeoutSeconds = 1) }

        assertTrue(failure.message.orEmpty().endsWith("did not finish within 1s"), failure.message)
        assertTrue(nowMillis() - started < 15_000, "the program was not killed")
    }

    @Test
    fun aProgramThatLeavesSomethingBehindDoesNotHoldTheCallUp() {
        val command =
            if (isWindowsHost) listOf("cmd", "/c", "start /b ping -n 20 127.0.0.1 & echo done")
            else listOf("sh", "-c", "sleep 20 & echo done")
        val started = nowMillis()

        val result = runProcess(command)

        assertTrue(result.text.contains("done"), result.text)
        assertTrue(nowMillis() - started < 15_000, "the call waited for what the program started")
    }

    @Test
    fun aLotOfOutputIsCollectedCompletely() {
        val command =
            if (isWindowsHost) listOf("cmd", "/c", "for /l %i in (1,1,20000) do @echo line %i")
            else listOf("sh", "-c", "i=0; while [ \$i -lt 20000 ]; do echo line \$i; i=\$((i+1)); done")

        val lines = runProcess(command).text.lines()

        assertEquals(20_000, lines.size)
    }

    @Test
    fun aDetachedProgramWritesToItsLogAndOutlivesTheCaller() {
        val directory = createPrivateDirectory("gekko-detached-test")
        val log = "$directory/out.log"
        try {
            val command = if (isWindowsHost) listOf("cmd", "/c", "echo started") else listOf("sh", "-c", "echo started")

            assertTrue(spawnDetached(command, mapOf("GEKKO_TEST_VALUE" to "x"), log))

            val deadline = nowMillis() + 10_000
            while (nowMillis() < deadline && !(fileExists(log) && readTextFile(log).contains("started"))) {
                runProcess(
                    TestPrograms.sleep(1).let {
                        if (isWindowsHost) listOf("ping", "-n", "1", "127.0.0.1") else listOf("sleep", "0.2")
                    }
                )
            }
            assertTrue(readTextFile(log).contains("started"))
        } finally {
            removeRecursively(directory)
        }
    }

    @Test
    fun aProcessIsAliveWhileItRuns() {
        assertTrue(isProcessAlive(currentProcessId()))
        assertFalse(isProcessAlive(0))
        assertFalse(isProcessAlive(-5))
    }

    @Test
    fun theTemporaryDirectoryAndTheHomeAreUsablePaths() {
        assertTrue(tempDirectory().isNotEmpty())
        assertFalse(tempDirectory().endsWith("/"))
        assertFalse(tempDirectory().contains('\\'))
        assertTrue(fileExists(tempDirectory()))
        assertFalse(currentDirectory().contains('\\'))
    }

    @Test
    fun secureRandomNumbersDiffer() {
        val first = randomHex(16)

        assertEquals(32, first.length)
        assertTrue(first != randomHex(16))
    }

    @Test
    fun theTimeZoneOffsetIsSane() {
        assertTrue(hostUtcOffsetMinutes() in -14 * 60..14 * 60)
    }
}
