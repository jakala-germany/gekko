package com.jakala.gekko.cli

import com.jakala.gekko.protocol.Bounds
import com.jakala.gekko.protocol.Command
import com.jakala.gekko.protocol.Response
import com.jakala.gekko.protocol.UiNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class RunnerPanesTest {
    private val project =
        paneProject(
            mapOf(
                "panes/login.yaml" to LOGIN_PANE,
                "panes/home.yaml" to
                    "identity: [title]\nelements:\n  title: { id: Home.Title }\n  profile: { id: Home.Profile }",
            )
        )

    private fun window(visible: Set<String>): List<UiNode> {
        val nodes = visible.mapIndexed { index, id ->
            UiNode(id = id, text = id, bounds = Bounds(0, index * 10, 100, index * 10 + 8), actions = listOf("click"))
        }
        return listOf(UiNode(bounds = Bounds(0, 0, 100, 200), children = nodes))
    }

    /** An app that shows the elements of [visible]; a tap on an id in [missing] finds nothing. */
    private fun agent(visible: Set<String>, seen: MutableList<Command>, missing: Set<String> = emptySet()): FakeClient {
        return FakeClient { command ->
            seen += command
            when (command) {
                Command.Foreground -> Response.ForegroundResult(true)
                Command.Tree -> Response.TreeResult(window(visible))
                is Command.WaitFor ->
                    if (command.visible == (command.selector.id in visible)) Response.Ok
                    else Response.Error("Expected visible: ${command.selector}")
                is Command.Tap ->
                    if (command.selector.id in missing) Response.Error("Element not found: ${command.selector}")
                    else Response.Ok
                else -> Response.Ok
            }
        }
    }

    private fun runner(platform: Platform, client: FakeClient, log: MutableList<String> = mutableListOf()): TestRunner {
        val device = FakeDevice(supportsWaitFor = true)
        val drivers = Drivers(device, GekkoConfig(), agent = client, system = FakeClient { Response.Ok })
        return TestRunner(project, platform, device, drivers, "${project.root}/results", { log += it })
    }

    private fun test(yaml: String): TestFile {
        return Yamls.parse(TestFile.serializer(), yaml.trimIndent())
    }

    private val loginScreen = setOf("Login.Title", "Login.Email", "login_submit")

    @Test
    fun anActionWaitsForItsPaneAndRunsItsSteps() = runTest {
        val seen = mutableListOf<Command>()

        val result =
            runner(Platform.Android, agent(loginScreen, seen))
                .run(
                    test(
                        """
                        name: Sign in
                        steps:
                          - say: Sign in
                            do: { use: login.signIn, with: { user: ada, password: secret } }
                        """
                    ),
                    "tests/sign-in.yaml",
                )

        assertNull(result.failure)
        val types = seen.filterIsInstance<Command.Type>()
        assertEquals(
            listOf("Login.Email" to "ada", "Login.Password" to "secret"),
            types.map { it.selector.id to it.text },
        )
        assertEquals(listOf("login_submit"), seen.filterIsInstance<Command.Tap>().map { it.selector.id })
        val identity = seen.filterIsInstance<Command.WaitFor>().map { it.selector.id }
        assertEquals(listOf("Login.Title", "Login.Email"), identity)
    }

    @Test
    fun theSameStepsUseTheTextOnIos() = runTest {
        val seen = mutableListOf<Command>()

        val result =
            runner(Platform.Ios, agent(loginScreen, seen))
                .run(
                    test("name: Tap\nsteps:\n  - say: Submit\n    do: { tap: login.submit }"),
                    "tests/tap.yaml",
                )

        assertNull(result.failure)
        val tap = seen.filterIsInstance<Command.Tap>().single()
        assertEquals("Sign in", tap.selector.text)
        assertNull(tap.selector.id)
    }

    @Test
    fun anActionFailsBeforeItsFirstStepWhenThePaneIsNotOnScreen() = runTest {
        val seen = mutableListOf<Command>()

        val result =
            runner(Platform.Android, agent(setOf("Home.Title"), seen))
                .run(
                    test(
                        """
                        name: Wrong screen
                        steps:
                          - say: Sign in
                            do: { use: login.signIn, with: { user: ada, password: secret } }
                        """
                    ),
                    "tests/wrong.yaml",
                )

        assertNotNull(result.failure)
        assertTrue(result.failure.contains("not on pane 'login'"), result.failure)
        assertTrue(result.failure.contains("Login.Title"), result.failure)
        assertTrue(result.failure.contains("current pane: home"), result.failure)
        assertTrue(seen.none { it is Command.Type })
        assertEquals("Sign in", result.failedStep)
    }

    @Test
    fun assertPaneAndIfPaneLookAtTheIdentity() = runTest {
        val seen = mutableListOf<Command>()
        val log = mutableListOf<String>()

        val result =
            runner(Platform.Android, agent(loginScreen, seen), log)
                .run(
                    test(
                        """
                        name: Panes
                        steps:
                          - say: On the login
                            do: { assertPane: login }
                          - say: Only on the login
                            if: { pane: login }
                            do: { tap: { id: OnLogin } }
                          - say: Only on the home
                            if: { pane: home }
                            do: { tap: { id: OnHome } }
                        """
                    ),
                    "tests/panes.yaml",
                )

        assertNull(result.failure)
        assertEquals(listOf("OnLogin"), seen.filterIsInstance<Command.Tap>().map { it.selector.id })
        assertTrue(log.any { it.contains("Only on the home (skipped, condition not met)") }, log.toString())
    }

    @Test
    fun anUntilCanWaitForAPane() = runTest {
        val seen = mutableListOf<Command>()

        val result =
            runner(Platform.Android, agent(setOf("Home.Title", "Home.Profile"), seen))
                .run(
                    test(
                        """
                        name: Navigate
                        steps:
                          - say: Open the profile
                            do: { tap: home.profile }
                            until: { assertPane: home }
                        """
                    ),
                    "tests/navigate.yaml",
                )

        assertNull(result.failure)
        assertEquals(listOf("Home.Profile"), seen.filterIsInstance<Command.Tap>().map { it.selector.id })
        assertEquals(listOf("Home.Title"), seen.filterIsInstance<Command.WaitFor>().map { it.selector.id })
    }

    @Test
    fun aPaneThatIsNotThereFailsTheAssertion() = runTest {
        val result =
            runner(Platform.Android, agent(setOf("Home.Title"), mutableListOf()))
                .run(
                    test("name: Wrong\nsteps:\n  - say: On the login\n    do: { assertPane: login }"),
                    "tests/wrong.yaml",
                )

        assertTrue(result.failure!!.contains("not on pane 'login'"), result.failure)
    }

    @Test
    fun aFailureNamesTheElementAndWhatItStandsFor() = runTest {
        val seen = mutableListOf<Command>()

        val result =
            runner(Platform.Android, agent(setOf("Login.Title", "Login.Email"), seen, missing = setOf("login_submit")))
                .run(
                    test("name: Missing\nsteps:\n  - say: Submit\n    do: { tap: login.submit }\n    timeout: 200"),
                    "tests/missing.yaml",
                )

        assertTrue(result.failure!!.startsWith("Element not found"), result.failure)
        assertTrue(result.failure.contains("element login.submit = Selector(id=login_submit)"), result.failure)
        assertTrue(result.failure.contains("current pane: login"), result.failure)
    }

    @Test
    fun aStepWithoutSayIsLoggedWithTheElementName() = runTest {
        val log = mutableListOf<String>()

        runner(Platform.Android, agent(loginScreen, mutableListOf()), log)
            .run(test("name: Log\nsteps:\n  - do: { tap: login.submit }"), "tests/log.yaml")

        assertTrue(log.any { it.trim() == "tap login.submit" }, log.toString())
    }

    @Test
    fun anElementKeepsItsKeysWhenTheStepAddsOnes() = runTest {
        val seen = mutableListOf<Command>()

        runner(Platform.Android, agent(loginScreen, seen))
            .run(
                test("name: Row\nsteps:\n  - say: Third row\n    do: { tap: { element: login.row, index: 2 } }"),
                "tests/row.yaml",
            )

        val tap = seen.filterIsInstance<Command.Tap>().single()
        assertEquals("Login.Row", tap.selector.id)
        assertEquals(2, tap.selector.index)
    }

    @Test
    fun aRepeatWhileCanUseAPaneCondition() = runTest {
        val seen = mutableListOf<Command>()
        var rounds = 0
        val client = FakeClient { command ->
            seen += command
            when (command) {
                Command.Foreground -> Response.ForegroundResult(true)
                is Command.WaitFor ->
                    // The login shows twice, then it is gone
                    if (command.selector.id == "Login.Title" && command.visible) {
                        if (rounds++ < 4) Response.Ok else Response.Error("Expected visible: ${command.selector}")
                    } else {
                        Response.Ok
                    }
                else -> Response.Ok
            }
        }

        val result =
            runner(Platform.Android, client)
                .run(
                    test(
                        """
                        name: Repeat
                        steps:
                          - say: Close it
                            do:
                              repeat:
                                while: { pane: login }
                                max: 5
                                steps:
                                  - say: Back
                                    do: { tap: { id: Back } }
                        """
                    ),
                    "tests/repeat.yaml",
                )

        assertNull(result.failure)
        assertTrue(seen.filterIsInstance<Command.Tap>().isNotEmpty())
    }

    @Test
    fun aHealedStepSaysWhichPaneElementToFix() = runTest {
        val seen = mutableListOf<Command>()
        val healer =
            Healer(
                object : LlmClient {
                    override suspend fun complete(system: String, user: String): String {
                        return """{"action":"tap","id":"Login.Title","reason":"the closest button"}"""
                    }
                }
            )
        val device = FakeDevice(supportsWaitFor = true)
        val client = agent(setOf("Login.Title", "Login.Email"), seen, missing = setOf("login_submit"))
        val drivers = Drivers(device, GekkoConfig(), agent = client, system = FakeClient { Response.Ok })

        val result =
            TestRunner(project, Platform.Android, device, drivers, "${project.root}/results", {}, healer = healer)
                .run(
                    test("name: Heal\nsteps:\n  - say: Submit the form\n    do: { tap: login.submit }"),
                    "tests/heal.yaml",
                )

        assertNull(result.failure)
        assertEquals(listOf("login.submit (panes/login.yaml)"), result.healed.single().elements)
        assertTrue(HealReport.warnings(result.healed).contains("fix:  login.submit (panes/login.yaml)"))
        assertTrue(HealReport.markdown(result.healed).contains("| Fix in |"))
    }

    @Test
    fun theTreeNamesTheElementsOfThePanes() {
        val windows = window(setOf("Login.Title", "Other"))
        val elements = project.panes.selectors(Platform.Android)

        val printed =
            TreePrinter.print(windows) { node ->
                elements.filter { it.second.matches(node) }.joinToString(", ") { it.first.qualified }.ifEmpty { null }
            }

        assertTrue(printed.lines().any { it.contains("id=Login.Title") && it.endsWith("<- login.title") }, printed)
        assertTrue(printed.lines().any { it.contains("id=Other") && !it.contains("<-") }, printed)
    }

    @Test
    fun anActionCanCallAnotherActionOfItsPaneByItsShortName() = runTest {
        val twoActions =
            paneProject(
                mapOf(
                    "panes/p.yaml" to
                        """
                        elements:
                          a: { id: P.A }
                        actions:
                          inner:
                            steps:
                              - do: { tap: a }
                          outer:
                            steps:
                              - do: { use: inner }
                              - do: { tap: a }
                        """
                )
            )
        val seen = mutableListOf<Command>()
        val device = FakeDevice(supportsWaitFor = true)
        val drivers =
            Drivers(device, GekkoConfig(), agent = agent(emptySet(), seen), system = FakeClient { Response.Ok })

        val result =
            TestRunner(twoActions, Platform.Android, device, drivers, "${twoActions.root}/results", {})
                .run(test("name: Nested\nsteps:\n  - do: { use: p.outer }"), "tests/nested.yaml")

        assertNull(result.failure)
        assertEquals(listOf("P.A", "P.A"), seen.filterIsInstance<Command.Tap>().map { it.selector.id })
    }
}
