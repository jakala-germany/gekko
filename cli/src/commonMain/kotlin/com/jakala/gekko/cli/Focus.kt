package com.jakala.gekko.cli

import com.jakala.gekko.device.ElementLookup
import com.jakala.gekko.protocol.Command
import com.jakala.gekko.protocol.Response
import com.jakala.gekko.protocol.Selector
import com.jakala.gekko.protocol.SettleTracker
import com.jakala.gekko.protocol.UiNode
import kotlinx.coroutines.delay

/** What the focus is on, read from a tree: the element a TV remote or a keyboard acts on. */
object FocusTree {
    /** The deepest node that has the focus, the topmost window winning; null when nothing has it. */
    fun focusedNode(windows: List<UiNode>): UiNode? {
        var found: UiNode? = null

        fun visit(node: UiNode) {
            if (node.focused) {
                found = node
            }
            node.children.forEach { visit(it) }
        }

        windows.forEach { visit(it) }
        return found
    }

    /** True when [node] or a node below it has the focus: the focus of a card can sit on something inside of it. */
    fun holdsFocus(node: UiNode): Boolean {
        return node.focused || node.children.any { holdsFocus(it) }
    }

    /**
     * The node [selector] means, counted like the index of a tap: over the nodes a person can see and use. The index
     * comes first and the focus second, so `index: 1` is the second element, whether it has the focus or not.
     */
    fun locate(windows: List<UiNode>, selector: Selector): UiNode? {
        return ElementLookup.find(windows, selector) { it.clip }.pick(selector.index)
    }

    /** True when the element [selector] means has the focus. */
    fun holds(windows: List<UiNode>, selector: Selector): Boolean {
        val target = locate(windows, selector) ?: return false
        return holdsFocus(target)
    }

    /** How a person would name [node]: its id and its text, else its role. Everything in it comes from the app. */
    fun describe(node: UiNode?): String {
        if (node == null) {
            return "nothing"
        }
        val text =
            node.text
                ?.takeIf { it.isNotEmpty() }
                ?.let { "\"${it.take(MAX_TEXT)}${if (it.length > MAX_TEXT) "…" else ""}\"" }
        val name = listOfNotNull(node.id, text).joinToString(" ")
        return Terminal.inline(name.ifEmpty { node.role ?: "an element" })
    }

    /** Changes when the focus moves to another element, or when the focused element changes in place. */
    fun identity(node: UiNode?): Int? {
        if (node == null) {
            return null
        }
        return 31 * (node.role?.hashCode() ?: 0) + node.signature()
    }

    fun signature(windows: List<UiNode>): Int {
        return windows.fold(17) { hash, window -> 31 * hash + window.signature() }
    }

    /** Why the element [selector] means does not have the focus. */
    fun failure(selector: Selector, windows: List<UiNode>): String {
        if (locate(windows, selector) == null) {
            return "Element not found: $selector"
        }
        val focus = focusedNode(windows)
        val where = if (focus == null) "nothing has the focus" else "focus is on ${describe(focus)}"
        return "$EXPECTED_FOCUSED $selector ($where)"
    }

    /** What a failure of [failure] starts with when the element exists and the focus is elsewhere. */
    const val EXPECTED_FOCUSED = "Expected focused:"

    private const val MAX_TEXT = 60
}

/**
 * Waits for the focus and moves it with the keys of a remote (`assertFocused`, `pressKey` with `until`). It reads the
 * tree, where the focus of the app is the `focused` flag of a node, so it works for every platform that reports it.
 * Every function answers null on success, else what went wrong.
 */
class FocusWaiter(
    private val drivers: Drivers,
    private val settleMs: Long,
    private val now: () -> Long = { nowMillis() },
) {
    /**
     * Waits up to [timeoutMs] for the element [selector] means to have the focus. Like a wait for an element it ends
     * early once the screen stopped changing, because the focus cannot arrive by itself after that.
     */
    suspend fun awaitFocus(selector: Selector, timeoutMs: Long, settle: Boolean = true): String? {
        val deadline = now() + timeoutMs
        val tracker = SettleTracker(if (settle) settleMs else 0, now())
        while (true) {
            val windows = readTree()
            if (FocusTree.holds(windows, selector)) {
                return null
            }
            tracker.observe(FocusTree.signature(windows), windows.any { it.hasBusyNode() }, now())
            if (tracker.isSettled(now()) || now() > deadline) {
                return FocusTree.failure(selector, windows)
            }
            delay(POLL_MS)
        }
    }

    /**
     * [awaitFocus] for a field that is about to get text. On a TV the failure adds what a test has to do: move the
     * focus to the field with the remote first.
     */
    suspend fun awaitFieldFocus(selector: Selector, timeoutMs: Long, settle: Boolean = true): String? {
        val failure = awaitFocus(selector, timeoutMs, settle) ?: return null
        return if (failure.startsWith(FocusTree.EXPECTED_FOCUSED)) "$failure; ${TvRules.FOCUS_FIRST}" else failure
    }

    /**
     * Presses [key] until the element [selector] means has the focus, at most [max] times. Gives up as soon as a press
     * did not move the focus (the end of a row, a focus trap) and says where it is stuck.
     */
    suspend fun pressUntil(
        key: String,
        selector: Selector,
        max: Int,
        timeoutMs: Long,
        system: Boolean,
    ): String? {
        var windows = readTree()
        if (FocusTree.holds(windows, selector)) {
            return null
        }
        for (press in 1..max) {
            val before = FocusTree.focusedNode(windows)
            val response = drivers.sendWithRunnerFallback(Command.PressKey(key, 1, timeoutMs), system)
            if (response is Response.Error) {
                return response.message
            }
            windows = awaitMove(FocusTree.identity(before))
            if (FocusTree.holds(windows, selector)) {
                return null
            }
            val after = FocusTree.focusedNode(windows)
            if (FocusTree.identity(after) == FocusTree.identity(before)) {
                val presses = pressesOf(press)
                return if (after == null) {
                    "nothing has the focus after $presses of $key"
                } else {
                    "focus stuck on ${FocusTree.describe(after)} after $presses of $key"
                }
            }
        }
        if (FocusTree.locate(windows, selector) == null) {
            return "Element not found: $selector after ${pressesOf(max)} of $key"
        }
        return "$selector did not get the focus after ${pressesOf(max)} of $key " +
            "(focus is on ${FocusTree.describe(FocusTree.focusedNode(windows))})"
    }

    /**
     * Waits for the focus to leave the element with the identity [before] (up to [FOCUS_MOVE_MS]), then until it stops
     * moving (a focus effect still scales the new element). Gives the tree it settled on; when the focus never left,
     * that is the tree of the last read.
     */
    private suspend fun awaitMove(before: Int?): List<UiNode> {
        val moveDeadline = now() + FOCUS_MOVE_MS
        var windows = readTree()
        while (FocusTree.identity(FocusTree.focusedNode(windows)) == before && now() < moveDeadline) {
            delay(POLL_MS)
            windows = readTree()
        }
        if (FocusTree.identity(FocusTree.focusedNode(windows)) == before) {
            return windows
        }
        val quietDeadline = now() + FOCUS_QUIET_TIMEOUT_MS
        var last = FocusTree.identity(FocusTree.focusedNode(windows))
        while (now() < quietDeadline) {
            delay(FOCUS_QUIET_MS)
            windows = readTree()
            val current = FocusTree.identity(FocusTree.focusedNode(windows))
            if (current == last) {
                return windows
            }
            last = current
        }
        return windows
    }

    private suspend fun readTree(): List<UiNode> {
        return (drivers.send(Command.Tree) as? Response.TreeResult)?.windows.orEmpty()
    }

    private companion object {
        fun pressesOf(count: Int): String {
            return if (count == 1) "1 press" else "$count presses"
        }

        const val POLL_MS = 50L
        const val FOCUS_MOVE_MS = 1_000L
        const val FOCUS_QUIET_MS = 100L
        const val FOCUS_QUIET_TIMEOUT_MS = 2_000L
    }
}
