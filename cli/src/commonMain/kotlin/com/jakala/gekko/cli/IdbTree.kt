package com.jakala.gekko.cli

import com.jakala.gekko.protocol.UiNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * An iOS tree in the shape of idb's accessibility tree (`accessibility_info` with the `COMPLETE` output format), so a
 * script or a tool that reads idb's tree reads gekko's as well. Every node has the keys idb's nodes have; what the
 * XCUITest snapshot of the system runner does not know is null, as idb leaves it null where it does not know it.
 *
 * Read from the system runner, not the in-app agent: the runner sees the same accessibility tree idb does (rows with
 * their texts as children, labels rather than the visible texts, the order of the screen).
 *
 * Not the same as idb: [UiNode.role] names the XCUITest type, so a view that is nothing more specific is `Other` here,
 * where idb names its class (`UIStackView`, a class of the app); views that are not accessibility elements (separators,
 * scroll edge effects) are not in the tree; `traits` is derived from the type and the state, without `Scrollable` (idb
 * sets it on nearly every node), `Header` and `TabButton`; and `pid` is null.
 */
object IdbTree {
    /** The backend this tree names in its envelope, where idb names its own (`axbridge-exclusive`). */
    const val BACKEND = "gekko-system-runner"

    fun encode(windows: List<UiNode>): String {
        return json.encodeToString(JsonElement.serializer(), envelope(windows))
    }

    private val json = Json { prettyPrint = true }

    fun envelope(windows: List<UiNode>): JsonObject {
        val screen = windows.firstOrNull()?.bounds
        return buildJsonObject {
            put("backend", BACKEND)
            put("elements", JsonArray(windows.map { convert(it, parentRole = null, top = true) }))
            put(
                "screen",
                buildJsonObject {
                    put("coordinate_space", "screen")
                    put("width", screen?.let { it.right - it.left } ?: 0)
                    put("height", screen?.let { it.bottom - it.top } ?: 0)
                },
            )
            put("truncated", false)
        }
    }

    /** One node and the ones below it, as a node somewhere inside the tree. */
    fun node(node: UiNode): JsonObject {
        return convert(node, parentRole = null, top = false)
    }

    /**
     * [top] is the top of the tree, [parentRole] the role of the parent: idb names the top of an app's tree `Other`
     * (with the app's name) and the window below it `Application`, where XCUITest has `Application` and `Window`; the
     * two trees have the same depth from there on.
     */
    private fun convert(node: UiNode, parentRole: String?, top: Boolean): JsonObject {
        val type =
            when {
                top && node.role == "Application" -> "Other"
                parentRole == "Application" && node.role == "Window" -> "Application"
                else -> type(node.role)
            }
        return buildJsonObject {
            put(
                "children",
                buildJsonArray {
                    // XCUITest reports some elements twice (the scroll indicators of a collection view), idb once
                    node.children.distinct().forEach { add(convert(it, parentRole = node.role, top = false)) }
                },
            )
            put("content_required", false)
            put("custom_actions", JsonArray(emptyList()))
            // idb's tree reports no enabled state: a disabled element has the trait NotEnabled
            put("enabled", JsonNull)
            put(
                "frame",
                buildJsonObject {
                    put("x", node.bounds.left)
                    put("y", node.bounds.top)
                    put("width", node.bounds.right - node.bounds.left)
                    put("height", node.bounds.bottom - node.bounds.top)
                },
            )
            put("help", JsonNull)
            put("identifier", node.id.orNull())
            put("label", label(node).orNull())
            put("pid", JsonNull)
            put("role_description", JsonNull)
            put("subrole", JsonNull)
            put("title", JsonNull)
            put("traits", JsonArray(traits(node, type).map { JsonPrimitive(it) }))
            put("type", type)
            // A cell of a table always has a value in idb's tree, empty when it announces none; one of a collection not
            put("value", (node.value ?: if (type == "Cell" && parentRole == "Table") "" else null).orNull())
        }
    }

    /** idb's name of an XCUITest type: the same name, but `StaticText` for a text and `Other` for a plain view. */
    fun type(role: String?): String {
        return when (role) {
            null,
            "View" -> "Other"
            "Text" -> "StaticText"
            else -> role
        }
    }

    /**
     * The accessibility label. A runner that sends it apart ([UiNode.description]) is taken at its word; an older one
     * only sends the label in [UiNode.text], where an element without a label has its value instead.
     */
    fun label(node: UiNode): String? {
        node.description?.let {
            return it
        }
        return node.text?.takeIf { it != node.value }
    }

    /** The traits idb would report, as far as the type and the state of the node tell them. */
    fun traits(node: UiNode, type: String): List<String> {
        val traits = mutableListOf<String>()
        when (type) {
            "Button",
            "Switch",
            "Toggle" -> traits += "Button"
            "Link" -> traits += "Link"
            "Image" -> traits += "Image"
            "StaticText" -> traits += "StaticText"
            "SearchField" -> traits += "SearchField"
            "Key" -> traits += "KeyboardKey"
        }
        if (type in ADJUSTABLE) traits += "Adjustable"
        if (node.selected == true) traits += "Selected"
        if (!node.enabled) traits += "NotEnabled"
        return traits.ifEmpty { listOf("None") }
    }

    private val ADJUSTABLE = setOf("Switch", "Slider", "PickerWheel", "Stepper")

    private fun String?.orNull(): JsonElement {
        return if (this == null) JsonNull else JsonPrimitive(this)
    }
}
