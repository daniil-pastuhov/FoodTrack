package com.foodtracker.recipe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The same cases as iOS `SavedSelection` in `iosApp/RecipeConfigCheck/main.swift`. */
class SavedSelectionTest {
    private val ids = listOf("c", "b", "a")

    @Test
    fun aTapTogglesOneRow() {
        assertEquals(setOf("a"), SavedSelection.toggled(emptySet(), "a"))
        assertEquals(setOf("a", "b"), SavedSelection.toggled(setOf("a"), "b"))
        assertEquals(setOf("b"), SavedSelection.toggled(setOf("a", "b"), "a"))
    }

    @Test
    fun selectAllUntilEverythingIsSelectedThenDeselectAll() {
        assertFalse(SavedSelection.allSelected(emptySet(), ids))
        assertFalse(SavedSelection.allSelected(setOf("a", "b"), ids))
        assertTrue(SavedSelection.allSelected(setOf("a", "b", "c"), ids))
        assertTrue(SavedSelection.allSelected(setOf("a", "b", "c", "gone"), ids), "a stale id doesn't count against")
        assertFalse(SavedSelection.allSelected(emptySet(), emptyList()), "an empty list is never all selected")

        assertEquals(setOf("a", "b", "c"), SavedSelection.toggledAll(setOf("b"), ids))
        assertEquals(emptySet(), SavedSelection.toggledAll(setOf("a", "b", "c"), ids))
    }

    @Test
    fun idsThatLeaveTheListLeaveTheSelection() {
        assertEquals(setOf("a"), SavedSelection.pruned(setOf("a", "gone"), ids))
        assertEquals(emptySet(), SavedSelection.pruned(setOf("a"), emptyList()))
    }

    @Test
    fun deleteRemovesTheSelectedRowsStillListedInListOrder() {
        val saved = ids.mapIndexed { i, id -> SavedRecipe.of(id, Recipe(title = id, steps = listOf("s")), 10L - i) }
        assertEquals(listOf("c", "a"), SavedSelection.toDelete(saved, setOf("a", "c", "gone")).map { it.id })
        assertTrue(SavedSelection.toDelete(saved, emptySet()).isEmpty())
    }
}
