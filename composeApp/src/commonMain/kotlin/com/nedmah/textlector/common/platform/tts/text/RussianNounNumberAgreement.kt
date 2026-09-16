package com.nedmah.textlector.common.platform.tts.text

/**
 * Lightweight noun-aware rewrite that runs before the generic Russian number
 * normalizer. It only rewrites the gender-sensitive tail of numerals, letting
 * RussianNumberNormalizer handle the remaining numeric prefix and case.
 *
 * Examples:
 * 21 страница -> 20 одна страница -> двадцать одна страница
 * к 21 странице -> к 20 одной странице -> к двадцати одной странице
 * 2 минуты -> две минуты
 */
object RussianNounNumberAgreement {

    private val feminineNouns = setOf(
        "страница", "страницы", "страниц", "странице", "страницу", "страницей", "страницами", "страницах",
        "минута", "минуты", "минут", "минуте", "минуту", "минутой", "минутами", "минутах",
        "секунда", "секунды", "секунд", "секунде", "секунду", "секундой", "секундами", "секундах",
        "неделя", "недели", "недель", "неделе", "неделю", "неделей", "неделями", "неделях",
        "глава", "главы", "глав", "главе", "главу", "главой", "главами", "главах",
        "строка", "строки", "строк", "строке", "строку", "строкой", "строками", "строках",
        "часть", "части", "частей", "частью", "частями", "частях",
        "книга", "книги", "книг", "книге", "книгу", "книгой", "книгами", "книгах",
    )

    private val nounAlternation = feminineNouns
        .sortedByDescending { it.length }
        .joinToString("|") { Regex.escape(it) }

    private val feminineNumber = Regex(
        """(?<![\d.,/:\-])(\d{1,6})\s+($nounAlternation)(?![А-Яа-яЁё0-9_])""",
        RegexOption.IGNORE_CASE,
    )

    fun normalize(input: String): String = feminineNumber.replace(input) { match ->
        val number = match.groupValues[1].toIntOrNull() ?: return@replace match.value
        val noun = match.groupValues[2]
        val lastTwo = number % 100
        val last = number % 10

        when {
            last == 1 && lastTwo != 11 -> replaceTail(number, "одна", noun)
            last == 2 && lastTwo != 12 -> replaceTail(number, "две", noun)
            else -> match.value
        }
    }

    private fun replaceTail(number: Int, feminineTail: String, noun: String): String {
        val prefix = number - number % 10
        return if (prefix == 0) "$feminineTail $noun" else "$prefix $feminineTail $noun"
    }
}
