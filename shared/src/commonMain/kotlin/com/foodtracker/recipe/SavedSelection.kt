package com.foodtracker.recipe

/**
 * Saved recipes' multi-select: which saved recipes and menus are selected for one Delete. One rule
 * on both platforms; iOS: `SavedSelection` in `Recipe/RecipeWire.swift` (checked by
 * `iosApp/RecipeConfigCheck/check.sh`). The selection is only ever ids still listed: one deleted
 * or gone otherwise drops out ([pruned]).
 */
object SavedSelection {
    /** A tap on a row while selecting. */
    fun toggled(selected: Set<String>, id: String): Set<String> =
        if (id in selected) selected - id else selected + id

    /** Every listed id is selected (and there is at least one): the bar offers Deselect all. */
    fun allSelected(selected: Set<String>, savedIds: List<String>): Boolean =
        savedIds.isNotEmpty() && savedIds.all { it in selected }

    /** Select all, or Deselect all once everything is selected. */
    fun toggledAll(selected: Set<String>, savedIds: List<String>): Set<String> =
        if (allSelected(selected, savedIds)) emptySet() else savedIds.toSet()

    /** The selection without ids no longer listed. */
    fun pruned(selected: Set<String>, savedIds: List<String>): Set<String> {
        if (selected.isEmpty()) return selected
        val listed = savedIds.toSet()
        return selected.filterTo(LinkedHashSet()) { it in listed }
    }

    /** What one Delete removes: the listed rows that are selected, in list order. */
    fun toDelete(saved: List<SavedRecipe>, selected: Set<String>): List<SavedRecipe> =
        saved.filter { it.id in selected }
}
