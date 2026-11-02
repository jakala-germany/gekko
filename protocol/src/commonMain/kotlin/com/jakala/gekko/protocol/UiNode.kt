package com.jakala.gekko.protocol

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@Serializable
data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerX: Int
        get() = (left + right) / 2

    val centerY: Int
        get() = (top + bottom) / 2
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UiNode(
    val id: String? = null,
    val text: String? = null,
    /** Accessibility label (content description); selectors on text match it as well. */
    val description: String? = null,
    val role: String? = null,
    val bounds: Bounds,
    val actions: List<String> = emptyList(),
    val enabled: Boolean = true,
    /** State of a switch, checkbox or radio button; null for everything that cannot be checked. */
    val checked: Boolean? = null,
    /** State of a tab, chip or segment; null for everything that cannot be selected. */
    val selected: Boolean? = null,
    /** A password field: the agents never send its text, [text] is null. */
    val secure: Boolean = false,
    /** A progress indicator or similar: the screen is still working. */
    val busy: Boolean = false,
    /**
     * Where the ancestors of the node (a scrolled container, a collapsed section) cut it off; the node cannot be seen
     * outside of it. Null: nowhere. Only the web agent sets it; the other agents keep this to themselves.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val clip: Bounds? = null,
    val children: List<UiNode> = emptyList(),
) {
    /** Changes whenever something a person could notice changes (ids, texts, positions, state). */
    fun signature(): Int {
        var hash = listOf(id, text, description, bounds, enabled, checked, selected, busy).hashCode()
        children.forEach { hash = 31 * hash + it.signature() }
        return hash
    }

    fun hasBusyNode(): Boolean {
        return busy || children.any { it.hasBusyNode() }
    }
}

/**
 * A node counts as visible when its center lies inside the window and, if it has one, inside its [UiNode.clip]; lists
 * may report off-screen rows.
 */
fun UiNode.isOnScreen(window: Bounds): Boolean {
    val inWindow = bounds.centerX in window.left until window.right && bounds.centerY in window.top until window.bottom
    val inClip =
        clip == null || (bounds.centerX in clip.left until clip.right && bounds.centerY in clip.top until clip.bottom)
    return inWindow && inClip
}
