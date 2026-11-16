package com.foodtracker.staples

import com.foodtracker.model.Staple
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StaplesTest {

    private val languages = listOf("en", "ru", "nb", "es", "de", "fr", "sv", "da")

    private fun preset(id: String, at: Long = 0, language: String = "en", rowId: String = "p_$id") =
        Staple(rowId, StaplePresets.byId(id)!!.name(language), id, at)

    private fun own(name: String, at: Long, id: String = "o_$name") = Staple(id, name, null, at)

    // --- Presets ---------------------------------------------------------------------------

    @Test
    fun fourteenPresetsWithUniqueIdsAndANameInEveryLanguage() {
        assertEquals(14, StaplePresets.all.size)
        assertEquals(14, StaplePresets.all.map { it.id }.toSet().size)
        for (preset in StaplePresets.all) {
            assertEquals(languages.toSet(), preset.names.keys, preset.id)
            for (language in languages) assertTrue(preset.name(language).isNotBlank(), "${preset.id} $language")
        }
    }

    @Test
    fun presetsAreInTheTablesOrderWithItsIdsAndNames() {
        assertEquals(
            listOf("salt", "black_pepper", "sugar", "flour", "cooking_oil", "olive_oil", "butter", "eggs", "milk", "rice", "pasta", "garlic", "onions", "vinegar"),
            StaplePresets.all.map { it.id }
        )
        assertEquals("Huile d'olive", StaplePresets.byId("olive_oil")!!.name("fr"))
        assertEquals("Œufs", StaplePresets.byId("eggs")!!.name("fr"))
        assertEquals("Чёрный перец", StaplePresets.byId("black_pepper")!!.name("ru"))
        assertEquals("Sort peber", StaplePresets.byId("black_pepper")!!.name("da"))
        assertEquals("Salt", StaplePresets.byId("salt")!!.name("it"), "a language without names reads English")
        assertNull(StaplePresets.byId("truffle_oil"))
    }

    @Test
    fun stapleLanguageIsTheAppLanguagesBaseOrEnglish() {
        assertEquals("nb", stapleLanguage("nb"))
        assertEquals("nb", stapleLanguage("no"))
        assertEquals("nb", stapleLanguage("nn-NO"))
        assertEquals("de", stapleLanguage("de-AT"))
        assertEquals("fr", stapleLanguage("FR"))
        assertEquals("sv", stapleLanguage("sv_SE"))
        assertEquals("en", stapleLanguage("it"))
        assertEquals("en", stapleLanguage("pl"))
        assertEquals("en", stapleLanguage(""))
    }

    // --- Names, order, out -----------------------------------------------------------------

    @Test
    fun aPresetIsShownInTheAppsLanguageAnOwnEntryOrAnUnknownPresetByItsName() {
        val salt = preset("salt", language = "ru")
        assertEquals("Соль", salt.name)
        assertEquals("Salz", displayName(salt, "de"))
        assertEquals("Smoked paprika", displayName(own("Smoked paprika", 1), "de"))
        assertEquals("Truffle oil", displayName(Staple("x", "Truffle oil", "truffle_oil", 1), "de"))
    }

    @Test
    fun orderedPutsKnownPresetsInTableOrderThenTheRestByTimeThenId() {
        val staples = listOf(
            own("b", 5, id = "b"),
            preset("vinegar", at = 1),
            Staple("future", "Truffle oil", "truffle_oil", 3),
            preset("salt", at = 9),
            own("a", 5, id = "a"),
            own("first", 2)
        )
        assertEquals(listOf("p_salt", "p_vinegar", "o_first", "future", "a", "b"), ordered(staples).map { it.id })
    }

    @Test
    fun matchKeysCoverTheStoredNameEveryLanguageAndTheCleanedNames() {
        val keys = matchKeys(preset("olive_oil", language = "ru"))
        for (name in listOf("оливковое масло", "olive oil", "olivenolje", "aceite de oliva", "olivenöl", "huile d'olive", "olivolja", "olivenolie")) {
            assertTrue(name in keys, name)
        }
        assertEquals(setOf("smoked paprika"), matchKeys(own("  Smoked Paprika ", 1)))
        // An unknown preset answers only to its stored name.
        assertEquals(setOf("truffle oil"), matchKeys(Staple("x", "Truffle oil", "truffle_oil", 1)))
    }

    @Test
    fun aStapleIsOutWhileTheShoppingListHasOneOfItsNames() {
        val salt = preset("salt")
        assertFalse(isOut(salt, emptyList()))
        assertFalse(isOut(salt, listOf("Sea salt", "Bread")))
        assertTrue(isOut(salt, listOf(" SALT ")))
        assertTrue(isOut(salt, listOf("Соль")), "any language of a preset")
        assertTrue(isOut(own("Soy sauce", 1), listOf("soy sauce")))
    }

    @Test
    fun rowsNameAndMarkEachStapleAndOnlyTheOnesAtHomeAreSent() {
        val staples = listOf(own("Soy sauce", 4), preset("eggs"), preset("salt"))
        val rows = stapleRows(staples, listOf("Eier"), "de")
        assertEquals(listOf("Salz", "Eier", "Soy sauce"), rows.map { it.name })
        assertEquals(listOf(false, true, false), rows.map { it.out })
        assertEquals(listOf("Salz", "Soy sauce"), namesToSend(rows))
        val keys = currentStapleKeys(rows)
        assertTrue("salt" in keys && "соль" in keys && "soy sauce" in keys)
        assertFalse("eggs" in keys, "an out staple is to buy")
    }

    @Test
    fun promptStaplesDropsWhatTheRouteListsAlreadyAndRepeats() {
        val rows = stapleRows(listOf(preset("salt"), preset("eggs"), preset("olive_oil"), own("salt ", 1), own("Honey", 2)), listOf("Honey"), "en")
        assertEquals(listOf("Salt", "Olive oil"), promptStaples(rows, listed = listOf(" eggs", "Bread")), "Honey is out, Eggs listed, salt repeated")
        assertEquals(emptyList(), promptStaples(emptyList(), listOf("Bread")))
    }

    @Test
    fun promptStaplesDropsAStapleListedUnderAnyOfItsNames() {
        val rows = stapleRows(listOf(preset("eggs"), preset("salt"), own("Smoked paprika", 1)), emptyList(), "de")
        assertEquals(listOf("Salz", "Smoked paprika"), promptStaples(rows, listed = listOf("Eggs", "Bread")), "a German Eier never goes beside Eggs")
        assertEquals(listOf("Salz", "Eier", "Smoked paprika"), promptStaples(rows, listed = listOf("Bread")), "order and shown names kept")
    }

    @Test
    fun theFortyLimitCountsCodePointsAndNeverCutsOne() {
        assertEquals("a".repeat(40), cutStapleName("a".repeat(45)))
        val wide = "𠮷".repeat(45)
        assertEquals("𠮷".repeat(40), cutStapleName(wide), "40 code points, 80 UTF-16 units")
        assertEquals("x".repeat(39) + "𠮷", cutStapleName("x".repeat(39) + "𠮷𠮷"), "a pair is never split")
        assertEquals("Short", cutStapleName("Short"))
    }

    @Test
    fun undoPutsAStapleBackOnlyWhenNoneAnswersToItsNamesAndThereIsRoom() {
        val salt = preset("salt")
        assertTrue(mayRestore(salt, listOf(own("Honey", 1))))
        assertFalse(mayRestore(salt, listOf(preset("salt", rowId = "again"))), "ticked again meanwhile")
        assertFalse(mayRestore(salt, listOf(own("Sel", 1))), "by another language's name")
        val twenty = StaplePresets.all.drop(1).map { preset(it.id) } + (1..7).map { own("Own $it", it.toLong()) }
        assertEquals(MAX_STAPLES, twenty.size)
        assertFalse(mayRestore(salt, twenty), "no room")
        assertTrue(mayRestore(salt, twenty.drop(1)))
    }

    // --- Adding ----------------------------------------------------------------------------

    @Test
    fun addCheckSaysWhatAddingTheTypedTextDoes() {
        val staples = listOf(preset("salt"), own("Soy sauce", 1))
        assertEquals(StapleAddCheck.Empty, addCheck(staples, "   ", "en"))
        assertEquals(StapleAddCheck.Duplicate("Salt"), addCheck(staples, " sel ", "en"), "a ticked preset by any of its names")
        assertEquals(StapleAddCheck.Duplicate("Soy sauce"), addCheck(staples, "SOY SAUCE", "en"))
        assertEquals(StapleAddCheck.Preset("olive_oil"), addCheck(staples, "Olivenöl", "en"), "an unticked preset is ticked instead")
        assertEquals(StapleAddCheck.Own("Smoked paprika"), addCheck(staples, "  Smoked paprika ", "en"))
    }

    @Test
    fun addCheckIsFullAtTwentyButADuplicateStillSaysSo() {
        val full = StaplePresets.all.map { preset(it.id) } + (1..6).map { own("Own $it", it.toLong()) }
        assertEquals(MAX_STAPLES, full.size)
        assertEquals(StapleAddCheck.Duplicate("Own 1"), addCheck(full, "own 1", "en"))
        assertEquals(StapleAddCheck.Full, addCheck(full, "Honey", "en"))
        val nineteen = full.drop(1)
        assertEquals(StapleAddCheck.Preset("salt"), addCheck(nineteen, "salt", "en"))
    }
}
