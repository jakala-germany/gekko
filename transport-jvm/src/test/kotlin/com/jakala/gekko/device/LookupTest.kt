package com.jakala.gekko.device

import com.jakala.gekko.protocol.Bounds
import com.jakala.gekko.protocol.ErrorKind
import com.jakala.gekko.protocol.Selector
import com.jakala.gekko.protocol.UiNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LookupTest {
    private val window = Bounds(0, 0, 1080, 2000)

    private fun node(
        id: String,
        bounds: Bounds = Bounds(100, 100, 300, 200),
        enabled: Boolean = true,
        selected: Boolean? = null,
        children: List<UiNode> = emptyList(),
    ): UiNode {
        return UiNode(id = id, bounds = bounds, enabled = enabled, selected = selected, children = children)
    }

    private fun screen(vararg children: UiNode): UiNode {
        return UiNode(id = "window", bounds = window, children = children.toList())
    }

    @Test
    fun aNodeWithoutAreaIsNotOnScreen() {
        assertFalse(Visibility.isOnScreen(Bounds(100, 100, 100, 200), window))
        assertFalse(Visibility.isOnScreen(Bounds(100, 100, 300, 100), window))
        assertFalse(Visibility.isOnScreen(Bounds(300, 100, 100, 200), window))
        assertFalse(Visibility.isOnScreen(NO_BOUNDS, window))
    }

    @Test
    fun aNodeOutsideTheWindowIsNotOnScreen() {
        assertFalse(Visibility.isOnScreen(Bounds(100, 2100, 300, 2200), window))
        assertFalse(Visibility.isOnScreen(Bounds(-300, 100, -100, 200), window))
        assertFalse(Visibility.isOnScreen(Bounds(1080, 100, 1280, 200), window))
    }

    @Test
    fun aNodeWhoseCenterIsOutsideTheWindowIsNotOnScreen() {
        assertFalse(Visibility.isOnScreen(Bounds(900, 100, 1400, 200), window))
        assertTrue(Visibility.isOnScreen(Bounds(900, 100, 1200, 200), window))
    }

    @Test
    fun aNodeFullyOutsideTheClipOfItsAncestorsIsNotOnScreen() {
        val clip = Bounds(0, 500, 1080, 1500)

        assertFalse(Visibility.isOnScreen(Bounds(100, 100, 300, 200), window, clip))
        assertFalse(Visibility.isOnScreen(Bounds(100, 1500, 300, 1600), window, clip))
        assertTrue(Visibility.isOnScreen(Bounds(100, 480, 300, 700), window, clip))
        assertFalse(Visibility.isOnScreen(Bounds(100, 100, 300, 200), window, NO_BOUNDS))
    }

    @Test
    fun intersectingRectanglesThatOnlyTouchGivesNoArea() {
        assertEquals(NO_BOUNDS, Bounds(0, 0, 10, 10).intersect(Bounds(10, 0, 20, 10)))
        assertEquals(Bounds(5, 5, 10, 10), Bounds(0, 0, 10, 10).intersect(Bounds(5, 5, 20, 20)))
    }

    @Test
    fun anIndexCountsOnlyUsableNodes() {
        val tree =
            screen(
                node("row", Bounds(0, 2100, 500, 2200)),
                node("row", Bounds(0, 100, 500, 200), enabled = false),
                node("row", Bounds(0, 300, 500, 400)),
                node("row", Bounds(0, 500, 500, 600)),
            )

        val first = ElementLookup.find(listOf(tree), Selector(id = "row", index = 0))
        val second = ElementLookup.find(listOf(tree), Selector(id = "row", index = 1))
        val third = ElementLookup.find(listOf(tree), Selector(id = "row", index = 2))

        assertEquals(300, first.pick(0)?.bounds?.top)
        assertEquals(500, second.pick(1)?.bounds?.top)
        assertNull(third.pick(2))
        assertEquals(ErrorKind.NOT_FOUND, third.missing)
    }

    @Test
    fun notFoundWhenNothingMatches() {
        val lookup = ElementLookup.find(listOf(screen(node("a"))), Selector(id = "b"))

        assertNull(lookup.pick(0))
        assertEquals(ErrorKind.NOT_FOUND, lookup.missing)
        assertEquals(ErrorKind.NOT_FOUND, lookup.absent)
    }

    @Test
    fun notOnScreenWhenEveryMatchIsOutside() {
        val lookup = ElementLookup.find(listOf(screen(node("a", Bounds(0, 2100, 100, 2200)))), Selector(id = "a"))

        assertEquals(ErrorKind.NOT_ON_SCREEN, lookup.missing)
        assertEquals(ErrorKind.NOT_ON_SCREEN, lookup.absent)
    }

    @Test
    fun disabledWhenEveryNodeOnScreenIsDisabled() {
        val lookup = ElementLookup.find(listOf(screen(node("a", enabled = false))), Selector(id = "a"))

        assertNull(lookup.pick(0))
        assertEquals(ErrorKind.DISABLED, lookup.missing)
        assertEquals(1, lookup.onScreen.size)
    }

    @Test
    fun aSelectorForDisabledNodesFindsThem() {
        val lookup = ElementLookup.find(listOf(screen(node("a", enabled = false))), Selector(id = "a", enabled = false))

        assertEquals("a", lookup.pick(0)?.id)
    }

    @Test
    fun theClipHidesNodesThatAreStillInTheTree() {
        val tree = screen(node("row", Bounds(100, 100, 300, 200)))
        val hidden = ElementLookup.find(listOf(tree), Selector(id = "row")) { Bounds(0, 500, 1080, 1500) }

        assertEquals(ErrorKind.NOT_ON_SCREEN, hidden.missing)
    }

    @Test
    fun selectedIsPartOfTheSelector() {
        val tree =
            screen(
                node("tab", Bounds(0, 0, 100, 100), selected = true),
                node("tab", Bounds(100, 0, 200, 100), selected = false),
                node("tab", Bounds(200, 0, 300, 100)),
            )

        assertEquals(0, ElementLookup.find(listOf(tree), Selector(id = "tab", selected = true)).pick(0)?.bounds?.left)
        assertEquals(
            100,
            ElementLookup.find(listOf(tree), Selector(id = "tab", selected = false)).pick(0)?.bounds?.left,
        )
        assertEquals(3, ElementLookup.find(listOf(tree), Selector(id = "tab")).matches.size)
    }

    @Test
    fun withinLimitsTheMatchesToTheChildrenOfAContainer() {
        val tree =
            screen(
                node("input", Bounds(0, 0, 100, 100)),
                node("dialog", Bounds(0, 200, 500, 800), children = listOf(node("input", Bounds(0, 300, 100, 400)))),
            )

        val lookup = ElementLookup.find(listOf(tree), Selector(id = "input", within = "dialog"))

        assertEquals(300, lookup.pick(0)?.bounds?.top)
        assertEquals(1, lookup.matches.size)
    }

    @Test
    fun everyWindowIsTheReferenceForItsOwnNodes() {
        val small =
            UiNode(
                id = "popup",
                bounds = Bounds(500, 500, 800, 800),
                children = listOf(node("item", Bounds(520, 520, 700, 600))),
            )
        val lookup = ElementLookup.find(listOf(screen(), small), Selector(id = "item"))

        assertSame(small.children.first(), lookup.pick(0))
    }

    @Test
    fun anEmptySelectorMatchesNothing() {
        assertEquals(ErrorKind.NOT_FOUND, ElementLookup.find(listOf(screen(node("a"))), Selector()).missing)
    }
}
