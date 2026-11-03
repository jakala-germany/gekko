package com.jakala.gekko.cli

import com.jakala.gekko.protocol.Command
import com.jakala.gekko.protocol.MockHit
import com.jakala.gekko.protocol.Response
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class RunnerMocksTest {
    private val root = "/tmp/gekko-runner-mocks-test"

    init {
        makeDirs("$root/mocks/routes")
        makeDirs("$root/flows")
        writeTextFile("$root/mocks/routes/daytime.json", "{\"routes\":[{\"line\":\"N7\"}]}")
        writeTextFile("$root/mocks/routes/named.json", "{\"routes\":[{\"line\":\"N7\",\"name\":\"\${var.name}\"}]}")
        writeTextFile("$root/mocks/page.html", "<p>\${var.name}</p>")
        writeTextFile(
            "$root/flows/mock-routes.yaml",
            """
            name: mock-routes
            steps:
              - say: Mock the routes from a flow
                do: { mock: { url: "https://api.example.com/v1/routes*", file: routes/daytime.json } }
            """
                .trimIndent(),
        )
    }

    private fun test(yaml: String): TestFile {
        return Yamls.parse(TestFile.serializer(), yaml.trimIndent())
    }

    /** An agent that keeps what it is told and answers `hits` once when asked for them. */
    private class MockAgent(var hits: List<MockHit> = emptyList(), val answerSet: Response = Response.Ok) {
        val client = FakeClient { command ->
            when (command) {
                Command.Foreground -> Response.ForegroundResult(true)
                is Command.SetMocks -> answerSet
                Command.MockHits -> Response.MockHitsResult(hits).also { hits = emptyList() }
                else -> Response.Ok
            }
        }

        val mockCommands: List<Command>
            get() =
                client.received.filter { it is Command.SetMocks || it == Command.ClearMocks || it == Command.MockHits }
    }

    private fun runner(
        agent: FakeClient,
        device: FakeDevice = FakeDevice(supportsWaitFor = true),
        log: MutableList<String> = mutableListOf(),
        platform: Platform = Platform.Android,
        config: GekkoConfig = GekkoConfig(),
    ): TestRunner {
        val drivers = Drivers(device, config, agent = agent, system = FakeClient { Response.Ok })
        return TestRunner(Project(root, config), platform, device, drivers, "$root/results", { log += it })
    }

    private val laterMock =
        """
        name: Night bus
        steps:
          - say: Look at the screen
            do: { assertVisible: { id: Home.Title } }
          - say: Mock the route search
            do: { mock: { url: "https://api.example.com/v1/routes*", method: GET, file: routes/daytime.json } }
          - say: Look again
            do: { assertVisible: { id: Home.Title } }
        """

    private fun bodyOf(command: Command.SetMocks, index: Int = 0): String {
        return Base64.decode(command.rules[index].bodyBase64).decodeToString()
    }

    @Test
    fun aMockStepAfterTheStartTellsTheRunningApp() {
        runTest {
            val agent = MockAgent()
            val device = FakeDevice(supportsWaitFor = true)
            val log = mutableListOf<String>()

            val result = runner(agent.client, device, log).run(test(laterMock), "tests/night.yaml")

            assertNull(result.failure)
            val set = agent.client.received.filterIsInstance<Command.SetMocks>().single()
            assertEquals("^https://api\\.example\\.com/v1/routes.*$", set.rules.single().urlRegex)
            assertEquals("GET", set.rules.single().method)
            assertEquals(200, set.rules.single().status)
            assertEquals(mapOf("Content-Type" to "application/json"), set.rules.single().headers)
            // The app runs, so nothing is handed over for a start
            assertEquals(emptyList(), device.startupMocks)
            assertTrue(log.any { it.contains("mock GET https://api.example.com/v1/routes* → routes/daytime.json") })
        }
    }

    @Test
    fun leadingMockStepsAreHandedOverBeforeTheAppStarts() {
        runTest {
            val agent = MockAgent()
            val device = FakeDevice(supportsWaitFor = true)
            val yaml =
                """
                name: Startup
                steps:
                  - say: Mock the routes
                    do: { mock: { url: "https://api.example.com/v1/routes*", file: routes/daytime.json } }
                  - say: Mock the profile
                    do: { mock: { url: "https://api.example.com/v1/profile", status: 503 } }
                  - say: Look at the screen
                    do: { assertVisible: { id: Home.Title } }
                  - say: Mock something later
                    do: { mock: { url: "https://api.example.com/v1/late*", status: 404 } }
                """

            val result = runner(agent.client, device).run(test(yaml), "tests/startup.yaml")

            assertNull(result.failure)
            // The first handover holds the two leading mocks and comes before the launch
            assertEquals(listOf("mocks", "launch"), device.events.take(2))
            assertTrue(device.startupMocks.first()!!.contains("\"type\":\"setMocks\""))
            assertEquals(2, Regex("\"id\":\"m\\d+\"").findAll(device.startupMocks.first()!!).count())
            // The mock after the first look is set in the running app only
            val sets = agent.client.received.filterIsInstance<Command.SetMocks>()
            assertEquals(3, sets.last().rules.size)
            // After the test the handover that was not picked up is taken back
            assertEquals(listOf("mocks", "launch", "mocks off"), device.events)
        }
    }

    @Test
    fun leadingMocksOfAFlowThatTheTestUsesAreAppliedBeforeTheStart() {
        runTest {
            val device = FakeDevice(supportsWaitFor = true)
            val yaml =
                """
                name: Flow
                uses: [mock-routes]
                steps:
                  - say: Look at the screen
                    do: { assertVisible: { id: Home.Title } }
                """

            val result = runner(MockAgent().client, device).run(test(yaml), "tests/flow.yaml")

            assertNull(result.failure)
            assertEquals(listOf("mocks", "launch"), device.events.take(2))
        }
    }

    @Test
    fun aStepOfAnotherPlatformDoesNotEndTheLeadingMocks() {
        runTest {
            val device = FakeDevice(supportsWaitFor = true)
            val yaml =
                """
                name: Platforms
                steps:
                  - say: Web only
                    platform: web
                    do: { tap: { id: Web.Banner } }
                  - say: Mock
                    do: { mock: { url: "https://a/*", status: 500 } }
                """

            runner(MockAgent().client, device).run(test(yaml), "tests/platforms.yaml")

            assertEquals(listOf("mocks", "launch"), device.events.take(2))
        }
    }

    @Test
    fun aMockStepThatIsNotLeadingIsNotHandedOverBeforeTheStart() {
        runTest {
            val device = FakeDevice(supportsWaitFor = true)

            runner(MockAgent().client, device).run(test(laterMock), "tests/night.yaml")

            assertEquals("launch", device.events.first())
        }
    }

    @Test
    fun theFileIsFilledWithTheTextsOfTheTestAndEscapedForJson() {
        runTest {
            val agent = MockAgent()
            val yaml =
                """
                name: Texts
                steps:
                  - say: Read a name with a quote
                    do: { copyText: { from: { id: Home.Title }, as: name } }
                  - say: Mock
                    do: { mock: { url: "https://a/*", file: routes/named.json } }
                  - say: Mock a page
                    do: { mock: { url: "https://b/*", file: page.html } }
                """
            val tree =
                listOf(
                    com.jakala.gekko.protocol.UiNode(
                        id = "Home.Title",
                        text = "Zoe \"Z\"",
                        bounds = com.jakala.gekko.protocol.Bounds(0, 0, 10, 10),
                    )
                )
            val client = FakeClient { command ->
                when (command) {
                    Command.Foreground -> Response.ForegroundResult(true)
                    Command.Tree -> Response.TreeResult(tree)
                    is Command.SetMocks -> agent.client.send(command)
                    else -> Response.Ok
                }
            }

            val result = runner(client).run(test(yaml), "tests/texts.yaml")

            assertNull(result.failure, result.failure)
            val set = agent.client.received.filterIsInstance<Command.SetMocks>().last()
            assertEquals("""{"routes":[{"line":"N7","name":"Zoe \"Z\""}]}""", bodyOf(set, 0))
            assertEquals("""<p>Zoe "Z"</p>""", bodyOf(set, 1))
        }
    }

    @Test
    fun aMockedRequestIsLoggedAndCountedSoTheMockIsNotUnused() {
        runTest {
            val agent = MockAgent(hits = listOf(MockHit("m1", "GET", "https://api.example.com/v1/routes?from=A")))
            val log = mutableListOf<String>()

            val result = runner(agent.client, log = log).run(test(laterMock), "tests/night.yaml")

            assertTrue(
                log.any { it.trim() == "mocked GET https://api.example.com/v1/routes?from=A → routes/daytime.json" }
            )
            assertEquals(emptyList(), result.warnings)
        }
    }

    @Test
    fun aMockNoRequestUsedIsAWarningAndTheTestStillPasses() {
        runTest {
            val log = mutableListOf<String>()

            val result = runner(MockAgent().client, log = log).run(test(laterMock), "tests/night.yaml")

            assertNull(result.failure)
            assertEquals(
                listOf("network mock was never used: GET https://api.example.com/v1/routes* → routes/daytime.json"),
                result.warnings,
            )
            assertTrue(log.any { it.trim().startsWith("warning: network mock was never used") })
        }
    }

    @Test
    fun everyMockIsUndoneWhenTheTestEnds() {
        runTest {
            val agent = MockAgent()
            val device = FakeDevice(supportsWaitFor = true)

            runner(agent.client, device).run(test(laterMock), "tests/night.yaml")

            assertEquals(Command.ClearMocks, agent.mockCommands.last())
        }
    }

    @Test
    fun everyMockIsUndoneWhenTheTestFails() {
        runTest {
            val agent = MockAgent()
            val yaml =
                """
                name: Fails
                steps:
                  - say: Mock
                    do: { mock: { url: "https://a/*", status: 500 } }
                  - say: A file that is not there
                    do: { mock: { url: "https://b/*", file: missing.json } }
                """

            val device = FakeDevice(supportsWaitFor = true)
            val result = runner(agent.client, device).run(test(yaml), "tests/fails.yaml")

            assertTrue(result.failure!!.contains("Mock file does not exist: mocks/missing.json"), result.failure)
            assertEquals(Command.ClearMocks, agent.mockCommands.last())
            // The failure came before anything was handed over, so there is nothing to take back
            assertEquals(emptyList(), device.startupMocks)
        }
    }

    @Test
    fun theNextTestStartsWithoutMocks() {
        runTest {
            val agent = MockAgent()
            val device = FakeDevice(supportsWaitFor = true)
            val runner = runner(agent.client, device)
            runner.run(test(laterMock), "tests/night.yaml")
            agent.client.received.clear()

            val plain =
                runner.run(
                    test("name: Plain\nsteps:\n  - do: { assertVisible: { id: Home.Title } }"),
                    "tests/plain.yaml",
                )

            assertNull(plain.failure)
            assertEquals(emptyList(), plain.warnings)
            assertEquals(emptyList(), agent.mockCommands)
        }
    }

    @Test
    fun aTestWithoutMocksAsksNothingAboutThem() {
        runTest {
            val agent = MockAgent()
            val device = FakeDevice(supportsWaitFor = true)

            runner(agent.client, device)
                .run(test("name: Plain\nsteps:\n  - do: { assertVisible: { id: Home.Title } }"), "tests/plain.yaml")

            assertEquals(emptyList(), agent.mockCommands)
            assertEquals(listOf("launch"), device.events)
        }
    }

    @Test
    fun clearMocksRemovesThemAndCountsWhatTheyAnswered() {
        runTest {
            val agent = MockAgent(hits = listOf(MockHit("m1", "GET", "https://a/x")))
            val yaml =
                """
                name: Clear
                steps:
                  - say: Look
                    do: { assertVisible: { id: Home.Title } }
                  - say: Mock
                    do: { mock: { url: "https://a/*", status: 500 } }
                  - say: Back to the real backend
                    do: { clearMocks: true }
                """

            val result = runner(agent.client).run(test(yaml), "tests/clear.yaml")

            assertNull(result.failure)
            val commands = agent.mockCommands
            // The hits come before the mocks go: they are asked for first
            assertEquals(Command.MockHits, commands[commands.indexOfFirst { it == Command.ClearMocks } - 1])
            assertEquals(emptyList(), result.warnings)
        }
    }

    @Test
    fun anAppThatCannotMockFailsTheStepAndSaysWhy() {
        runTest {
            val agent =
                MockAgent(answerSet = Response.Error("A network mock cannot be used: unsupported", "unsupported"))

            val result = runner(agent.client).run(test(laterMock), "tests/night.yaml")

            assertTrue(result.failure!!.contains("The app cannot mock network requests"), result.failure)
        }
    }

    @Test
    fun anAppThatIsNotRunningGetsTheMocksWhenItStarts() {
        runTest {
            val device = FakeDevice(supportsWaitFor = true)
            val agent = FakeClient { command ->
                when (command) {
                    Command.Foreground -> Response.ForegroundResult(true)
                    is Command.SetMocks,
                    Command.ClearMocks,
                    Command.MockHits -> throw IllegalStateException("nothing listens")
                    else -> Response.Ok
                }
            }

            val result = runner(agent, device).run(test(laterMock), "tests/night.yaml")

            assertNull(result.failure)
            // Handed over when it was set, because there was nobody to tell, and taken back when the test ended
            assertEquals(listOf("launch", "mocks", "mocks off"), device.events)
        }
    }

    @Test
    fun launchingTheAppAgainHandsTheMocksOverAgain() {
        runTest {
            val device = FakeDevice(supportsWaitFor = true)
            val yaml =
                """
                name: Restart
                steps:
                  - say: Look
                    do: { assertVisible: { id: Home.Title } }
                  - say: Mock
                    do: { mock: { url: "https://a/*", status: 500 } }
                  - say: Stop
                    do: { stopApp: true }
                  - say: Start again
                    do: { launchApp: {} }
                """

            runner(MockAgent().client, device).run(test(yaml), "tests/restart.yaml")

            assertEquals(listOf("launch", "mocks", "launch", "mocks off"), device.events)
        }
    }

    @Test
    fun aValueFromTheEnvironmentIsPutInAsItIsEvenWhenItLooksLikeAPlaceholder() {
        runTest {
            writeTextFile("$root/.env", "MOCK_SECRET=pa\${var.nothing}ss\${random.uuid}\n")
            writeTextFile("$root/mocks/secret.json", "{\"token\":\"\${env.MOCK_SECRET}\"}")
            val agent = MockAgent()
            val yaml =
                """
                name: Secret
                steps:
                  - say: Look
                    do: { assertVisible: { id: Home.Title } }
                  - say: Mock
                    do: { mock: { url: "https://a/*", file: secret.json } }
                """

            val result =
                runner(agent.client, config = GekkoConfig(env = listOf("MOCK_SECRET"))).run(test(yaml), "tests/s.yaml")

            assertNull(result.failure, result.failure)
            val set = agent.client.received.filterIsInstance<Command.SetMocks>().last()
            assertEquals("""{"token":"pa${'$'}{var.nothing}ss${'$'}{random.uuid}"}""", bodyOf(set))
        }
    }

    @Test
    fun aMissingEnvironmentVariableStopsTheRunAndStillRemovesTheMocks() {
        runTest {
            writeTextFile("$root/.env", "OTHER=1\n")
            writeTextFile("$root/mocks/needs-env.json", "{\"token\":\"\${env.MOCK_MISSING_TOKEN}\"}")
            val agent = MockAgent()
            val device = FakeDevice(supportsWaitFor = true)
            val yaml =
                """
                name: Missing
                steps:
                  - say: Look
                    do: { assertVisible: { id: Home.Title } }
                  - say: Mock
                    do: { mock: { url: "https://a/*", status: 500 } }
                  - say: Mock with a variable that is not set
                    do: { mock: { url: "https://b/*", file: needs-env.json } }
                """
            val config = GekkoConfig(env = listOf("MOCK_MISSING_TOKEN"))

            assertFailsWith<ConfigurationException> {
                runner(agent.client, device, config = config).run(test(yaml), "tests/missing.yaml")
            }

            // What the first mock set in the app is taken out again
            assertEquals(Command.ClearMocks, agent.client.received.last { it !is Command.MockHits })
        }
    }

    @Test
    fun aMockFileThatIsNoUtf8TextFailsTheStep() {
        runTest {
            writeTextFile("$root/mocks/avatar.png", "��PNG")
            val yaml =
                """
                name: Binary
                steps:
                  - say: Look
                    do: { assertVisible: { id: Home.Title } }
                  - say: Mock an image
                    do: { mock: { url: "https://a/avatar.png", file: avatar.png } }
                """

            val result = runner(MockAgent().client).run(test(yaml), "tests/binary.yaml")

            assertTrue(result.failure!!.contains("mocks/avatar.png: is not a UTF-8 text file"), result.failure)
        }
    }

    @Test
    fun aTestWithoutLeadingMocksGeneratesNothingBeforeTheAppStarts() {
        runTest {
            val log = mutableListOf<String>()
            val yaml =
                """
                name: Plain
                steps:
                  - say: Look at ${'$'}{random.email}
                    do: { assertVisible: { id: Home.Title } }
                """

            runner(MockAgent().client, log = log).run(test(yaml), "tests/plain.yaml")

            val started = log.indexOfFirst { it.contains("fresh start") }
            val generated = log.indexOfFirst { it.contains("random.email") }
            assertTrue(started in 0 until generated, "the value was generated before the start: $log")
        }
    }
}
