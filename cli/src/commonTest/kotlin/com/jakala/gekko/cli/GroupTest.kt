package com.jakala.gekko.cli

import com.jakala.gekko.protocol.Bounds
import com.jakala.gekko.protocol.Command
import com.jakala.gekko.protocol.ErrorKind
import com.jakala.gekko.protocol.Response
import com.jakala.gekko.protocol.UiNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class GroupTest {
    private val root = "/tmp/gekko-group-test"

    private fun project(files: Map<String, String>): Project {
        // Every test starts from an empty folder: files of an earlier test must not become tests or groups of this one
        removeRecursively(root)
        files.forEach { (name, text) ->
            makeDirs("$root/${name.substringBeforeLast('/')}")
            writeTextFile("$root/$name", text.trimIndent())
        }
        return Project(root, GekkoConfig())
    }

    private val tap = "do: { tap: { id: Home.Tab } }"

    private val files =
        mapOf(
            "flows/login.yaml" to "name: login\nsteps:\n  - say: Log in\n    $tap",
            "flows/logout.yaml" to "name: logout\nsteps:\n  - say: Log out\n    $tap",
            "tests/open/ohne-login.yaml" to "name: No login\nsteps:\n  - say: Look\n    $tap",
            "tests/konto/group.yaml" to "name: Konto\nsetup: [login]\nteardown: [logout]",
            "tests/konto/a-erst.yaml" to "name: First\nsteps:\n  - say: Look\n    $tap",
            "tests/konto/b-dann.yaml" to "name: Second\nsteps:\n  - say: Look\n    $tap",
        )

    private val screen =
        listOf(
            UiNode(
                bounds = Bounds(0, 0, 1000, 2000),
                children =
                    listOf(UiNode(id = "Home.Tab", bounds = Bounds(10, 10, 200, 100), actions = listOf("click"))),
            )
        )

    /** A screen with the tab on it, which taps reach. */
    private val working: suspend (Command) -> Response = { command ->
        when (command) {
            Command.Foreground -> Response.ForegroundResult(true)
            Command.Tree -> Response.TreeResult(screen)
            else -> Response.Ok
        }
    }

    private fun runner(
        project: Project,
        device: FakeDevice,
        answer: suspend (Command) -> Response = working,
    ): TestRunner {
        val drivers = Drivers(device, GekkoConfig(), agent = FakeClient(answer), system = FakeClient { Response.Ok })
        return TestRunner(project, Platform.Android, device, drivers, "$root/results", {})
    }

    private fun units(project: Project): List<RunUnit> {
        val tests = project.testFiles(emptyList()).map { it to project.loadTest(it) }
        return RunUnits.of(project, tests)
    }

    @Test
    fun theGroupFileIsNoTestAndBelongsToTheTestsBelowIt() {
        val project = project(files)

        assertEquals(3, project.testFiles(emptyList()).size)
        assertEquals("$root/tests/konto/group.yaml", project.groupFileOf("$root/tests/konto/a-erst.yaml"))
        assertNull(project.groupFileOf("$root/tests/open/ohne-login.yaml"))
    }

    @Test
    fun theTestsOfAGroupAreOneUnitInOrder() {
        val units = units(project(files))

        // Sorted by path: the folder konto comes before open
        assertEquals(listOf("Konto", null), units.map { it.group?.name })
        assertEquals(listOf("First", "Second"), units[0].tests.map { it.second.name })
        assertEquals(listOf("No login"), units[1].tests.map { it.second.name })
    }

    @Test
    fun aGroupWhoseTestsAreAllFilteredOutIsNoUnit() {
        val project = project(files)
        val tests = project.testFiles(emptyList()).filter { "konto" !in it }.map { it to project.loadTest(it) }

        assertEquals(listOf(null), RunUnits.of(project, tests).map { it.group })
    }

    @Test
    fun theSetupRunsOnceThenTheTestsKeepTheStateThenTheTeardownRuns() = runTest {
        val project = project(files)
        val device = FakeDevice(supportsWaitFor = true)
        val names = mutableListOf<String>()

        val results = runner(project, device).runUnit(units(project)[0], started = { names += it }) {}

        assertEquals(listOf("Konto (setup)", "First", "Second", "Konto (teardown)"), names)
        assertEquals(names, results.map { it.name })
        assertTrue(results.all { it.failure == null })
        // The setup starts fresh; every later start keeps the data
        assertEquals(listOf(false, true, true, true), device.starts)
        // The session is kept until the teardown has run
        assertEquals(listOf(true, true, true, false), device.ends)
    }

    @Test
    fun withoutATeardownTheLastTestEndsTheSession() = runTest {
        val project =
            project(
                files +
                    mapOf(
                        "tests/konto/group.yaml" to "setup: [login]",
                        "tests/konto/c-ende.yaml" to "name: Last\nsteps:\n  - do: { wait: 1 }",
                    )
            )
        val device = FakeDevice(supportsWaitFor = true)

        runner(project, device).runUnit(units(project)[0], started = {}) {}

        assertEquals(listOf(true, true, true, false), device.ends)
    }

    @Test
    fun aFailedSetupSkipsTheTestsAndEndsTheSession() = runTest {
        val project = project(files)
        val device = FakeDevice(supportsWaitFor = true)

        val results =
            runner(project, device) { command ->
                    if (command is Command.Tap) Response.Error("Element not found: Home.Tab", ErrorKind.NOT_FOUND)
                    else working(command)
                }
                .runUnit(units(project)[0], started = {}) {}

        assertEquals(listOf("Konto (setup)", "First", "Second"), results.map { it.name })
        assertNotNull(results[0].failure)
        assertTrue(!results[0].skipped)
        assertTrue(
            results.drop(1).all { it.skipped && it.failure!!.startsWith("skipped: the setup of group 'Konto' failed") }
        )
        assertEquals(1, results.count { it.failed })
        // Nothing of the tests was tried, and the session was closed
        assertEquals(listOf(false), device.starts)
        assertEquals(listOf(true, false), device.ends)
    }

    @Test
    fun theReportShowsSkippedTestsAsSkipped() {
        val skipped = TestResult("Second", "t", 0, emptyList(), "skipped: why", null, emptyList(), skipped = true)
        val failed = TestResult("Setup", "t", 10, emptyList(), "boom", "Log in", emptyList())

        val xml = Reporter.junit("gekko", listOf(failed, skipped))

        assertTrue("""failures="1" errors="0" skipped="1"""" in xml)
        assertTrue("""<skipped message="skipped: why"/>""" in xml)
    }

    @Test
    fun aTestOnItsOwnIsAFreshStart() = runTest {
        val project = project(files)
        val device = FakeDevice(supportsWaitFor = true)

        val results = runner(project, device).runUnit(units(project)[1], started = {}) {}

        assertEquals(1, results.size)
        assertEquals(listOf(false), device.starts)
        assertEquals(listOf(false), device.ends)
    }

    @Test
    fun checkingReportsABrokenGroup() {
        val project =
            project(
                files +
                    mapOf(
                        "tests/kaputt/group.yaml" to "setup: [missing-flow]",
                        "tests/kaputt/x.yaml" to "name: X\nsteps:\n  - do: { wait: 1 }",
                    )
            )

        val problems = Checker.check(project, listOf("$root/tests/kaputt/x.yaml"))

        assertEquals(1, problems.size)
        assertTrue("group.yaml" in problems[0] && "missing-flow" in problems[0], problems[0])
        assertEquals(emptyList(), Checker.check(project, listOf("$root/tests/konto/a-erst.yaml")))
    }

    @Test
    fun aGroupNeedsASetup() {
        val project =
            project(
                files +
                    mapOf(
                        "tests/leer/group.yaml" to "name: Leer\nsetup: []",
                        "tests/leer/x.yaml" to "name: X\nsteps:\n  - do: { wait: 1 }",
                    )
            )

        assertEquals(1, Checker.check(project, listOf("$root/tests/leer/x.yaml")).size)
    }

    private val webFiles =
        files +
            mapOf(
                "tests/konto/group.yaml" to "name: Konto\nplatform: web\nsetup: [login]\nteardown: [logout]",
                "tests/zz-web/group.yaml" to "platform: [android, web]\nsetup: [login]",
                "tests/zz-web/x.yaml" to "name: X\nsteps:\n  - say: Look\n    $tap",
            )

    @Test
    fun aGroupLimitedToWebIsLeftOutOnOtherPlatforms() {
        val units = units(project(webFiles))

        // konto: web only, zz-web: android and web, open: no group
        assertEquals(listOf("Konto", null, "zz-web"), units.map { it.group?.name })
        assertEquals(listOf("Konto", null, "zz-web"), RunUnits.forPlatform(units, Platform.Web).map { it.group?.name })
        assertEquals(listOf(null, "zz-web"), RunUnits.forPlatform(units, Platform.Android).map { it.group?.name })
        assertEquals(listOf(null), RunUnits.forPlatform(units, Platform.Ios).map { it.group?.name })
    }

    @Test
    fun aGroupWithoutAPlatformRunsEverywhere() {
        val units = units(project(files))

        Platform.entries.forEach { assertEquals(units.size, RunUnits.forPlatform(units, it).size) }
    }

    @Test
    fun theGroupPlatformTakesOneNameOrAListAndRejectsOthers() {
        val project =
            project(
                webFiles +
                    mapOf(
                        "tests/falsch/group.yaml" to "platform: windows\nsetup: [login]",
                        "tests/falsch/y.yaml" to "name: Y\nsteps:\n  - do: { wait: 1 }",
                    )
            )

        assertEquals(1, Checker.check(project, listOf("$root/tests/falsch/y.yaml")).size)
        assertEquals(emptyList(), Checker.check(project, listOf("$root/tests/zz-web/x.yaml")))
    }
}
