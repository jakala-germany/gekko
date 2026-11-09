package com.jakala.gekko.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** How a program is started on Windows is pure text work, so it is tested on every host. */
class ProcessLaunchTest {
    private val pathExt = ".COM;.EXE;.BAT;.CMD"

    @Test
    fun anArgumentWithoutSpecialCharactersIsLeftAlone() {
        assertEquals("--prefix", windowsQuote("--prefix"))
        assertEquals("C:\\Tools\\x.js", windowsQuote("C:\\Tools\\x.js"))
    }

    @Test
    fun anArgumentIsQuotedSoThatTheProgramReadsItBack() {
        assertEquals("\"a b\"", windowsQuote("a b"))
        assertEquals("\"\"", windowsQuote(""))
        assertEquals("\"say \\\"hi\\\"\"", windowsQuote("say \"hi\""))
        // Backslashes only count in front of a quote; a trailing one is doubled so it cannot escape the closing quote
        assertEquals("\"ends with\\\\\"", windowsQuote("ends with\\"))
        assertEquals("\"C:\\Program Files\\x\\\\\"", windowsQuote("C:\\Program Files\\x\\"))
        assertEquals("\"a\\\\\\\"b c\"", windowsQuote("a\\\"b c"))
    }

    @Test
    fun aCommandLineIsItsQuotedArgumentsSeparatedBySpaces() {
        assertEquals(
            "adb.exe -s emulator-5554 shell \"input text 'a b'\"",
            windowsCommandLine(listOf("adb.exe", "-s", "emulator-5554", "shell", "input text 'a b'")),
        )
    }

    @Test
    fun aBatchArgumentIsAlwaysQuotedAndItsQuotesAreDoubled() {
        assertEquals("\"plain\"", batchArgument("plain"))
        assertEquals("\"a \"\"b\"\"\"", batchArgument("a \"b\""))
        assertEquals("\"x\\\\\"", batchArgument("x\\"))
    }

    @Test
    fun aPercentSignCannotStartAVariableInABatchArgument() {
        assertEquals("\"100%%cd:~,%\"", batchArgument("100%"))
        assertEquals("\"%%cd:~,%PATH%%cd:~,%\"", batchArgument("%PATH%"))
    }

    @Test
    fun aBatchArgumentWithALineBreakIsRefused() {
        assertFailsWith<IllegalArgumentException> { batchArgument("one\ntwo") }
        assertFailsWith<IllegalArgumentException> { batchArgument("nul\u0000") }
    }

    @Test
    fun aBatchFileRunsThroughCmdWithTheOuterQuotesThatSRemoves() {
        val line =
            batchCommandLine(
                "C:\\Windows\\System32\\cmd.exe",
                "C:\\Tools\\npm.cmd",
                listOf("install", "--prefix", "C:\\a b"),
            )

        assertEquals(
            "\"C:\\Windows\\System32\\cmd.exe\" /e:ON /v:OFF /d /s /c " +
                "\"\"C:\\Tools\\npm.cmd\" \"install\" \"--prefix\" \"C:\\a b\"\"",
            line,
        )
    }

    @Test
    fun aProgramIsFoundOnThePathThroughTheExtensionsOfPathext() {
        val files = setOf("C:\\tools\\adb.exe", "C:\\nodejs\\npm", "C:\\nodejs\\npm.cmd")
        val path = "C:\\missing;\"C:\\tools\\\";C:\\nodejs"

        assertEquals("C:\\tools\\adb.exe", resolveWindowsProgram("adb", path, pathExt) { it in files })
        assertEquals("C:\\nodejs\\npm.cmd", resolveWindowsProgram("npm", path, pathExt) { it in files })
        assertEquals("C:\\nodejs\\npm.cmd", resolveWindowsProgram("npm.cmd", path, pathExt) { it in files })
        assertNull(resolveWindowsProgram("claude", path, pathExt) { it in files })
    }

    @Test
    fun aProgramWithADirectoryIsNotSearchedOnThePath() {
        val files = setOf("D:\\bin\\gekko.exe", "C:\\other\\gekko.exe")

        assertEquals(
            "D:\\bin\\gekko.exe",
            resolveWindowsProgram("D:\\bin\\gekko", "C:\\other", pathExt) { it in files },
        )
        assertNull(resolveWindowsProgram("D:\\bin\\missing", "C:\\other", pathExt) { it in files })
    }

    @Test
    fun onlyWhatWindowsCanStartIsFound() {
        val files = setOf("C:\\x\\script.ps1", "C:\\x\\tool.vbs", "C:\\x\\tool.js")

        assertNull(resolveWindowsProgram("script", "C:\\x", ".PS1;.VBS;.JS;.EXE", { it in files }))
        assertNull(resolveWindowsProgram("script.ps1", "C:\\x", ".PS1;.EXE", { it in files }))
    }

    @Test
    fun theSearchOrderIsTheOrderOfPathext() {
        val files = setOf("C:\\x\\tool.cmd", "C:\\x\\tool.exe")

        assertEquals("C:\\x\\tool.exe", resolveWindowsProgram("tool", "C:\\x", ".EXE;.CMD") { it in files })
        assertEquals("C:\\x\\tool.cmd", resolveWindowsProgram("tool", "C:\\x", ".CMD;.EXE") { it in files })
    }

    @Test
    fun anExecutableIsStartedItselfWithItsFullPathAsFirstArgument() {
        val launch =
            planLaunch(listOf("adb", "-s", "a b"), true, { if (it == "PATH") "C:\\tools" else null }) {
                it == "C:\\tools\\adb.exe"
            }!!

        assertEquals("C:\\tools\\adb.exe", launch.application)
        assertEquals("C:\\tools\\adb.exe -s \"a b\"", launch.commandLine)
        assertEquals(listOf("C:\\tools\\adb.exe", "-s", "a b"), launch.argv)
    }

    @Test
    fun aCmdFileIsStartedThroughTheCommandInterpreter() {
        val environment = mapOf("PATH" to "C:\\nodejs", "ComSpec" to "C:\\Windows\\System32\\cmd.exe")

        val launch =
            planLaunch(listOf("claude", "-p", "--model", "haiku"), true, { environment[it] }) {
                it == "C:\\nodejs\\claude.cmd"
            }!!

        assertEquals("C:\\Windows\\System32\\cmd.exe", launch.application)
        assertEquals(
            "\"C:\\Windows\\System32\\cmd.exe\" /e:ON /v:OFF /d /s /c " +
                "\"\"C:\\nodejs\\claude.cmd\" \"-p\" \"--model\" \"haiku\"\"",
            launch.commandLine,
        )
    }

    @Test
    fun aProgramThatIsNotThereHasNoLaunch() {
        assertNull(planLaunch(listOf("nothing"), true, { null }) { false })
    }

    @Test
    fun aPosixHostStartsTheProgramAsItIs() {
        val launch = planLaunch(listOf("adb", "devices"), false, { null }) { false }!!

        assertEquals("adb", launch.application)
        assertEquals(listOf("adb", "devices"), launch.argv)
        assertNull(launch.commandLine)
    }

    @Test
    fun aCommandIsFoundOnAPosixPath() {
        val files = setOf("/usr/local/bin/claude", "/opt/x/tool")

        assertEquals(
            "/usr/local/bin/claude",
            findProgram("claude", false, "/bin::/usr/local/bin", null) { it in files },
        )
        assertEquals("/opt/x/tool", findProgram("/opt/x/tool", false, "/bin", null) { it in files })
        assertNull(findProgram("missing", false, "/bin:/usr/local/bin", null) { it in files })
        assertEquals("C:\\n\\claude.cmd", findProgram("claude", true, "C:\\n", pathExt) { it == "C:\\n\\claude.cmd" })
    }

    @Test
    fun aCurlConfigurationValueCannotBreakOutOfItsQuotes() {
        assertEquals("secret\\\" --output \\\\x", curlEscape("secret\" --output \\x"))
        assertEquals("ab", curlEscape("a\r\nb"))
    }

    @Test
    fun pathsAreWrittenWithForwardSlashesOnEveryHost() {
        assertEquals("a/b/c.yaml", slashes("a/b/c.yaml"))
        if (isWindowsHost) {
            assertEquals("C:/Work/gekko/home", slashes("C:\\Work\\gekko\\home"))
        } else {
            assertEquals("/tmp/a\\b", slashes("/tmp/a\\b"))
        }
    }
}
