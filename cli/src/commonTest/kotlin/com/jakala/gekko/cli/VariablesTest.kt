package com.jakala.gekko.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VariablesTest {
    @Test
    fun movesDatesAcrossMonthAndYearBoundaries() {
        assertEquals("2026-10-26", Dates.plusDays("2026-10-27", -1))
        assertEquals("2027-01-01", Dates.plusDays("2026-12-31", 1))
        assertEquals("2028-02-29", Dates.plusDays("2028-02-28", 1))
        assertEquals("2026-10-27", Dates.isoDate(1_793_059_200_000))
    }

    @Test
    fun writesDatesInGermanAndEnglish() {
        assertEquals("Montag, 26. Oktober 2026", Dates.format("2026-10-26", "EEEE, d. MMMM yyyy", "de-DE"))
        assertEquals("Mo., 26. Okt. 2026", Dates.format("2026-10-26", "EEE, d. MMM yyyy", "de-DE"))
        assertEquals("Di., 1. Sept.", Dates.format("2026-09-01", "EEE, d. MMM", "de-DE"))
        assertEquals("Monday 26 October", Dates.format("2026-10-26", "EEEE dd MMMM", "en-US"))
        assertEquals("26.10.26", Dates.format("2026-10-26", "dd.MM.yy", null))
    }

    @Test
    fun fillsInVariablesAndDates() {
        val variables = Variables(language = { "de-DE" }, today = { "2026-10-27" })
        variables.set("first", "Departure 14:32")

        assertEquals(".*Departure 14:32.*", variables.resolve(".*\${var.first}.*"))
        assertEquals("Montag, 26. Oktober 2026", variables.resolve("\${date(-1, 'EEEE, d. MMMM yyyy')}"))
        assertEquals("26. Oktober", variables.resolve("\${date( -1 , \"d. MMMM\")}"))
        assertEquals("plain", variables.resolve("plain"))
    }

    @Test
    fun anUnsetVariableFailsTheStep() {
        val variables = Variables(language = { null }, today = { "2026-10-27" })

        assertFailsWith<StepFailure> { variables.resolve("\${var.missing}") }
    }

    @Test
    fun randomTestDataIsFreshPerTestAndStableWithinOne() {
        val reported = mutableListOf<String>()
        var counter = 0
        val variables =
            Variables(
                language = { null },
                today = { "2026-10-27" },
                random = { count -> (counter++).toString(16).padStart(count * 2, 'a') },
                onGenerated = { placeholder, value -> reported += "$placeholder=$value" },
            )

        val email = variables.resolve("\${random.email}")
        assertTrue(email.startsWith("gekko-") && email.endsWith("@example.com"), email)
        assertEquals(
            email,
            variables.resolve("\${random.email}"),
            "the same placeholder gives the same value in a test",
        )
        assertEquals(1, reported.size)

        val number = variables.resolve("\${random.number(6)}")
        assertEquals(6, number.length)
        assertTrue(number.all { it.isDigit() } && number.first() != '0')
        assertTrue(
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
                .matches(variables.resolve("\${random.uuid}"))
        )

        variables.clear()
        assertTrue(variables.resolve("\${random.email}") != email, "a new test gets a new value")
    }

    @Test
    fun theRandomNumberHasALimit() {
        assertFailsWith<IllegalArgumentException> {
            Variables(language = { null }, today = { "2026-10-27" }).resolve("\${random.number(40)}")
        }
    }
}
