package com.jakala.gekko.cli

import com.jakala.gekko.protocol.Bounds
import com.jakala.gekko.protocol.UiNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Text of the app under test never reaches a terminal or a file with a character that a terminal acts on. */
class TerminalSafeTest {
    private val esc = '\u001b'

    @Test
    fun turnsAnEscapeSequenceIntoVisibleText() {
        // The hyperlink and title sequences that terminal emulators have been exploited through
        val hostile = "Pay${esc}]8;;file:///tmp/x${esc}\\here${esc}]0;title\u0007"

        val safe = Terminal.safe(hostile)

        assertFalse(safe.contains(esc))
        assertFalse(safe.contains('\u0007'))
        assertEquals("Pay\\u001b]8;;file:///tmp/x\\u001b\\here\\u001b]0;title\\u0007", safe)
    }

    @Test
    fun keepsTheLineBreaksAndTabsOfTheLayout() {
        assertEquals("a\n\tb\nc", Terminal.safe("a\n\tb\nc"))
    }

    @Test
    fun turnsACarriageReturnIntoVisibleText() {
        // A bare carriage return lets a line overwrite the one before it
        assertEquals("ok\\rFAILED", Terminal.safe("ok\rFAILED"))
    }

    @Test
    fun turnsTheControlsOfTheOtherCharacterSetsIntoVisibleText() {
        // DEL, the C1 controls (0x9b is a one character CSI) and the separators that start a line of their own
        assertEquals("\\u007f", Terminal.safe("\u007f"))
        assertEquals("\\u009b31m", Terminal.safe("\u009b31m"))
        assertEquals("\\u0085", Terminal.safe("\u0085"))
        assertEquals("a\\u2028b\\u2029c", Terminal.safe("a b c"))
    }

    @Test
    fun turnsTheCharactersThatReorderTextIntoVisibleText() {
        assertEquals("\\u202eevil", Terminal.safe("‮evil"))
        assertEquals("\\u2066x\\u2069", Terminal.safe("⁦x⁩"))
    }

    @Test
    fun leavesOrdinaryTextAlone() {
        val text = "Zahlung abschließen – 12,50 € ✓ 支払い 😀 שלום"

        assertEquals(text, Terminal.safe(text))
        assertEquals(text, Terminal.inline(text))
    }

    @Test
    fun anInlineValueCannotStartALineOfItsOwn() {
        assertEquals("one\\ntwo\\tthree", Terminal.inline("one\ntwo\tthree"))
    }

    @Test
    fun aTreeKeepsOneLinePerElementWhateverTheAppPutIntoIt() {
        val tree =
            listOf(
                UiNode(
                    role = "view",
                    id = "x${esc}[2J",
                    text = "Pay\nbutton id=fake text=\"Free\"${esc}]0;owned\u0007",
                    description = "a\rb",
                    bounds = Bounds(0, 0, 10, 10),
                )
            )

        val printed = TreePrinter.print(tree)

        assertEquals(1, printed.trimEnd('\n').lines().size)
        assertFalse(printed.any { it == esc || it == '\u0007' || it == '\r' })
        assertTrue(printed.contains("Pay\\nbutton"))
    }

    @Test
    fun theKeyOfAnAndroidServerIsDerivedFromTheTokenAndTheSocket() {
        val token = "0123456789abcdef".repeat(4)

        assertEquals(
            "9936d74fe0b6419aef7786c1b2f12f68641d51d1ebe0e6b5d928e7fbfa049c64",
            ClientAuth.androidKey(token, "gekko-com.example.app"),
        )
        assertFalse(
            ClientAuth.androidKey(token, "gekko-com.example.app") == ClientAuth.androidKey(token, "gekko-system-runner")
        )
    }
}
