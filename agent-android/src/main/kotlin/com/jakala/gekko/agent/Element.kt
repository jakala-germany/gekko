package com.jakala.gekko.agent

import android.view.View
import com.jakala.gekko.protocol.Bounds
import com.jakala.gekko.protocol.UiNode
import java.util.IdentityHashMap

/** What typing at the cursor of an element that has the focus needs: how to insert, and what it holds right now. */
internal class TextInput(
    val insert: (String) -> Boolean,
    /** The text of the field as it is at this moment (also of a password field); null when it cannot be read. */
    val current: () -> String?,
)

internal class Element(
    val node: UiNode,
    val parent: Element?,
    val rootView: View,
    val click: (() -> Boolean)?,
    val setText: ((String) -> Boolean)?,
    /** Scrolls by pixels in screen directions: positive x reveals content on the right, positive y content below. */
    val scrollBy: ((Float, Float) -> Boolean)?,
    /** The part of the screen the ancestors of this node leave visible. */
    val clip: Bounds,
    /** Layout direction of the node, which `start` and `end` of a scroll follow. */
    val rtl: Boolean,
    /** Set on an element that has the input focus and takes text. */
    val textInput: TextInput? = null,
) {
    val children = mutableListOf<Element>()
    private var tree: UiNode? = null

    /** This element or one below it has the input focus. */
    private val holdsFocus: Boolean by lazy { node.focused || children.any { it.holdsFocus } }

    /**
     * The node with its children; built once, so a [Snapshot] can find the element of any node it hands out. Only the
     * deepest element of a focus keeps the flag: Compose reports it on a field and on the node that merges the field.
     */
    fun toUiNode(): UiNode {
        return tree
            ?: node
                .copy(
                    children = children.map { it.toUiNode() },
                    focused = node.focused && children.none { it.holdsFocus },
                )
                .also { tree = it }
    }
}

internal class Snapshot(
    val roots: List<Element>,
    val flat: List<Element>,
) {
    val windows: List<UiNode> = roots.map { it.toUiNode() }

    private val byNode: IdentityHashMap<UiNode, Element> by lazy {
        IdentityHashMap<UiNode, Element>().also { map -> flat.forEach { map[it.toUiNode()] = it } }
    }

    fun elementOf(node: UiNode): Element {
        return checkNotNull(byNode[node]) { "Not a node of this snapshot" }
    }

    fun clipOf(node: UiNode): Bounds {
        return elementOf(node).clip
    }
}
