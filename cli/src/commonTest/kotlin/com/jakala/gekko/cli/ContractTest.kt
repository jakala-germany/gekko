package com.jakala.gekko.cli

import com.jakala.gekko.protocol.Protocol
import com.jakala.gekko.protocol.Response
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The parts of the test format and the CLI that are frozen with 1.0: what is accepted, what is refused and how. */
class ContractTest {
    private fun step(yaml: String): Step {
        return Yamls.parse(Step.serializer(), yaml.trimIndent())
    }

    private fun rejection(yaml: String): String {
        val error = assertFailsWith<Exception> { step(yaml) }
        return generateSequence<Throwable>(error) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
    }

    @Test
    fun aDoHoldsExactlyOneAction() {
        assertTrue(rejection("do: { tap: { id: a }, type: { id: b, value: x } }").contains("exactly one action"))
        assertTrue(rejection("do: {}").contains("needs an action"))
        assertTrue(rejection("do: { back: false }").contains("false is not an action"))
        assertNotNull(step("do: { back: true }").action.back)
    }

    @Test
    fun anUntilFollowsTheSameRule() {
        assertTrue(rejection("do: { tap: { id: a } }\nuntil: { tap: { id: a }, back: true }").contains("exactly one"))
    }

    @Test
    fun attemptsNeedAnUntil() {
        assertTrue(rejection("do: { tap: { id: a } }\nattempts: 3").contains("attempts needs an until"))
        assertEquals(3, step("do: { tap: { id: a } }\nuntil: { assertVisible: { id: b } }\nattempts: 3").attempts)
        assertTrue(rejection("do: { tap: { id: a } }\nattempts: 0").contains("at least 1"))
    }

    @Test
    fun untilAndTimeoutDoNotWorkOnFlowsAndRepeats() {
        assertTrue(rejection("do: { use: login }\nuntil: { assertVisible: { id: b } }").contains("until does not work"))
        assertTrue(rejection("do: { use: login }\ntimeout: 5000").contains("timeout does not work"))
    }

    @Test
    fun withOnlyGoesWithUse() {
        assertTrue(rejection("do: { tap: { id: a }, with: { x: y } }").contains("with: only goes with use:"))
    }

    @Test
    fun aSelectorNeedsSomethingToLookFor() {
        assertTrue(rejection("do: { tap: { index: 1 } }").contains("a selector needs"))
        assertTrue(rejection("do: { assertVisible: { enabled: true } }").contains("a selector needs"))
        assertTrue(rejection("do: { scroll: { within: List } }").contains("a selector needs"))
        assertNotNull(step("do: { tap: { point: '50%,50%' } }"))
    }

    @Test
    fun anIdForOnePlatformNeedsAPlatformLimit() {
        assertTrue(rejection("do: { tap: { id: { android: a } } }").contains("no value for ios"))
        assertNotNull(step("platform: android\ndo: { tap: { id: { android: a } } }"))
        assertNotNull(step("do: { tap: { id: { android: a, ios: b } } }"))
        assertTrue(rejection("do: { tap: { id: { andriod: a, ios: b } } }").contains("unknown key 'andriod'"))
    }

    @Test
    fun oldNamesAreRefusedWithAHint() {
        assertTrue(rejection("do: { doubleTapOn: { id: a } }").contains("renamed to 'doubleTap'"))
        assertTrue(rejection("do: { longPressOn: { id: a } }").contains("renamed to 'longPress'"))
        assertTrue(rejection("do: { scroll: { id: a, dy: 600 } }").contains("direction and distance"))
        assertTrue(rejection("do: { tapp: { id: a } }").contains("did you mean 'tap'?"))
    }

    @Test
    fun scrollsUseTheReadingDirectionVocabulary() {
        val scroll = step("do: { scroll: { id: list } }").action.scroll
        assertEquals("down", scroll?.direction)
        assertEquals(400, scroll?.distance)
        assertEquals("end", step("do: { scroll: { id: row, direction: end, distance: 250 } }").action.scroll?.direction)
        assertTrue(rejection("do: { scroll: { id: list, direction: sideways } }").contains("direction must be one of"))
        assertTrue(rejection("do: { scroll: { id: list, distance: 0 } }").contains("must be positive"))
        val until = step("do: { scrollUntilVisible: { id: card, in: { id: Row }, direction: start } }")
        assertEquals("Row", until.action.scrollUntilVisible?.container?.id?.shared)
        assertEquals("down", step("do: { scrollUntilVisible: { id: card } }").action.scrollUntilVisible?.direction)
    }

    @Test
    fun swipeStaysAFingerGesture() {
        assertNotNull(step("do: { swipe: { direction: left } }"))
        assertTrue(rejection("do: { swipe: { direction: start } }").contains("where the finger goes"))
    }

    @Test
    fun theSelectedStateIsASelectorKey() {
        assertEquals(
            true,
            step("do: { assertVisible: { id: Tab.Home, selected: true } }").action.assertVisible?.selected,
        )
        assertEquals(
            true,
            step("do: { tap: { id: Tab.Home, selected: true } }").action.tap?.toSelector(Platform.Ios)?.selected,
        )
    }

    @Test
    fun theOrientationIsPortraitOrLandscape() {
        assertEquals("landscape", step("do: { setOrientation: landscape }").action.setOrientation)
        assertTrue(rejection("do: { setOrientation: upside }").contains("portrait or landscape"))
    }

    @Test
    fun exitCodesTellTheKindOfProblem() {
        assertEquals(ExitCode.FAILED, ExitCode.of(StepFailure("Element not found")))
        assertEquals(ExitCode.USAGE, ExitCode.of(IllegalArgumentException("bad yaml")))
        assertEquals(ExitCode.USAGE, ExitCode.of(ConfigurationException("no gekko.yaml")))
        assertEquals(ExitCode.USAGE, ExitCode.of(IncompatibleVersionException("old agent")))
        assertEquals(ExitCode.INFRASTRUCTURE, ExitCode.of(InfrastructureException("agent did not respond")))
        assertEquals(ExitCode.INFRASTRUCTURE, ExitCode.of(IllegalStateException("adb failed")))
    }

    @Test
    fun aPeerWithTheSameProtocolNumberIsAccepted() {
        Compatibility.check(Response.HelloResult(Protocol.VERSION, "9.9.9", Protocol.ROLE_AGENT), Protocol.ROLE_AGENT)
    }

    @Test
    fun anOlderAgentIsToldToUpdateThePlugin() {
        val message =
            assertFailsWith<IncompatibleVersionException> {
                    Compatibility.check(Response.HelloResult(0, "0.9.0", Protocol.ROLE_AGENT), Protocol.ROLE_AGENT)
                }
                .message
                .orEmpty()
        assertTrue(message.contains("protocol 0"), message)
        assertTrue(message.contains("Gradle plugin"), message)
    }

    @Test
    fun aNewerPeerIsToldToUpdateTheCli() {
        val message =
            assertFailsWith<IncompatibleVersionException> {
                    Compatibility.check(
                        Response.HelloResult(Protocol.VERSION + 1, "2.0.0", Protocol.ROLE_AGENT),
                        Protocol.ROLE_AGENT,
                    )
                }
                .message
                .orEmpty()
        assertTrue(message.contains("Update the gekko CLI to 2.0.0"), message)
    }

    @Test
    fun aServerThatDoesNotKnowHelloIsTooOld() {
        assertFailsWith<IncompatibleVersionException> {
            Compatibility.check(Response.Error("unknown command"), Protocol.ROLE_RUNNER)
        }
    }

    @Test
    fun theWrongRoleOnAPortIsRefused() {
        assertFailsWith<IncompatibleVersionException> {
            Compatibility.check(
                Response.HelloResult(Protocol.VERSION, "1.0.0", Protocol.ROLE_RUNNER),
                Protocol.ROLE_AGENT,
            )
        }
    }
}
