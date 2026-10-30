package com.jakala.gekko.cli

/**
 * The few property list types usbmuxd speaks: dictionaries ([Map]), arrays ([List]), strings, integers ([Long]), reals
 * ([Double]), booleans, data ([ByteArray]) and dates (kept as their text). Requests are written as XML; answers are
 * read as XML or as a binary plist, whichever the daemon sends.
 */
object Plist {
    private const val BINARY_MAGIC = "bplist00"

    fun encode(root: Map<String, Any>): ByteArray {
        val xml = StringBuilder()
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        xml.append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" ")
        xml.append("\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n")
        xml.append("<plist version=\"1.0\">")
        write(xml, root)
        xml.append("</plist>\n")
        return xml.toString().encodeToByteArray()
    }

    private fun write(xml: StringBuilder, value: Any) {
        when (value) {
            is Map<*, *> -> {
                xml.append("<dict>")
                for ((key, item) in value) {
                    xml.append("<key>").append(escape(key.toString())).append("</key>")
                    write(xml, item ?: "")
                }
                xml.append("</dict>")
            }
            is List<*> -> {
                xml.append("<array>")
                value.forEach { write(xml, it ?: "") }
                xml.append("</array>")
            }
            is Boolean -> xml.append(if (value) "<true/>" else "<false/>")
            is Int -> xml.append("<integer>").append(value).append("</integer>")
            is Long -> xml.append("<integer>").append(value).append("</integer>")
            is Double -> xml.append("<real>").append(value).append("</real>")
            else -> xml.append("<string>").append(escape(value.toString())).append("</string>")
        }
    }

    private fun escape(text: String): String {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }

    /** Reads an XML or binary plist; anything else is an error that shows the start of what was received. */
    fun decode(bytes: ByteArray): Any {
        if (
            bytes.size > BINARY_MAGIC.length &&
                bytes.copyOfRange(0, BINARY_MAGIC.length).decodeToString() == BINARY_MAGIC
        ) {
            return BinaryReader(bytes).root()
        }
        return XmlReader(bytes.decodeToString()).root()
    }

    private class XmlReader(private val text: String) {
        private val tag = Regex("<(/?)([A-Za-z]+)\\s*(/?)>")
        private var position = 0

        fun root(): Any {
            val start = text.indexOf("<plist")
            check(start >= 0) { "not a property list: ${text.take(80)}" }
            position = text.indexOf('>', start) + 1
            return value()
        }

        private fun value(): Any {
            val match = tag.find(text, position) ?: error("unexpected end of the property list")
            position = match.range.last + 1
            val closing = match.groupValues[1] == "/"
            val selfClosed = match.groupValues[3] == "/"
            check(!closing) { "unexpected </${match.groupValues[2]}> in the property list" }
            return when (val name = match.groupValues[2]) {
                "dict" -> if (selfClosed) emptyMap<String, Any>() else dictionary()
                "array" -> if (selfClosed) emptyList<Any>() else array()
                "true" -> true
                "false" -> false
                "string",
                "date" -> if (selfClosed) "" else unescape(textUntil(name))
                "integer" -> textUntil(name).trim().toLongOrNull() ?: error("bad integer in the property list")
                "real" -> textUntil(name).trim().toDoubleOrNull() ?: error("bad real in the property list")
                "data" -> textUntil(name).filterNot { it.isWhitespace() }.encodeToByteArray()
                else -> error("unknown <$name> in the property list")
            }
        }

        private fun textUntil(name: String): String {
            val end = text.indexOf("</$name>", position)
            check(end >= 0) { "missing </$name> in the property list" }
            val content = text.substring(position, end)
            position = end + name.length + 3
            return content
        }

        private fun dictionary(): Map<String, Any> {
            val result = linkedMapOf<String, Any>()
            while (true) {
                val match = tag.find(text, position) ?: error("unterminated <dict> in the property list")
                if (match.groupValues[1] == "/" && match.groupValues[2] == "dict") {
                    position = match.range.last + 1
                    return result
                }
                check(match.groupValues[2] == "key" && match.groupValues[1].isEmpty()) {
                    "expected <key> in the property list, found <${match.groupValues[2]}>"
                }
                position = match.range.last + 1
                val key = unescape(textUntil("key"))
                result[key] = value()
            }
        }

        private fun array(): List<Any> {
            val result = mutableListOf<Any>()
            while (true) {
                val match = tag.find(text, position) ?: error("unterminated <array> in the property list")
                if (match.groupValues[1] == "/" && match.groupValues[2] == "array") {
                    position = match.range.last + 1
                    return result
                }
                result += value()
            }
        }

        private fun unescape(text: String): String {
            return text
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&")
        }
    }

    private class BinaryReader(private val data: ByteArray) {
        private val offsetSize: Int
        private val referenceSize: Int
        private val objectCount: Int
        private val top: Int
        private val tableStart: Int

        init {
            check(data.size >= TRAILER) { "binary property list is too short" }
            val trailer = data.size - TRAILER
            offsetSize = data[trailer + 6].toInt() and 0xff
            referenceSize = data[trailer + 7].toInt() and 0xff
            objectCount = number(trailer + 8, 8).toInt()
            top = number(trailer + 16, 8).toInt()
            tableStart = number(trailer + 24, 8).toInt()
            check(offsetSize in 1..8 && referenceSize in 1..8) { "binary property list has a bad trailer" }
        }

        fun root(): Any {
            return read(top, 0)
        }

        private fun number(at: Int, size: Int): Long {
            var result = 0L
            for (index in 0 until size) {
                result = (result shl 8) or (data[at + index].toLong() and 0xff)
            }
            return result
        }

        private fun offsetOf(reference: Int): Int {
            check(reference in 0 until objectCount) { "binary property list refers to a missing object" }
            return number(tableStart + reference * offsetSize, offsetSize).toInt()
        }

        private fun read(reference: Int, depth: Int): Any {
            check(depth < MAX_DEPTH) { "binary property list is nested too deeply" }
            val at = offsetOf(reference)
            val marker = data[at].toInt() and 0xff
            val low = marker and 0x0f
            return when (marker shr 4) {
                0x0 -> if (low == 0x09) true else if (low == 0x08) false else ""
                0x1 -> number(at + 1, 1 shl low)
                0x2 -> {
                    val bits = number(at + 1, 1 shl low)
                    if (low == 2) Float.fromBits(bits.toInt()).toDouble() else Double.fromBits(bits)
                }
                0x3 -> "date"
                0x4 -> {
                    val (length, start) = lengthAt(at, low)
                    data.copyOfRange(start, start + length)
                }
                0x5 -> {
                    val (length, start) = lengthAt(at, low)
                    data.copyOfRange(start, start + length).decodeToString()
                }
                0x6 -> {
                    val (length, start) = lengthAt(at, low)
                    buildString {
                        for (index in 0 until length) {
                            append(number(start + index * 2, 2).toInt().toChar())
                        }
                    }
                }
                0xA -> {
                    val (length, start) = lengthAt(at, low)
                    (0 until length).map { read(number(start + it * referenceSize, referenceSize).toInt(), depth + 1) }
                }
                0xD -> {
                    val (length, start) = lengthAt(at, low)
                    val result = linkedMapOf<String, Any>()
                    for (index in 0 until length) {
                        val key = read(number(start + index * referenceSize, referenceSize).toInt(), depth + 1)
                        val item =
                            read(number(start + (length + index) * referenceSize, referenceSize).toInt(), depth + 1)
                        result[key.toString()] = item
                    }
                    result
                }
                else -> error("binary property list has an unsupported object type 0x${marker.toString(16)}")
            }
        }

        /** The element count of a string, data, array or dictionary and where its content starts. */
        private fun lengthAt(at: Int, low: Int): Pair<Int, Int> {
            if (low != 0x0f) {
                return low to at + 1
            }
            val sizeMarker = data[at + 1].toInt() and 0xff
            val size = 1 shl (sizeMarker and 0x0f)
            return number(at + 2, size).toInt() to at + 2 + size
        }

        private companion object {
            const val TRAILER = 32
            const val MAX_DEPTH = 64
        }
    }
}
