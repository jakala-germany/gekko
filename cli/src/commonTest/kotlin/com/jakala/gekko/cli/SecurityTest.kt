package com.jakala.gekko.cli

import com.jakala.gekko.protocol.Bounds
import com.jakala.gekko.protocol.Command
import com.jakala.gekko.protocol.Response
import com.jakala.gekko.protocol.UiNode
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SecurityTest {
    @AfterTest
    fun clearMasker() {
        Masker.register(emptyList())
    }

    @Test
    fun aTestReadsOnlyTheVariablesGekkoYamlDeclares() {
        val access = EnvAccess(setOf("HOME"), emptyMap())
        assertTrue(Yamls.substitute("user: \"\${env.HOME}\"", emptyMap(), access).startsWith("user: \""))
        val error =
            assertFailsWith<IllegalArgumentException> { Yamls.substitute("x: \"\${env.PATH}\"", emptyMap(), access) }
        assertTrue(error.message.orEmpty().contains("declare PATH"))
        assertFailsWith<IllegalArgumentException> { Yamls.substitute("x: \"\${env.HOME}\"", emptyMap()) }
    }

    @Test
    fun theSecretsOfGekkoAndCiCannotBeDeclared() {
        for (name in listOf("GEKKO_LLM_API_KEY", "GITHUB_TOKEN", "OP_SERVICE_ACCOUNT_TOKEN", "ACTIONS_RUNTIME_TOKEN")) {
            assertFailsWith<IllegalArgumentException>(name) { EnvAccess(setOf(name), emptyMap()) }
        }
        assertFailsWith<IllegalArgumentException> { EnvAccess(setOf("not a name"), emptyMap()) }
    }

    @Test
    fun aDotEnvFileFillsWhatTheProcessDoesNotHave() {
        val dotEnv =
            EnvAccess.parseDotEnv(
                "# comment\nTEST_USER=ada\nexport TEST_PASSWORD=\"p w\"\nOTHER='x y' # note\nBROKEN\n"
            )
        assertEquals("ada", dotEnv["TEST_USER"])
        assertEquals("p w", dotEnv["TEST_PASSWORD"])
        assertEquals("x y", dotEnv["OTHER"])
        val access = EnvAccess(setOf("TEST_USER", "TEST_NOT_SET_ANYWHERE"), dotEnv)
        assertEquals("ada", access.value("TEST_USER"))
        assertFailsWith<ConfigurationException> {
            EnvAccess(setOf("TEST_MISSING_VARIABLE"), dotEnv).value("TEST_MISSING_VARIABLE")
        }
    }

    @Test
    fun placeholdersInCommentsAreLeftAlone() {
        val text = Yamls.substitute("# uses \${nothing}\nvalue: \"\${name}\"", mapOf("name" to "Ada"))
        assertEquals("# uses \${nothing}\nvalue: \"Ada\"", text)
    }

    @Test
    fun maskedValuesNeverReachOutput() {
        Masker.register(listOf("hunter2!", "ab"))
        assertEquals("password *** was typed", Masker.mask("password hunter2! was typed"))
        assertEquals("ab stays", Masker.mask("ab stays"), "values shorter than three characters are not masked")
        assertTrue(Reporter.junit("s", listOf(failed("login failed with hunter2!"))).contains("login failed with ***"))
        assertFalse(HealReport.markdown(listOf(record("hunter2!"))).contains("hunter2!"))
    }

    @Test
    fun theHealReportCannotInjectMarkdownOrMentions() {
        val markdown = HealReport.markdown(listOf(record("see @octocat <img src=x> | `code`\nnext line")))
        assertFalse(markdown.contains("@octocat"))
        assertFalse(markdown.contains("<img"))
        assertEquals(1, markdown.lines().count { it.startsWith("| t ") })
    }

    @Test
    fun theOpenCodeAgentDeniesEverythingUnlessTheUserNamesAnother() {
        val default = OpenCodeCliClient(model = null, agent = null, command = "opencode").environment()
        assertTrue(default.getValue("OPENCODE_CONFIG_CONTENT").contains("\"*\":\"deny\""))
        assertEquals("1", default["OPENCODE_DISABLE_PROJECT_CONFIG"])
        val optOut = OpenCodeCliClient(model = null, agent = "mine", command = "opencode").environment()
        assertNull(optOut["OPENCODE_CONFIG_CONTENT"])
        assertEquals("1", optOut["OPENCODE_DISABLE_PROJECT_CONFIG"])
    }

    private fun failed(message: String): TestResult {
        return TestResult("t", "t.yaml", 1, emptyList(), message, "step", emptyList())
    }

    private fun record(reason: String): HealRecord {
        return HealRecord("t", "say", "id=a", "tap id=b", reason)
    }

    // The heal rules of the runner

    private val screen =
        listOf(
            UiNode(
                bounds = Bounds(0, 0, 100, 200),
                children =
                    listOf(
                        UiNode(id = "Tab.Search", text = "Search", bounds = Bounds(0, 100, 100, 140), enabled = false)
                    ),
            )
        )

    private fun healer(answer: String): Healer {
        return Healer(
            object : LlmClient {
                override suspend fun complete(system: String, user: String): String {
                    return answer
                }
            }
        )
    }

    private fun runnerWith(seen: MutableList<Command>, healer: Healer, appRunning: Boolean? = null): TestRunner {
        val agent = FakeClient { command ->
            seen += command
            when (command) {
                Command.Foreground -> Response.ForegroundResult(true)
                Command.Tree -> Response.TreeResult(screen)
                is Command.Tap -> Response.Error("Element not found: Selector(id=${command.selector.id})")
                is Command.WaitFor -> Response.Error("Expected visible: ${command.selector}")
                else -> Response.Ok
            }
        }
        val device = FakeDevice(supportsWaitFor = true, appRunning = appRunning)
        val drivers = Drivers(device, GekkoConfig(), agent = agent, system = FakeClient { Response.Ok })
        return TestRunner(
            Project("/tmp/gekko-security-test", GekkoConfig()),
            Platform.Android,
            device,
            drivers,
            "/tmp/gekko-security-test/results",
            {},
            healer = healer,
        )
    }

    private fun test(yaml: String): TestFile {
        return Yamls.parse(TestFile.serializer(), yaml.trimIndent())
    }

    @Test
    fun aModelCannotTurnATapIntoTyping() {
        runTest {
            val seen = mutableListOf<Command>()
            val runner = runnerWith(seen, healer("""{"action":"type","id":"Tab.Search","value":"evil","reason":"x"}"""))

            val result =
                runner.run(
                    test("name: t\nsteps:\n  - say: Open search\n    do: { tap: { id: Tab.Missing } }"),
                    "t.yaml",
                )

            assertTrue(result.failure.orEmpty().contains("not found"))
            assertTrue(seen.none { it is Command.Type })
        }
    }

    @Test
    fun anAssertionIsNotHealedWhenTheIdExistsButTheStateIsWrong() {
        runTest {
            val seen = mutableListOf<Command>()
            val runner = runnerWith(seen, healer("""{"holds":true,"reason":"looks right"}"""))

            val result =
                runner.run(
                    test("name: t\nsteps:\n  - say: Search is shown\n    do: { assertVisible: { id: Tab.Search } }"),
                    "t.yaml",
                )

            assertTrue(result.failure.orEmpty().startsWith("Expected visible"))
            assertTrue(result.healed.isEmpty())
        }
    }

    @Test
    fun anAssertionWithAnIdThatIsNowhereOnTheScreenCanBeHealed() {
        runTest {
            val seen = mutableListOf<Command>()
            val runner = runnerWith(seen, healer("""{"holds":true,"reason":"the search tab is shown"}"""))

            val result =
                runner.run(
                    test("name: t\nsteps:\n  - say: Search is shown\n    do: { assertVisible: { id: Tab.Renamed } }"),
                    "t.yaml",
                )

            assertNull(result.failure)
            assertEquals(1, result.healed.size)
        }
    }

    @Test
    fun aStepThatFailsBecauseTheAppDiedIsADeviceProblem() {
        runTest {
            val runner = runnerWith(mutableListOf(), healer("{}"), appRunning = false)

            val result = runner.run(test("name: t\nsteps:\n  - do: { tap: { id: Tab.Missing } }"), "t.yaml")

            assertTrue(result.failure.orEmpty().startsWith("The app is not running any more"))
            assertTrue(result.infrastructure)
        }
    }

    @Test
    fun aFailureWhileTheAppRunsStaysATestFailure() {
        runTest {
            val runner = runnerWith(mutableListOf(), healer("{}"), appRunning = true)

            val result = runner.run(test("name: t\nsteps:\n  - do: { tap: { id: Tab.Missing } }"), "t.yaml")

            assertFalse(result.infrastructure)
        }
    }
}
