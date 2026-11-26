package com.jakala.gekko.cli

import com.jakala.gekko.protocol.DumpNode
import com.jakala.gekko.protocol.Response

/**
 * An Android tree as `adb shell uiautomator dump` writes it, so a script or a tool that reads the dump reads gekko's as
 * well: the same XML, byte for byte, as the dump of Android 16 for the same screen.
 *
 * The system runner reads the same accessibility tree the dump does ([Response.UiDumpResult]); what the dump makes of
 * it is done here: children that are not visible to the user are left out, but still count for `index` and for `NAF`,
 * and texts lose the characters XML cannot hold.
 *
 * A WebView describes its page once something that stays connected has read it (an accessibility service, a test, the
 * runner), and from then on as long as it lives, to the dump as well. The runner reads it, so the page is below the
 * WebView here; a dump of a WebView that only dumps have read shows it empty, with the resource id the app gave it.
 *
 * Not the same as the dump: a password field has a dot for every character, also for the last one Android shows for a
 * moment while typing; the characters a terminal acts on are character references (see [escape]).
 */
object AdbTree {
    fun encode(dump: Response.UiDumpResult): String {
        val out = StringBuilder("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>")
        out.append("<hierarchy rotation=\"").append(dump.rotation).append("\">")
        dump.windows.forEach { node(it, it.index, out) }
        return out.append("</hierarchy>").toString()
    }

    private fun node(node: DumpNode, index: Int, out: StringBuilder) {
        out.append("<node")
        if (!nafExcluded(node) && !accessible(node)) attribute(out, "NAF", "true")
        attribute(out, "index", index.toString())
        attribute(out, "text", node.text)
        attribute(out, "resource-id", node.resourceId)
        attribute(out, "class", node.className)
        attribute(out, "package", node.packageName)
        attribute(out, "content-desc", node.contentDescription)
        attribute(out, "checkable", node.checkable.toString())
        attribute(out, "checked", node.checked.toString())
        attribute(out, "clickable", node.clickable.toString())
        attribute(out, "enabled", node.enabled.toString())
        attribute(out, "focusable", node.focusable.toString())
        attribute(out, "focused", node.focused.toString())
        attribute(out, "scrollable", node.scrollable.toString())
        attribute(out, "long-clickable", node.longClickable.toString())
        attribute(out, "password", node.password.toString())
        attribute(out, "selected", node.selected.toString())
        val bounds = node.bounds
        attribute(out, "bounds", "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]")
        attribute(out, "drawing-order", node.drawingOrder.toString())
        attribute(out, "hint", node.hint)
        val shown = node.children.filter { it.visible }
        if (shown.isEmpty()) {
            out.append(" />")
            return
        }
        out.append(">")
        shown.forEach { node(it, it.index, out) }
        out.append("</node>")
    }

    /**
     * The dump marks a node "not accessibility friendly" (`NAF`) when it can be clicked but says nothing: no text, no
     * description, and none below it, visible or not.
     */
    private fun accessible(node: DumpNode): Boolean {
        val silent =
            node.clickable && node.enabled && node.contentDescription.isNullOrEmpty() && node.text.isNullOrEmpty()
        return !silent || node.children.any(::speaks)
    }

    private fun speaks(node: DumpNode): Boolean {
        return !node.contentDescription.isNullOrEmpty() || !node.text.isNullOrEmpty() || node.children.any(::speaks)
    }

    /** Lists and grids are never marked, whatever they say. */
    private fun nafExcluded(node: DumpNode): Boolean {
        val className = node.className.orEmpty()
        return NAF_EXCLUDED.any { className.endsWith(it) }
    }

    private fun attribute(out: StringBuilder, name: String, value: String?) {
        val text = clean(value.orEmpty())
        // Android's XML writer quotes a value that holds a double quote with single quotes
        val quote = if ('"' in text) '\'' else '"'
        out.append(' ').append(name).append('=').append(quote)
        escape(text, out, quote)
        out.append(quote)
    }

    /** The dump writes a dot for each control character, and for the code points Unicode keeps for itself. */
    internal fun clean(value: String): String {
        return buildString(value.length) {
            value.forEach { c ->
                val code = c.code
                val invalid =
                    code in 0x1..0x8 ||
                        code in 0xB..0xC ||
                        code in 0xE..0x1F ||
                        code in 0x7F..0x84 ||
                        code in 0x86..0x9F ||
                        code in 0xFDD0..0xFDDF
                append(if (invalid) '.' else c)
            }
        }
    }

    /**
     * Escapes an attribute value as Android's XML writer does: line breaks and tabs as character references, a
     * character outside the Basic Multilingual Plane (an emoji) as one reference to its code point. What XML cannot
     * hold at all makes the dump fail; it is a dot here. [quote] is the quote around the value, the one to escape.
     *
     * Unlike the dump, the characters a terminal acts on (the next line U+0085, the direction marks, the line and
     * paragraph separators) are character references as well: the output goes to a terminal, and an XML reader reads
     * the same text from either.
     */
    internal fun escape(value: String, out: StringBuilder, quote: Char = '"') {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            val code = c.code
            when {
                c == '\n' || c == '\r' || c == '\t' -> out.append("&#").append(code).append(';')
                c == '&' -> out.append("&amp;")
                c == '>' -> out.append("&gt;")
                c == '<' -> out.append("&lt;")
                c == quote -> out.append(if (quote == '"') "&quot;" else "&apos;")
                // What a terminal acts on is never printed as it is (see Terminal); a reference is the same text
                code >= 0x20 && Terminal.actsOn(c) -> out.append("&#").append(code).append(';')
                code in 0x20..0xD7FF || code in 0xE000..0xFFFD -> out.append(c)
                c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate() -> {
                    val point = ((code - 0xD800) shl 10) + (value[i + 1].code - 0xDC00) + 0x10000
                    out.append("&#").append(point).append(';')
                    i++
                }
                else -> out.append('.')
            }
            i++
        }
    }

    private val NAF_EXCLUDED =
        listOf(
            "android.widget.GridView",
            "android.widget.GridLayout",
            "android.widget.ListView",
            "android.widget.TableLayout",
        )
}
