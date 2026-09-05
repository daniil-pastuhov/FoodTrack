package com.foodtracker.match

import com.foodtracker.model.ShoppingListEntry
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShoppingListMatcherTest {

    private val addedDate = LocalDate(2026, 9, 1)

    private fun entry(id: String, name: String, barcode: String? = null) =
        ShoppingListEntry(id = id, productName = name, barcode = barcode, addedDate = addedDate)

    @Test
    fun testSimilarityIdenticalAfterNormalization() {
        assertEquals(1.0, ShoppingListMatcher.similarity("Whole Milk", "  whole   milk  "))
    }

    @Test
    fun testSimilarityIgnoresPunctuationAndCase() {
        assertEquals(1.0, ShoppingListMatcher.similarity("Coca-Cola 1.5L", "coca cola 1 5l"))
    }

    @Test
    fun testSimilarityContainmentScoresHigh() {
        assertEquals(0.9, ShoppingListMatcher.similarity("Milk", "Organic Whole Milk"))
        assertEquals(0.9, ShoppingListMatcher.similarity("Organic Whole Milk", "Milk"))
    }

    @Test
    fun testSimilarityDisjointTokensScoresZero() {
        assertEquals(0.0, ShoppingListMatcher.similarity("Bread", "Orange Juice"))
    }

    @Test
    fun testSimilarityPartialOverlapComputesJaccard() {
        // {whole, milk} vs {2, milk} -> intersection 1, union 3
        val score = ShoppingListMatcher.similarity("Whole Milk", "2% Milk")
        assertEquals(1.0 / 3.0, score, 0.0001)
    }

    @Test
    fun testSimilarityBlankInputScoresZero() {
        assertEquals(0.0, ShoppingListMatcher.similarity("", "Milk"))
        assertEquals(0.0, ShoppingListMatcher.similarity("   ", "Milk"))
    }

    @Test
    fun testMatchPrioritizesBarcodeOverNameSimilarity() {
        val list = listOf(
            entry("a", "Completely Unrelated Item", barcode = "123"),
            entry("b", "Whole Milk")
        )
        val result = ShoppingListMatcher.match(scannedName = "Whole Milk", scannedBarcode = "123", shoppingList = list)
        assertTrue(result is ShoppingListMatchResult.ExactBarcodeMatch)
        assertEquals("a", (result as ShoppingListMatchResult.ExactBarcodeMatch).entry.id)
    }

    @Test
    fun testMatchSingleFuzzySuggestion() {
        val list = listOf(entry("a", "Whole Milk"), entry("b", "Bread"))
        val result = ShoppingListMatcher.match(scannedName = "Organic Whole Milk 1L", scannedBarcode = null, shoppingList = list)
        assertTrue(result is ShoppingListMatchResult.Suggestions)
        val candidates = (result as ShoppingListMatchResult.Suggestions).candidates
        assertEquals(listOf("a"), candidates.map { it.id })
    }

    @Test
    fun testMatchCapsAtThreeSortedDescending() {
        val list = listOf(
            entry("a", "Milk Chocolate"),
            entry("b", "Almond Milk"),
            entry("c", "Whole Milk"),
            entry("d", "Milk"),
            entry("e", "Orange Juice")
        )
        val result = ShoppingListMatcher.match(scannedName = "Milk", scannedBarcode = null, shoppingList = list)
        assertTrue(result is ShoppingListMatchResult.Suggestions)
        val candidates = (result as ShoppingListMatchResult.Suggestions).candidates
        assertEquals(3, candidates.size)
        assertEquals("d", candidates.first().id) // exact normalized match scores 1.0, ranks first
        assertTrue(candidates.none { it.id == "e" })
    }

    @Test
    fun testMatchNoMatchWhenNothingAboveThreshold() {
        val list = listOf(entry("a", "Bread"), entry("b", "Orange Juice"))
        val result = ShoppingListMatcher.match(scannedName = "Milk", scannedBarcode = null, shoppingList = list)
        assertEquals(ShoppingListMatchResult.NoMatch, result)
    }

    @Test
    fun testMatchNoMatchWhenNameBlankAndNoBarcode() {
        val list = listOf(entry("a", "Milk"))
        val result = ShoppingListMatcher.match(scannedName = null, scannedBarcode = null, shoppingList = list)
        assertEquals(ShoppingListMatchResult.NoMatch, result)
    }
}
