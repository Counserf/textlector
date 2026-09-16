package com.nedmah.textlector

import com.nedmah.textlector.common.platform.tts.text.NeuralTextPreprocessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RussianTtsPreprocessorTest {

    private val preprocessor = NeuralTextPreprocessor()

    @Test
    fun expandsGovernedNumbersWithCase() {
        assertEquals(
            "Мы проехали около пятисот километров.",
            preprocessor.process("Мы проехали около 500 км.", "ru")
        )
        assertEquals(
            "Я пришёл с пятьюстами рублями.",
            preprocessor.process("Я пришёл с 500 рублями.", "ru")
        )
    }

    @Test
    fun expandsYearsAndOrdinals() {
        assertEquals(
            "Это произошло в две тысячи двадцать четвёртом году.",
            preprocessor.process("Это произошло в 2024 году.", "ru")
        )
        assertEquals(
            "Он живёт на пятом этаже.",
            preprocessor.process("Он живёт на 5 этаже.", "ru")
        )
        assertEquals(
            "Продолжение в третьей главе.",
            preprocessor.process("Продолжение в 3 главе.", "ru")
        )
    }

    @Test
    fun agreesNumbersWithFeminineAndNeuterNouns() {
        assertEquals(
            "Прочитана одна страница и две страницы.",
            preprocessor.process("Прочитана 1 страница и 2 страницы.", "ru")
        )
        assertEquals(
            "К двадцати одной странице добавили одну задачу.",
            preprocessor.process("К 21 странице добавили 1 задачу.", "ru")
        )
        assertEquals(
            "Около одной минуты и с двадцатью одной секундой.",
            preprocessor.process("Около 1 минуты и с 21 секундой.", "ru")
        )
        assertEquals(
            "Открыто одно окно.",
            preprocessor.process("Открыто 1 окно.", "ru")
        )
    }

    @Test
    fun expandsClockTimeAndBareYears() {
        assertEquals(
            "Начало в тринадцать часов одна минута.",
            preprocessor.process("Начало в 13:01.", "ru")
        )
        assertEquals(
            "две тысячи двадцать четвёртый год завершён.",
            preprocessor.process("2024 год завершён.", "ru")
        )
        assertEquals(
            "События две тысячи двадцать четвёртого года.",
            preprocessor.process("События 2024 года.", "ru")
        )
        assertEquals(
            "Подготовились к две тысячи двадцать четвёртому году.",
            preprocessor.process("Подготовились к 2024 году.", "ru")
        )
        assertEquals(
            "Встреча в девять часов две минуты.",
            preprocessor.process("Встреча в 09:02.", "ru")
        )
    }

    @Test
    fun resolvesHomographsOnlyWhenContextIsUseful() {
        val lock = preprocessor.process("Он открыл замок ключом.", "ru")
        val castle = preprocessor.process("Старинный замок возвышался над городом.", "ru")
        val payment = preprocessor.process("Я плачу картой за покупку.", "ru")

        assertTrue("замо́к" in lock)
        assertTrue("за́мок" in castle)
        assertTrue("плачу́" in payment)
    }

    @Test
    fun doesNotRewriteNonRussianText() {
        assertEquals(
            "I paid 500 dollars.",
            preprocessor.process("I paid 500 dollars.", "en")
        )
    }
}
