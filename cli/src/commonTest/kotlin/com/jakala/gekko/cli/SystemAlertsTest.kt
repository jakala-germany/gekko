package com.jakala.gekko.cli

import com.jakala.gekko.protocol.Bounds
import com.jakala.gekko.protocol.UiNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SystemAlertsTest {
    private val bounds = Bounds(0, 0, 1, 1)

    private fun button(text: String): UiNode {
        return UiNode(role = "Button", text = text, bounds = bounds)
    }

    @Test
    fun namesTheAlertAndItsButtons() {
        val alert =
            UiNode(
                role = "Alert",
                text = "Allow “Sample” to use your location?",
                bounds = bounds,
                children = listOf(button("Don’t Allow"), button("Allow")),
            )

        assertEquals(
            "The system alert \"Allow “Sample” to use your location?\" is in front of the app " +
                "(buttons: Don’t Allow, Allow); answer it first, e.g. tap: { text: \"Don’t Allow\" }",
            SystemAlerts.hint(listOf(alert)),
        )
    }

    @Test
    fun takesTheTitleFromTheFirstTextWhenTheAlertHasNoLabel() {
        val title = UiNode(role = "Text", text = "Notifications", bounds = bounds)
        val alert = UiNode(role = "Alert", bounds = bounds, children = listOf(title, button("OK")))

        assertEquals(
            "The system alert \"Notifications\" is in front of the app (buttons: OK); answer it first, e.g. tap: { text: \"OK\" }",
            SystemAlerts.hint(listOf(alert)),
        )
    }

    @Test
    fun anAlertWithoutTextOrButtonsIsStillNamed() {
        assertEquals(
            "A system alert is in front of the app",
            SystemAlerts.hint(listOf(UiNode(role = "Alert", bounds = bounds))),
        )
    }

    @Test
    fun aScreenWithoutAnAlertHasNoHint() {
        assertNull(
            SystemAlerts.hint(listOf(UiNode(role = "Application", bounds = bounds, children = listOf(button("Go")))))
        )
        assertNull(SystemAlerts.hint(emptyList()))
    }
}
