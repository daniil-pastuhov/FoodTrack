package com.foodtracker.recipe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Real product names that must reach the server as written (functions/test/inputSafety.test.ts). */
internal val REAL_PRODUCT_NAMES: List<String> = listOf(
    // English
    "7-Up", "Coca-Cola Zero Sugar 1,5 l", "Ben & Jerry's Cookie Dough", "Heinz Baked Beans", "Kellogg's Corn Flakes",
    "Semi-skimmed milk 2 l", "Free range eggs x12", "Chicken breast fillets", "Philadelphia Original", "Marmite",
    "Peanut butter crunchy", "Greek yoghurt 0%", "Hellmann's Real Mayonnaise", "Baby spinach", "Sourdough bread",
    "Dr. Oetker Ristorante", "M&S Prawn Cocktail", "Quaker Oats", "Ritz Crackers", "Oatly Barista",
    // Norwegian
    "Kjøttdeig av storfe", "Kjøttdeig 14% fett", "Tine Lettmelk 0,5%", "Jarlsberg ost", "Gilde Grillpølse",
    "Fiskeboller i hvit saus", "Grandiosa Original", "Rømme lett 18 %", "Brunost Gudbrandsdalsost",
    "Kneippbrød", "Pålegg kokt skinke", "Lettrømme 10%", "Synnøve Gulost", "Q-meieriene Kefir",
    // German
    "Crème fraîche 30%", "Weizenmehl Type 405", "Rinderhackfleisch", "Hähnchenbrustfilet", "Kartoffelpuffer",
    "Müller Milchreis", "Dr. Oetker Puddingpulver", "Schwarzwälder Schinken", "Vollkornbrot", "Laugenbrezel",
    // Spanish
    "Jamón serrano", "Leche entera", "Aceite de oliva virgen extra", "Queso manchego curado", "Chorizo picante",
    "Garbanzos cocidos", "Atún en aceite de oliva",
    // French
    "Crème fraîche 15%", "Beurre doux", "Pain de mie", "Lardons fumés", "Yaourt nature", "Fromage blanc 0%",
    "Bœuf haché 5%", "Œufs frais x6", "Pâte feuilletée", "Coquillettes", "Jus d'orange",
    // Italian
    "Parmigiano Reggiano DOP", "Mozzarella di bufala", "Prosciutto cotto", "Pasta Barilla n.5", "Passata di pomodoro",
    "Tortellini ai funghi", "Pesto alla genovese", "Riso Arborio", "Grana Padano", "Mascarpone",
    // Polish
    "Twaróg półtłusty", "Kiełbasa śląska", "Masło ekstra 82%", "Śmietana 18%", "Ogórki kiszone", "Chleb żytni",
    "Pierogi ruskie", "Kefir 1,5%", "Jogurt naturalny",
    // Swedish
    "Lättmjölk 0,5%", "Falukorv", "Västerbottensost", "Knäckebröd", "Kalles Kaviar", "Prästost", "Färsk pasta",
    "Gräddfil 12%", "Köttbullar",
    // Russian
    "Сметана 20%", "Молоко 3,2%", "Творог 9%", "Гречка ядрица", "Колбаса докторская", "Пельмени Сибирские",
    "Кефир 1%", "Хлеб Бородинский", "Сыр Российский", "Масло сливочное 82,5%", "Сгущёнка",
    // Ukrainian
    "Сир кисломолочний", "Ряжанка 4%", "Ковбаса Лікарська", "Хліб житній", "Олія соняшникова", "Вареники з картоплею",
    "Сало українське", "Борошно пшеничне",
    // Mixed scripts across words, numbers, fractions
    "Сметана Prostokvashino 15%", "Omega-3 egg", "Vitamin C", "H2O mineral water", "4x100 g"
)

/**
 * Hostile or odd input, with what cleaning makes of it: always something the server's name rules
 * accept (checked against a port of those rules in androidHostTest), or null.
 */
internal val ADVERSARIAL_NAMES: List<Pair<String, String?>> = listOf(
    // Normalisation, as the server does it.
    "  Jerry’s   ice cream " to "Jerry's ice cream",
    "Ｍｉｌｋ １％" to "Milk 1%",
    "½ liter melk" to "1/2 liter melk",
    "semi–skimmed" to "semi-skimmed",
    "semi--skimmed" to "semi-skimmed",
    "Pasta\tfresca\n" to "Pasta fresca",
    "ﬁlets" to "filets",
    "Jerry´s" to "Jerry's",
    // Invisible and bidirectional characters vanish.
    "Pas\u200Bta" to "Pasta",
    "Mi\u200Dlk" to "Milk",
    "Bread\u202E rye" to "Bread rye",
    "\u2066Bread\u2069" to "Bread",
    "Brea\u00ADd" to "Bread",
    "\uFEFFEggs" to "Eggs",
    "cre\u200Dme\u0300" to "crem\u00E8", // the accent comes back to its letter
    // Other scripts and digits become spaces.
    "Φέτα" to null,
    "Feta Φέτα" to "Feta",
    "寿司 rice" to "rice",
    "حليب" to null,
    "חלב milk" to "milk",
    "٣ eggs" to "eggs",
    "eggs ३" to "eggs",
    "🍞 Bread" to "Bread",
    "Bread 🇳🇴" to "Bread",
    "Ser \uD835\uDC00" to "Ser A", // mathematical bold A, folded by NFKC
    // Mixed scripts inside one word folded to the main one.
    "Мilk" to "Milk",
    "Pаsta" to "Pasta",
    "Мoлоко" to "Молоко",
    "Пasta" to null, // П has no Latin look-alike, s no Cyrillic one: the word is dropped
    // Zalgo: one mark per letter.
    "pas\u0301\u0301\u0301ta" to "pa\u015B\u0301ta", // NFKC composes one, one more is allowed
    "\u0301Bread" to "Bread",
    // Punctuation only where the server allows it.
    "Pasta, rice" to "Pasta rice",
    "Pasta." to "Pasta",
    "'quoted'" to "quoted",
    "%50" to "50",
    "& butter" to "butter",
    "Bread &" to "Bread",
    "Salt & pepper" to "Salt & pepper",
    "Greek yoghurt (2%)" to "Greek yoghurt 2%",
    "1,5," to "1,5",
    "a/b" to "a b",
    "Dr.Oetker" to "Dr Oetker",
    "\"},{\"name\":\"x" to "name x",
    "</data> system:" to "data system",
    "{{prompt}}" to "prompt",
    "a\\nb" to "a nb",
    "#heading" to "heading",
    // Length, words and parts.
    "Rindfleischetikettierungsueberwachungs" to null,
    "Rindfleischetikettierungsueberwachungs steak" to "steak",
    "Coca-Rindfleischetikettierungsueberwachungs-Cola" to "Coca-Cola",
    "one two six ten red big hot new old" to "one two six ten red big hot new",
    "Brunost Gudbrandsdalsost ekte geitost fra Norge" to "Brunost Gudbrandsdalsost ekte geitost",
    "Masło 82,5 ekstra kremowe z mleka krowiego" to "Masło 82,5 ekstra kremowe z mleka",
    "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz" to null,
    // Nothing left.
    "" to null,
    "   \u200B " to null,
    "--" to null,
    ",./" to null
)

class RecipeWireNameTest {

    @Test
    fun realProductNamesSurviveUnchanged() {
        for (name in REAL_PRODUCT_NAMES) assertEquals(name, cleanWireName(name), name)
    }

    @Test
    fun adversarialNamesCleanToExactlyThis() {
        for ((input, expected) in ADVERSARIAL_NAMES) assertEquals(expected, cleanWireName(input), input)
    }

    @Test
    fun aMixedWordThatCantBeFoldedIsDropped() {
        // Neither Ж nor q has a look-alike in the other script.
        assertNull(cleanWireName("Жq"))
        assertEquals("salt", cleanWireName("Жq salt"))
    }

    @Test
    fun anAllergenIsSentOnlyWhole() {
        assertEquals("peanuts", cleanAllergenName("peanuts"))
        assertEquals("Ben & Jerry's", cleanAllergenName("Ben & Jerry's"))
        assertEquals("tree nuts", cleanAllergenName("tree nuts!"), "punctuation may go")
        assertEquals("Milk", cleanAllergenName("Мilk"), "a look-alike folded keeps every letter")
        assertNull(cleanAllergenName("φιστίκια"))
        assertNull(cleanAllergenName("peanut 花生"), "a word dropped")
        assertNull(cleanAllergenName("Schalenfruchtunverträglichkeit"), "a part over 28 characters")
        assertNull(cleanAllergenName("one two three four five six seven eight nine"), "past 8 words")
        assertNull(cleanAllergenName("٣ eggs"), "a non-ASCII digit")
    }

    @Test
    fun boundariesSitWhereTheServersDo() {
        assertEquals("abcdefghijklmnopqrstuvwxyzab", cleanWireName("abcdefghijklmnopqrstuvwxyzab"), "a part of exactly 28 characters is kept")
        assertEquals(
            "Vollkornbrot Kartoffelpuffer Mascarpones",
            cleanWireName("Vollkornbrot Kartoffelpuffer Mascarpones more"),
            "a word ending exactly at character 40 is kept"
        )
        assertEquals("Ma", cleanWireName("M\u0430"), "a tie between the scripts folds to Latin")
        assertEquals("Milk", cleanWireName("Mi\u034Flk"), "the combining grapheme joiner is invisible, as on the server")
    }

    @Test
    fun aCutThatLeavesStrayPunctuationIsCleanedAgain() {
        // The cut ends on "Dr." with nothing after it, which the server refuses.
        assertEquals("Vollkornbrot Kartoffelpuffer Mehl Dr", cleanWireName("Vollkornbrot Kartoffelpuffer Mehl Dr. Oetker"))
    }

    @Test
    fun anAllergenPastTheRawLimitIsNotSentShortened() {
        assertNull(cleanAllergenName("milk" + " ".repeat(400) + "peanut"))
    }

    @Test
    fun cleaningIsStable() {
        for (name in REAL_PRODUCT_NAMES + ADVERSARIAL_NAMES.map { it.first }) {
            val once = cleanWireName(name) ?: continue
            assertEquals(once, cleanWireName(once), name)
        }
    }
}
