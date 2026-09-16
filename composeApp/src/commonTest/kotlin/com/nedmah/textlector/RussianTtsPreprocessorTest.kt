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
