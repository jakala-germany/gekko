package com.jakala.gekko.cli

import com.jakala.gekko.protocol.Command
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelTest {
    @Test
    fun parsesSharedAndPlatformIds() {
        val yaml =
            """
            name: Ids
            steps:
              - say: shared
                do: { tap: { id: home.tab } }
              - say: per platform
                do: { tap: { id: { android: a.b, ios: a_b } } }
            """
                .trimIndent()

        val test = Yamls.parse(TestFile.serializer(), yaml)

        val shared = test.steps[0].action.tap!!.toSelector(Platform.Ios)
        val android = test.steps[1].action.tap!!.toSelector(Platform.Android)
        val ios = test.steps[1].action.tap!!.toSelector(Platform.Ios)
        assertEquals("home.tab", shared.id)
        assertEquals("a.b", android.id)
        assertEquals("a_b", ios.id)
    }

    @Test
    fun parsesTagsAndUses() {
        val yaml =
            """
            name: Buy
            tags: [smoke]
            uses: [login]
            steps:
              - do: { assertVisible: { text: Hi } }
            """
                .trimIndent()

        val test = Yamls.parse(TestFile.serializer(), yaml)

        assertEquals(listOf("smoke"), test.tags)
        assertEquals(listOf("login"), test.uses)
        assertNull(test.steps[0].say)
    }

    @Test
    fun substitutesParams() {
        val result = Yamls.substitute("value: \"\${name}\"", mapOf("name" to "Ada"))

        assertEquals("value: \"Ada\"", result)
    }

    @Test
    fun missingParamFails() {
        assertFailsWith<IllegalStateException> { Yamls.substitute("\${nope}", emptyMap()) }
    }

    @Test
    fun unknownActionKeyIsRejected() {
        val yaml =
            """
            name: Bad
            steps:
              - do: { tapp: { id: x } }
            """
                .trimIndent()

        assertFailsWith<Exception> { Yamls.parse(TestFile.serializer(), yaml) }
    }

    @Test
    fun junitReportsFailures() {
        val results =
            listOf(
                TestResult("ok", "ok.yaml", 1000, emptyList(), null, null, emptyList()),
                TestResult("bad <x>", "bad.yaml", 2000, emptyList(), "boom", "step", listOf("a.png")),
            )

        val xml = Reporter.junit("suite", results)

        assertTrue(xml.contains("tests=\"2\" failures=\"1\""))
        assertTrue(xml.contains("bad &lt;x&gt;"))
        assertTrue(xml.contains("Artifacts: a.png"))
    }

    @Test
    fun junitHealedListsOnlyHealedSteps() {
        val record = HealRecord("Open <tabs>", "Open the tab", "id=Old", "tap id=New", "closest & visible")
        val results =
            listOf(
                TestResult("ok", "ok.yaml", 1000, emptyList(), null, null, emptyList()),
                TestResult(
                    "Open <tabs>",
                    "tabs.yaml",
                    2000,
                    emptyList(),
                    null,
                    null,
                    emptyList(),
                    healed = listOf(record),
                ),
            )

        val xml = Reporter.junitHealed("suite", results)

        assertTrue(xml.contains("tests=\"1\" failures=\"1\""))
        assertTrue(xml.contains("Open &lt;tabs&gt; › Open the tab"))
        assertTrue(xml.contains("message=\"id=Old missing; used tap id=New\""))
        assertTrue(xml.contains(">closest &amp; visible</failure>"))
        assertTrue(Reporter.junitHealed("suite", emptyList()).contains("tests=\"0\" failures=\"0\""))
    }

    @Test
    fun treePrinterSkipsPlainContainers() {
        val tree =
            com.jakala.gekko.protocol.UiNode(
                bounds = com.jakala.gekko.protocol.Bounds(0, 0, 1, 1),
                children =
                    listOf(
                        com.jakala.gekko.protocol.UiNode(
                            id = "x",
                            text = "T",
                            bounds = com.jakala.gekko.protocol.Bounds(0, 0, 1, 1),
                        )
                    ),
            )

        assertEquals("node id=x text=\"T\"\n", TreePrinter.print(listOf(tree)))
    }

    @Test
    fun parsesPlatformFilteredStepsAndScopedSelectors() {
        val yaml =
            """
            name: Platform
            steps:
              - say: android only
                platform: android
                do: { tap: { within: { android: picker.android, ios: picker_ios }, action: setText, index: 1 } }
              - say: both
                do: { tap: { id: x } }
            """
                .trimIndent()

        val test = Yamls.parse(TestFile.serializer(), yaml)

        assertEquals("android", test.steps[0].platform)
        val selector = test.steps[0].action.tap!!.toSelector(Platform.Ios)
        assertEquals("picker_ios", selector.within)
        assertEquals("setText", selector.action)
        assertEquals(1, selector.index)
        assertNull(test.steps[1].platform)
    }

    @Test
    fun parsesUntilAndAttempts() {
        val yaml =
            """
            name: Retry
            steps:
              - say: open details
                do: { tap: { id: card } }
                until: { assertVisible: { id: details } }
                attempts: 3
            """
                .trimIndent()

        val step = Yamls.parse(TestFile.serializer(), yaml).steps[0]

        assertEquals(3, step.attempts)
        assertEquals("details", step.until!!.assertVisible!!.id!!.shared)
    }

    @Test
    fun deviceSettingsAreParsedAndOverridden() {
        val yaml =
            """
            name: Time
            device: { language: en-US, timeFormat: 12h }
            steps:
              - do: { wait: 1 }
            """
                .trimIndent()
        val test = Yamls.parse(TestFile.serializer(), yaml)
        val defaults = DeviceSettings(language = "de-DE", timeFormat = "24h")

        val effective = defaults.overriddenBy(test.device)

        assertEquals("en-US", effective.language)
        assertEquals("12h", effective.timeFormat)
        assertEquals("de-DE", defaults.overriddenBy(DeviceSettings(timeFormat = "12h")).language)
        assertEquals(true, DeviceSettings().isEmpty)
    }

    @Test
    fun invalidTimeFormatIsRejected() {
        assertFailsWith<Exception> { Yamls.parse(DeviceSettings.serializer(), "timeFormat: 25h") }
    }

    @Test
    fun relativeProjectRootsBecomeAbsolute() {
        assertEquals("/work/app", absolutePath(".", "/work/app"))
        assertEquals("/work/app/tests", absolutePath("./tests", "/work/app"))
        assertEquals("/work/app/tests", absolutePath("tests/", "/work/app"))
        assertEquals("/other", absolutePath("/other", "/work/app"))
    }

    @Test
    fun actionsCanBeRestrictedToAPlatform() {
        val step =
            Yamls.parse(
                Step.serializer(),
                "say: Choose\ndo: { tap: { id: Row } }\nuntil: { platform: ios, assertNotVisible: { id: Row } }\nattempts: 3",
            )

        assertTrue(step.action.runsOn(Platform.Android) && step.action.runsOn(Platform.Ios))
        assertTrue(step.until?.runsOn(Platform.Ios) == true)
        assertTrue(step.until?.runsOn(Platform.Android) == false)
        assertFailsWith<Exception> { Yamls.parse(Action.serializer(), "{ platform: windows, wait: 1 }") }
    }

    @Test
    fun interactionActionsParse() {
        val scroll =
            Yamls.parse(
                Step.serializer(),
                "do: { scrollUntilVisible: { id: Result.Row.5, direction: up, maxScrolls: 4 } }",
            )
        assertEquals(4, scroll.action.scrollUntilVisible?.maxScrolls)
        assertEquals("Result.Row.5", scroll.action.scrollUntilVisible?.selector(Platform.Android)?.id)

        val erase = Yamls.parse(Step.serializer(), "do: { eraseText: { id: Search.Input } }")
        assertEquals("Search.Input", erase.action.eraseText?.toSelector(Platform.Ios)?.id)

        assertEquals(true, Yamls.parse(Step.serializer(), "do: { back: true }").action.back)
        assertEquals(
            true,
            Yamls.parse(Step.serializer(), "do: { waitForAnimationToEnd: true }").action.waitForAnimationToEnd,
        )
        assertEquals("login", Yamls.parse(Step.serializer(), "do: { takeScreenshot: login }").action.takeScreenshot)
    }

    @Test
    fun lifecycleActionsParse() {
        assertEquals(
            true,
            Yamls.parse(Step.serializer(), "do: { launchApp: { clearState: true } }").action.launchApp?.clearState,
        )
        assertEquals(false, Yamls.parse(Step.serializer(), "do: { launchApp: {} }").action.launchApp?.clearState)
        assertEquals(true, Yamls.parse(Step.serializer(), "do: { stopApp: true }").action.stopApp)
        assertEquals(true, Yamls.parse(Step.serializer(), "do: { clearState: true }").action.clearState)
    }

    @Test
    fun deviceActionsParse() {
        assertEquals(true, Yamls.parse(Step.serializer(), "do: { setDarkMode: true }").action.setDarkMode)
        assertEquals(false, Yamls.parse(Step.serializer(), "do: { setAirplaneMode: false }").action.setAirplaneMode)
        val location =
            Yamls.parse(Step.serializer(), "do: { setLocation: { latitude: 53.55, longitude: 9.99 } }").action
        assertEquals(53.55, location.setLocation?.latitude)
        assertEquals(9.99, location.setLocation?.longitude)
        val permissions =
            Yamls.parse(Step.serializer(), "do: { setPermissions: { location: allow, notifications: deny } }")
        assertEquals(mapOf("location" to "allow", "notifications" to "deny"), permissions.action.setPermissions)
    }

    @Test
    fun repeatHoldsNestedSteps() {
        val step =
            Yamls.parse(
                Step.serializer(),
                """
                do:
                  repeat:
                    times: 3
                    steps:
                      - say: Next page
                        do: { swipe: { direction: left } }
                      - do: { tap: { id: Next } }
                """
                    .trimIndent(),
            )
        val repeat = step.action.repeat
        assertEquals(3, repeat?.times)
        assertEquals(2, repeat?.steps?.size)
        assertEquals("left", repeat?.steps?.first()?.action?.swipe?.direction)
    }

    @Test
    fun invalidCountsAreRejected() {
        assertFailsWith<Exception> { Yamls.parse(Step.serializer(), "do: { repeat: { times: 0, steps: [] } }") }
        assertFailsWith<Exception> {
            Yamls.parse(Step.serializer(), "do: { scrollUntilVisible: { id: Row, maxScrolls: 0 } }")
        }
    }

    @Test
    fun touchGesturesParse() {
        val double = Yamls.parse(Step.serializer(), "do: { doubleTap: { id: Photo } }").action
        assertEquals("Photo", double.doubleTap?.toSelector(Platform.Android)?.id)

        val long = Yamls.parse(Step.serializer(), "do: { longPress: { id: Row, holdMs: 1200, index: 1 } }").action
        assertEquals(1200, long.longPress?.holdMs)
        assertEquals(1, long.longPress?.toSelector(Platform.Ios)?.index)
        assertEquals(
            Command.DEFAULT_HOLD_MS,
            Yamls.parse(Step.serializer(), "do: { longPress: { text: Menu } }").action.longPress?.holdMs,
        )
        assertFailsWith<Exception> { Yamls.parse(Step.serializer(), "do: { longPress: { id: Row, holdMs: 0 } }") }
    }

    @Test
    fun parsesOptionalConditionsCaptureAndSwipes() {
        val yaml =
            """
            name: New keys
            steps:
              - say: Close the banner
                optional: true
                do: { tap: { id: GlobalMessage.Close } }
              - say: Log in from the PDP
                if: { visible: { textMatches: "Zu den Kauf.*" } }
                do: { use: functions/login }
              - say: Remember the departure
                do: { copyText: { from: { textContains: Departure, within: Row }, match: "\\d\\d:\\d\\d", as: first } }
              - say: Buy
                do: { swipe: { from: { id: Offer.Detail.BuyButton }, direction: right, durationMs: 400 } }
              - say: Back out
                do:
                  repeat:
                    while: { visible: { text: Selection } }
                    max: 3
                    steps:
                      - do: { back: true }
            """
                .trimIndent()

        val steps = Yamls.parse(TestFile.serializer(), yaml).steps

        assertTrue(steps[0].optional)
        assertEquals("Zu den Kauf.*", steps[1].condition?.visible?.textMatches)
        assertEquals("first", steps[2].action.copyText?.name)
        assertEquals(400L, steps[3].action.swipe?.durationMs)
        assertEquals(3, steps[4].action.repeat?.max)
    }

    @Test
    fun rejectsIncompleteSwipesAndOptionalFlows() {
        assertFailsWith<Exception> { Yamls.parse(Step.serializer(), "do: { swipe: { durationMs: 100 } }") }
        assertFailsWith<Exception> { Yamls.parse(Step.serializer(), "do: { swipe: { start: '50%,80%' } }") }
        assertFailsWith<Exception> { Yamls.parse(Step.serializer(), "optional: true\ndo: { use: flow }") }
        assertFailsWith<Exception> { Yamls.parse(Step.serializer(), "if: {}\ndo: { back: true }") }
        assertFailsWith<Exception> {
            Yamls.parse(Step.serializer(), "do: { repeat: { times: 2, while: { visible: { id: x } }, steps: [] } }")
        }
    }

    @Test
    fun runtimeVariablesSurviveLoading() {
        assertEquals(
            "tap \${var.first} \${date(-1, 'd')}",
            Yamls.substitute("tap \${var.first} \${date(-1, 'd')}", emptyMap()),
        )
    }

    @Test
    fun parameterValuesWithBackslashesAndQuotesSurviveInDoubleQuotes() {
        val text = Yamls.substitute("textMatches: \"\${START}\"", mapOf("START" to "Wohin möchtest du\\? \"x\""))

        val parsed = Yamls.parse(SelectorSpec.serializer(), text)

        assertEquals("Wohin möchtest du\\? \"x\"", parsed.textMatches)
    }
}
