package com.jakala.gekko.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class ProtocolTest {
    private val json = Json { classDiscriminator = "type" }

    private val tree =
        UiNode(
            id = "root",
            bounds = Bounds(0, 0, 100, 200),
            children =
                listOf(
                    UiNode(id = "home.tab.tickets", text = "Tickets", bounds = Bounds(0, 100, 50, 200)),
                    UiNode(id = "home.tab.tickets", text = "Tickets 2", bounds = Bounds(50, 100, 100, 200)),
                ),
        )

    @Test
    fun commandRoundTrips() {
        val command: Command = Command.Tap(Selector(id = "a"))

        val decoded = json.decodeFromString<Command>(json.encodeToString(command))

        assertEquals(command, decoded)
    }

    @Test
    fun gestureCommandsRoundTripWithTheirWireNames() {
        val double: Command = Command.DoubleTap(Selector(id = "photo"), timeoutMs = 3_000)
        val long: Command = Command.LongPress(Selector(text = "Row"), holdMs = 1_200)

        assertEquals(double, json.decodeFromString<Command>(json.encodeToString(double)))
        assertEquals(long, json.decodeFromString<Command>(json.encodeToString(long)))
        assertEquals(true, json.encodeToString(double).contains("\"type\":\"doubleTap\""))
        assertEquals(true, json.encodeToString(long).contains("\"type\":\"longPress\""))
        assertEquals(Command.DEFAULT_HOLD_MS, (Command.LongPress(Selector(id = "x"))).holdMs)
    }

    @Test
    fun responseRoundTrips() {
        val response: Response = Response.TreeResult(listOf(tree))

        val decoded = json.decodeFromString<Response>(json.encodeToString(response))

        assertEquals(response, decoded)
    }

    @Test
    fun findReturnsIndexedMatch() {
        val found = tree.find(Selector(id = "home.tab.tickets", index = 1))

        assertEquals("Tickets 2", found?.text)
    }

    @Test
    fun emptySelectorMatchesNothing() {
        assertNull(tree.find(Selector()))
    }

    @Test
    fun boundsCenter() {
        assertEquals(25, Bounds(0, 100, 50, 200).centerX)
        assertEquals(150, Bounds(0, 100, 50, 200).centerY)
    }

    @Test
    fun textMatchingIgnoresNonBreakingSpaces() {
        val node = UiNode(text = "20\u00A0Std.", bounds = Bounds(0, 0, 1, 1))

        assertEquals(true, Selector(text = "20 Std.").matches(node))
    }

    @Test
    fun screenIsSettledOnlyAfterAQuietPeriodWithoutProgress() {
        val tracker = SettleTracker(settleMs = 1_000, startedAt = 0)

        tracker.observe(signature = 1, busy = false, now = 100)
        assertEquals(false, tracker.isSettled(now = 900))
        tracker.observe(signature = 1, busy = false, now = 1_200)
        assertEquals(true, tracker.isSettled(now = 1_200))
        tracker.observe(signature = 2, busy = false, now = 1_300)
        assertEquals(false, tracker.isSettled(now = 1_400))
    }

    @Test
    fun aProgressIndicatorKeepsTheScreenUnsettled() {
        val tracker = SettleTracker(settleMs = 1_000, startedAt = 0)

        tracker.observe(signature = 1, busy = true, now = 5_000)

        assertEquals(false, tracker.isSettled(now = 5_500))
        assertEquals(true, tracker.isSettled(now = 6_000))
    }

    @Test
    fun settlingIsOffWithoutAWindow() {
        assertEquals(false, SettleTracker(settleMs = 0, startedAt = 0).isSettled(now = 99_999))
    }

    @Test
    fun signatureChangesWithContentAndBusyState() {
        val plain = UiNode(text = "a", bounds = Bounds(0, 0, 1, 1))

        assertEquals(plain.signature(), plain.copy().signature())
        assertEquals(false, plain.signature() == plain.copy(text = "b").signature())
        assertEquals(true, plain.copy(children = listOf(plain.copy(busy = true))).hasBusyNode())
    }

    @Test
    fun offScreenNodesAreNotVisible() {
        val window = Bounds(0, 0, 100, 200)

        val below = UiNode(bounds = Bounds(0, 300, 100, 340))
        val inside = UiNode(bounds = Bounds(0, 100, 100, 140))

        assertEquals(false, below.isOnScreen(window))
        assertEquals(true, inside.isOnScreen(window))
    }

    @Test
    fun waitForAndNotForegroundRoundTripWithTheirWireNames() {
        val wait: Command = Command.WaitFor(Selector(id = "a"), visible = false, timeoutMs = 3_000, settleMs = 500)
        val response: Response = Response.NotForeground

        assertEquals(wait, json.decodeFromString<Command>(json.encodeToString(wait)))
        assertEquals(response, json.decodeFromString<Response>(json.encodeToString(response)))
        assertEquals(true, json.encodeToString(wait).contains("\"type\":\"waitFor\""))
        assertEquals(true, json.encodeToString(response).contains("\"type\":\"notForeground\""))
    }
}

class VisibilityWaiterTest {
    private val window = Bounds(0, 0, 100, 200)
    private val button = UiNode(id = "go", bounds = Bounds(0, 100, 100, 140))

    /** A clock that only moves when the waiter pauses, so the tests need no real time. */
    private class Clock {
        var time = 0L

        fun now(): Long = time

        fun pause(ms: Long) {
            time += ms
        }
    }

    private fun waiter(clock: Clock): VisibilityWaiter {
        return VisibilityWaiter(clock::now, clock::pause, pollMs = 50)
    }

    private fun screen(vararg children: UiNode): List<UiNode> {
        return listOf(UiNode(bounds = window, children = children.toList()))
    }

    @Test
    fun answersAtOnceWhenTheElementIsAlreadyShown() {
        val clock = Clock()

        val failure = waiter(clock).await(Command.WaitFor(Selector(id = "go"))) { screen(button) }

        assertNull(failure)
        assertEquals(0, clock.time)
    }

    @Test
    fun waitsUntilTheElementAppears() {
        val clock = Clock()
        var reads = 0

        val failure =
            waiter(clock).await(Command.WaitFor(Selector(id = "go"), timeoutMs = 1_000)) {
                reads++
                if (reads < 4) screen() else screen(button)
            }

        assertNull(failure)
        assertEquals(150, clock.time)
    }

    @Test
    fun waitsUntilTheElementIsGone() {
        val clock = Clock()
        var reads = 0

        val failure =
            waiter(clock).await(Command.WaitFor(Selector(id = "go"), visible = false, timeoutMs = 1_000)) {
                reads++
                if (reads < 3) screen(button) else screen()
            }

        assertNull(failure)
    }

    @Test
    fun reportsTheMissingElementAfterTheTimeout() {
        val clock = Clock()

        val failure =
            waiter(clock).await(Command.WaitFor(Selector(id = "go"), timeoutMs = 200)) {
                screen(UiNode(text = "other", bounds = Bounds(0, 100, 100, 140), busy = true))
            }

        assertEquals("Expected visible: ${Selector(id = "go")}", failure)
    }

    @Test
    fun givesUpEarlyOnceTheScreenIsSettled() {
        val clock = Clock()

        val failure =
            waiter(clock).await(Command.WaitFor(Selector(id = "go"), timeoutMs = 10_000, settleMs = 300)) { screen() }

        assertEquals("Expected visible: ${Selector(id = "go")}", failure)
        assertEquals(true, clock.time < 1_000)
    }

    @Test
    fun anElementOutsideTheWindowDoesNotCount() {
        val clock = Clock()

        val failure =
            waiter(clock).await(Command.WaitFor(Selector(id = "go"), timeoutMs = 100)) {
                screen(button.copy(bounds = Bounds(0, 900, 100, 940)))
            }

        assertEquals("Expected visible: ${Selector(id = "go")}", failure)
    }

    @Test
    fun textMatchesTakesTheWholeTextAndIgnoresCase() {
        val node = UiNode(text = "Departure 14:32 Arrival 15:01", bounds = Bounds(0, 0, 1, 1))

        assertTrue(Selector(textMatches = "departure \\d\\d:\\d\\d.*").matches(node))
        assertFalse(Selector(textMatches = "Departure").matches(node))
    }

    @Test
    fun enabledAndCheckedFilterTheMatches() {
        val off = UiNode(id = "wallet", checked = false, bounds = Bounds(0, 0, 1, 1))
        val disabled = UiNode(id = "wallet", enabled = false, bounds = Bounds(0, 0, 1, 1))

        assertTrue(Selector(id = "wallet", checked = false).matches(off))
        assertFalse(Selector(id = "wallet", checked = true).matches(off))
        assertFalse(Selector(id = "wallet", checked = false).matches(disabled))
        assertTrue(Selector(id = "wallet", enabled = false).matches(disabled))
        assertFalse(Selector(enabled = false).matches(disabled))
    }

    @Test
    fun textMatchesAlsoAcceptsTheLiteralText() {
        val node = UiNode(text = "Wohin möchtest du?", bounds = Bounds(0, 0, 1, 1))

        assertTrue(Selector(textMatches = "Wohin möchtest du?").matches(node))
        assertFalse(Selector(textMatches = "Wohin möchtest ich?").matches(node))
    }

    @Test
    fun textSelectorsAlsoMatchTheAccessibilityLabel() {
        val tab = UiNode(text = "Fahrten", description = "Fahrten. Tab 3 von 5", bounds = Bounds(0, 0, 1, 1))

        assertTrue(Selector(text = "Fahrten. Tab 3 von 5").matches(tab))
        assertTrue(Selector(text = "Fahrten").matches(tab))
        assertTrue(Selector(textContains = "Tab 3").matches(tab))
        assertTrue(Selector(textMatches = "Fahrten\\..*Tab 3 von 5").matches(tab))
        assertFalse(Selector(text = "Tab 4 von 5").matches(tab))
    }
}
