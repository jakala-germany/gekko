package com.jakala.gekko.agent

import android.view.View
import com.jakala.gekko.protocol.Bounds
import com.jakala.gekko.protocol.UiNode
import java.util.IdentityHashMap

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
) {
    val children = mutableListOf<Element>()
    private var tree: UiNode? = null

    /** The node with its children; built once, so a [Snapshot] can find the element of any node it hands out. */
    fun toUiNode(): UiNode {
        return tree ?: node.copy(children = children.map { it.toUiNode() }).also { tree = it }
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
