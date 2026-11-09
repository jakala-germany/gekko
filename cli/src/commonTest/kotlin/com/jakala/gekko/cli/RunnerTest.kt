package com.jakala.gekko.cli

import com.jakala.gekko.protocol.Bounds
import com.jakala.gekko.protocol.Command
import com.jakala.gekko.protocol.ErrorKind
import com.jakala.gekko.protocol.Response
import com.jakala.gekko.protocol.UiNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class RunnerTest {
    private val screen =
        listOf(
            UiNode(
                bounds = Bounds(0, 0, 100, 200),
                children =
                    listOf(
                        UiNode(
                            id = "Search.Button.Large",
                            text = "Search",
                            bounds = Bounds(0, 100, 100, 140),
                            actions = listOf("click"),
                        )
                    ),
            )
        )

    private fun agent(tapAnswer: Response, seen: MutableList<Command> = mutableListOf()): FakeClient {
        return FakeClient { command ->
            seen += command
            when (command) {
                Command.Foreground -> Response.ForegroundResult(true)
                Command.Tree -> Response.TreeResult(screen)
                is Command.Tap -> tapAnswer
                else -> Response.Ok
            }
        }
    }

    private fun runner(
        platform: Platform,
        client: FakeClient,
        suggestIds: Boolean,
        log: MutableList<String> = mutableListOf(),
        healer: Healer? = null,
        device: FakeDevice = FakeDevice(supportsWaitFor = true),
        system: FakeClient = FakeClient { Response.Ok },
        agentStartTimeoutMs: Long = 20_000L,
    ): TestRunner {
        val drivers = Drivers(device, GekkoConfig(), agent = client, system = system)
        return TestRunner(
            Project("/tmp/gekko-runner-test", GekkoConfig()),
            platform,
            device,
            drivers,
            "/tmp/gekko-runner-test/results",
            { log += it },
            healer = healer,
            suggestIds = suggestIds,
            agentStartTimeoutMs = agentStartTimeoutMs,
        )
    }

    /** Answers like a model: a proposal for the action prompt, and `holds` for the assertion prompt. */
    private fun healer(holds: Boolean): Healer {
        return Healer(
            object : LlmClient {
                override suspend fun complete(system: String, user: String): String {
                    return if (system.startsWith("You repair")) {
                        """{"action":"tap","id":"Search.Button.Large","reason":"closest tab"}"""
                    } else {
                        """{"holds":$holds,"reason":"the screen shows it"}"""
                    }
                }
            }
        )
    }

    private fun tabAgent(seen: MutableList<Command>, missingTabId: Boolean): FakeClient {
        return FakeClient { command ->
            seen += command
            when (command) {
                Command.Foreground -> Response.ForegroundResult(true)
                Command.Tree -> Response.TreeResult(screen)
                is Command.Tap ->
                    if (missingTabId && command.selector.id == "Search.Button.Medium") {
                        Response.Error("Element not found: Selector(id=Search.Button.Medium)")
                    } else {
                        Response.Ok
                    }
                is Command.WaitFor ->
                    if (command.selector.id == "Screen.Search") {
                        Response.Error("Expected visible: Selector(id=Screen.Search)")
                    } else {
                        Response.Ok
                    }
                else -> Response.Ok
            }
        }
    }

    private val tabTest =
        """
        name: Tabs
        steps:
          - say: Open the search tab
            do: { tap: { id: Search.Button.Medium } }
            until: { assertVisible: { id: Screen.Search } }
        """

    private fun test(yaml: String): TestFile {
        return Yamls.parse(TestFile.serializer(), yaml.trimIndent())
    }

    private val locationTest =
        """
        name: Location
        steps:
          - say: Put the device in Hamburg
            do: { setLocation: { latitude: 53.5511, longitude: 9.9937 } }
        """

    @Test
    fun anEmulatorTakesTheLocationFromItsConsole() = runTest {
        val device = FakeDevice(supportsWaitFor = true)
        val system = FakeClient { Response.Ok }
        val runner = runner(Platform.Android, agent(Response.Ok), suggestIds = false, device = device, system = system)

        val result = runner.run(test(locationTest), "tests/location.yaml")

        assertNull(result.failure)
        assertEquals(listOf(53.5511 to 9.9937), device.locations)
        assertEquals(0, device.mockLocationAllowed)
        assertTrue(system.received.none { it is Command.SetLocation })
    }

    @Test
    fun aPhysicalDeviceGetsTheLocationFromTheSystemRunnerAndItEndsWithTheTest() = runTest {
        val device = FakeDevice(supportsWaitFor = true, locationConsole = false)
        val system = FakeClient { Response.Ok }
        val runner = runner(Platform.Android, agent(Response.Ok), suggestIds = false, device = device, system = system)

        val result = runner.run(test(locationTest), "tests/location.yaml")

        assertNull(result.failure)
        assertEquals(1, device.mockLocationAllowed)
        assertEquals(
            listOf(Command.Hello, Command.SetLocation(53.5511, 9.9937), Command.ClearLocation),
            system.received.filter { it == Command.Hello || it is Command.SetLocation || it == Command.ClearLocation },
        )
    }

    @Test
    fun aSimulatorThatCannotSetTheLocationFailsTheStep() = runTest {
        val device = FakeDevice(supportsWaitFor = true, locationConsole = false)
        val system = FakeClient { Response.Ok }
        val runner = runner(Platform.Ios, agent(Response.Ok), suggestIds = false, device = device, system = system)

        val result = runner.run(test(locationTest), "tests/location.yaml")

        assertTrue(result.failure.orEmpty().contains("emulators only"))
        assertTrue(system.received.none { it is Command.SetLocation })
    }

    @Test
    fun hideKeyboardAsksTheAgentOnIos() {
        runTest {
            val seen = mutableListOf<Command>()
            val runner = runner(Platform.Ios, agent(Response.Ok, seen), suggestIds = false)

            val result =
                runner.run(
                    test(
                        """
                        name: Keyboard
                        steps:
                          - say: Close the keyboard
                            do: { hideKeyboard: true }
                        """
                    ),
                    "tests/keyboard.yaml",
                )

            assertNull(result.failure)
            assertTrue(Command.HideKeyboard in seen)
        }
    }

    @Test
    fun aMissingIdFailsWithTheClosestElementsOnScreenWhenHealHasNoModel() {
        runTest {
            val tap = Response.Error("Element not found: Selector(id=Search.Button.Medium)")
            val runner = runner(Platform.Android, agent(tap), suggestIds = true)

            val result =
                runner.run(
                    test(
                        """
                        name: Search
                        steps:
                          - say: Open the search
                            do: { tap: { id: Search.Button.Medium } }
                        """
                    ),
                    "tests/search.yaml",
                )

            val failure = result.failure.orEmpty()
            assertTrue(failure.startsWith("Element not found: Selector(id=Search.Button.Medium)"))
            assertTrue(failure.contains("closest on screen: id=Search.Button.Large text=\"Search\""))
        }
    }

    @Test
    fun withoutHealTheFailureStaysAsItWas() {
        runTest {
            val tap = Response.Error("Element not found: Selector(id=Search.Button.Medium)")
            val runner = runner(Platform.Android, agent(tap), suggestIds = false)

            val result =
                runner.run(
                    test(
                        """
                        name: Search
                        steps:
                          - say: Open the search
                            do: { tap: { id: Search.Button.Medium } }
                        """
                    ),
                    "tests/search.yaml",
                )

            assertEquals("Element not found: Selector(id=Search.Button.Medium)", result.failure)
        }
    }

    @Test
    fun stepsForTheOtherPlatformSendNothingToTheDevice() {
        runTest {
            val seen = mutableListOf<Command>()
            val log = mutableListOf<String>()
            val runner = runner(Platform.Android, agent(Response.Ok, seen), suggestIds = false, log = log)

            val result =
                runner.run(
                    test(
                        """
                        name: Platforms
                        steps:
                          - say: Allow notifications in the system prompt
                            platform: ios
                            do: { tap: { action: click, index: 1, system: true } }
                          - say: Continue
                            do: { tap: { id: Search.Button.Large } }
                        """
                    ),
                    "tests/platforms.yaml",
                )

            assertNull(result.failure)
            assertEquals(1, seen.filterIsInstance<Command.Tap>().size)
            assertTrue(log.none { it.contains("Allow notifications") })
        }
    }

    private fun scripted(answer: (Command) -> Response, seen: MutableList<Command>): FakeClient {
        return FakeClient { command ->
            seen += command
            if (command == Command.Foreground) Response.ForegroundResult(true) else answer(command)
        }
    }

    @Test
    fun anOptionalStepThatFindsNothingIsSkipped() {
        runTest {
            val seen = mutableListOf<Command>()
            val client =
                scripted(
                    {
                        if (it is Command.Tap) Response.Error("Element not found: Selector(id=Banner.Close)")
                        else Response.Ok
                    },
                    seen,
                )
            val log = mutableListOf<String>()

            val result =
                runner(Platform.Android, client, suggestIds = false, log = log)
                    .run(
                        test(
                            """
                            name: Optional
                            steps:
                              - say: Close the banner
                                optional: true
                                do: { tap: { id: Banner.Close } }
                              - say: Go on
                                do: { assertVisible: { id: Search.Button.Large } }
                            """
                        ),
                        "tests/optional.yaml",
                    )

            assertNull(result.failure)
            assertTrue(log.any { it.contains("skipped, optional") })
        }
    }

    @Test
    fun aStepWithAnUnmetConditionDoesNothing() {
        runTest {
            val seen = mutableListOf<Command>()
            val client =
                scripted(
                    {
                        if (it is Command.WaitFor && it.visible) Response.Error("Expected visible: ${it.selector}")
                        else Response.Ok
                    },
                    seen,
                )

            val result =
                runner(Platform.Android, client, suggestIds = false)
                    .run(
                        test(
                            """
                            name: Condition
                            steps:
                              - say: Close the tooltip when there is one
                                if: { visible: { id: TooltipOverlay.card } }
                                do: { tap: { text: Schließen } }
                              - say: Nothing to do if the tooltip is gone
                                if: { notVisible: { id: TooltipOverlay.card } }
                                do: { tap: { id: Search.Button.Large } }
                            """
                        ),
                        "tests/condition.yaml",
                    )

            assertNull(result.failure)
            assertEquals(listOf("Search.Button.Large"), seen.filterIsInstance<Command.Tap>().map { it.selector.id })
        }
    }

    @Test
    fun copiedTextCanBeUsedByLaterSteps() {
        runTest {
            val seen = mutableListOf<Command>()
            val connections =
                listOf(
                    UiNode(
                        bounds = Bounds(0, 0, 100, 200),
                        children =
                            listOf(
                                UiNode(
                                    id = "Result.Row.2",
                                    bounds = Bounds(0, 100, 100, 140),
                                    children =
                                        listOf(
                                            UiNode(
                                                text = "Departure 14:32 Arrival 15:01",
                                                bounds = Bounds(0, 100, 100, 120),
                                            )
                                        ),
                                )
                            ),
                    )
                )
            val client = scripted({ if (it == Command.Tree) Response.TreeResult(connections) else Response.Ok }, seen)

            val result =
                runner(Platform.Android, client, suggestIds = false)
                    .run(
                        test(
                            """
                            name: Copy
                            steps:
                              - say: Remember the departure
                                do:
                                  copyText:
                                    from: { textContains: Departure, within: Result.Row.2 }
                                    match: "Departure \\d\\d:\\d\\d"
                                    as: first
                              - say: Open it
                                do: { tap: { textMatches: ".*${'$'}{var.first}.*" } }
                            """
                        ),
                        "tests/copy.yaml",
                    )

            assertNull(result.failure)
            assertEquals(".*Departure 14:32.*", seen.filterIsInstance<Command.Tap>().single().selector.textMatches)
        }
    }

    @Test
    fun repeatWhileStopsOnceTheConditionIsGone() {
        runTest {
            val seen = mutableListOf<Command>()
            var visible = 2
            val client =
                scripted(
                    {
                        when (it) {
                            is Command.WaitFor ->
                                if (it.visible == (visible > 0)) Response.Ok
                                else Response.Error("Expected: ${it.selector}")
                            is Command.Tap -> {
                                visible--
                                Response.Ok
                            }
                            else -> Response.Ok
                        }
                    },
                    seen,
                )

            val result =
                runner(Platform.Android, client, suggestIds = false)
                    .run(
                        test(
                            """
                            name: Loop
                            steps:
                              - say: Close the tooltips
                                do:
                                  repeat:
                                    while: { visible: { id: TooltipOverlay.card } }
                                    steps:
                                      - do: { tap: { text: Schließen } }
                            """
                        ),
                        "tests/loop.yaml",
                    )

            assertNull(result.failure)
            assertEquals(2, seen.filterIsInstance<Command.Tap>().size)
        }
    }

    @Test
    fun aMissingIdInTheUntilConditionIsHealedWithoutRepeatingTheAction() {
        runTest {
            val seen = mutableListOf<Command>()
            val runner = runner(Platform.Android, tabAgent(seen, missingTabId = false), false, healer = healer(true))

            val result = runner.run(test(tabTest), "tests/tabs.yaml")

            assertNull(result.failure)
            assertEquals(1, seen.count { it is Command.Tap })
            val heal = result.healed.single()
            assertEquals("id=Screen.Search", heal.missing)
            assertEquals("Open the search tab", heal.say)
            assertEquals("confirmed from the screen (no id check)", heal.applied)
        }
    }

    @Test
    fun anUntilConditionThatTheModelDoesNotSeeStaysAFailure() {
        runTest {
            val seen = mutableListOf<Command>()
            val runner = runner(Platform.Android, tabAgent(seen, missingTabId = false), false, healer = healer(false))

            val result = runner.run(test(tabTest), "tests/tabs.yaml")

            assertEquals("Expected visible: Selector(id=Screen.Search)", result.failure)
            assertTrue(result.healed.isEmpty())
        }
    }

    @Test
    fun bothTheActionAndTheUntilConditionOfOneStepAreHealed() {
        runTest {
            val seen = mutableListOf<Command>()
            val runner = runner(Platform.Android, tabAgent(seen, missingTabId = true), false, healer = healer(true))

            val result = runner.run(test(tabTest), "tests/tabs.yaml")

            assertNull(result.failure)
            assertEquals(listOf("id=Search.Button.Medium", "id=Screen.Search"), result.healed.map { it.missing })
        }
    }

    @Test
    fun anUntilConditionIsNotHealedWithoutTheHealOption() {
        runTest {
            val seen = mutableListOf<Command>()
            val runner = runner(Platform.Android, tabAgent(seen, missingTabId = false), false)

            val result = runner.run(test(tabTest), "tests/tabs.yaml")

            assertEquals("Expected visible: Selector(id=Screen.Search)", result.failure)
        }
    }

    /** A list of 300 px rows in a 1000 x 1600 px viewport that scrolls vertically; the screen is 1000 x 2000 px. */
    private class ScrollWorld(private val rows: Int, private val rtl: Boolean = false) {
        var offset = 0
        val seen = mutableListOf<Command>()
        private val maxOffset = rows * ROW - VIEWPORT_HEIGHT

        fun client(): FakeClient {
            return FakeClient { command ->
                seen += command
                when (command) {
                    Command.Foreground -> Response.ForegroundResult(true)
                    Command.Metrics -> Response.MetricsResult(1f, rtl)
                    Command.Tree -> Response.TreeResult(listOf(window()))
                    is Command.Drag -> {
                        offset = (offset + (command.fromY - command.toY)).coerceIn(0, maxOffset)
                        Response.Ok
                    }
                    is Command.WaitFor ->
                        if (onScreen(command.selector.id)) Response.Ok
                        else Response.Error("Expected visible: ${command.selector}")
                    else -> Response.Ok
                }
            }
        }

        private fun onScreen(id: String?): Boolean {
            val row = window().children.single().children.firstOrNull { it.id == id } ?: return false
            return row.bounds.centerY in 0 until 2000
        }

        private fun window(): UiNode {
            val list =
                UiNode(
                    id = "List",
                    bounds = Bounds(0, TOP, 1000, TOP + VIEWPORT_HEIGHT),
                    actions = listOf("scroll"),
                    children =
                        (0 until rows).map { index ->
                            val top = TOP + index * ROW - offset
                            UiNode(
                                id = "Row.$index",
                                bounds = Bounds(0, top, 1000, top + ROW),
                                actions = listOf("click"),
                            )
                        },
                )
            return UiNode(bounds = Bounds(0, 0, 1000, 2000), children = listOf(list))
        }

        companion object {
            const val ROW = 300
            const val TOP = 200
            const val VIEWPORT_HEIGHT = 1600
        }
    }

    @Test
    fun scrollingDownMovesTheFingerUpUntilTheRowIsCompletelyInsideTheList() {
        runTest {
            val world = ScrollWorld(rows = 12)
            val result =
                runner(Platform.Android, world.client(), suggestIds = false)
                    .run(test("name: s\nsteps:\n  - do: { scrollUntilVisible: { id: Row.8 } }"), "tests/s.yaml")

            assertNull(result.failure)
            val swipes = world.seen.filterIsInstance<Command.Drag>()
            assertTrue(swipes.isNotEmpty())
            assertTrue(world.seen.none { it is Command.Swipe }, "a fling would carry the list past the row")
            assertTrue(swipes.all { it.fromY > it.toY }, "the finger moves up to show content below")
            val top = ScrollWorld.TOP + 8 * ScrollWorld.ROW - world.offset
            assertTrue(top >= ScrollWorld.TOP && top + ScrollWorld.ROW <= ScrollWorld.TOP + ScrollWorld.VIEWPORT_HEIGHT)
        }
    }

    @Test
    fun aListThatStopsMovingEndsTheSearchAtOnce() {
        runTest {
            val world = ScrollWorld(rows = 12)
            val result =
                runner(Platform.Android, world.client(), suggestIds = false)
                    .run(
                        test("name: s\nsteps:\n  - do: { scrollUntilVisible: { id: Row.99, maxScrolls: 50 } }"),
                        "tests/s.yaml",
                    )

            assertTrue(result.failure.orEmpty().contains("end of the list reached"), result.failure)
            assertTrue(world.seen.filterIsInstance<Command.Drag>().size < 20)
        }
    }

    @Test
    fun aContainerKeepsTheSwipesInsideItself() {
        runTest {
            val world = ScrollWorld(rows = 12)
            runner(Platform.Android, world.client(), suggestIds = false)
                .run(
                    test(
                        "name: s\nsteps:\n  - do: { scrollUntilVisible: { id: Row.8, in: { id: List }, direction: down } }"
                    ),
                    "tests/s.yaml",
                )

            val swipes = world.seen.filterIsInstance<Command.Drag>()
            assertTrue(swipes.isNotEmpty())
            assertTrue(swipes.all { it.fromY in ScrollWorld.TOP..(ScrollWorld.TOP + ScrollWorld.VIEWPORT_HEIGHT) })
        }
    }

    @Test
    fun theEndOfAHorizontalListFollowsTheLayoutDirection() {
        runTest {
            for ((rtl, expectedRight) in listOf(false to false, true to true)) {
                val world = ScrollWorld(rows = 12, rtl = rtl)
                runner(Platform.Android, world.client(), suggestIds = false)
                    .run(
                        test(
                            "name: s\nsteps:\n  - do: { scrollUntilVisible: { id: Row.99, direction: end, maxScrolls: 1 } }"
                        ),
                        "tests/s.yaml",
                    )

                val swipe = world.seen.filterIsInstance<Command.Drag>().first()
                assertEquals(expectedRight, swipe.toX > swipe.fromX, "rtl=$rtl")
            }
        }
    }

    @Test
    fun aScrollCommandCarriesTheDirectionAndTheDistanceInDp() {
        runTest {
            val world = ScrollWorld(rows = 3)
            runner(Platform.Android, world.client(), suggestIds = false)
                .run(
                    test("name: s\nsteps:\n  - do: { scroll: { id: List, direction: end, distance: 250 } }"),
                    "tests/s.yaml",
                )

            val scroll = world.seen.filterIsInstance<Command.Scroll>().single()
            assertEquals("end", scroll.direction)
            assertEquals(250, scroll.distanceDp)
        }
    }

    private suspend fun runStep(platform: Platform, yaml: String, agent: FakeClient, system: FakeClient): String? {
        val result = runner(platform, agent, suggestIds = false, system = system).run(test(yaml), "tests/s.yaml")
        return result.failure
    }

    @Test
    fun aDragBetweenTwoSpotsMovesTheContentThroughTheAgent() {
        runTest {
            val agent = agent(Response.Ok)
            val system = FakeClient { Response.Ok }

            val failure =
                runStep(
                    Platform.Android,
                    "name: s\nsteps:\n  - do: { drag: { start: \"50%,80%\", end: \"50%,30%\" } }",
                    agent,
                    system,
                )

            assertNull(failure)
            assertEquals(listOf(Command.Drag(50, 160, 50, 60)), agent.received.filterIsInstance<Command.Drag>())
            assertTrue(system.received.isEmpty())
        }
    }

    @Test
    fun aDragFromAnElementStartsInItsCenter() {
        runTest {
            val agent = agent(Response.Ok)

            val failure =
                runStep(
                    Platform.Android,
                    "name: s\nsteps:\n  - do: { drag: { from: { id: Search.Button.Large }, end: \"50%,30%\" } }",
                    agent,
                    FakeClient { Response.Ok },
                )

            assertNull(failure)
            assertEquals(listOf(Command.Drag(50, 120, 50, 60)), agent.received.filterIsInstance<Command.Drag>())
        }
    }

    @Test
    fun aDragCanBeForcedToTheSystemRunner() {
        runTest {
            val agent = agent(Response.Ok)
            val system = FakeClient { Response.Ok }

            val failure =
                runStep(
                    Platform.Android,
                    "name: s\nsteps:\n  - do: { drag: { start: \"50%,80%\", end: \"50%,30%\", system: true } }",
                    agent,
                    system,
                )

            assertNull(failure)
            assertEquals(1, system.received.filterIsInstance<Command.Drag>().size)
            assertTrue(agent.received.none { it is Command.Drag })
        }
    }

    @Test
    fun aDragThatTheIosAgentCannotMakeIsTriedOnTheSystemRunner() {
        runTest {
            val agent = FakeClient { command ->
                when (command) {
                    Command.Foreground -> Response.ForegroundResult(true)
                    Command.Tree -> Response.TreeResult(screen)
                    is Command.Drag -> Response.Error("No scroll view moved", ErrorKind.UNSUPPORTED)
                    else -> Response.Ok
                }
            }
            val system = FakeClient { Response.Ok }

            val failure =
                runStep(
                    Platform.Ios,
                    "name: s\nsteps:\n  - do: { drag: { start: \"50%,80%\", end: \"50%,30%\" } }",
                    agent,
                    system,
                )

            assertNull(failure)
            assertEquals(1, agent.received.filterIsInstance<Command.Drag>().size)
            assertEquals(1, system.received.filterIsInstance<Command.Drag>().size)
        }
    }

    @Test
    fun aSwipeOnIosIsARealTouchOfTheSystemRunner() {
        runTest {
            val agent = agent(Response.Ok)
            val system = FakeClient { Response.Ok }

            val failure =
                runStep(
                    Platform.Ios,
                    "name: s\nsteps:\n  - do: { swipe: { direction: up, durationMs: 300 } }",
                    agent,
                    system,
                )

            assertNull(failure)
            assertEquals(300L, system.received.filterIsInstance<Command.Swipe>().single().durationMs)
            assertTrue(agent.received.none { it is Command.Swipe })
        }
    }

    @Test
    fun aSwipeOnAndroidGoesToTheAgentUnlessItIsForced() {
        runTest {
            val agent = agent(Response.Ok)
            val system = FakeClient { Response.Ok }

            runStep(Platform.Android, "name: s\nsteps:\n  - do: { swipe: { direction: up } }", agent, system)
            runStep(
                Platform.Android,
                "name: s\nsteps:\n  - do: { swipe: { direction: up, system: true } }",
                agent,
                system,
            )

            assertEquals(1, agent.received.filterIsInstance<Command.Swipe>().size)
            assertEquals(1, system.received.filterIsInstance<Command.Swipe>().size)
        }
    }

    @Test
    fun aLongPressOnASpotHoldsThatSpotOfTheScreen() {
        runTest {
            val agent = agent(Response.Ok)

            val failure =
                runStep(
                    Platform.Android,
                    "name: s\nsteps:\n  - do: { longPress: { point: \"50%,50%\", holdMs: 900 } }",
                    agent,
                    FakeClient { Response.Ok },
                )

            assertNull(failure)
            assertEquals(
                listOf(Command.LongPressAt(50, 100, 900)),
                agent.received.filterIsInstance<Command.LongPressAt>(),
            )
        }
    }

    @Test
    fun aLongPressOnASpotOnIosIsATouchOfTheSystemRunner() {
        runTest {
            val agent = agent(Response.Ok)
            val system = FakeClient { Response.Ok }

            val failure =
                runStep(
                    Platform.Ios,
                    "name: s\nsteps:\n  - do: { longPress: { point: \"50%,50%\" } }",
                    agent,
                    system,
                )

            assertNull(failure)
            assertEquals(1, system.received.filterIsInstance<Command.LongPressAt>().size)
            assertTrue(agent.received.none { it is Command.LongPressAt })
        }
    }

    @Test
    fun anOptionalStepStillFailsWhenItsUntilIsNotReached() {
        runTest {
            val seen = mutableListOf<Command>()
            val client =
                scripted(
                    {
                        if (it is Command.WaitFor && it.selector.id == "Next.Screen") {
                            Response.Error("Expected visible: Selector(id=Next.Screen)")
                        } else {
                            Response.Ok
                        }
                    },
                    seen,
                )

            val result =
                runner(Platform.Android, client, suggestIds = false)
                    .run(
                        test(
                            """
                            name: Optional until
                            steps:
                              - optional: true
                                do: { tap: { id: Search.Button.Large } }
                                until: { assertVisible: { id: Next.Screen } }
                            """
                        ),
                        "tests/optional.yaml",
                    )

            assertEquals("Expected visible: Selector(id=Next.Screen)", result.failure)
        }
    }

    private val killedStartTest =
        """
        name: Killed start
        steps:
          - say: Look at the screen
            do: { assertVisible: { id: Search.Button.Large } }
        """

    @Test
    fun anAppThatIsGoneRightAfterItsStartIsStartedOnce() {
        runTest {
            val device = FakeDevice(supportsWaitFor = true, appRunning = false)
            // The first process is killed: only the second one answers
            val client = FakeClient { command ->
                when (command) {
                    Command.Foreground -> Response.ForegroundResult(true)
                    else -> Response.Ok
                }
            }
            client.reachable = { device.events.count { it == "launch" } >= 2 }
            val log = mutableListOf<String>()

            val result =
                runner(
                        Platform.Android,
                        client,
                        suggestIds = false,
                        log = log,
                        device = device,
                        agentStartTimeoutMs = 200,
                    )
                    .run(test(killedStartTest), "tests/killed.yaml")

            assertNull(result.failure)
            assertEquals(listOf("launch", "launch"), device.events)
            assertTrue(log.any { it.contains("starting it again") })
        }
    }

    @Test
    fun anAppThatStillRunsButDoesNotAnswerIsNotStartedAgain() {
        runTest {
            val device = FakeDevice(supportsWaitFor = true, appRunning = true)
            val client = FakeClient { Response.Ok }
            client.reachable = { false }

            val result =
                runner(Platform.Android, client, suggestIds = false, device = device, agentStartTimeoutMs = 200)
                    .run(test(killedStartTest), "tests/killed.yaml")

            assertEquals("In-process agent did not respond; is the gekko agent part of this build?", result.failure)
            assertEquals(listOf("launch"), device.events)
        }
    }

    @Test
    fun anAppThatIsGoneAgainAfterTheSecondStartFails() {
        runTest {
            val device = FakeDevice(supportsWaitFor = true, appRunning = false)
            val client = FakeClient { Response.Ok }
            client.reachable = { false }

            val result =
                runner(Platform.Android, client, suggestIds = false, device = device, agentStartTimeoutMs = 200)
                    .run(test(killedStartTest), "tests/killed.yaml")

            assertEquals("In-process agent did not respond; is the gekko agent part of this build?", result.failure)
            assertEquals(listOf("launch", "launch"), device.events)
        }
    }
}
