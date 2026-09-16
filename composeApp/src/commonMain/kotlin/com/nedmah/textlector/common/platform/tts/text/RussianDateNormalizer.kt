package com.nedmah.textlector.common.platform.tts.text

/** Converts unambiguous numeric calendar dates into natural Russian speech. */
object RussianDateNormalizer {

    private val numericDate = Regex(
        """(?<!\d)(0?[1-9]|[12]\d|3[01])([./])(0?[1-9]|1[0-2])\2(1\d{3}|2\d{3})(?!\d)"""
    )

    private val monthsGenitive = listOf(
        "",
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря",
    )

    fun normalize(input: String): String = numericDate.replace(input) { match ->
        val day = match.groupValues[1].toIntOrNull() ?: return@replace match.value
        val month = match.groupValues[3].toIntOrNull() ?: return@replace match.value
        val year = match.groupValues[4].toIntOrNull() ?: return@replace match.value

        if (!isValidDate(day, month, year)) return@replace match.value

        val dayWords = dayOrdinalGenitive(day) ?: return@replace match.value
        val yearWords = RussianContextNumberNormalizer
            .normalizeRaw("$year года")
            .removeSuffix(" года")

        "$dayWords ${monthsGenitive[month]} $yearWords года"
    }

    private fun isValidDate(day: Int, month: Int, year: Int): Boolean {
        if (month !in 1..12 || day < 1) return false
        val maxDay = when (month) {
            2 -> if (isLeapYear(year)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
        return day <= maxDay
    }

    private fun isLeapYear(year: Int): Boolean =
        year % 400 == 0 || (year % 4 == 0 && year % 100 != 0)

    private fun dayOrdinalGenitive(day: Int): String? {
        val direct = mapOf(
            1 to "первого", 2 to "второго", 3 to "третьего", 4 to "четвёртого",
            5 to "пятого", 6 to "шестого", 7 to "седьмого", 8 to "восьмого",
            9 to "девятого", 10 to "десятого", 11 to "одиннадцатого",
            12 to "двенадцатого", 13 to "тринадцатого", 14 to "четырнадцатого",
            15 to "пятнадцатого", 16 to "шестнадцатого", 17 to "семнадцатого",
            18 to "восемнадцатого", 19 to "девятнадцатого", 20 to "двадцатого",
            30 to "тридцатого",
        )
        direct[day]?.let { return it }

        return when (day / 10) {
            2 -> "двадцать ${direct[day % 10]}"
            3 -> "тридцать ${direct[day % 10]}"
            else -> null
        }
    }
}
