package com.jakala.gekko.cli

import com.github.ajalt.clikt.core.CliktCommand

/**
 * Text that comes from the app under test (ids, texts, error messages, the console of a page) is not the CLI's own: it
 * can hold whatever the app, a web page or a backend put into it. A terminal executes what it reads, and escape
 * sequences have been enough to run code in a terminal emulator that merely displayed them (`cat tree.txt` after a
 * failed test). So nothing that came from outside is printed or written to an artifact without its control characters
 * turned into visible escapes (`\u001b`): the tester sees that the app sent something odd, and the terminal never sees
 * it.
 */
object Terminal {
    /**
     * Turns the characters that a terminal or an editor acts on into `\uXXXX`; line breaks and tabs of the layout stay.
     */
    fun safe(text: String): String {
        return escape(text, keepLayout = true)
    }

    /**
     * For one value inside a line (the text of an element): the same, and a line break or a tab is escaped too, so the
     * value cannot start a line of its own.
     */
    fun inline(text: String): String {
        return escape(text, keepLayout = false)
    }

    private fun escape(text: String, keepLayout: Boolean): String {
        if (text.none { needsEscape(it, keepLayout) }) {
            return text
        }
        val builder = StringBuilder(text.length + ESCAPE_EXTRA)
        for (char in text) {
            when {
                !needsEscape(char, keepLayout) -> builder.append(char)
                char == '\r' -> builder.append("\\r")
                char == '\n' -> builder.append("\\n")
                char == '\t' -> builder.append("\\t")
                else -> builder.append("\\u").append(char.code.toString(HEX).padStart(HEX_DIGITS, '0'))
            }
        }
        return builder.toString()
    }

    private fun needsEscape(char: Char, keepLayout: Boolean): Boolean {
        val code = char.code
        return when {
            char == '\n' || char == '\t' -> !keepLayout
            code < SPACE -> true
            code == DEL -> true
            code in C1_FIRST..C1_LAST -> true
            // Text that is shown in another order than it is written: a file name or a button that reads otherwise
            code in BIDI_EMBEDDING_FIRST..BIDI_EMBEDDING_LAST -> true
            code in BIDI_ISOLATE_FIRST..BIDI_ISOLATE_LAST -> true
            // Line and paragraph separators that some terminals and editors treat as a line break
            code == LINE_SEPARATOR || code == PARAGRAPH_SEPARATOR -> true
            else -> false
        }
    }

    private const val HEX = 16
    private const val HEX_DIGITS = 4
    private const val ESCAPE_EXTRA = 16
    private const val SPACE = 0x20
    private const val DEL = 0x7f
    private const val C1_FIRST = 0x80
    private const val C1_LAST = 0x9f
    private const val BIDI_EMBEDDING_FIRST = 0x202a
    private const val BIDI_EMBEDDING_LAST = 0x202e
    private const val BIDI_ISOLATE_FIRST = 0x2066
    private const val BIDI_ISOLATE_LAST = 0x2069
    private const val LINE_SEPARATOR = 0x2028
    private const val PARAGRAPH_SEPARATOR = 0x2029
}

/** Prints [message] the way every line of the CLI is printed: with the control characters of outside text defused. */
internal fun CliktCommand.say(message: Any?, err: Boolean = false) {
    echo(Terminal.safe(message.toString()), err = err)
}

/** Writes a file that a person or a model reads later (a tree, a report): the same rule as for the terminal. */
internal fun writeReportFile(path: String, text: String) {
    writeTextFile(path, Terminal.safe(text))
}
