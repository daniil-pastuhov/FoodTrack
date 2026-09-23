package com.foodtracker.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProductNutritionTest {

    private val milk = ProductNutrition(
        nutriScore = "b",
        novaGroup = 1,
        allergens = listOf("milk-proteins", "lactose"),
        ingredients = "Whole milk"
    )

    @Test
    fun anAllergenTheUserCaresAboutIsMatched() {
        assertEquals(listOf("milk-proteins"), milk.matchedAllergens(setOf("milk-proteins")))
    }

    @Test
    fun matchingIsLooseInBothDirections() {
        // The two vocabularies don't line up: a user types "milk" while OFF tags
        // "milk-proteins", and a user types "tree nuts" where OFF tags "nuts".
        assertEquals(listOf("milk-proteins"), milk.matchedAllergens(setOf("milk-proteins")))
        assertTrue(milk.matchedAllergens(setOf("milk")).contains("milk-proteins"))

        val nuts = ProductNutrition(allergens = listOf("nuts"))
        assertEquals(listOf("nuts"), nuts.matchedAllergens(setOf("tree nuts")))
    }

    @Test
    fun matchingIgnoresCaseAndSurroundingSpace() {
        assertEquals(listOf("lactose"), milk.matchedAllergens(setOf("  LACTOSE  ")))
    }

    @Test
    fun anUnrelatedAllergenDoesNotMatch() {
        assertEquals(emptyList(), milk.matchedAllergens(setOf("gluten")))
    }

    @Test
    fun nothingIsMatchedWhenEitherSideIsEmpty() {
        assertEquals(emptyList(), milk.matchedAllergens(emptySet()))
        assertEquals(emptyList(), ProductNutrition().matchedAllergens(setOf("milk")))
        // A blank entry must not match everything by being a substring of every string.
        assertEquals(emptyList(), milk.matchedAllergens(setOf("   ")))
    }

    @Test
    fun everyMatchingAllergenIsReportedNotJustTheFirst() {
        val both = milk.matchedAllergens(setOf("milk", "lactose"))
        assertEquals(listOf("milk-proteins", "lactose"), both)
    }

    @Test
    fun emptinessIsDistinguishedFromHavingData() {
        assertTrue(ProductNutrition().isEmpty)
        assertFalse(milk.isEmpty)
        assertFalse(ProductNutrition(nutriScore = "a").isEmpty)
        assertFalse(ProductNutrition(allergens = listOf("gluten")).isEmpty)
        // A blank ingredients string is not data.
        assertTrue(ProductNutrition(ingredients = "   ").isEmpty)
    }
}
