package com.foodtracker.recipe

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StapleWireTest {

    private fun wire(name: String) = WireIngredient(name, "2026-11-20", 1)

    @Test
    fun namesAreCleanedAndRepeatsOrNamesAlreadySentLeftOut() {
        val ticked = listOf(wire("Bread"))
        val optional = listOf(wire("Eggs"))
        assertEquals(
            listOf("Salt", "Olive oil"),
            stapleWireNames(listOf("  Salt ", "bread", "EGGS", "Olive  oil", "salt", "   "), ticked, optional)
        )
    }

    @Test
    fun aNameRefusedBeforeIsNotSentAgain() {
        assertEquals(listOf("Salt"), stapleWireNames(listOf("Salt", "Pepper"), listOf(wire("Bread")), refused = setOf("pepper")))
    }

    @Test
    fun atMostTwentyNames() {
        val names = (1..25).map { "Staple $it" }
        val kept = stapleWireNames(names, emptyList())
        assertEquals(MAX_REQUEST_STAPLES, kept.size)
        assertEquals(names.take(20), kept)
    }

    @Test
    fun atMostFourHundredCodePointsANameThatDoesntFitIsSkippedAndAShorterOneStillKept() {
        // Forty code points each: "Tinned plum tomatoes in a basil sauce " and two letters.
        val forty = ('a'..'j').map { "Tinned plum tomatoes in a basil sauce " + it + "x" }
        assertTrue(forty.all { it.length == 40 })
        // Ten names of 40 code points fill 400 exactly, so the eleventh doesn't fit.
        assertEquals(forty, stapleWireNames(forty + "Salt", emptyList()))
        // 9 x 40 + 37 = 397: "Olive" would pass 400 and is skipped, "Oil" still fits.
        val thirtySeven = "Tinned plum tomatoes in basil sauce k"
        val names = forty.take(9) + thirtySeven + "Olive" + "Oil"
        val kept = stapleWireNames(names, emptyList())
        assertEquals(forty.take(9) + thirtySeven + "Oil", kept)
        assertEquals(MAX_REQUEST_STAPLE_NAME_CHARS, kept.sumOf { codePointsOf(it).size })
    }

    @Test
    fun theCapCountsCodePointsNotUtf16Units() {
        // 20 code points each (39 UTF-16 units): twenty of them fill 400 code points exactly.
        val names = (0 until 25).map { "𠮷".repeat(19) + ('a' + it) }
        val kept = stapleWireNames(names, emptyList())
        assertEquals(names.take(20), kept)
        assertEquals(MAX_REQUEST_STAPLE_NAME_CHARS, kept.sumOf { codePointsOf(it).size })
    }

    @Test
    fun theFlagIsOnlyAJsonTrue() {
        assertTrue(acceptsStaplesOf(JsonPrimitive(true)))
        for (value in listOf(null, JsonPrimitive(false), JsonPrimitive("true"), JsonPrimitive(1))) {
            assertFalse(acceptsStaplesOf(value), "$value")
        }
    }

    @Test
    fun aRequestWithoutStaplesIsTheOneFromBefore() {
        val request = GenerateRequest(
            requestId = "r",
            kind = WireKind.RECIPE,
            config = wireConfigFor(RecipeConfig(), "en", false)!!,
            ingredients = listOf(wire("Bread")),
            allergens = emptyList(),
            today = "2026-11-16"
        )
        val json = RecipeJson.encodeToString(GenerateRequest.serializer(), request)
        assertFalse("staples" in json, json)
        val withStaples = RecipeJson.encodeToString(GenerateRequest.serializer(), request.copy(staples = listOf("Salt")))
        assertTrue("\"staples\":[\"Salt\"]" in withStaples, withStaples)
        assertNull(RecipeJson.decodeFromString(GenerateRequest.serializer(), json).staples, "a kept request from before reads back")
        assertFalse(request.sameAskAs(request.copy(requestId = "s", staples = listOf("Salt"))), "staples are part of the ask")
        assertTrue(request.copy(staples = listOf("Salt")).sameAskAs(request.copy(requestId = "s", staples = listOf("Salt"))))
    }

    @Test
    fun stapleIndexGivesEachIngredientItsStaplesName() {
        val recipe = Recipe(
            title = "T",
            ingredients = listOf(
                RecipeIngredient("1 tsp", "salt", stapleIndex = 0, staple = true),
                RecipeIngredient("", "Olive oil", stapleIndex = 2),
                RecipeIngredient("", "Water", stapleIndex = 9, stapleName = "From the reply"),
                RecipeIngredient("2", "Bread", fromPantry = true, pantryIndex = 0)
            )
        )
        val named = recipe.withStapleNames(listOf("Salt", "Pepper", "Olive oil"))
        assertEquals(listOf("Salt", "Olive oil", null, null), named.ingredients.map { it.stapleName })
        val menu = Menu(listOf(MenuDay(kotlinx.datetime.LocalDate(2026, 11, 16), courses = listOf(MenuCourse(MenuCourseKind.MAIN, recipe)))))
        assertEquals(listOf("Salt", "Olive oil", null, null), menu.withStapleNames(listOf("Salt", "Pepper", "Olive oil")).days[0].courses[0].recipe.ingredients.map { it.stapleName })
    }

    @Test
    fun anIngredientsStapleIndexIsReadFromTheReply() {
        val reply = """{"title":"T","ingredients":[{"amount":"1 tsp","name":"salt","fromPantry":false,"stapleIndex":0,"staple":true}],"steps":["S"]}"""
        val ingredient = RecipeJson.decodeFromString(Recipe.serializer(), reply).ingredients.single()
        assertEquals(0, ingredient.stapleIndex)
        assertTrue(ingredient.staple)
        assertFalse(ingredient.fromPantry)
    }
}
