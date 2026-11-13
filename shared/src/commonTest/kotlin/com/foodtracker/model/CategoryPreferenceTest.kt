package com.foodtracker.model

import com.foodtracker.viewmodel.PantryDerivation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CategoryPreferenceTest {

    @Test
    fun testResolveCategoryDisplayNameReturnsNullForNullOrigin() {
        assertNull(resolveCategoryDisplayName(null, emptyList()))
    }

    @Test
    fun testResolveCategoryDisplayNameFallsBackToOriginWhenUnknown() {
        assertEquals("en:dairy", resolveCategoryDisplayName("en:dairy", emptyList()))
    }

    @Test
    fun testResolveCategoryDisplayNameUsesRenamedDisplayName() {
        val prefs = listOf(CategoryPreference(originName = "en:dairy", displayName = "Dairy"))
        assertEquals("Dairy", resolveCategoryDisplayName("en:dairy", prefs))
    }

    @Test
    fun testResolveCategoryDisplayNameHidesDisabledCategory() {
        val prefs = listOf(CategoryPreference(originName = "en:dairy", displayName = "Dairy", enabled = false))
        assertNull(resolveCategoryDisplayName("en:dairy", prefs))
    }

    @Test
    fun testCategoryPreferenceSortOrderPutsUnknownAndDisabledLast() {
        val prefs = listOf(
            CategoryPreference(originName = "known", displayName = "Known", sortOrder = 2.0),
            CategoryPreference(originName = "disabled", displayName = "Disabled", sortOrder = 0.5, enabled = false)
        )
        assertEquals(2.0, categoryPreferenceSortOrder("known", prefs))
        assertEquals(Double.MAX_VALUE, categoryPreferenceSortOrder("disabled", prefs))
        assertEquals(Double.MAX_VALUE, categoryPreferenceSortOrder("unregistered", prefs))
        assertEquals(Double.MAX_VALUE, categoryPreferenceSortOrder(null, prefs))
    }

    @Test
    fun testSuggestableDisplayNamesSkipsDisabledCategories() {
        val prefs = listOf(
            CategoryPreference(originName = "en:dairy", displayName = "Dairy"),
            CategoryPreference(originName = "dairy", displayName = "Dairy"),
            CategoryPreference(originName = "en:snacks", displayName = "Snacks", enabled = false)
        )
        assertEquals(listOf("Dairy"), prefs.suggestableDisplayNames())
    }

    @Test
    fun testCategoryRowsPutDisabledCategoriesLast() {
        // Out of sortOrder on purpose, so each part has to be sorted by it too.
        val prefs = listOf(
            CategoryPreference(originName = "d", displayName = "D", sortOrder = 3.0),
            CategoryPreference(originName = "c", displayName = "C", sortOrder = 2.0, enabled = false),
            CategoryPreference(originName = "b", displayName = "B", sortOrder = 1.0),
            CategoryPreference(originName = "a", displayName = "A", sortOrder = 0.0, enabled = false)
        )
        assertEquals(
            listOf("B", "D", "A", "C"),
            PantryDerivation.buildCategoryRows(prefs).map { it.preference.displayName }
        )
    }

    @Test
    fun testCategoryRowsMoveOnlyWithinEnabledOrDisabledPart() {
        val rows = PantryDerivation.buildCategoryRows(
            listOf(
                CategoryPreference(originName = "a", displayName = "A", sortOrder = 0.0),
                CategoryPreference(originName = "b", displayName = "B", sortOrder = 1.0),
                CategoryPreference(originName = "c", displayName = "C", sortOrder = 2.0, enabled = false),
                CategoryPreference(originName = "d", displayName = "D", sortOrder = 3.0, enabled = false)
            )
        )
        assertTrue(PantryDerivation.canMoveCategoryRow(rows, 0, 1))
        assertTrue(PantryDerivation.canMoveCategoryRow(rows, 3, 2))
        assertFalse(PantryDerivation.canMoveCategoryRow(rows, 1, 2))
        assertFalse(PantryDerivation.canMoveCategoryRow(rows, 2, 1))
        assertFalse(PantryDerivation.canMoveCategoryRow(rows, 0, -1))
        assertFalse(PantryDerivation.canMoveCategoryRow(rows, 3, 4))
        assertFalse(PantryDerivation.canMoveCategoryRow(rows, -1, 0))
        assertFalse(PantryDerivation.canMoveCategoryRow(rows, 4, 3))
    }
}
