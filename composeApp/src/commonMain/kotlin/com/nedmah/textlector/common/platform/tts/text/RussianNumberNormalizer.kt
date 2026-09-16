package com.nedmah.textlector.common.platform.tts.text

/**
 * Focused Russian text normalizer for local TTS.
 *
 * It deliberately prefers deterministic grammar over guessing: dates/decimal
 * notation are left untouched unless a supported pattern is recognized. Plain
 * integers, common measurement units, governed cases and year/ordinal phrases
 * are expanded to words.
 */
object RussianNumberNormalizer {

    private enum class Case { NOM, GEN, DAT, INS, PREP }
    private enum class Gender { MASC, FEM }

    private data class UnitForms(
        val nominativeSingular: String,
        val genitiveSingular: String,
        val genitivePlural: String,
        val dativeSingular: String,
        val dativePlural: String,
        val instrumentalSingular: String,
        val instrumentalPlural: String,
        val prepositionalSingular: String,
        val prepositionalPlural: String,
    )

    private val yearPrepositional = Regex(
        """(?<![А-Яа-яЁё0-9_])(в|на)\s+(\d{4})\s+(году)(?![А-Яа-яЁё0-9_])""",
        RegexOption.IGNORE_CASE
    )
    private val yearGenitive = Regex(
        """(?<![А-Яа-яЁё0-9_])(с|до|после)\s+(\d{4})\s+(года)(?![А-Яа-яЁё0-9_])""",
        RegexOption.IGNORE_CASE
    )
    private val masculineOrdinalPrepositional = Regex(
        """(?<![А-Яа-яЁё0-9_])(в|на)\s+(\d{1,4})\s+(этаже|месте|уровне|пункте|разделе)(?![А-Яа-яЁё0-9_])""",
        RegexOption.IGNORE_CASE
    )
    private val feminineOrdinalPrepositional = Regex(
        """(?<![А-Яа-яЁё0-9_])(в|на)\s+(\d{1,4})\s+(странице|главе|строке|части)(?![А-Яа-яЁё0-9_])""",
        RegexOption.IGNORE_CASE
    )

    private val governedNumber = Regex(
        """(?<![А-Яа-яЁё0-9_])(около|возле|вокруг|без|до|из|от|у|для|после|кроме|к|ко|с|со|над|под|перед|между|о|об|обо|при)\s+(\d{1,6})(?:\s*(км|кг|см|мм|м|руб\.?|рубль|рубля|рублей|₽|%)(?![А-Яа-яЁё]))?""",
        RegexOption.IGNORE_CASE
    )

    private val numberWithUnit = Regex(
        """(?<![\d.,/:\-])(\d{1,6})\s*(км|кг|см|мм|м|руб\.?|рубль|рубля|рублей|₽|%)(?![А-Яа-яЁё])""",
        RegexOption.IGNORE_CASE
    )

    // Avoid touching parts of dates, decimals, versions, ranges and similar notation.
    private val plainInteger = Regex("""(?<![\d.,/:\-])\d{1,6}(?![\d.,/:\-])""")

    private val unitForms = mapOf(
        "км" to UnitForms("километр", "километра", "километров", "километру", "километрам", "километром", "километрами", "километре", "километрах"),
        "м" to UnitForms("метр", "метра", "метров", "метру", "метрам", "метром", "метрами", "метре", "метрах"),
        "см" to UnitForms("сантиметр", "сантиметра", "сантиметров", "сантиметру", "сантиметрам", "сантиметром", "сантиметрами", "сантиметре", "сантиметрах"),
        "мм" to UnitForms("миллиметр", "миллиметра", "миллиметров", "миллиметру", "миллиметрам", "миллиметром", "миллиметрами", "миллиметре", "миллиметрах"),
        "кг" to UnitForms("килограмм", "килограмма", "килограммов", "килограмму", "килограммам", "килограммом", "килограммами", "килограмме", "килограммах"),
        "руб" to UnitForms("рубль", "рубля", "рублей", "рублю", "рублям", "рублём", "рублями", "рубле", "рублях"),
        "%" to UnitForms("процент", "процента", "процентов", "проценту", "процентам", "процентом", "процентами", "проценте", "процентах"),
    )

    fun normalize(input: String): String {
        var text = input

        // Year phrases are ordinal in Russian: «в 2024 году», «с 2024 года».
        text = yearPrepositional.replace(text) { m ->
            val number = m.groupValues[2].toIntOrNull() ?: return@replace m.value
            "${m.groupValues[1]} ${ordinal(number, Case.PREP, Gender.MASC, yearStyle = true)} ${m.groupValues[3]}"
        }
        text = yearGenitive.replace(text) { m ->
            val number = m.groupValues[2].toIntOrNull() ?: return@replace m.value
            "${m.groupValues[1]} ${ordinal(number, Case.GEN, Gender.MASC, yearStyle = true)} ${m.groupValues[3]}"
        }

        // Ordinal locatives: «на 5 этаже», «в 3 главе».
        text = masculineOrdinalPrepositional.replace(text) { m ->
            val number = m.groupValues[2].toIntOrNull() ?: return@replace m.value
            "${m.groupValues[1]} ${ordinal(number, Case.PREP, Gender.MASC)} ${m.groupValues[3]}"
        }
        text = feminineOrdinalPrepositional.replace(text) { m ->
            val number = m.groupValues[2].toIntOrNull() ?: return@replace m.value
            "${m.groupValues[1]} ${ordinal(number, Case.PREP, Gender.FEM)} ${m.groupValues[3]}"
        }

        // Cases governed by a preposition: «около 500 км», «с 500 рублями».
        text = governedNumber.replace(text) { m ->
            val number = m.groupValues[2].toIntOrNull() ?: return@replace m.value
            if (number > 999_999) return@replace m.value
            val grammaticalCase = caseForPreposition(m.groupValues[1])
            val unit = m.groupValues[3].takeIf { it.isNotBlank() }
            val words = cardinal(number, grammaticalCase)
            val unitWords = unit?.let { unitWord(number, grammaticalCase, it) }
            buildString {
                append(m.groupValues[1])
                append(' ')
                append(words)
                if (unitWords != null) {
                    append(' ')
                    append(unitWords)
                }
            }
        }

        // Common units without an explicit governing preposition.
        text = numberWithUnit.replace(text) { m ->
            val number = m.groupValues[1].toIntOrNull() ?: return@replace m.value
            if (number > 999_999) return@replace m.value
            val unit = unitWord(number, Case.NOM, m.groupValues[2]) ?: return@replace m.value
            "${cardinal(number, Case.NOM)} $unit"
        }

        // Finally expand safe standalone integers. Dates, decimals and versions are skipped.
        text = plainInteger.replace(text) { m ->
            val number = m.value.toIntOrNull() ?: return@replace m.value
            if (number > 999_999) m.value else cardinal(number, Case.NOM)
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

    private fun canonicalUnit(raw: String): String = when (raw.lowercase().removeSuffix(".")) {
        "руб", "рубль", "рубля", "рублей", "₽" -> "руб"
        else -> raw.lowercase().removeSuffix(".")
    }

    private fun unitWord(number: Int, grammaticalCase: Case, raw: String): String? {
        val forms = unitForms[canonicalUnit(raw)] ?: return null
        val lastTwo = number % 100
        val last = number % 10
        val singular = last == 1 && lastTwo != 11
        val few = last in 2..4 && lastTwo !in 12..14

        return when (grammaticalCase) {
            Case.NOM -> when {
                singular -> forms.nominativeSingular
                few -> forms.genitiveSingular
                else -> forms.genitivePlural
            }
            Case.GEN -> if (singular) forms.genitiveSingular else forms.genitivePlural
            Case.DAT -> if (singular) forms.dativeSingular else forms.dativePlural
            Case.INS -> if (singular) forms.instrumentalSingular else forms.instrumentalPlural
            Case.PREP -> if (singular) forms.prepositionalSingular else forms.prepositionalPlural
        }
    }

    private fun cardinal(number: Int, grammaticalCase: Case, gender: Gender = Gender.MASC): String {
        if (number == 0) return zero(grammaticalCase)
        if (number < 0 || number > 999_999) return number.toString()

        val parts = mutableListOf<String>()
        val thousands = number / 1000
        val remainder = number % 1000

        if (thousands > 0) {
            parts += belowThousand(thousands, grammaticalCase, Gender.FEM)
            parts += thousandWord(thousands, grammaticalCase)
        }
        if (remainder > 0) {
            parts += belowThousand(remainder, grammaticalCase, gender)
        }
        return parts.filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun belowThousand(number: Int, grammaticalCase: Case, gender: Gender): String {
        if (number == 0) return ""
        val parts = mutableListOf<String>()
        val hundreds = number / 100
        val lastTwo = number % 100

        if (hundreds > 0) parts += hundredWord(hundreds, grammaticalCase)

        if (lastTwo in 1..19) {
            parts += smallWord(lastTwo, grammaticalCase, gender)
        } else if (lastTwo >= 20) {
            val tens = lastTwo / 10
            val units = lastTwo % 10
            parts += tensWord(tens, grammaticalCase)
            if (units > 0) parts += smallWord(units, grammaticalCase, gender)
        }
        return parts.joinToString(" ")
    }

    private fun zero(c: Case): String = when (c) {
        Case.NOM -> "ноль"
        Case.GEN -> "нуля"
        Case.DAT -> "нулю"
        Case.INS -> "нулём"
        Case.PREP -> "нуле"
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
                Case.GEN -> "двух"
                Case.DAT -> "двум"
                Case.INS -> "двумя"
                Case.PREP -> "двух"
            }
        }

        val nominative = mapOf(
            3 to "три", 4 to "четыре", 5 to "пять", 6 to "шесть", 7 to "семь",
            8 to "восемь", 9 to "девять", 10 to "десять", 11 to "одиннадцать",
            12 to "двенадцать", 13 to "тринадцать", 14 to "четырнадцать",
            15 to "пятнадцать", 16 to "шестнадцать", 17 to "семнадцать",
            18 to "восемнадцать", 19 to "девятнадцать"
        )
        if (c == Case.NOM) return nominative[number] ?: number.toString()

        val oblique = mapOf(
            3 to Triple("трёх", "трём", "тремя"),
            4 to Triple("четырёх", "четырём", "четырьмя"),
            5 to Triple("пяти", "пяти", "пятью"),
            6 to Triple("шести", "шести", "шестью"),
            7 to Triple("семи", "семи", "семью"),
            8 to Triple("восьми", "восьми", "восемью"),
            9 to Triple("девяти", "девяти", "девятью"),
            10 to Triple("десяти", "десяти", "десятью"),
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
        1 -> when (c) { Case.NOM -> "сто"; else -> "ста" }
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

    private fun ordinal(number: Int, c: Case, gender: Gender, yearStyle: Boolean = false): String {
        if (number <= 0 || number > 999_999) return number.toString()

        val tailValue = ordinalTail(number)
        val prefixValue = number - tailValue
        var prefix = if (prefixValue > 0) cardinal(prefixValue, Case.NOM) else ""

        // Russian years use «тысяча девятьсот...», not «одна тысяча девятьсот...».
        if (yearStyle && prefix.startsWith("одна тысяча")) {
            prefix = prefix.removePrefix("одна ")
        }

        val tail = ordinalWord(tailValue, c, gender)
        if (tail.isBlank()) return cardinal(number, Case.NOM)
        return listOf(prefix, tail).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun ordinalTail(number: Int): Int {
        if (number % 100 in 1..19) return number % 100
        if (number % 10 != 0) return number % 10
        if (number % 100 != 0) return number % 100
        if (number % 1000 != 0) return number % 1000
        if (number % 10_000 != 0) return number % 10_000
        return number
    }

    private fun ordinalWord(value: Int, c: Case, gender: Gender): String {
        val mascPrep = mapOf(
            1 to "первом", 2 to "втором", 3 to "третьем", 4 to "четвёртом", 5 to "пятом",
            6 to "шестом", 7 to "седьмом", 8 to "восьмом", 9 to "девятом", 10 to "десятом",
            11 to "одиннадцатом", 12 to "двенадцатом", 13 to "тринадцатом", 14 to "четырнадцатом",
            15 to "пятнадцатом", 16 to "шестнадцатом", 17 to "семнадцатом", 18 to "восемнадцатом",
            19 to "девятнадцатом", 20 to "двадцатом", 30 to "тридцатом", 40 to "сороковом",
            50 to "пятидесятом", 60 to "шестидесятом", 70 to "семидесятом", 80 to "восьмидесятом",
            90 to "девяностом", 100 to "сотом", 200 to "двухсотом", 300 to "трёхсотом",
            400 to "четырёхсотом", 500 to "пятисотом", 600 to "шестисотом", 700 to "семисотом",
            800 to "восьмисотом", 900 to "девятисотом", 1000 to "тысячном", 2000 to "двухтысячном",
            3000 to "трёхтысячном", 4000 to "четырёхтысячном", 5000 to "пятитысячном",
            6000 to "шеститысячном", 7000 to "семитысячном", 8000 to "восьмитысячном", 9000 to "девятитысячном"
        )
        val mascGen = mapOf(
            1 to "первого", 2 to "второго", 3 to "третьего", 4 to "четвёртого", 5 to "пятого",
            6 to "шестого", 7 to "седьмого", 8 to "восьмого", 9 to "девятого", 10 to "десятого",
            11 to "одиннадцатого", 12 to "двенадцатого", 13 to "тринадцатого", 14 to "четырнадцатого",
            15 to "пятнадцатого", 16 to "шестнадцатого", 17 to "семнадцатого", 18 to "восемнадцатого",
            19 to "девятнадцатого", 20 to "двадцатого", 30 to "тридцатого", 40 to "сорокового",
            50 to "пятидесятого", 60 to "шестидесятого", 70 to "семидесятого", 80 to "восьмидесятого",
            90 to "девяностого", 100 to "сотого", 200 to "двухсотого", 300 to "трёхсотого",
            400 to "четырёхсотого", 500 to "пятисотого", 600 to "шестисотого", 700 to "семисотого",
            800 to "восьмисотого", 900 to "девятисотого", 1000 to "тысячного", 2000 to "двухтысячного",
            3000 to "трёхтысячного", 4000 to "четырёхтысячного", 5000 to "пятитысячного",
            6000 to "шеститысячного", 7000 to "семитысячного", 8000 to "восьмитысячного", 9000 to "девятитысячного"
        )
        val femPrep = mapOf(
            1 to "первой", 2 to "второй", 3 to "третьей", 4 to "четвёртой", 5 to "пятой",
            6 to "шестой", 7 to "седьмой", 8 to "восьмой", 9 to "девятой", 10 to "десятой",
            11 to "одиннадцатой", 12 to "двенадцатой", 13 to "тринадцатой", 14 to "четырнадцатой",
            15 to "пятнадцатой", 16 to "шестнадцатой", 17 to "семнадцатой", 18 to "восемнадцатой",
            19 to "девятнадцатой", 20 to "двадцатой", 30 to "тридцатой", 40 to "сороковой",
            50 to "пятидесятой", 60 to "шестидесятой", 70 to "семидесятой", 80 to "восьмидесятой",
            90 to "девяностой", 100 to "сотой", 200 to "двухсотой", 300 to "трёхсотой",
            400 to "четырёхсотой", 500 to "пятисотой", 600 to "шестисотой", 700 to "семисотой",
            800 to "восьмисотой", 900 to "девятисотой"
        )

        return when {
            gender == Gender.FEM && c == Case.PREP -> femPrep[value].orEmpty()
            gender == Gender.MASC && c == Case.GEN -> mascGen[value].orEmpty()
            gender == Gender.MASC && c == Case.PREP -> mascPrep[value].orEmpty()
            else -> ""
        }
    }
}
