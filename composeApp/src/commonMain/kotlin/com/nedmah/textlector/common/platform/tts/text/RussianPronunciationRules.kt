package com.nedmah.textlector.common.platform.tts.text

/**
 * Conservative pronunciation hints for Russian neural TTS.
 *
 * U+0301 COMBINING ACUTE ACCENT is intentionally used for stress. Both the
 * Supertonic tokenizer and the espeak-ng path used by Piper can consume this
 * representation without changing the text stored in the document.
 *
 * Ambiguous homographs are changed only when nearby context gives a useful
 * signal. Otherwise the original spelling is preserved so a future contextual
 * ONNX resolver can make the decision instead of a brittle rule.
 */
object RussianPronunciationRules {

    private val wordRegex = Regex("[А-Яа-яЁё]+")

    private val safeYo = mapOf(
        "елка" to "ёлка", "елки" to "ёлки", "елку" to "ёлку",
        "ребенок" to "ребёнок", "ребенка" to "ребёнка", "ребенку" to "ребёнку",
        "береза" to "берёза", "березы" to "берёзы", "березу" to "берёзу",
        "мед" to "мёд", "лед" to "лёд", "слезы" to "слёзы",
        "звезды" to "звёзды", "звезд" to "звёзд",
        "черный" to "чёрный", "черная" to "чёрная", "черное" to "чёрное", "черные" to "чёрные",
        "четвертый" to "четвёртый", "четвертом" to "четвёртом", "четвертого" to "четвёртого",
        "свекла" to "свёкла", "желтый" to "жёлтый", "желтая" to "жёлтая",
        "шепот" to "шёпот", "щелкнул" to "щёлкнул", "щелкнула" to "щёлкнула"
    )

    private val safeStress = mapOf(
        "каталог" to "катало́г",
        "каталога" to "катало́га",
        "договор" to "догово́р",
        "договоры" to "догово́ры",
        "договоров" to "догово́ров",
        "квартал" to "кварта́л",
        "квартала" to "кварта́ла",
        "звонит" to "звони́т",
        "звонят" to "звоня́т",
        "звонила" to "звони́ла",
        "звонил" to "звони́л",
        "торты" to "то́рты",
        "тортов" to "то́ртов",
        "банты" to "ба́нты",
        "бантов" to "ба́нтов",
        "жалюзи" to "жалюзи́",
        "щавель" to "щаве́ль",
        "красивее" to "краси́вее",
        "облегчить" to "облегчи́ть",
        "диспансер" to "диспансе́р",
        "эксперт" to "экспе́рт",
        "эксперты" to "экспе́рты"
    )

    fun apply(text: String): String {
        val matches = wordRegex.findAll(text).toList()
        if (matches.isEmpty()) return text

        val out = StringBuilder(text.length + 16)
        var cursor = 0

        matches.forEachIndexed { index, match ->
            val start = match.range.first
            val endExclusive = match.range.last + 1
            out.append(text, cursor, start)

            val original = match.value
            val alreadyStressed = endExclusive < text.length && text[endExclusive] == '\u0301'
            val replacement = if (alreadyStressed) {
                original
            } else {
                val from = (index - 6).coerceAtLeast(0)
                val until = (index + 7).coerceAtMost(matches.size)
                val context = matches.subList(from, until)
                    .map { it.value.lowercase() }
                    .filter { it != original.lowercase() }
                    .toSet()
                pronunciationFor(original.lowercase(), context)
                    ?: safeYo[original.lowercase()]
                    ?: safeStress[original.lowercase()]
                    ?: original
            }

            out.append(preserveCase(original, replacement))
            cursor = endExclusive
        }

        out.append(text, cursor, text.length)
        return out.toString()
    }

    private fun pronunciationFor(word: String, context: Set<String>): String? = when (word) {
        "замок" -> choose(
            context,
            primary = setOf("ключ", "ключом", "дверь", "двери", "запер", "запереть", "отпер", "открыть", "закрыть", "навесной", "кодовый", "скважина"),
            primaryValue = "замо́к",
            secondary = setOf("дворец", "башня", "башни", "крепость", "рыцарь", "рыцари", "король", "короля", "средневековый", "старинный"),
            secondaryValue = "за́мок"
        )

        "мука" -> choose(
            context,
            primary = setOf("тесто", "хлеб", "пекарь", "печь", "мешок", "мешка", "пшеничная", "ржаная", "просеять", "ложка"),
            primaryValue = "мука́",
            secondary = setOf("боль", "страдание", "страдания", "пытка", "пытки", "мучение", "мучения", "невыносимая", "душевная"),
            secondaryValue = "му́ка"
        )

        "плачу" -> choose(
            context,
            primary = setOf("рублей", "рубля", "рубль", "деньги", "деньгами", "счёт", "счет", "карта", "картой", "покупку", "аренду", "налоги"),
            primaryValue = "плачу́",
            secondary = setOf("слёзы", "слезы", "рыдаю", "рыдать", "горе", "боль", "грусть", "обида", "плакать"),
            secondaryValue = "пла́чу"
        )

        "орган" -> choose(
            context,
            primary = setOf("музыка", "музыки", "церковь", "церкви", "клавиши", "органист", "инструмент", "концерт", "бах"),
            primaryValue = "орга́н",
            secondary = setOf("тело", "тела", "внутренний", "внутренние", "власть", "власти", "государственный", "надзорный", "пищеварения", "дыхания"),
            secondaryValue = "о́рган"
        )

        "атлас" -> choose(
            context,
            primary = setOf("ткань", "ткани", "шёлк", "шелк", "платье", "лента", "гладкий", "материал"),
            primaryValue = "атла́с",
            secondary = setOf("карта", "карты", "география", "географический", "анатомия", "анатомический", "страница", "книга"),
            secondaryValue = "а́тлас"
        )

        "ирис" -> choose(
            context,
            primary = setOf("конфета", "конфеты", "сладкий", "сладость", "карамель", "магазин"),
            primaryValue = "ири́с",
            secondary = setOf("цветок", "цветы", "сад", "клумба", "лепестки", "фиолетовый", "растение"),
            secondaryValue = "и́рис"
        )

        "белки" -> choose(
            context,
            primary = setOf("лес", "лесу", "дерево", "деревья", "орех", "орехи", "хвост", "хвосты", "зверёк", "зверек"),
            primaryValue = "бе́лки",
            secondary = setOf("белок", "жиры", "углеводы", "питание", "рацион", "грамм", "граммов", "протеин", "калории"),
            secondaryValue = "белки́"
        )

        "пропасть" -> choose(
            context,
            primary = setOf("край", "краю", "обрыв", "бездна", "глубина", "глубокая", "ущелье", "дна"),
            primaryValue = "про́пасть",
            secondary = setOf("может", "мог", "могла", "хочет", "хотел", "хотела", "исчезнуть", "вдруг", "навсегда"),
            secondaryValue = "пропа́сть"
        )

        "стоит" -> choose(
            context,
            primary = setOf("сколько", "цена", "цену", "рублей", "рубля", "долларов", "евро", "дорого", "дёшево", "дешево", "попробовать", "подумать", "сделать"),
            primaryValue = "сто́ит",
            secondary = setOf("здесь", "там", "рядом", "перед", "возле", "дом", "машина", "стол", "дерево", "человек", "углу"),
            secondaryValue = "стои́т"
        )

        "уже" -> choose(
            context,
            primary = setOf("чем", "становится", "становился", "становилась", "стала", "стал", "сделался", "сделалась"),
            primaryValue = "у́же",
            secondary = setOf("давно", "сейчас", "теперь", "сегодня", "вчера", "был", "была", "были", "пришёл", "пришел", "готов"),
            secondaryValue = "уже́"
        )

        "дорога" -> choose(
            context,
            primary = setOf("путь", "машина", "машины", "трасса", "улица", "асфальт", "ведёт", "ведет", "длинная", "просёлочная", "проселочная"),
            primaryValue = "доро́га",
            secondary = setOf("мне", "тебе", "ему", "ей", "нам", "сердцу", "память", "очень"),
            secondaryValue = "дорога́"
        )

        "духи" -> choose(
            context,
            primary = setOf("флакон", "запах", "аромат", "парфюм", "надушилась", "надушился", "магазин"),
            primaryValue = "духи́",
            secondary = setOf("призрак", "призраки", "души", "нечистые", "злые", "добрые", "вызывать", "потусторонние"),
            secondaryValue = "ду́хи"
        )

        "трусы" -> choose(
            context,
            primary = setOf("бельё", "белье", "одежда", "одежду", "хлопок", "шорты", "размер", "стирка"),
            primaryValue = "трусы́",
            secondary = setOf("боятся", "боится", "страх", "смелость", "предатели", "трус", "сбежали"),
            secondaryValue = "тру́сы"
        )

        else -> null
    }

    private fun choose(
        context: Set<String>,
        primary: Set<String>,
        primaryValue: String,
        secondary: Set<String>,
        secondaryValue: String,
    ): String? {
        val primaryHits = context.count { it in primary }
        val secondaryHits = context.count { it in secondary }
        return when {
            primaryHits > secondaryHits && primaryHits > 0 -> primaryValue
            secondaryHits > primaryHits && secondaryHits > 0 -> secondaryValue
            else -> null
        }
    }

    private fun preserveCase(original: String, replacement: String): String {
        if (original.isEmpty() || replacement.isEmpty()) return replacement
        return when {
            original.all { !it.isLetter() || it.isUpperCase() } -> replacement.uppercase()
            original.first().isUpperCase() -> replacement.replaceFirstChar { it.uppercase() }
            else -> replacement
        }
    }
}
