package com.nedmah.textlector.common.platform.tts.text

/**
 * Adds noun-aware Russian number forms before the generic integer normalizer.
 *
 * This layer is intentionally narrow: it handles common book/time/navigation
 * nouns where grammatical gender matters for 1/2 and common governed phrases.
 * It leaves punctuation-heavy numeric notation untouched.
 */
object RussianNounNumberAgreement {

    private enum class Case { NOM, GEN, DAT, INS, PREP }
    private enum class Gender { MASC, FEM }

    private data class NounRule(
        val gender: Gender,
        val forms: Set<String>,
    )

    private val feminineRules = listOf(
        NounRule(Gender.FEM, setOf("страница", "страницы", "страниц", "странице", "страницу", "страницей", "страницами", "страницах")),
        NounRule(Gender.FEM, setOf("минута", "минуты", "минут", "минуте", "минуту", "минутой", "минутами", "минутах")),
        NounRule(Gender.FEM, setOf("секунда", "секунды", "секунд", "секунде", "секунду", "секундой", "секундами", "секундах")),
        NounRule(Gender.FEM, setOf("неделя", "недели", "недель", "неделе", "неделю", "неделей", "неделями", "неделях")),
        NounRule(Gender.FEM, setOf("глава", "главы", "глав", "главе", "главу", "главой", "главами", "главах")),
        NounRule(Gender.FEM, setOf("строка", "строки", "строк", "строке", "строку", "строкой", "строками", "строках")),
        NounRule(Gender.FEM, setOf("часть", "части", "частей", "частью", "частями", "частях")),
        NounRule(Gender.FEM, setOf("книга", "книги", "книг", "книге", "книгу", "книгой", "книгами", "книгах")),
    )

    private val masculineRules = listOf(
        NounRule(Gender.MASC, setOf("час", "часа", "часов", "часу", "часом", "часами", "часах")),
        NounRule(Gender.MASC, setOf("день", "дня", "дней", "дню", "днём", "днями", "днях")),
        NounRule(Gender.MASC, setOf("раз", "раза", "разов", "разу", "разом", "разами")),
        NounRule(Gender.MASC, setOf("этаж", "этажа", "этажей", "этажу", "этажом", "этажами", "этажах")),
        NounRule(Gender.MASC, setOf("пункт", "пункта", "пунктов", "пункту", "пунктом", "пунктами", "пунктах")),
        NounRule(Gender.MASC, setOf("раздел", "раздела", "разделов", "разделу", "разделом", "разделами", "разделах")),
    )

    private val rules = feminineRules + masculineRules
    private val nounToRule = buildMap {
        for (rule in rules) {
            for (form in rule.forms) put(form, rule)
        }
    }

    private val nounAlternation = nounToRule.keys
        .sortedByDescending { it.length }
        .joinToString("|") { Regex.escape(it) }

    private val governed = Regex(
        """(?<![А-Яа-яЁё0-9_])(около|возле|вокруг|без|до|из|от|у|для|после|кроме|к|ко|с|со|над|под|перед|между|о|об|обо|при)\s+(\d{1,6})\s+($nounAlternation)(?![А-Яа-яЁё0-9_])""",
        RegexOption.IGNORE_CASE,
    )

    private val plainWithNoun = Regex(
        """(?<![\d.,/:\-])(\d{1,6})\s+($nounAlternation)(?![А-Яа-яЁё0-9_])""",
        RegexOption.IGNORE_CASE,
    )

    private val standaloneYear = Regex(
        """(?<![А-Яа-яЁё0-9_])(\d{4})\s+(год)(?![А-Яа-яЁё0-9_])""",
        RegexOption.IGNORE_CASE,
    )

    fun normalize(input: String): String {
        var text = input

        // «2024 год» -> «две тысячи двадцать четвёртый год».
        // Prepositional/genitive year phrases are handled earlier by the main normalizer.
        text = standaloneYear.replace(text) { match ->
            val number = match.groupValues[1].toIntOrNull() ?: return@replace match.value
            if (number !in 1000..9999) return@replace match.value
            "${yearOrdinalNominative(number)} ${match.groupValues[2]}"
        }

        // Preposition determines case: «к 21 странице» -> «к двадцати одной странице».
        text = governed.replace(text) { match ->
            val number = match.groupValues[2].toIntOrNull() ?: return@replace match.value
            val noun = match.groupValues[3]
            val rule = nounToRule[noun.lowercase()] ?: return@replace match.value
            val grammaticalCase = caseForPreposition(match.groupValues[1])
            "${match.groupValues[1]} ${cardinal(number, grammaticalCase, rule.gender)} $noun"
        }

        // Gender-sensitive nominative forms: «21 страница», «2 минуты», «1 час».
        text = plainWithNoun.replace(text) { match ->
            val number = match.groupValues[1].toIntOrNull() ?: return@replace match.value
            val noun = match.groupValues[2]
            val rule = nounToRule[noun.lowercase()] ?: return@replace match.value
            "$${cardinal(number, Case.NOM, rule.gender)} $noun".removePrefix("$")
        }

        return text
    }

    private fun caseForPreposition(raw: String): Case = when (raw.lowercase()) {
        "около", "возле", "вокруг", "без", "до", "из", "от", "у", "для", "после", "кроме" -> Case.GEN
        "к", "ко" -> Case.DAT
        "с", "со", "над", "под", "перед", "между" -> Case.INS
        "о", "об", "обо", "при" -> Case.PREP
        else -> Case.NOM
    }

    private fun cardinal(number: Int, grammaticalCase: Case, gender: Gender): String {
        if (number == 0) return zero(grammaticalCase)
        if (number !in 0..999_999) return number.toString()

        val parts = mutableListOf<String>()
        val thousands = number / 1000
        val remainder = number % 1000
        if (thousands > 0) {
            parts += belowThousand(thousands, grammaticalCase, Gender.FEM)
            parts += thousandWord(thousands, grammaticalCase)
        }
        if (remainder > 0) parts += belowThousand(remainder, grammaticalCase, gender)
        return parts.filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun belowThousand(number: Int, grammaticalCase: Case, gender: Gender): String {
        val parts = mutableListOf<String>()
        val hundreds = number / 100
        val lastTwo = number % 100
        if (hundreds > 0) parts += hundredWord(hundreds, grammaticalCase)
        if (lastTwo in 1..19) {
            parts += smallWord(lastTwo, grammaticalCase, gender)
        } else if (lastTwo >= 20) {
            parts += tensWord(lastTwo / 10, grammaticalCase)
            if (lastTwo % 10 > 0) parts += smallWord(lastTwo % 10, grammaticalCase, gender)
        }
        return parts.joinToString(" ")
    }

    private fun smallWord(number: Int, c: Case, gender: Gender): String {
        if (number == 1) {
            return if (gender == Gender.FEM) when (c) {
                Case.NOM -> "одна"
                Case.GEN, Case.DAT, Case.INS, Case.PREP -> "одной"
            } else when (c) {
                Case.NOM -> "один"
                Case.GEN -> "одного"
                Case.DAT -> "одному"
                Case.INS -> "одним"
                Case.PREP -> "одном"
            }
        }
        if (number == 2) {
            return when (c) {
                Case.NOM -> if (gender == Gender.FEM) "две" else "два"
                Case.GEN, Case.PREP -> "двух"
                Case.DAT -> "двум"
                Case.INS -> "двумя"
            }
        }

        val nominative = mapOf(
            3 to "три", 4 to "четыре", 5 to "пять", 6 to "шесть", 7 to "семь", 8 to "восемь",
            9 to "девять", 10 to "десять", 11 to "одиннадцать", 12 to "двенадцать",
            13 to "тринадцать", 14 to "четырнадцать", 15 to "пятнадцать", 16 to "шестнадцать",
            17 to "семнадцать", 18 to "восемнадцать", 19 to "девятнадцать",
        )
        if (c == Case.NOM) return nominative[number] ?: number.toString()
        val oblique = mapOf(
            3 to Triple("трёх", "трём", "тремя"), 4 to Triple("четырёх", "четырём", "четырьмя"),
            5 to Triple("пяти", "пяти", "пятью"), 6 to Triple("шести", "шести", "шестью"),
            7 to Triple("семи", "семи", "семью"), 8 to Triple("восьми", "восьми", "восемью"),
            9 to Triple("девяти", "девяти", "девятью"), 10 to Triple("десяти", "десяти", "десятью"),
            11 to Triple("одиннадцати", "одиннадцати", "одиннадцатью"),
            12 to Triple("двенадцати", "двенадцати", "двенадцатью"),
            13 to Triple("тринадцати", "тринадцати", "тринадцатью"),
            14 to Triple("четырнадцати", "четырнадцати", "четырнадцатью"),
            15 to Triple("пятнадцати", "пятнадцати", "пятнадцатью"),
            16 to Triple("шестнадцати", "шестнадцати", "шестнадцатью"),
            17 to Triple("семнадцати", "семнадцати", "семнадцатью"),
            18 to Triple("восемнадцати", "восемнадцати", "восемнадцатью"),
            19 to Triple("девятнадцати", "девятнадцати", "девятнадцатью"),
        )[number] ?: return number.toString()
        return when (c) {
            Case.GEN, Case.PREP -> oblique.first
            Case.DAT -> oblique.second
            Case.INS -> oblique.third
            Case.NOM -> nominative[number] ?: number.toString()
        }
    }

    private fun tensWord(tens: Int, c: Case): String = when (tens) {
        2 -> when (c) { Case.NOM -> "двадцать"; Case.INS -> "двадцатью"; else -> "двадцати" }
        3 -> when (c) { Case.NOM -> "тридцать"; Case.INS -> "тридцатью"; else -> "тридцати" }
        4 -> if (c == Case.NOM) "сорок" else "сорока"
        5 -> when (c) { Case.NOM -> "пятьдесят"; Case.INS -> "пятьюдесятью"; else -> "пятидесяти" }
        6 -> when (c) { Case.NOM -> "шестьдесят"; Case.INS -> "шестьюдесятью"; else -> "шестидесяти" }
        7 -> when (c) { Case.NOM -> "семьдесят"; Case.INS -> "семьюдесятью"; else -> "семидесяти" }
        8 -> when (c) { Case.NOM -> "восемьдесят"; Case.INS -> "восемьюдесятью"; else -> "восьмидесяти" }
        9 -> if (c == Case.NOM) "девяносто" else "девяноста"
        else -> ""
    }

    private fun hundredWord(hundreds: Int, c: Case): String = when (hundreds) {
        1 -> if (c == Case.NOM) "сто" else "ста"
        2 -> when (c) { Case.NOM -> "двести"; Case.GEN, Case.PREP -> "двухсот"; Case.DAT -> "двумстам"; Case.INS -> "двумястами" }
        3 -> when (c) { Case.NOM -> "триста"; Case.GEN, Case.PREP -> "трёхсот"; Case.DAT -> "трёмстам"; Case.INS -> "тремястами" }
        4 -> when (c) { Case.NOM -> "четыреста"; Case.GEN, Case.PREP -> "четырёхсот"; Case.DAT -> "четырёмстам"; Case.INS -> "четырьмястами" }
        5 -> when (c) { Case.NOM -> "пятьсот"; Case.GEN, Case.PREP -> "пятисот"; Case.DAT -> "пятистам"; Case.INS -> "пятьюстами" }
        6 -> when (c) { Case.NOM -> "шестьсот"; Case.GEN, Case.PREP -> "шестисот"; Case.DAT -> "шестистам"; Case.INS -> "шестьюстами" }
        7 -> when (c) { Case.NOM -> "семьсот"; Case.GEN, Case.PREP -> "семисот"; Case.DAT -> "семистам"; Case.INS -> "семьюстами" }
        8 -> when (c) { Case.NOM -> "восемьсот"; Case.GEN, Case.PREP -> "восьмисот"; Case.DAT -> "восьмистам"; Case.INS -> "восемьюстами" }
        9 -> when (c) { Case.NOM -> "девятьсот"; Case.GEN, Case.PREP -> "девятисот"; Case.DAT -> "девятистам"; Case.INS -> "девятьюстами" }
        else -> ""
    }

    private fun thousandWord(group: Int, c: Case): String {
        val lastTwo = group % 100
        val last = group % 10
        val singular = last == 1 && lastTwo != 11
        val few = last in 2..4 && lastTwo !in 12..14
        return when (c) {
            Case.NOM -> when { singular -> "тысяча"; few -> "тысячи"; else -> "тысяч" }
            Case.GEN -> if (singular) "тысячи" else "тысяч"
            Case.DAT -> if (singular) "тысяче" else "тысячам"
            Case.INS -> if (singular) "тысячей" else "тысячами"
            Case.PREP -> if (singular) "тысяче" else "тысячах"
        }
    }

    private fun zero(c: Case): String = when (c) {
        Case.NOM -> "ноль"
        Case.GEN -> "нуля"
        Case.DAT -> "нулю"
        Case.INS -> "нулём"
        Case.PREP -> "нуле"
    }

    private fun yearOrdinalNominative(year: Int): String {
        val tail = when {
            year % 100 in 1..19 -> year % 100
            year % 10 != 0 -> year % 10
            year % 100 != 0 -> year % 100
            year % 1000 != 0 -> year % 1000
            else -> year
        }
        val prefixValue = year - tail
        var prefix = if (prefixValue > 0) cardinal(prefixValue, Case.NOM, Gender.MASC) else ""
        if (prefix.startsWith("одна тысяча")) prefix = prefix.removePrefix("одна ")
        val ordinal = ordinalNominative(tail)
        return listOf(prefix, ordinal).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun ordinalNominative(value: Int): String = mapOf(
        1 to "первый", 2 to "второй", 3 to "третий", 4 to "четвёртый", 5 to "пятый",
        6 to "шестой", 7 to "седьмой", 8 to "восьмой", 9 to "девятый", 10 to "десятый",
        11 to "одиннадцатый", 12 to "двенадцатый", 13 to "тринадцатый", 14 to "четырнадцатый",
        15 to "пятнадцатый", 16 to "шестнадцатый", 17 to "семнадцатый", 18 to "восемнадцатый",
        19 to "девятнадцатый", 20 to "двадцатый", 30 to "тридцатый", 40 to "сороковой",
        50 to "пятидесятый", 60 to "шестидесятый", 70 to "семидесятый", 80 to "восьмидесятый",
        90 to "девяностый", 100 to "сотый", 200 to "двухсотый", 300 to "трёхсотый",
        400 to "четырёхсотый", 500 to "пятисотый", 600 to "шестисотый", 700 to "семисотый",
        800 to "восьмисотый", 900 to "девятисотый", 1000 to "тысячный",
        2000 to "двухтысячный", 3000 to "трёхтысячный", 4000 to "четырёхтысячный",
        5000 to "пятитысячный", 6000 to "шеститысячный", 7000 to "семитысячный",
        8000 to "восьмитысячный", 9000 to "девятитысячный",
    )[value].orEmpty()
}
