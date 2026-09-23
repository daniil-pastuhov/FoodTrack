package com.foodtracker.recognition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FoodLabelsTest {

    private fun label(text: String, confidence: Float = 0.9f) = RecognizedLabel(text, confidence)

    @Test
    fun aKnownFoodLabelBecomesASuggestionWithItsCategory() {
        val suggestion = FoodLabels.suggestionsFrom(listOf(label("Banana"))).single()
        assertEquals("banana", suggestion.key)
        assertEquals(FoodLabels.CATEGORY_FRUIT, suggestion.category)
    }

    @Test
    fun labelsAreMatchedRegardlessOfCase() {
        assertEquals(
            FoodLabels.suggestionsFrom(listOf(label("banana"))),
            FoodLabels.suggestionsFrom(listOf(label("  BANANA  ")))
        )
    }

    @Test
    fun nonFoodLabelsAreDroppedRatherThanOffered() {
        // The classifier's vocabulary is general-purpose; without the allow-list it would
        // happily suggest "Person" as a product name.
        val suggestions = FoodLabels.suggestionsFrom(
            listOf(label("Person"), label("Table"), label("Plastic"), label("Smile"))
        )
        assertEquals(emptyList(), suggestions)
    }

    @Test
    fun lowConfidenceLabelsAreDropped() {
        val justUnder = FoodLabels.MIN_CONFIDENCE - 0.01f
        assertEquals(emptyList(), FoodLabels.suggestionsFrom(listOf(label("Banana", justUnder))))
        assertEquals(1, FoodLabels.suggestionsFrom(listOf(label("Banana", FoodLabels.MIN_CONFIDENCE))).size)
    }

    @Test
    fun genericBucketsRankBelowSpecificProductsEvenWhenMoreConfident() {
        // The classifier is nearly always very sure something is "Food"; letting that outrank
        // "Banana" would bury the only suggestion with any use in it.
        val suggestions = FoodLabels.suggestionsFrom(
            listOf(label("Food", 0.99f), label("Fruit", 0.95f), label("Banana", 0.72f))
        )
        assertEquals("banana", suggestions.first().key)
        assertTrue(suggestions.map { it.key }.containsAll(listOf("food", "fruit")))
    }

    @Test
    fun labelsSharingAKeyCollapseToTheMostConfidentOne() {
        // "Citrus" and "Orange" are both an orange to someone unpacking a bag.
        val suggestions = FoodLabels.suggestionsFrom(listOf(label("Citrus", 0.7f), label("Orange", 0.9f)))
        assertEquals(1, suggestions.size)
        assertEquals("orange", suggestions.single().key)
        assertEquals(0.9f, suggestions.single().confidence)
    }

    @Test
    fun specificSuggestionsAreOrderedByConfidence() {
        val suggestions = FoodLabels.suggestionsFrom(
            listOf(label("Bread", 0.7f), label("Cheese", 0.95f), label("Apple", 0.8f))
        )
        assertEquals(listOf("cheese", "apple", "bread"), suggestions.map { it.key })
    }

    @Test
    fun theSuggestionListIsCapped() {
        val many = listOf("Apple", "Banana", "Bread", "Cheese", "Carrot", "Milk", "Egg")
            .mapIndexed { index, text -> label(text, 0.9f - index * 0.01f) }
        assertEquals(FoodLabels.MAX_SUGGESTIONS, FoodLabels.suggestionsFrom(many).size)
    }

    @Test
    fun everyMappedLabelCarriesOneOfTheKnownCategories() {
        val known = setOf(
            FoodLabels.CATEGORY_FRUIT,
            FoodLabels.CATEGORY_VEGETABLES,
            FoodLabels.CATEGORY_BAKERY,
            FoodLabels.CATEGORY_DAIRY,
            FoodLabels.CATEGORY_PROTEIN,
            FoodLabels.CATEGORY_DRINKS,
            FoodLabels.CATEGORY_SWEETS,
            FoodLabels.CATEGORY_PANTRY
        )
        // Every label in the table has to resolve to a category the settings screen can hang a
        // default shelf life off, or the suggestion carries no expiry benefit.
        val sampled = listOf("Apple", "Tomato", "Bread", "Milk", "Fish", "Juice", "Candy", "Rice")
        sampled.forEach { text ->
            val suggestion = FoodLabels.suggestionsFrom(listOf(label(text))).single()
            assertTrue(suggestion.category in known, "${suggestion.key} -> ${suggestion.category}")
        }
    }

    @Test
    fun anEmptyClassificationYieldsNoSuggestions() {
        assertEquals(emptyList(), FoodLabels.suggestionsFrom(emptyList()))
    }
}
