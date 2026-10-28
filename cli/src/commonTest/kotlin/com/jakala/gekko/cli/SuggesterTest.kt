package com.jakala.gekko.cli

import com.jakala.gekko.protocol.Bounds
import com.jakala.gekko.protocol.Selector
import com.jakala.gekko.protocol.UiNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SuggesterTest {
    private fun screen(vararg children: UiNode): List<UiNode> {
        return listOf(UiNode(bounds = Bounds(0, 0, 100, 200), children = children.toList()))
    }

    private fun node(id: String? = null, text: String? = null, clickable: Boolean = false): UiNode {
        return UiNode(
            id = id,
            text = text,
            bounds = Bounds(0, 0, 10, 10),
            actions = if (clickable) listOf("click") else emptyList(),
        )
    }

    @Test
    fun aRenamedIdIsSuggestedByItsSimilarity() {
        val windows =
            screen(node(id = "Search.Button.Large", text = "Search"), node(id = "Home.Tab.Tickets", text = "Tickets"))

        val closest = Suggester.closest(Selector(id = "Search.Button.Medium"), "Open the search", windows)

        assertEquals("id=Search.Button.Large text=\"Search\"", closest.first())
        assertTrue(closest.none { it.contains("Home.Tab.Tickets") })
    }

    @Test
    fun aButtonWithAWordFromTheStepIntentIsSuggestedWhenIdsAreMissingEverywhere() {
        val windows = screen(node(text = "Continue", clickable = true), node(text = "Welcome to the app"))

        val closest =
            Suggester.closest(Selector(id = "Onboarding.Welcome.Continue"), "Continue on the welcome page", windows)

        assertEquals(listOf("text=\"Continue\""), closest)
    }

    @Test
    fun textContainsFindsTheTextThatChangedAround() {
        val windows = screen(node(id = "price", text = "Single ticket 3,50 EUR"))

        val closest = Suggester.closest(Selector(textContains = "Single ticket"), "Check the price", windows)

        assertEquals(listOf("id=price text=\"Single ticket 3,50 EUR\""), closest)
    }

    @Test
    fun nothingLooksLikeItGivesNoSuggestion() {
        val windows = screen(node(id = "Map.Zoom.In"), node(text = "Impressum"))

        assertTrue(Suggester.closest(Selector(id = "Checkout.Pay.Button"), "Pay the ticket", windows).isEmpty())
    }

    @Test
    fun listsAtMostTheRequestedNumberWithoutDuplicates() {
        val windows =
            screen(
                node(id = "Search.Field.1"),
                node(id = "Search.Field.1"),
                node(id = "Search.Field.2"),
                node(id = "Search.Field.3"),
                node(id = "Search.Field.4"),
            )

        val closest = Suggester.closest(Selector(id = "Search.Field.0"), "Type", windows, limit = 3)

        assertEquals(3, closest.size)
        assertEquals(closest.distinct(), closest)
    }

    @Test
    fun similarityIsOneForEqualStringsAndShrinksWithEdits() {
        assertEquals(1.0, Suggester.similarity("abc", "abc"))
        assertTrue(Suggester.similarity("abcd", "abce") > Suggester.similarity("abcd", "wxyz"))
    }
}
