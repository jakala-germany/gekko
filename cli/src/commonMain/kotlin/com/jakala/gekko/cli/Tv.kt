package com.jakala.gekko.cli

import com.jakala.gekko.protocol.Command

/**
 * What a TV does not have: a touch screen. Its apps are driven with a remote that moves the focus, so a test that taps
 * or scrolls would pass where a person with the remote gets stuck (a focus trap, a row the focus cannot reach). One
 * policy for the steps of a test and for the commands of the CLI; both ask it when the device is a TV.
 */
object TvRules {
    /** Why [action] is refused on a TV, or null when a TV can do it. */
    fun touchAction(action: Action): String? {
        val name =
            when {
                action.tap != null -> "tap"
                action.doubleTap != null -> "doubleTap"
                action.longPress != null -> "longPress"
                action.scroll != null -> "scroll"
                action.scrollUntilVisible != null -> "scrollUntilVisible"
                action.swipe != null -> "swipe"
                action.drag != null -> "drag"
                else -> return null
            }
        return refusal(name)
    }

    /** The same for a command that goes to the device. */
    fun touchCommand(command: Command): String? {
        val name =
            when (command) {
                is Command.Tap,
                is Command.TapAt -> "tap"
                is Command.DoubleTap -> "doubleTap"
                is Command.LongPress,
                is Command.LongPressAt -> "longPress"
                is Command.Scroll,
                is Command.Wheel -> "scroll"
                is Command.Swipe -> "swipe"
                is Command.Drag -> "drag"
                else -> return null
            }
        return refusal(name)
    }

    /** Appended to the failure of a `type` into an element that does not have the focus. */
    const val FOCUS_FIRST =
        "on a TV a field takes text only when the remote moved the focus to it (pressKey with until, then type)"

    private fun refusal(name: String): String {
        return "$name is not available on a TV: use pressKey (up, down, left, right, select) to move the focus"
    }
}
