package com.foodtracker.recipe

import kotlin.test.Test
import kotlin.test.assertEquals

/** The staples' part of "Add to shopping list" (C.6): one table on both platforms. */
class RecipeShoppingStaplesTest {

    private val staples = listOf("Salt", "Olive oil", "Eggs")

    @Test
    fun anIngredientIsAStapleOnlyWhenEveryEntryOfItsNameIsOne() {
        val table = listOf(
            "Salt" to "Salt",
            "salt" to "Salt",
            "Salt, to taste" to "Salt",
            "Salt and pepper" to null,
            "Salt, pepper" to null,
            "Olive oil" to "Olive oil",
            "Extra virgin olive oil" to null,
            "Eggs, beaten" to "Eggs",
            "Water" to null
        )
        for ((input, expected) in table) assertEquals(expected, stapleNameOf(input, staples), input)
    }

    @Test
    fun theStaplesAtHomeAreLeftOutOfTheShoppingList() {
        val recipe = Recipe(
            title = "T",
            ingredients = listOf(
                RecipeIngredient("", "Salt"),
                RecipeIngredient("", "Salt, pepper"),
                RecipeIngredient("", "Olive oil", stapleName = "Olive oil"),
                RecipeIngredient("", "Onion"),
                RecipeIngredient("", "Sea salt", stapleName = "Salt")
            )
        )
        val candidates = shoppingCandidates(listOf(recipe), emptyList(), stapleKeys = setOf("salt", "olive oil"))
        assertEquals(listOf("Pepper", "Onion"), candidates.map { it.name })
    }

    @Test
    fun anOutStapleIsStillOfferedAsAlreadyOnTheList() {
        // Eggs is on the shopping list, so it is out and not in the keys.
        val recipe = Recipe(title = "T", ingredients = listOf(RecipeIngredient("2", "Eggs", stapleName = "Eggs")))
        val candidates = shoppingCandidates(listOf(recipe), listOf("Eggs"), stapleKeys = setOf("salt"))
        assertEquals(listOf("Eggs" to true), candidates.map { it.name to it.alreadyListed })
    }
}
