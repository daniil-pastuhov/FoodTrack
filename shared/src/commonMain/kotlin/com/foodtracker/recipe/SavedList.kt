package com.foodtracker.recipe

/**
 * Saved recipes' tabs and order. One rule on both platforms; iOS: `SavedList` in
 * `Recipe/RecipeWire.swift` (checked by `iosApp/RecipeConfigCheck/check.sh`).
 *
 * Tabs: [SavedTab.All] first (everything), then one per meal in [RECIPE_MEAL_CHOICES] order while a
 * saved recipe has that meal ([Recipe.meal]), then [SavedTab.Menus] while a menu is saved. A recipe
 * without a meal (older rows, a reply without one) is only under All. The row is shown only when
 * there is more than All ([showsSavedTabs]).
 *
 * Order, in every tab ([savedOrder]): new (unread) rows first, then favourites, then the rest; each
 * group newest first. An unread favourite is new.
 */
sealed interface SavedTab {
    /** Kept across rotation and process death ([savedTabOf]). */
    val key: String

    data object All : SavedTab {
        override val key: String get() = "all"
    }

    data class Meal(val meal: RecipeMealType) : SavedTab {
        override val key: String get() = meal.name.lowercase()
    }

    data object Menus : SavedTab {
        override val key: String get() = "menus"
    }
}

/** The tab [key] names, or [SavedTab.All] for one that names none. */
fun savedTabOf(key: String?): SavedTab = when (key) {
    SavedTab.Menus.key -> SavedTab.Menus
    else -> recipeMealOfWire(key)?.let { SavedTab.Meal(it) } ?: SavedTab.All
}

/** The tabs [saved] gives, All first. */
fun savedTabs(saved: List<SavedRecipe>): List<SavedTab> {
    val meals = saved.mapNotNullTo(HashSet()) { it.meal }
    return buildList {
        add(SavedTab.All)
        RECIPE_MEAL_CHOICES.filter { it in meals }.forEach { add(SavedTab.Meal(it)) }
        if (saved.any { it.kind == SavedRecipeKind.MENU }) add(SavedTab.Menus)
    }
}

/** Whether the tab row shows: only when there is a tab besides All. */
fun showsSavedTabs(tabs: List<SavedTab>): Boolean = tabs.size > 1

/** [selected] while it is still one of [tabs], else All (its last row went). */
fun savedTabOrAll(selected: SavedTab, tabs: List<SavedTab>): SavedTab = if (selected in tabs) selected else SavedTab.All

/** The rows of [saved] that [tab] holds, in [saved]'s order. */
fun savedRowsFor(saved: List<SavedRecipe>, tab: SavedTab): List<SavedRecipe> = when (tab) {
    SavedTab.All -> saved
    is SavedTab.Meal -> saved.filter { it.meal == tab.meal }
    SavedTab.Menus -> saved.filter { it.kind == SavedRecipeKind.MENU }
}

/** [saved] in the list's order: [unread] ids first, then favourites, then the rest, each newest
 *  first (ties by id, newest-looking last, as the database orders them). */
fun savedOrder(saved: List<SavedRecipe>, unread: Set<String>): List<SavedRecipe> =
    saved.sortedWith(
        compareBy<SavedRecipe> {
            when {
                it.id in unread -> 0
                it.favorite -> 1
                else -> 2
            }
        }.thenByDescending { it.createdAtEpochMillis }.thenByDescending { it.id }
    )
