package com.foodtracker.android.util

import com.foodtracker.model.CategoryPreference
import com.foodtracker.model.DefaultStorageLocations
import com.foodtracker.model.UNCATEGORIZED_ORIGIN
import com.foodtracker.recognition.FoodLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Built-in storage locations and categories are stored under English keys and only translated
 * on screen. These cover the places where a translated name comes back as input (the item
 * editor's category field, the Settings rename fields), which must turn it back into the key
 * rather than store the translation. Uses Russian names, where the two differ.
 */
class BuiltInNamesTest {

    private val russian = mapOf(
        FoodLabels.CATEGORY_DAIRY to "Молочное",
        FoodLabels.CATEGORY_PROTEIN to "Мясо и рыба",
        DefaultStorageLocations.FRIDGE to "Холодильник",
        DefaultStorageLocations.FREEZER to "Морозилка",
        DefaultStorageLocations.PANTRY to "Кладовая"
    )
    private val label: (String) -> String = { russian[it] ?: it }

    // Item editor: category field

    @Test
    fun testEditorShowsUntouchedBuiltInCategoryTranslated() {
        val prefs = listOf(CategoryPreference(FoodLabels.CATEGORY_DAIRY, FoodLabels.CATEGORY_DAIRY, 0.0))
        assertEquals("Молочное", categoryFieldText(FoodLabels.CATEGORY_DAIRY, prefs, label))
    }

    @Test
    fun testEditorShowsRenamedCategoryUnderItsNewName() {
        val prefs = listOf(CategoryPreference(FoodLabels.CATEGORY_DAIRY, "Молочка", 0.0))
        assertEquals("Молочка", categoryFieldText(FoodLabels.CATEGORY_DAIRY, prefs, label))
    }

    @Test
    fun testSavingUnchangedFieldKeepsTheItemsOwnCategoryEvenWhenMerged() {
        // "en:dairies" was merged into Dairy in Settings, so both show as "Молочное". An item in
        // "en:dairies" saved without touching the field must not move to "Dairy".
        val prefs = listOf(
            CategoryPreference(FoodLabels.CATEGORY_DAIRY, FoodLabels.CATEGORY_DAIRY, 0.0),
            CategoryPreference("en:dairies", FoodLabels.CATEGORY_DAIRY, 0.0)
        )
        assertEquals("en:dairies", categoryForFieldText("Молочное", "en:dairies", prefs, label))
    }

    @Test
    fun testTypingTranslatedBuiltInNameStoresTheEnglishKey() {
        assertEquals(FoodLabels.CATEGORY_DAIRY, categoryForFieldText("Молочное", null, emptyList(), label))
    }

    @Test
    fun testTypingAnotherCategorysNameStoresThatCategory() {
        val prefs = listOf(CategoryPreference("bathroom", "Ванная", 0.0))
        assertEquals("bathroom", categoryForFieldText("Ванная", FoodLabels.CATEGORY_DAIRY, prefs, label))
    }

    @Test
    fun testTypingTheOtherBucketNameMeansNoCategory() {
        val prefs = listOf(CategoryPreference(UNCATEGORIZED_ORIGIN, "Другое", Double.MAX_VALUE))
        assertEquals(UNCATEGORIZED_ORIGIN, categoryForFieldText("Другое", null, prefs, label))
    }

    @Test
    fun testTypingANewNameStoresItAsTyped() {
        assertEquals("Бытовая химия", categoryForFieldText("Бытовая химия", null, emptyList(), label))
    }

    // Settings: category rename

    @Test
    fun testRenamingToATranslatedNameStoresTheKeySoTheCategoriesMerge() {
        val names = listOf(FoodLabels.CATEGORY_DAIRY, "en:dairies")
        assertEquals(FoodLabels.CATEGORY_DAIRY, categoryNameForInput("Молочное", names, label))
    }

    @Test
    fun testRenamingToANewNameStoresItAsTyped() {
        assertEquals("Молочка", categoryNameForInput("Молочка", listOf(FoodLabels.CATEGORY_DAIRY), label))
    }

    @Test
    fun testConfirmingAnUntouchedBuiltInUnchangedLeavesItAlone() {
        val names = listOf(FoodLabels.CATEGORY_DAIRY)
        assertNull(categoryRename(FoodLabels.CATEGORY_DAIRY, "Молочное", names, label))
    }

    @Test
    fun testConfirmingUnchangedLeavesItAloneWhenAnotherCategoryShowsTheSameName() {
        // A user category literally named "Молочное" shows the same text as the built-in Dairy.
        val names = listOf("Молочное", FoodLabels.CATEGORY_DAIRY)
        assertNull(categoryRename(FoodLabels.CATEGORY_DAIRY, "Молочное", names, label))
        assertNull(categoryRename("Молочное", "Молочное", names, label))
    }

    @Test
    fun testRenamingACategoryToAnotherCategorysTranslatedNameMergesIntoIt() {
        val names = listOf(FoodLabels.CATEGORY_DAIRY, "en:dairies")
        assertEquals(FoodLabels.CATEGORY_DAIRY, categoryRename("en:dairies", "Молочное", names, label))
    }

    // Settings: storage locations

    @Test
    fun testConfirmingTheTranslatedLocationNameUnchangedKeepsTheKey() {
        assertNull(storageLocationRename(DefaultStorageLocations.FRIDGE, "Холодильник ", DefaultStorageLocations.ALL, label))
    }

    @Test
    fun testRenamingALocationReturnsTheNewName() {
        assertEquals("Погреб", storageLocationRename(DefaultStorageLocations.PANTRY, "Погреб", DefaultStorageLocations.ALL, label))
    }

    @Test
    fun testRenamingALocationToAnotherLocationsTranslatedNameIsRefused() {
        assertNull(storageLocationRename(DefaultStorageLocations.PANTRY, "Морозилка", DefaultStorageLocations.ALL, label))
    }

    @Test
    fun testAddingABuiltInLocationsTranslatedNameCountsAsExisting() {
        assertTrue(isExistingLocation(DefaultStorageLocations.ALL, "Холодильник", label))
        assertFalse(isExistingLocation(DefaultStorageLocations.ALL, "Балкон", label))
    }
}
