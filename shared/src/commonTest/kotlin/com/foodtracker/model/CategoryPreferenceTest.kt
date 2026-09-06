package com.foodtracker.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
