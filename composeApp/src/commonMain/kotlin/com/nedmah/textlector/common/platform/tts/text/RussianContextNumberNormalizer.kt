package com.nedmah.textlector.common.platform.tts.text

/**
 * Context that cannot be inferred from a bare integer alone.
 *
 * This layer intentionally stays deterministic. It handles Russian clock/year
 * notation before the generic number normalizer and repairs grammatical gender
 * after that normalizer has expanded a number into words.
 */
object RussianContextNumberNormalizer {

    private enum class YearCase { NOM, GEN, DAT, INS, PREP }

    private val clock = Regex("""(?<!\d)([01]?\d|2[0-3]):([0-5]\d)(?!\d)""")
    private val yearPrepositional = Regex(
        """\b(в|на)\s+(1\d{3}|2\d{3})\s+(году)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val yearDative = Regex(
        """\b(к|ко)\s+(1\d{3}|2\d{3})\s+(году)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val yearGenitiveWithPreposition = Regex(
        """\b(с|до|после)\s+(1\d{3}|2\d{3})\s+(года)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val bareYear = Regex(
        """\b(1\d{3}|2\d{3})\s+(год|года|годом)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val feminineNominative = listOf(
        "страница", "глава", "строка", "минута", "секунда", "неделя", "книга",
        "машина", "комната", "задача", "версия", "попытка", "штука", "тысяча",
        "доля", "цифра", "буква", "фраза", "таблица", "группа", "система", "программа",
    )
    private val feminineAccusative = listOf(
        "страницу", "главу", "строку", "минуту", "секунду", "неделю", "книгу",
        "машину", "комнату", "задачу", "версию", "попытку", "штуку", "тысячу",
        "долю", "цифру", "букву", "фразу", "таблицу", "группу", "систему", "программу",
    )
    private val feminineGenitive = listOf(
        "страницы", "главы", "строки", "минуты", "секунды", "недели", "книги",
        "машины", "комнаты", "задачи", "версии", "попытки", "штуки", "тысячи",
        "доли", "цифры", "буквы", "фразы", "таблицы", "группы", "системы", "программы",
    )
    private val feminineDativeOrPrepositional = listOf(
        "странице", "главе", "строке", "минуте", "секунде", "неделе", "книге",
        "машине", "комнате", "задаче", "версии", "попытке", "штуке", "тысяче",
        "доле", "цифре", "букве", "фразе", "таблице", "группе", "системе", "программе",
    )
    private val feminineInstrumental = listOf(
        "страницей", "главой", "строкой", "минутой", "секундой", "неделей", "книгой",
        "машиной", "комнатой", "задачей", "версией", "попыткой", "штукой", "тысячей",
        "долей", "цифрой", "буквой", "фразой", "таблицей", "группой", "системой", "программой",
    )
    private val feminineAfterTwo = listOf(
        "страницы", "главы", "строки", "минуты", "секунды", "недели", "книги",
        "машины", "комнаты", "задачи", "версии", "попытки", "штуки", "тысячи",
        "доли", "цифры", "буквы", "фразы", "таблицы", "группы", "системы", "программы",
    )
    private val neuterNominativeOrAccusative = listOf(
        "слово", "место", "окно", "письмо", "число", "утро", "дело", "задание",
        "предложение", "издание", "сообщение", "приложение", "устройство", "событие",
        "правило", "поле", "значение",
    )

    /** Run before [RussianNumberNormalizer]. */
    fun normalizeRaw(input: String): String {
        var text = input

        text = clock.replace(text) { match ->
            val hours = match.groupValues[1].toInt()
            val minutes = match.groupValues[2].toInt()
            buildString {
                append(cardinalNom(hours, feminine = false))
                append(' ')
                append(countedNoun(hours, "час", "часа", "часов"))
                if (minutes != 0) {
                    append(' ')
                    append(cardinalNom(minutes, feminine = true))
                    append(' ')
                    append(countedNoun(minutes, "минута", "минуты", "минут"))
                }
            }
        }

        text = yearPrepositional.replace(text) { match ->
            val year = match.groupValues[2].toInt()
            "${match.groupValues[1]} ${yearOrdinal(year, YearCase.PREP)} ${match.groupValues[3]}"
        }
        text = yearDative.replace(text) { match ->
            val year = match.groupValues[2].toInt()
            "${match.groupValues[1]} ${yearOrdinal(year, YearCase.DAT)} ${match.groupValues[3]}"
        }
        text = yearGenitiveWithPreposition.replace(text) { match ->
            val year = match.groupValues[2].toInt()
            "${match.groupValues[1]} ${yearOrdinal(year, YearCase.GEN)} ${match.groupValues[3]}"
        }
        text = bareYear.replace(text) { match ->
            val year = match.groupValues[1].toInt()
            val grammaticalCase = when (match.groupValues[2].lowercase()) {
                "год" -> YearCase.NOM
                "года" -> YearCase.GEN
                "годом" -> YearCase.INS
                else -> YearCase.NOM
            }
            "${yearOrdinal(year, grammaticalCase)} ${match.groupValues[2]}"
        }

        return text
    }

    /** Run after [RussianNumberNormalizer]. */
    fun repairAgreement(input: String): String {
        var text = input

        // RussianNumberNormalizer deliberately defaults to masculine. Only the
        // final one/two word in a compound numeral is gender-sensitive, so these
        // substitutions also repair 21/22, 101/102, etc.
        text = replaceBefore(text, "один", "одна", feminineNominative)
        text = replaceBefore(text, "один", "одну", feminineAccusative)
        text = replaceBefore(text, "одного", "одной", feminineGenitive)
        text = replaceBefore(text, "одному", "одной", feminineDativeOrPrepositional)
        text = replaceBefore(text, "одном", "одной", feminineDativeOrPrepositional)
        text = replaceBefore(text, "одним", "одной", feminineInstrumental)
        text = replaceBefore(text, "два", "две", feminineAfterTwo)

        // Neuter differs from masculine only in nominative/accusative one.
        text = replaceBefore(text, "один", "одно", neuterNominativeOrAccusative)

        return text
    }

    private fun replaceBefore(text: String, from: String, to: String, nouns: List<String>): String {
        val alternatives = nouns.joinToString("|") { Regex.escape(it) }
        val regex = Regex(
            """\b${Regex.escape(from)}(?=\s+(?:$alternatives)\b)""",
            RegexOption.IGNORE_CASE,
        )
        return regex.replace(text, to)
    }

    private fun countedNoun(number: Int, one: String, few: String, many: String): String {
        val mod100 = number % 100
        val mod10 = number % 10
        return when {
            mod100 in 11..14 -> many
            mod10 == 1 -> one
            mod10 in 2..4 -> few
            else -> many
        }
    }

    private fun cardinalNom(number: Int, feminine: Boolean): String {
        require(number in 0..9999)
        if (number == 0) return "ноль"

        val parts = mutableListOf<String>()
        val thousands = number / 1000
        val remainder = number % 1000

        if (thousands > 0) {
            parts += belowThousandNom(thousands, feminine = true)
            parts += countedNoun(thousands, "тысяча", "тысячи", "тысяч")
        }
        if (remainder > 0) parts += belowThousandNom(remainder, feminine)
        return parts.joinToString(" ")
    }

    private fun belowThousandNom(number: Int, feminine: Boolean): String {
        val parts = mutableListOf<String>()
        val hundreds = number / 100
        val lastTwo = number % 100

        val hundredsWords = listOf("", "сто", "двести", "триста", "четыреста", "пятьсот", "шестьсот", "семьсот", "восемьсот", "девятьсот")
        if (hundreds > 0) parts += hundredsWords[hundreds]

        val small = mapOf(
            1 to if (feminine) "одна" else "один",
            2 to if (feminine) "две" else "два",
            3 to "три", 4 to "четыре", 5 to "пять", 6 to "шесть", 7 to "семь",
            8 to "восемь", 9 to "девять", 10 to "десять", 11 to "одиннадцать",
            12 to "двенадцать", 13 to "тринадцать", 14 to "четырнадцать",
            15 to "пятнадцать", 16 to "шестнадцать", 17 to "семнадцать",
            18 to "восемнадцать", 19 to "девятнадцать",
        )
        val tens = mapOf(
            2 to "двадцать", 3 to "тридцать", 4 to "сорок", 5 to "пятьдесят",
            6 to "шестьдесят", 7 to "семьдесят", 8 to "восемьдесят", 9 to "девяносто",
        )

        if (lastTwo in 1..19) {
            parts += small.getValue(lastTwo)
        } else if (lastTwo >= 20) {
            parts += tens.getValue(lastTwo / 10)
            val units = lastTwo % 10
            if (units > 0) parts += small.getValue(units)
        }

        return parts.joinToString(" ")
    }

    private fun yearOrdinal(year: Int, grammaticalCase: YearCase): String {
        if (year !in 1000..2999) return year.toString()

        val tail = ordinalTail(year)
        val prefixValue = year - tail
        var prefix = if (prefixValue > 0) cardinalNom(prefixValue, feminine = false) else ""
        if (prefix.startsWith("одна тысяча")) prefix = prefix.removePrefix("одна ")

        val nominative = ordinalNominative[tail] ?: return year.toString()
        val inflected = inflectOrdinal(nominative, grammaticalCase)
        return listOf(prefix, inflected).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun ordinalTail(number: Int): Int {
        if (number % 100 in 1..19) return number % 100
        if (number % 10 != 0) return number % 10
        if (number % 100 != 0) return number % 100
        if (number % 1000 != 0) return number % 1000
        return number % 10_000
    }

    private fun inflectOrdinal(nominative: String, grammaticalCase: YearCase): String {
        if (grammaticalCase == YearCase.NOM) return nominative
        if (nominative == "третий") {
            return when (grammaticalCase) {
                YearCase.NOM -> "третий"
                YearCase.GEN -> "третьего"
                YearCase.DAT -> "третьему"
                YearCase.INS -> "третьим"
                YearCase.PREP -> "третьем"
            }
        }

        val stem = when {
            nominative.endsWith("ый") || nominative.endsWith("ой") -> nominative.dropLast(2)
            else -> return nominative
        }
        return when (grammaticalCase) {
            YearCase.NOM -> nominative
            YearCase.GEN -> stem + "ого"
            YearCase.DAT -> stem + "ому"
            YearCase.INS -> stem + "ым"
            YearCase.PREP -> stem + "ом"
        }
    }

    private val ordinalNominative = mapOf(
        1 to "первый", 2 to "второй", 3 to "третий", 4 to "четвёртый", 5 to "пятый",
        6 to "шестой", 7 to "седьмой", 8 to "восьмой", 9 to "девятый", 10 to "десятый",
        11 to "одиннадцатый", 12 to "двенадцатый", 13 to "тринадцатый", 14 to "четырнадцатый",
        15 to "пятнадцатый", 16 to "шестнадцатый", 17 to "семнадцатый", 18 to "восемнадцатый",
        19 to "девятнадцатый", 20 to "двадцатый", 30 to "тридцатый", 40 to "сороковой",
        50 to "пятидесятый", 60 to "шестидесятый", 70 to "семидесятый", 80 to "восьмидесятый",
        90 to "девяностый", 100 to "сотый", 200 to "двухсотый", 300 to "трёхсотый",
        400 to "четырёхсотый", 500 to "пятисотый", 600 to "шестисотый", 700 to "семисотый",
        800 to "восьмисотый", 900 to "девятисотый", 1000 to "тысячный", 2000 to "двухтысячный",
    )
}
