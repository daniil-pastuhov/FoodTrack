package com.foodtracker.recipe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SavedListTest {

    private fun recipe(id: String, at: Long, meal: RecipeMealType? = null, favorite: Boolean = false) =
        SavedRecipe.of(id, Recipe(title = id, steps = listOf("a"), meal = meal), at).copy(favorite = favorite)

    private fun menu(id: String, at: Long, favorite: Boolean = false) =
        SavedRecipe.of(id, "Menu", Menu(emptyList()), at).copy(favorite = favorite)

    @Test
    fun allFirstThenMealsInTheFormsOrderThenMenus() {
        val saved = listOf(
            recipe("a", 1, RecipeMealType.DESSERT),
            menu("m", 2),
            recipe("b", 3, RecipeMealType.BREAKFAST),
            recipe("c", 4),
            recipe("d", 5, RecipeMealType.DESSERT)
        )
        assertEquals(
            listOf(SavedTab.All, SavedTab.Meal(RecipeMealType.BREAKFAST), SavedTab.Meal(RecipeMealType.DESSERT), SavedTab.Menus),
            savedTabs(saved)
        )
        assertTrue(showsSavedTabs(savedTabs(saved)))
    }

    @Test
    fun onlyAllHidesTheRow() {
        assertEquals(listOf(SavedTab.All), savedTabs(emptyList()))
        assertFalse(showsSavedTabs(savedTabs(listOf(recipe("a", 1), recipe("b", 2)))), "old recipes without a meal: no row")
        assertTrue(showsSavedTabs(savedTabs(listOf(menu("m", 1)))), "a menu alone still has its tab")
    }

    @Test
    fun aMenusDishesNeverMakeAMealTab() {
        val dish = Recipe(title = "Stew", meal = RecipeMealType.DINNER)
        val saved = SavedRecipe.of("m", "Menu", Menu(listOf(MenuDay(kotlinx.datetime.LocalDate(2026, 11, 20), courses = listOf(MenuCourse(MenuCourseKind.MAIN, dish))))), 1)
        assertNull(saved.meal)
        assertEquals(listOf(SavedTab.All, SavedTab.Menus), savedTabs(listOf(saved)))
    }

    @Test
    fun eachTabHoldsItsRows() {
        val saved = listOf(recipe("a", 3, RecipeMealType.LUNCH), menu("m", 2), recipe("old", 1))
        assertEquals(listOf("a", "m", "old"), savedRowsFor(saved, SavedTab.All).map { it.id })
        assertEquals(listOf("a"), savedRowsFor(saved, SavedTab.Meal(RecipeMealType.LUNCH)).map { it.id })
        assertEquals(emptyList(), savedRowsFor(saved, SavedTab.Meal(RecipeMealType.SNACK)))
        assertEquals(listOf("m"), savedRowsFor(saved, SavedTab.Menus).map { it.id })
    }

    @Test
    fun aTabThatWentFallsBackToAll() {
        val tabs = savedTabs(listOf(recipe("a", 1, RecipeMealType.LUNCH)))
        assertEquals(SavedTab.Meal(RecipeMealType.LUNCH), savedTabOrAll(SavedTab.Meal(RecipeMealType.LUNCH), tabs))
        assertEquals(SavedTab.All, savedTabOrAll(SavedTab.Menus, tabs))
        assertEquals(SavedTab.All, savedTabOrAll(SavedTab.Meal(RecipeMealType.DINNER), tabs))
    }

    @Test
    fun tabsAreKeptByKey() {
        (listOf(SavedTab.All, SavedTab.Menus) + RECIPE_MEAL_CHOICES.map { SavedTab.Meal(it) }).forEach {
            assertEquals(it, savedTabOf(it.key))
        }
        assertEquals(SavedTab.All, savedTabOf(null))
        assertEquals(SavedTab.All, savedTabOf("any"))
        assertEquals(SavedTab.All, savedTabOf("brunch"))
    }

    @Test
    fun newFirstThenFavouritesThenTheRestEachNewestFirst() {
        val saved = listOf(
            recipe("r1", 1),
            recipe("f1", 2, favorite = true),
            recipe("u1", 3),
            recipe("r2", 4),
            menu("f2", 5, favorite = true),
            recipe("uf", 6, favorite = true),
            recipe("r3", 7)
        )
        assertEquals(
            listOf("uf", "u1", "f2", "f1", "r3", "r2", "r1"),
            savedOrder(saved, unread = setOf("u1", "uf", "gone")).map { it.id }
        )
        assertEquals(listOf("r3", "r2", "r1"), savedOrder(saved.filter { it.id.startsWith("r") }, emptySet()).map { it.id })
    }

    @Test
    fun sameTimeIsOrderedByIdAsTheDatabaseDoes() {
        assertEquals(listOf("b", "a"), savedOrder(listOf(recipe("a", 1), recipe("b", 1)), emptySet()).map { it.id })
    }

    @Test
    fun aMealIsReadLeniently() {
        fun read(json: String) = RecipeJson.decodeFromString(Recipe.serializer(), json).meal
        assertEquals(RecipeMealType.DINNER, read("""{"title":"x","meal":"dinner"}"""))
        assertNull(read("""{"title":"x"}"""), "rows saved before it")
        assertNull(read("""{"title":"x","meal":null}"""))
        assertNull(read("""{"title":"x","meal":"any"}"""))
        assertNull(read("""{"title":"x","meal":"brunch"}"""))
        assertNull(read("""{"title":"x","meal":3}"""))
        assertNull(read("""{"title":"x","meal":{"a":1}}"""))
        val back = RecipeJson.decodeFromString(Recipe.serializer(), RecipeJson.encodeToString(Recipe.serializer(), Recipe(title = "x", meal = RecipeMealType.SNACK)))
        assertEquals(RecipeMealType.SNACK, back.meal)
        assertNull(RecipeJson.decodeFromString(Recipe.serializer(), RecipeJson.encodeToString(Recipe.serializer(), Recipe(title = "x"))).meal)
    }
}
