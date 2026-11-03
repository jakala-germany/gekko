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

    // 2026-11-02 22:30:00 UTC; a clock one hour east of UTC
    private val night = DeviceTime(epochMillis = 1_793_658_600_000, offsetMinutes = 60)

    private fun at(time: DeviceTime): Variables {
        return Variables(language = { "en-US" }, today = { "2026-11-02" }, clock = { time })
    }

    @Test
    fun nowIsAnIsoTimeInTheTimeZoneOfTheDevice() {
        assertEquals("2026-11-02T23:30:00+01:00", at(night).resolve("\${now()}"))
        assertEquals("2026-11-02T23:40:00+01:00", at(night).resolve("\${now(+10m)}"))
        assertEquals("2026-11-03T00:12:00+01:00", at(night).resolve("\${now(+42m)}"))
        assertEquals("2026-11-01T23:30:00+01:00", at(night).resolve("\${now(-1d)}"))
        assertEquals("2026-11-03T01:30:00+01:00", at(night).resolve("\${now( + 2 h )}"))
        assertEquals("2026-11-02T23:30:30+01:00", at(night).resolve("\${now(+30s)}"))
    }

    @Test
    fun theClockOfTheDeviceIsReadOncePerTextAndOnlyWhenThereIsANow() {
        var reads = 0
        val variables =
            Variables(
                language = { "en-US" },
                today = { "2026-11-02" },
                clock = {
                    reads++
                    DeviceTime(night.epochMillis + reads * 60_000L, 60)
                },
            )

        assertEquals("plain", variables.resolve("plain"))
        assertEquals(0, reads)
        assertEquals(
            "2026-11-02T23:31:00+01:00 / 1793658660000 / 2026-11-02T23:31:00+01:00",
            variables.resolve("\${now()} / \${now(+0m, epochMillis)} / \${now()}"),
        )
        assertEquals(1, reads)
    }

    @Test
    fun nowKnowsNegativeAndZeroOffsetsOfTheTimeZone() {
        val west = DeviceTime(night.epochMillis, -330)

        assertEquals("2026-11-02T17:00:00-05:30", at(west).resolve("\${now()}"))
        assertEquals("2026-11-02T22:30:00+00:00", at(DeviceTime(night.epochMillis, 0)).resolve("\${now()}"))
    }

    @Test
    fun nowCanBeWrittenWithAPattern() {
        assertEquals("23:40", at(night).resolve("\${now(+10m, 'HH:mm')}"))
        assertEquals("11:40 PM", at(night).resolve("\${now(+10m, \"hh:mm a\")}"))
        assertEquals(
            "Monday, 2 November 2026, 23:30:05",
            at(night).resolve("\${now(+5s, 'EEEE, d MMMM yyyy, HH:mm:ss')}"),
        )
        assertEquals("12:05 AM", at(DeviceTime(night.epochMillis + 35 * 60_000, 60)).resolve("\${now(+0m, 'h:mm a')}"))
        // A comma in the pattern is part of it
        assertEquals("23:30, 2026", at(night).resolve("\${now(0s, 'HH:mm, yyyy')}"))
    }

    @Test
    fun nowCanBeWrittenAsEpochTime() {
        assertEquals((night.epochMillis + 600_000).toString(), at(night).resolve("\${now(+10m, epochMillis)}"))
        assertEquals(
            ((night.epochMillis + 600_000) / 1000).toString(),
            at(night).resolve("\${now(+10m, epochSeconds)}"),
        )
        assertEquals(night.epochMillis.toString(), at(night).resolve("\${now(epochMillis)}"))
    }

    @Test
    fun nowIsFilledInEveryTimeItIsWritten() {
        assertEquals(
            "{\"a\":\"2026-11-02T23:40:00+01:00\",\"b\":1793659200000}",
            at(night).resolve("{\"a\":\"\${now(+10m)}\",\"b\":\${now(+10m, epochMillis)}}"),
        )
    }

    @Test
    fun anUnderstoodNowFailsTheStepWithTheWayToWriteIt() {
        for (text in listOf("\${now(10)}", "\${now(+10x)}", "\${now(+10m, yesterday)}", "\${now(+10m, 'HH', 3)}")) {
            val failure = assertFailsWith<StepFailure> { at(night).resolve(text) }
            assertTrue(failure.message!!.startsWith("now("), failure.message)
        }
    }

    @Test
    fun timeLettersAreOnlyTimeLettersInNow() {
        // The pattern of date() keeps writing every other letter as text
        assertEquals("Hallo 26.10.2026", at(night).resolve("\${date(-7, 'Hallo dd.MM.yyyy')}"))
        assertEquals("a:26", Dates.format("2026-10-26", "a:d", null))
    }

    @Test
    fun theEscapeIsAppliedToEveryValueThatIsFilledIn() {
        val variables = at(night)
        variables.set("name", "Zoe \"Z\"")

        assertEquals(
            "Zoe \\\"Z\\\" at 2026-11-02T23:30:00+01:00",
            variables.resolve("\${var.name} at \${now()}", escape = { it.replace("\"", "\\\"") }),
        )
    }

    @Test
    fun theParsedOffsetsOfTheDateCommand() {
        assertEquals(60, parseUtcOffset("+0100"))
        assertEquals(-330, parseUtcOffset("-0530"))
        assertEquals(0, parseUtcOffset("+0000"))
        assertEquals(null, parseUtcOffset("CET"))
    }
}
