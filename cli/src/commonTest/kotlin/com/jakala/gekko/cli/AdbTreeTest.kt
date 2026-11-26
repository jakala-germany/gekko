package com.jakala.gekko.cli

import com.jakala.gekko.protocol.Bounds
import com.jakala.gekko.protocol.DumpNode
import com.jakala.gekko.protocol.Response
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `gekko tree --format adb`: the system runner's tree as `adb shell uiautomator dump` writes it. */
class AdbTreeTest {
    private val screen = Bounds(0, 0, 1080, 2400)

    private fun dump(vararg children: DumpNode, rotation: Int = 0): String {
        val root = DumpNode(index = 0, className = "android.widget.FrameLayout", packageName = PKG, bounds = screen)
        return AdbTree.encode(Response.UiDumpResult(rotation, listOf(root.copy(children = children.toList()))))
    }

    @Test
    fun writesTheHeaderAndEveryAttributeInTheOrderOfTheDump() {
        val button =
            DumpNode(
                index = 0,
                text = "Send",
                resourceId = "$PKG:id/send",
                className = "android.widget.Button",
                packageName = PKG,
                clickable = true,
                focusable = true,
                bounds = Bounds(40, 300, 1040, 360),
                drawingOrder = 2,
            )

        assertEquals(
            "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"1\">" +
                "<node index=\"0\" text=\"\" resource-id=\"\" class=\"android.widget.FrameLayout\" package=\"$PKG\" " +
                "content-desc=\"\" checkable=\"false\" checked=\"false\" clickable=\"false\" enabled=\"true\" " +
                "focusable=\"false\" focused=\"false\" scrollable=\"false\" long-clickable=\"false\" " +
                "password=\"false\" selected=\"false\" bounds=\"[0,0][1080,2400]\" drawing-order=\"0\" hint=\"\">" +
                "<node index=\"0\" text=\"Send\" resource-id=\"$PKG:id/send\" class=\"android.widget.Button\" " +
                "package=\"$PKG\" content-desc=\"\" checkable=\"false\" checked=\"false\" clickable=\"true\" " +
                "enabled=\"true\" focusable=\"true\" focused=\"false\" scrollable=\"false\" long-clickable=\"false\" " +
                "password=\"false\" selected=\"false\" bounds=\"[40,300][1040,360]\" drawing-order=\"2\" hint=\"\" />" +
                "</node></hierarchy>",
            dump(button, rotation = 1),
        )
    }

    @Test
    fun leavesOutWhatIsNotVisibleButKeepsTheIndexOfTheRest() {
        val hidden = DumpNode(index = 0, text = "Account", bounds = Bounds(0, 200, 1080, 150), visible = false)
        val shown = DumpNode(index = 1, text = "Display", bounds = Bounds(40, 300, 1040, 360))

        val xml = dump(hidden, shown)

        assertFalse(xml.contains("Account"))
        assertTrue(xml.contains("<node index=\"1\" text=\"Display\""))
    }

    @Test
    fun aClickableNodeThatSaysNothingIsNotAccessibilityFriendly() {
        val silent = DumpNode(index = 0, className = "android.view.View", clickable = true, bounds = screen)
        val described = silent.copy(contentDescription = "Favorite")
        val disabled = silent.copy(enabled = false)
        // A text below it counts, also where it cannot be seen
        val withText =
            silent.copy(
                children = listOf(DumpNode(index = 0, text = "More", bounds = Bounds(0, 0, 0, 0), visible = false))
            )
        val list = silent.copy(className = "android.widget.ListView")

        assertTrue(
            dump(silent)
                .contains("<node NAF=\"true\" index=\"0\" text=\"\" resource-id=\"\" class=\"android.view.View\"")
        )
        assertFalse(dump(described).contains("NAF"))
        assertFalse(dump(disabled).contains("NAF"))
        assertFalse(dump(withText).contains("NAF"))
        assertFalse(dump(list).contains("NAF"))
    }

    @Test
    fun aWebViewKeepsItsPageAsInADumpWhileTheScreenIsRead() {
        val page = DumpNode(index = 0, text = "Welcome", className = "android.view.View", bounds = screen)
        val web =
            DumpNode(
                index = 0,
                className = "android.webkit.WebView",
                clickable = true,
                bounds = screen,
                children = listOf(page.copy(className = "android.webkit.WebView", children = listOf(page))),
            )

        val xml = dump(web)

        assertTrue(xml.contains("text=\"Welcome\""))
        // A text below the WebView makes it accessible
        assertFalse(xml.contains("NAF"))
    }

    @Test
    fun aNodeWithoutVisibleChildrenClosesItselfWithASpace() {
        val xml =
            dump(DumpNode(index = 0, bounds = screen, children = listOf(DumpNode(0, bounds = screen, visible = false))))

        assertTrue(xml.endsWith("hint=\"\" /></node></hierarchy>"))
    }

    @Test
    fun escapesAsAndroidsXmlWriter() {
        assertEquals("a&amp;b &lt;c&gt; &quot;d&quot; 'e'", escaped("a&b <c> \"d\" 'e'"))
        assertEquals("line&#10;break&#13;&#9;tab", escaped("line\nbreak\r\ttab"))
        assertEquals("Café · über", escaped("Café · über"))
        assertEquals("smile &#128512;", escaped("smile 😀"))
        // What XML cannot hold at all is a dot: a lone surrogate, a null character
        assertEquals("a.b.", escaped("a\uD83Db\u0000"))
    }

    @Test
    fun whatATerminalActsOnIsACharacterReference() {
        val xml = dump(DumpNode(index = 0, text = "a\u202Eb c\u0085d", bounds = screen))

        assertTrue(xml.contains("text=\"a&#8238;b&#8232;c&#133;d\""))
        assertEquals(xml, Terminal.safe(xml))
    }

    @Test
    fun replacesControlCharactersWithADotAsTheDumpDoes() {
        assertEquals("a.b.c\ne\tf", AdbTree.clean("a\u0001b\u001Fc\ne\tf"))
        assertEquals("..", AdbTree.clean("\u007F﷐"))
        // The next line (U+0085) is the one the dump keeps between the two ranges
        assertEquals("\u0085.", AdbTree.clean("\u0085\u0086"))
    }

    @Test
    fun attributeValuesAreCleanedThenEscaped() {
        val node = DumpNode(index = 0, text = "Tom & Jerry\u0007", hint = "Search…", bounds = screen)

        val xml = dump(node)

        assertTrue(xml.contains("text=\"Tom &amp; Jerry.\""))
        assertTrue(xml.contains("hint=\"Search…\""))
    }

    @Test
    fun aValueWithADoubleQuoteIsInSingleQuotes() {
        val node = DumpNode(index = 0, text = "\"Hello\": it's me", contentDescription = "it's", bounds = screen)

        val xml = dump(node)

        assertTrue(xml.contains("text='\"Hello\": it&apos;s me'"))
        assertTrue(xml.contains("content-desc=\"it's\""))
    }

    private fun escaped(value: String): String {
        return StringBuilder().also { AdbTree.escape(value, it) }.toString()
    }

    private companion object {
        const val PKG = "com.example.app"
    }
}
