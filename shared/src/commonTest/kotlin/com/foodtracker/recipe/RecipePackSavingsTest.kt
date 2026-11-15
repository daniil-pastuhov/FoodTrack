package com.foodtracker.recipe

import kotlin.test.Test
import kotlin.test.assertEquals

/** The Buy sheet's "−26%" badges, worked out from the store's own prices. */
class RecipePackSavingsTest {

    private fun packs(currency: String, p10: Long, p50: Long, p100: Long) = listOf(
        StorePack("recipes_10", 10, "?", p10, currency),
        StorePack("recipes_50", 50, "?", p50, currency),
        StorePack("recipes_100", 100, "?", p100, currency)
    )

    @Test
    fun todaysPricesSaveOnTheLargerPacks() {
        assertEquals(
            mapOf("recipes_50" to 26, "recipes_100" to 36),
            recipePackSavingPercents(packs("USD", 2_990_000, 10_990_000, 18_990_000))
        )
    }

    @Test
    fun aSavingIsFlooredNotRounded() {
        // 10.99 / 50 = 0.2198 against 0.399 a recipe: 44.9 %, shown as 44.
        assertEquals(
            mapOf("recipes_50" to 44, "recipes_100" to 52),
            recipePackSavingPercents(packs("USD", 3_990_000, 10_990_000, 18_990_000))
        )
    }

    @Test
    fun aDearerPackAndASavingUnderFivePercentGetNoBadge() {
        // 50 for 10.99 is dearer per recipe than 10 for 1.99; 100 for 18.99 saves 4.57 %.
        assertEquals(emptyMap(), recipePackSavingPercents(packs("USD", 1_990_000, 10_990_000, 18_990_000)))
    }

    @Test
    fun exactlyFivePercentShowsAndJustUnderDoesNot() {
        // 10 for 10.00: 100 for 95.00 is 5 % less a recipe, 100 for 95.01 is 4.99 %.
        val five = listOf(StorePack("a", 10, "?", 10_000_000, "EUR"), StorePack("b", 100, "?", 95_000_000, "EUR"))
        assertEquals(mapOf("b" to 5), recipePackSavingPercents(five))
        val under = listOf(StorePack("a", 10, "?", 10_000_000, "EUR"), StorePack("b", 100, "?", 95_010_000, "EUR"))
        assertEquals(emptyMap(), recipePackSavingPercents(under))
    }

    @Test
    fun theSamePricePerRecipeIsNoSaving() {
        assertEquals(emptyMap(), recipePackSavingPercents(packs("USD", 2_000_000, 10_000_000, 20_000_000)))
    }

    @Test
    fun regionalPricesUseTheirOwnNumbers() {
        // NOK 3.50 a recipe; 129 / 50 = 2.58 (26.3 %), 219 / 100 = 2.19 (37.4 %).
        assertEquals(
            mapOf("recipes_50" to 26, "recipes_100" to 37),
            recipePackSavingPercents(packs("NOK", 35_000_000, 129_000_000, 219_000_000))
        )
        // A currency without minor units: JPY 450 / 1,600 / 2,800; 32 a recipe is 28.9 %, 28 is 37.8 %.
        assertEquals(
            mapOf("recipes_50" to 28, "recipes_100" to 37),
            recipePackSavingPercents(packs("JPY", 450_000_000, 1_600_000_000, 2_800_000_000))
        )
    }

    @Test
    fun aDifferentCurrencyIsNeverCompared() {
        val mixed = listOf(
            StorePack("recipes_10", 10, "?", 2_990_000, "USD"),
            StorePack("recipes_50", 50, "?", 10_990_000, "EUR"),
            StorePack("recipes_100", 100, "?", 18_990_000, "GBP")
        )
        assertEquals(emptyMap(), recipePackSavingPercents(mixed))
    }

    @Test
    fun aMissingCurrencyOnTheBaseShowsNothing() {
        // The 50-pack is not promoted to base because the 10-pack lacks a currency.
        assertEquals(emptyMap(), recipePackSavingPercents(packs("", 2_990_000, 10_990_000, 18_990_000)))
        val baseOnly = listOf(
            StorePack("recipes_10", 10, "?", 2_990_000, ""),
            StorePack("recipes_50", 50, "?", 10_990_000, "USD"),
            StorePack("recipes_100", 100, "?", 18_990_000, "USD")
        )
        assertEquals(emptyMap(), recipePackSavingPercents(baseOnly))
    }

    @Test
    fun aPackWithoutAPriceIsLeftOutAndTheNextSmallestIsTheBase() {
        // No numeric price on the 10-pack: the 50-pack is the base, and 18.99 / 100 against
        // 10.99 / 50 saves 13.6 %.
        assertEquals(
            mapOf("recipes_100" to 13),
            recipePackSavingPercents(packs("USD", 0, 10_990_000, 18_990_000))
        )
        assertEquals(emptyMap(), recipePackSavingPercents(packs("USD", 2_990_000, 0, -1)))
    }

    @Test
    fun onePackOrNoneShowsNothing() {
        assertEquals(emptyMap(), recipePackSavingPercents(listOf(StorePack("recipes_10", 10, "?", 2_990_000, "USD"))))
        assertEquals(emptyMap(), recipePackSavingPercents(emptyList()))
    }

    @Test
    fun theOrderTheStoreReturnsDoesNotMatter() {
        val shuffled = packs("USD", 2_990_000, 10_990_000, 18_990_000).reversed()
        assertEquals(mapOf("recipes_50" to 26, "recipes_100" to 36), recipePackSavingPercents(shuffled))
    }

    @Test
    fun aPriceTooLargeToMultiplyIsSkippedNotWrapped() {
        val huge = listOf(
            StorePack("recipes_10", 10, "?", Long.MAX_VALUE / 2, "XXX"),
            StorePack("recipes_100", 100, "?", Long.MAX_VALUE / 3, "XXX")
        )
        assertEquals(emptyMap(), recipePackSavingPercents(huge))
    }

    @Test
    fun theBadgeReadsWithAMinusSignAndNoSpace() {
        assertEquals("−26%", recipePackSavingBadge(26))
    }
}
