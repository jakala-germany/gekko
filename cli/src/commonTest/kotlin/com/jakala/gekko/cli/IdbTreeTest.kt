package com.jakala.gekko.cli

import com.jakala.gekko.protocol.Bounds
import com.jakala.gekko.protocol.UiNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** `gekko tree --format idb`: the system runner's tree in the shape of idb's accessibility tree (COMPLETE format). */
class IdbTreeTest {
    private val screen = Bounds(0, 0, 402, 874)

    /** A settings row as the system runner reads it: the cell has a label and a value, its texts are children. */
    private val switchRow =
        UiNode(
            role = "Cell",
            text = "Airplane mode, switch",
            description = "Airplane mode, switch",
            value = "Off",
            bounds = Bounds(0, 300, 402, 352),
            actions = listOf("click"),
            children =
                listOf(
                    UiNode(
                        role = "Text",
                        text = "Airplane mode",
                        description = "Airplane mode",
                        bounds = Bounds(20, 316, 140, 336),
                    ),
                    UiNode(
                        role = "Switch",
                        text = "Airplane mode, switch",
                        description = "Airplane mode, switch",
                        value = "0",
                        checked = false,
                        bounds = Bounds(330, 311, 381, 342),
                    ),
                ),
        )

    private val window = UiNode(role = "Application", text = "Sample", description = "Sample", bounds = screen)

    @Test
    fun aNodeHasEveryKeyOfAnIdbNode() {
        val node = IdbTree.node(switchRow)

        assertEquals(
            setOf(
                "children",
                "content_required",
                "custom_actions",
                "enabled",
                "frame",
                "help",
                "identifier",
                "label",
                "pid",
                "role_description",
                "subrole",
                "title",
                "traits",
                "type",
                "value",
            ),
            node.keys,
        )
    }

    @Test
    fun keepsTheLabelAndTheValueApartAndTheTextsAsChildren() {
        val cell = IdbTree.node(switchRow)
        val (text, toggle) = cell.getValue("children").jsonArray.map { it.jsonObject }

        assertEquals("Cell", cell.string("type"))
        assertEquals("Airplane mode, switch", cell.string("label"))
        assertEquals("Off", cell.string("value"))
        assertEquals("StaticText", text.string("type"))
        assertEquals("Airplane mode", text.string("label"))
        assertEquals("Switch", toggle.string("type"))
        assertEquals("0", toggle.string("value"))
        assertEquals(listOf("Button", "Adjustable"), toggle.traits())
    }

    @Test
    fun theFrameIsOriginAndSize() {
        val frame = IdbTree.node(switchRow).getValue("frame").jsonObject

        assertEquals(
            listOf(0, 300, 402, 52),
            listOf("x", "y", "width", "height").map { frame.getValue(it).jsonPrimitive.int },
        )
    }

    @Test
    fun aPlainViewIsOtherAndATextIsStaticText() {
        assertEquals("Other", IdbTree.type("View"))
        assertEquals("Other", IdbTree.type(null))
        assertEquals("StaticText", IdbTree.type("Text"))
        assertEquals("NavigationBar", IdbTree.type("NavigationBar"))
    }

    @Test
    fun theStateIsInTheTraitsAndEnabledIsNullAsInIdb() {
        val tab = IdbTree.node(UiNode(role = "Button", text = "Home", selected = true, bounds = screen))
        val disabled = IdbTree.node(UiNode(role = "Button", text = "Send", enabled = false, bounds = screen))
        val plain = IdbTree.node(UiNode(role = "View", bounds = screen))

        assertEquals(listOf("Button", "Selected"), tab.traits())
        assertEquals(listOf("Button", "NotEnabled"), disabled.traits())
        assertEquals(listOf("None"), plain.traits())
        assertEquals(JsonNull, disabled.getValue("enabled"))
    }

    @Test
    fun anOlderRunnerWithoutTheLabelApartGivesTheTextAsLabel() {
        val old = UiNode(role = "Button", text = "Search", bounds = screen)
        // An element without a label: the runner put its value into text
        val valueOnly = UiNode(role = "TextField", text = "typed", value = "typed", bounds = screen)

        assertEquals("Search", IdbTree.node(old).string("label"))
        assertEquals(null, IdbTree.node(valueOnly).string("label"))
        assertEquals("typed", IdbTree.node(valueOnly).string("value"))
    }

    @Test
    fun theTopIsOtherWithTheAppNameAndTheWindowIsApplicationAsInIdb() {
        val tree =
            window.copy(children = listOf(UiNode(role = "Window", bounds = screen, children = listOf(switchRow))))

        val top = IdbTree.envelope(listOf(tree)).getValue("elements").jsonArray.single().jsonObject
        val below = top.getValue("children").jsonArray.single().jsonObject

        assertEquals("Other", top.string("type"))
        assertEquals("Sample", top.string("label"))
        assertEquals("Application", below.string("type"))
        assertEquals("Cell", below.getValue("children").jsonArray.single().jsonObject.string("type"))
        // A window further down keeps its name
        assertEquals("Window", IdbTree.node(UiNode(role = "Window", bounds = screen)).string("type"))
    }

    @Test
    fun aTableCellWithoutAValueHasAnEmptyOneAndACollectionCellNone() {
        val cell = UiNode(role = "Cell", text = "Profile", description = "Profile", bounds = screen)
        val table = IdbTree.node(UiNode(role = "Table", bounds = screen, children = listOf(cell)))
        val collection = IdbTree.node(UiNode(role = "CollectionView", bounds = screen, children = listOf(cell)))

        assertEquals("", table.children().single().string("value"))
        assertEquals(null, collection.children().single().string("value"))
        assertEquals(null, table.string("value"))
    }

    @Test
    fun anElementXcuitestReportsTwiceIsInTheTreeOnce() {
        val indicator =
            UiNode(role = "View", text = "Vertical scroll bar", value = "0 %", bounds = Bounds(372, 100, 400, 800))
        val other =
            UiNode(role = "View", text = "Horizontal scroll bar", value = "0 %", bounds = Bounds(60, 760, 340, 790))
        val list =
            IdbTree.node(
                UiNode(role = "CollectionView", bounds = screen, children = listOf(indicator, other, indicator, other))
            )

        assertEquals(listOf("Vertical scroll bar", "Horizontal scroll bar"), list.children().map { it.string("label") })
    }

    @Test
    fun theEnvelopeNamesTheBackendAndTheScreen() {
        val envelope = IdbTree.envelope(listOf(window.copy(children = listOf(switchRow))))

        assertEquals(IdbTree.BACKEND, envelope.string("backend"))
        assertEquals(1, envelope.getValue("elements").jsonArray.size)
        assertEquals(402, envelope.getValue("screen").jsonObject.getValue("width").jsonPrimitive.int)
        assertEquals(874, envelope.getValue("screen").jsonObject.getValue("height").jsonPrimitive.int)
        assertTrue(IdbTree.encode(listOf(window)).contains("\"backend\": \"${IdbTree.BACKEND}\""))
    }

    private fun JsonObject.string(key: String): String? {
        val value = getValue(key)
        return if (value is JsonNull) null else value.jsonPrimitive.content
    }

    private fun JsonObject.children(): List<JsonObject> {
        return getValue("children").jsonArray.map { it.jsonObject }
    }

    private fun JsonObject.traits(): List<String> {
        return (getValue("traits") as JsonArray).map { it.jsonPrimitive.content }
    }
}
