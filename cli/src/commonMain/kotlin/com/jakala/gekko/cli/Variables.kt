package com.jakala.gekko.cli

/**
 * Values that only exist while a test runs: texts kept by `copyText` (`${var.name}`), dates relative to the device's
 * today (`${date(-1, 'EEEE, d. MMMM yyyy')}`), written in the language of the test, and fresh test data
 * (`${random.email}`, `${random.number(6)}`, `${random.uuid}`): one value per test for each placeholder, reported
 * through [onGenerated] so a failure can be reproduced.
 */
class Variables(
    private val language: () -> String?,
    private val today: () -> String,
    private val random: (Int) -> String = ::randomHex,
    private val onGenerated: (String, String) -> Unit = { _, _ -> },
) {
    private val values = mutableMapOf<String, String>()
    private val generated = mutableMapOf<String, String>()

    fun set(name: String, value: String) {
        values[name] = value
    }

    fun clear() {
        values.clear()
        generated.clear()
    }

    fun resolve(text: String): String {
        if (!text.contains("\${var.") && !text.contains("\${date(") && !text.contains("\${random.")) {
            return text
        }
        val withRandom =
            RANDOM.replace(text) { match -> generate(match.value, match.groupValues[1], match.groupValues[2]) }
        val withVariables =
            VARIABLE.replace(withRandom) { match ->
                val name = match.groupValues[1]
                values[name] ?: throw StepFailure("Variable '$name' is not set; copyText has to run before it is used")
            }
        return DATE.replace(withVariables) { match ->
            val offset = match.groupValues[1].toInt()
            val pattern = match.groupValues[3]
            Dates.format(Dates.plusDays(today(), offset), pattern, language())
        }
    }

    private fun generate(placeholder: String, kind: String, digits: String): String {
        return generated.getOrPut(placeholder) {
            val value =
                when (kind) {
                    "email" -> "gekko-${random(4)}@example.com"
                    "uuid" -> uuid(random(16))
                    else -> number(digits.toInt(), random(digits.toInt()))
                }
            onGenerated(placeholder, value)
            value
        }
    }

    private fun uuid(hex: String): String {
        val variant = "89ab"[hex[16].digitToInt(16) % 4]
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-4${hex.substring(13, 16)}-$variant${hex.substring(17, 20)}-${hex.substring(20, 32)}"
    }

    private fun number(digits: Int, hex: String): String {
        require(digits in 1..MAX_DIGITS) { "random.number(n): n must be between 1 and $MAX_DIGITS, was $digits" }
        val text = hex.chunked(2).take(digits).joinToString("") { (it.toInt(16) % 10).toString() }
        return if (text.first() == '0') "1" + text.drop(1) else text
    }

    private companion object {
        const val MAX_DIGITS = 18
        val RANDOM = Regex("""\$\{random\.(email|uuid|number\((\d+)\))}""")
        val VARIABLE = Regex("""\$\{var\.([A-Za-z0-9_]+)}""")
        val DATE = Regex("""\$\{date\(\s*([+-]?\d+)\s*,\s*(['"])(.*?)\2\s*\)}""")
    }
}

/** Calendar arithmetic on ISO dates (`2026-10-27`) and German / English names, without a time library. */
object Dates {
    private val GERMAN_DAYS = listOf("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag")
    private val GERMAN_DAYS_SHORT = listOf("Mo.", "Di.", "Mi.", "Do.", "Fr.", "Sa.", "So.")
    private val GERMAN_MONTHS =
        listOf(
            "Januar",
            "Februar",
            "März",
            "April",
            "Mai",
            "Juni",
            "Juli",
            "August",
            "September",
            "Oktober",
            "November",
            "Dezember",
        )
    private val GERMAN_MONTHS_SHORT =
        listOf("Jan.", "Feb.", "März", "Apr.", "Mai", "Juni", "Juli", "Aug.", "Sept.", "Okt.", "Nov.", "Dez.")
    private val ENGLISH_DAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    private val ENGLISH_DAYS_SHORT = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val ENGLISH_MONTHS =
        listOf(
            "January",
            "February",
            "March",
            "April",
            "May",
            "June",
            "July",
            "August",
            "September",
            "October",
            "November",
            "December",
        )
    private val ENGLISH_MONTHS_SHORT =
        listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** The UTC date of an instant, for devices that have no clock of their own to ask. */
    fun isoDate(epochMillis: Long): String {
        val (year, month, day) = civil(epochMillis.floorDiv(MILLIS_PER_DAY))
        return "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
    }

    fun plusDays(isoDate: String, days: Int): String {
        val parts = isoDate.trim().split('-').map { it.toIntOrNull() }
        val year = parts.getOrNull(0)
        val month = parts.getOrNull(1)
        val day = parts.getOrNull(2)
        if (parts.size != 3 || year == null || month == null || day == null) {
            throw StepFailure("The device answered '$isoDate' when asked for today's date")
        }
        return isoDate((daysFromCivil(year, month, day) + days) * MILLIS_PER_DAY)
    }

    /** Letters of [pattern]: EEEE / EEE weekday, d / dd day, MMMM / MMM / MM / M month, yyyy / yy year. */
    fun format(isoDate: String, pattern: String, language: String?): String {
        val parts = isoDate.split('-').map { it.toInt() }
        val year = parts[0]
        val month = parts[1]
        val day = parts[2]
        val weekday = (daysFromCivil(year, month, day) + 3).mod(7L).toInt()
        val german = language?.substringBefore('-')?.lowercase() == "de"
        val days = if (german) GERMAN_DAYS else ENGLISH_DAYS
        val daysShort = if (german) GERMAN_DAYS_SHORT else ENGLISH_DAYS_SHORT
        val months = if (german) GERMAN_MONTHS else ENGLISH_MONTHS
        val monthsShort = if (german) GERMAN_MONTHS_SHORT else ENGLISH_MONTHS_SHORT
        val builder = StringBuilder()
        var index = 0
        while (index < pattern.length) {
            val letter = pattern[index]
            var end = index
            while (end < pattern.length && pattern[end] == letter) end++
            val count = end - index
            when (letter) {
                'E' -> builder.append(if (count >= 4) days[weekday] else daysShort[weekday])
                'd' -> builder.append(if (count >= 2) day.toString().padStart(2, '0') else day.toString())
                'M' ->
                    builder.append(
                        when {
                            count >= 4 -> months[month - 1]
                            count == 3 -> monthsShort[month - 1]
                            count == 2 -> month.toString().padStart(2, '0')
                            else -> month.toString()
                        }
                    )
                'y' -> builder.append(if (count == 2) (year % 100).toString().padStart(2, '0') else year.toString())
                else -> builder.append(pattern.substring(index, end))
            }
            index = end
        }
        return builder.toString()
    }

    private const val MILLIS_PER_DAY = 86_400_000L

    /** Days since 1970-01-01 (Howard Hinnant's civil calendar algorithm). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = y.floorDiv(400)
        val yearOfEra = y - era * 400
        val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146097L + dayOfEra - 719468
    }

    private fun civil(epochDay: Long): Triple<Int, Int, Int> {
        val z = epochDay + 719468
        val era = z.floorDiv(146097L)
        val dayOfEra = (z - era * 146097).toInt()
        val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / 365
        val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        val shifted = (5 * dayOfYear + 2) / 153
        val day = dayOfYear - (153 * shifted + 2) / 5 + 1
        val month = if (shifted < 10) shifted + 3 else shifted - 9
        val year = (yearOfEra + era * 400).toInt() + if (month <= 2) 1 else 0
        return Triple(year, month, day)
    }
}
