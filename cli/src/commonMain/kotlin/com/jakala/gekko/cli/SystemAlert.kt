package com.jakala.gekko.cli

import com.jakala.gekko.protocol.UiNode

/** What the iOS system runner reads of a system alert that covers the app, told to the person who wrote the test. */
object SystemAlerts {
    /** The role the runner gives the alert at the root of its tree. */
    const val ROLE = "Alert"

    private const val BUTTON = "Button"

    /** The sentence that names the alert at the root of [windows] and its buttons, or null when there is none. */
    fun hint(windows: List<UiNode>): String? {
        val alert = windows.firstOrNull { it.role == ROLE } ?: return null
        val below = alert.descendants()
        val title = alert.text ?: below.firstOrNull { it.text != null && it.role != BUTTON }?.text
        val buttons = below.filter { it.role == BUTTON }.mapNotNull { it.text }
        val named = if (title == null) "A system alert" else "The system alert \"$title\""
        val answer =
            if (buttons.isEmpty()) ""
            else " (buttons: ${buttons.joinToString()}); answer it first, e.g. tap: { text: \"${buttons.first()}\" }"
        return "$named is in front of the app$answer"
    }

    private fun UiNode.descendants(): List<UiNode> {
        return children.flatMap { listOf(it) + it.descendants() }
    }
}
