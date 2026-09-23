package com.foodtracker.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Mapping Open Food Facts' nutrition fields onto the domain model. OFF is crowd-sourced, so the
 * cases that matter are the absent and the placeholder ones.
 */
class OpenFoodFactsNutritionTest {

    @Test
    fun aFullyPopulatedProductMapsEveryField() {
        val nutrition = OffProduct(
            nutriScoreGrade = "B",
            novaGroup = 3,
            allergensTags = listOf("en:milk", "en:gluten"),
            ingredientsText = "Wheat flour, milk"
        ).toNutrition()

        assertEquals("b", nutrition.nutriScore)
        assertEquals(3, nutrition.novaGroup)
        assertEquals(listOf("milk", "gluten"), nutrition.allergens)
        assertEquals("Wheat flour, milk", nutrition.ingredients)
    }

    @Test
    fun anUnratedProductHasNoGradeRatherThanAPlaceholderOne() {
        // OFF reports these for products nobody has rated; showing them as a badge would imply
        // a judgement OFF never made.
        assertNull(OffProduct(nutriScoreGrade = "not-applicable").toNutrition().nutriScore)
        assertNull(OffProduct(nutriScoreGrade = "unknown").toNutrition().nutriScore)
        assertNull(OffProduct(nutriScoreGrade = "").toNutrition().nutriScore)
    }

    @Test
    fun anOutOfRangeNovaGroupIsDropped() {
        assertNull(OffProduct(novaGroup = 0).toNutrition().novaGroup)
        assertNull(OffProduct(novaGroup = 9).toNutrition().novaGroup)
        assertEquals(4, OffProduct(novaGroup = 4).toNutrition().novaGroup)
    }

    @Test
    fun allergenTagsAreStrippedOfTheirLanguagePrefix() {
        // Same shape categories arrive in - see stripLanguagePrefix.
        assertEquals(listOf("milk"), OffProduct(allergensTags = listOf("fr:milk")).toNutrition().allergens)
    }

    @Test
    fun blankAndDuplicateAllergenTagsAreDropped() {
        val nutrition = OffProduct(
            allergensTags = listOf("en:milk", "en:milk", "  ", "")
        ).toNutrition()
        assertEquals(listOf("milk"), nutrition.allergens)
    }

    @Test
    fun aProductWithNoNutritionDataAtAllMapsToEmpty() {
        assertEquals(true, OffProduct().toNutrition().isEmpty)
    }

    @Test
    fun blankIngredientsAreTreatedAsAbsent() {
        assertNull(OffProduct(ingredientsText = "   ").toNutrition().ingredients)
    }
}
