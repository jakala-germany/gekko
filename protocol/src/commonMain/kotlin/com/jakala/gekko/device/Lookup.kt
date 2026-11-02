package com.jakala.gekko.device

import com.jakala.gekko.protocol.Bounds
import com.jakala.gekko.protocol.ErrorKind
import com.jakala.gekko.protocol.Selector
import com.jakala.gekko.protocol.UiNode
import com.jakala.gekko.protocol.findAll

/*
 * What the Android agent and the system runner share: which nodes a selector means and whether a person could see
 * them. It works on [UiNode] trees only, so it runs in plain JVM tests.
 */

val NO_BOUNDS = Bounds(0, 0, 0, 0)

fun Bounds.hasArea(): Boolean {
    return right > left && bottom > top
}

/** The overlap of two rectangles; [NO_BOUNDS] when they only touch or miss each other. */
fun Bounds.intersect(other: Bounds): Bounds {
    val result =
        Bounds(maxOf(left, other.left), maxOf(top, other.top), minOf(right, other.right), minOf(bottom, other.bottom))
    return if (result.hasArea()) result else NO_BOUNDS
}

object Visibility {
    /**
     * A node is on screen when it has an area, some of it lies inside the window and the [clip] of its ancestors (a
     * scrolled list hides rows that are still in the tree), and its center is inside the window.
     */
    fun isOnScreen(bounds: Bounds, window: Bounds, clip: Bounds? = null): Boolean {
        if (!bounds.hasArea()) {
            return false
        }
        val shown = (if (clip == null) bounds else bounds.intersect(clip)).intersect(window)
        return shown.hasArea() &&
            bounds.centerX in window.left until window.right &&
            bounds.centerY in window.top until window.bottom
    }
}

/**
 * The nodes a selector means, narrowed step by step: [matches] fit the selector, [onScreen] of them can be seen,
 * [usable] of those can be acted on (enabled, unless the selector asks for disabled nodes itself).
 */
class Lookup(val matches: List<UiNode>, val onScreen: List<UiNode>, val usable: List<UiNode>) {
    /** The node a command acts on: counted over the usable nodes only. */
    fun pick(index: Int): UiNode? {
        return usable.getOrNull(index)
    }

    /** Why [pick] found nothing. */
    val missing: String
        get() {
            return when {
                matches.isEmpty() -> ErrorKind.NOT_FOUND
                onScreen.isEmpty() -> ErrorKind.NOT_ON_SCREEN
                usable.isEmpty() -> ErrorKind.DISABLED
                else -> ErrorKind.NOT_FOUND
            }
        }

    /** Why a wait for the selector to be seen found nothing; enabled or not does not matter there. */
    val absent: String
        get() {
            return if (matches.isEmpty()) ErrorKind.NOT_FOUND else ErrorKind.NOT_ON_SCREEN
        }
}

object ElementLookup {
    /**
     * Looks the [selector] up in the [windows], the topmost last. Every window is the reference for the nodes below it;
     * [clipOf] tells where the ancestors of a node cut it off (null: nowhere).
     */
    fun find(windows: List<UiNode>, selector: Selector, clipOf: (UiNode) -> Bounds? = { null }): Lookup {
        val matches = mutableListOf<UiNode>()
        val onScreen = mutableListOf<UiNode>()
        for (window in windows) {
            for (node in window.findAll(selector)) {
                matches += node
                if (Visibility.isOnScreen(node.bounds, window.bounds, clipOf(node))) {
                    onScreen += node
                }
            }
        }
        val usable = if (selector.enabled == null) onScreen.filter { it.enabled } else onScreen
        return Lookup(matches, onScreen, usable)
    }
}
