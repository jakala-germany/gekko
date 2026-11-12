package com.jakala.gekko.cli

import com.jakala.gekko.protocol.Bounds
import com.jakala.gekko.protocol.Command
import com.jakala.gekko.protocol.ErrorKind
import com.jakala.gekko.protocol.Response
import com.jakala.gekko.protocol.Selector
import com.jakala.gekko.protocol.UiNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class FocusTest {
    private val teasers = Selector(id = "home.teaser")

    /** Teasers side by side, the one at [focus] has the focus; null: nothing has it. */
    private fun row(focus: Int?, count: Int = 5): List<UiNode> {
        val cards =
            (0 until count).map { index ->
                UiNode(
                    id = "home.teaser",
                    text = "Teaser ${'A' + index}",
                    bounds = Bounds(index * 100, 100, index * 100 + 90, 160),
                    focused = index == focus,
                    actions = listOf("click"),
                )
            }
        return listOf(UiNode(bounds = Bounds(0, 0, 1000, 600), children = cards))
    }

    /**
     * A remote and a row of teasers: a press of `right` moves the focus one teaser to the right, but not past [stopAt];
     * [answerFor] tells what the tree reads while the focus moves.
     */
    private class Remote(
        var focus: Int?,
        val count: Int = 5,
        val stopAt: Int = count - 1,
        val pressAnswer: Response = Response.Ok,
    ) {
        var presses = 0
        var reads = 0
        val pressed = mutableListOf<Command.PressKey>()
    }

    private fun TestScope.waiter(remote: Remote, tree: (Remote) -> List<UiNode>): FocusWaiter {
        val client = FakeClient { command ->
            when (command) {
                is Command.PressKey -> {
                    remote.presses++
                    remote.pressed += command
                    if (remote.pressAnswer is Response.Ok) {
                        remote.focus = remote.focus?.let { minOf(it + 1, remote.stopAt) }
                    }
                    remote.pressAnswer
                }
                Command.Tree -> {
                    remote.reads++
                    Response.TreeResult(tree(remote))
                }
                else -> Response.Ok
            }
        }
        val drivers = Drivers(FakeDevice(supportsWaitFor = true), GekkoConfig(), agent = client, system = client)
        return FocusWaiter(drivers, settleMs = 100, now = { testScheduler.currentTime })
    }

    private fun TestScope.waiter(remote: Remote): FocusWaiter {
        return waiter(remote) { row(it.focus, it.count) }
    }

    @Test
    fun theFocusIsOnTheDeepestNodeThatHasIt() {
        val inner = UiNode(id = "inner", bounds = Bounds(0, 0, 10, 10), focused = true)
        val outer = UiNode(id = "outer", bounds = Bounds(0, 0, 50, 50), focused = true, children = listOf(inner))

        assertEquals(
            "inner",
            FocusTree.focusedNode(listOf(UiNode(bounds = Bounds(0, 0, 99, 99), children = listOf(outer))))?.id,
        )
        assertNull(FocusTree.focusedNode(row(null)))
    }

    @Test
    fun aNodeHoldsTheFocusWhenSomethingInsideOfItHasIt() {
        val label = UiNode(id = "label", bounds = Bounds(0, 0, 10, 10), focused = true)
        val card = UiNode(id = "card", bounds = Bounds(0, 0, 50, 50), children = listOf(label))
        val other = UiNode(id = "other", bounds = Bounds(0, 0, 50, 50))

        assertTrue(FocusTree.holdsFocus(card))
        assertTrue(!FocusTree.holdsFocus(other))
    }

    @Test
    fun theIndexIsCountedBeforeTheFocusIsChecked() {
        val screen = row(focus = 1)

        assertTrue(FocusTree.holds(screen, teasers.copy(index = 1)))
        assertTrue(!FocusTree.holds(screen, teasers.copy(index = 0)))
        assertTrue(!FocusTree.holds(screen, teasers))
        assertTrue(!FocusTree.holds(screen, teasers.copy(index = 9)))
    }

    @Test
    fun theFailureNamesWhereTheFocusIs() {
        val screen = row(focus = 0)

        assertEquals(
            "Expected focused: Selector(id=home.teaser, index=1) (focus is on home.teaser \"Teaser A\")",
            FocusTree.failure(teasers.copy(index = 1), screen),
        )
        assertEquals(
            "Expected focused: Selector(id=home.teaser, index=1) (nothing has the focus)",
            FocusTree.failure(teasers.copy(index = 1), row(null)),
        )
        assertEquals("Element not found: Selector(id=missing)", FocusTree.failure(Selector(id = "missing"), screen))
    }

    @Test
    fun aValueFromTheAppCannotStartALineOrBeEndless() {
        val node = UiNode(id = "x", text = "first\nsecond " + "y".repeat(100), bounds = Bounds(0, 0, 1, 1))

        val described = FocusTree.describe(node)

        assertTrue(!described.contains('\n'), described)
        assertTrue(described.endsWith("…\""), described)
    }

    @Test
    fun anElementThatHasTheFocusPassesAtOnce() = runTest {
        val remote = Remote(focus = 1)

        assertNull(waiter(remote).awaitFocus(teasers.copy(index = 1), 1_000))
    }

    @Test
    fun theFocusMayArriveAfterTheFirstLook() = runTest {
        val remote = Remote(focus = 0)
        // The third look, 100 ms in, is the last one before the screen counts as done
        val late = waiter(remote) { row(if (it.reads < 3) 0 else 1) }

        assertNull(late.awaitFocus(teasers.copy(index = 1), 5_000))
        assertEquals(3, remote.reads)
    }

    @Test
    fun aFocusThatDoesNotArriveFailsAsSoonAsTheScreenStoppedChanging() = runTest {
        val remote = Remote(focus = 0)

        val failure = waiter(remote).awaitFocus(teasers.copy(index = 2), 60_000)

        assertEquals(
            "Expected focused: Selector(id=home.teaser, index=2) (focus is on home.teaser \"Teaser A\")",
            failure,
        )
        assertTrue(testScheduler.currentTime < 1_000, "waited ${testScheduler.currentTime} ms")
    }

    @Test
    fun aKeyIsPressedUntilTheElementHasTheFocus() = runTest {
        val remote = Remote(focus = 0)

        val failure =
            waiter(remote).pressUntil("right", teasers.copy(index = 3), max = 10, timeoutMs = 1_000, system = false)

        assertNull(failure)
        assertEquals(3, remote.presses)
        assertTrue(remote.pressed.all { it.key == "right" && it.times == 1 })
    }

    @Test
    fun nothingIsPressedWhenTheElementHasTheFocusAlready() = runTest {
        val remote = Remote(focus = 2)

        val failure =
            waiter(remote).pressUntil("right", teasers.copy(index = 2), max = 10, timeoutMs = 1_000, system = false)

        assertNull(failure)
        assertEquals(0, remote.presses)
    }

    @Test
    fun aFocusThatStopsMovingIsAFocusTrap() = runTest {
        val remote = Remote(focus = 0, stopAt = 1)

        val failure =
            waiter(remote).pressUntil("right", teasers.copy(index = 4), max = 10, timeoutMs = 1_000, system = false)

        assertEquals("focus stuck on home.teaser \"Teaser B\" after 2 presses of right", failure)
        assertEquals(2, remote.presses)
    }

    @Test
    fun aFocusEffectThatBouncesBackIsStillATrap() = runTest {
        val remote = Remote(focus = 0, stopAt = 0)
        // The element nudges to the next one for a moment and is back after the press
        val bouncing = waiter(remote) { row(if (it.presses > 0 && it.reads % 3 == 1) 1 else 0, it.count) }

        val failure = bouncing.pressUntil("right", teasers.copy(index = 3), max = 10, timeoutMs = 1_000, system = false)

        assertEquals("focus stuck on home.teaser \"Teaser A\" after 1 press of right", failure)
    }

    @Test
    fun aKeyThatDoesNotMoveTheFocusOfAScreenWithoutFocusSaysSo() = runTest {
        val remote = Remote(focus = null)

        val failure =
            waiter(remote).pressUntil("down", teasers.copy(index = 1), max = 3, timeoutMs = 1_000, system = false)

        assertEquals("nothing has the focus after 1 press of down", failure)
    }

    @Test
    fun theKeyIsPressedAtMostMaxTimes() = runTest {
        val remote = Remote(focus = 0)

        val failure =
            waiter(remote).pressUntil("right", teasers.copy(index = 4), max = 2, timeoutMs = 1_000, system = false)

        assertEquals(
            "Selector(id=home.teaser, index=4) did not get the focus after 2 presses of right " +
                "(focus is on home.teaser \"Teaser C\")",
            failure,
        )
        assertEquals(2, remote.presses)
    }

    @Test
    fun anElementThatIsNotThereAtTheEndIsMissing() = runTest {
        val remote = Remote(focus = 0)

        val failure =
            waiter(remote).pressUntil("right", Selector(id = "row.sports"), max = 2, timeoutMs = 1_000, system = false)

        assertEquals("Element not found: Selector(id=row.sports) after 2 presses of right", failure)
    }

    @Test
    fun aKeyThatCannotBePressedEndsTheSearch() = runTest {
        val remote = Remote(focus = 0, pressAnswer = Response.Error("The system refused press 1", ErrorKind.REJECTED))

        val failure =
            waiter(remote).pressUntil("right", teasers.copy(index = 3), max = 10, timeoutMs = 1_000, system = false)

        assertEquals("The system refused press 1", failure)
        assertEquals(1, remote.presses)
    }
}
