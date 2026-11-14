package com.foodtracker.recipe

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The multi-choice config: toggling, normalising, reading old stored configs, the wire shapes
 *  and the legacy mapping; exact past-date warnings by item index; and the shopping-list dialog's
 *  rows. iOS ports these by hand (`RecipeChoices`, `RecipeWire`, `RecipeShopping`). */
class RecipeConfigChoicesTest {

    // --- Toggling ------------------------------------------------------------------------------

    @Test
    fun anyClearsTheOthersAndTheLastOneOffBringsItBack() {
        val none = emptySet<RecipeMealType>()
        val dinner = none.toggledChoice(RecipeMealType.DINNER)
        assertEquals(setOf(RecipeMealType.DINNER), dinner, "a meal turns Any off")
        val two = dinner.toggledChoice(RecipeMealType.LUNCH)
        assertEquals(setOf(RecipeMealType.DINNER, RecipeMealType.LUNCH), two)
        assertEquals(none, two.toggledChoice(null), "Any clears the others")
        assertEquals(none, dinner.toggledChoice(RecipeMealType.DINNER), "the last one off is Any again")
        assertEquals(setOf(RecipeDiet.VEGAN, RecipeDiet.VEGETARIAN), setOf(RecipeDiet.VEGAN).toggledChoice(RecipeDiet.VEGETARIAN), "redundant diets are allowed")
    }

    @Test
    fun theStrictestDietWinsInTheWording() {
        assertEquals(listOf(RecipeDiet.VEGETARIAN), promptDiets(setOf(RecipeDiet.PESCATARIAN, RecipeDiet.VEGETARIAN)))
        assertEquals(listOf(RecipeDiet.VEGAN, RecipeDiet.GLUTEN_FREE), promptDiets(RECIPE_DIET_CHOICES.toSet()))
        assertEquals(listOf(RecipeDiet.PESCATARIAN, RecipeDiet.DAIRY_FREE), promptDiets(setOf(RecipeDiet.DAIRY_FREE, RecipeDiet.PESCATARIAN)))
    }

    // --- Normalising and stored configs ------------------------------------------------------

    @Test
    fun boundsAtTheEndsAreNoneAndTheLowerNeverPassesTheUpper() {
        assertEquals(RecipeConfig(minMinutes = null, maxMinutes = null), RecipeConfig(minMinutes = 10, maxMinutes = 120).normalized())
        assertEquals(45, RecipeConfig(maxMinutes = 47).normalized().maxMinutes, "onto the 5-minute scale")
        assertEquals(10, RecipeConfig(maxMinutes = 3).normalized().maxMinutes)
        assertEquals(30, RecipeConfig(minMinutes = 50, maxMinutes = 30).normalized().minMinutes)
        assertNull(RecipeConfig(maxMinutes = 500).normalized().maxMinutes)
        assertEquals(emptySet(), RecipeConfig(mealTypes = setOf(RecipeMealType.ANY), diets = setOf(RecipeDiet.NONE)).normalized().let { it.mealTypes + it.diets })
    }

    @Test
    fun anOldStoredConfigReadsIntoTheNewModel() {
        val old = """{"difficulty":"medium","detail":"brief","servings":4,"maxMinutes":15,"mealType":"dinner","diet":"vegan","language":"nb"}"""
        assertEquals(
            RecipeConfig(RecipeDifficulty.MEDIUM, RecipeDetail.BRIEF, 4, minMinutes = null, maxMinutes = 15, mealTypes = setOf(RecipeMealType.DINNER), diets = setOf(RecipeDiet.VEGAN), language = "nb"),
            recipeConfigFromStoredJson(old)
        )
        val any = """{"maxMinutes":null,"mealType":"any","diet":"none"}"""
        assertEquals(RecipeConfig(maxMinutes = null), recipeConfigFromStoredJson(any))
        assertEquals(RecipeConfig(), recipeConfigFromStoredJson("not json"))
        assertEquals(RecipeConfig(servings = 3), recipeConfigFromStoredJson("""{"servings":3,"diets":["keto"]}"""), "an unknown diet drops the sets, not the rest")
    }

    @Test
    fun aNewConfigSurvivesARoundTrip() {
        val config = RecipeConfig(minMinutes = 20, maxMinutes = 45, mealTypes = setOf(RecipeMealType.LUNCH), diets = setOf(RecipeDiet.GLUTEN_FREE, RecipeDiet.VEGAN))
        assertEquals(config, recipeConfigFromStoredJson(RecipeJson.encodeToString(RecipeConfig.serializer(), config)))
    }

    // --- Wire ----------------------------------------------------------------------------------

    private fun legacy(config: RecipeConfig) = legacyWireConfig(config, "en")

    @Test
    fun theLegacyMappingTable() {
        // Time: the largest of 15, 30, 60 not above the upper bound; the lower bound is dropped.
        assertEquals(listOf(15, 15, 15, 30, 30, 60, 60, null), listOf(10, 15, 25, 30, 45, 60, 115, null).map { legacy(RecipeConfig(minMinutes = 20, maxMinutes = it))?.maxMinutes })
        // Meal: one as it is, several "any".
        assertEquals(RecipeMealType.SNACK, legacy(RecipeConfig(mealTypes = setOf(RecipeMealType.SNACK)))?.mealType)
        assertEquals(RecipeMealType.ANY, legacy(RecipeConfig(mealTypes = setOf(RecipeMealType.SNACK, RecipeMealType.DESSERT)))?.mealType)
        // Diet: the one legacy diet that forbids everything the selection does, or none at all.
        fun diet(vararg d: RecipeDiet) = legacy(RecipeConfig(diets = d.toSet()))?.diet
        assertEquals(RecipeDiet.NONE, diet())
        assertEquals(RecipeDiet.GLUTEN_FREE, diet(RecipeDiet.GLUTEN_FREE))
        assertEquals(RecipeDiet.VEGETARIAN, diet(RecipeDiet.VEGETARIAN, RecipeDiet.PESCATARIAN))
        assertEquals(RecipeDiet.VEGAN, diet(RecipeDiet.VEGAN, RecipeDiet.DAIRY_FREE))
        assertEquals(RecipeDiet.VEGAN, diet(RecipeDiet.VEGETARIAN, RecipeDiet.DAIRY_FREE))
        assertEquals(RecipeDiet.VEGAN, diet(RecipeDiet.PESCATARIAN, RecipeDiet.DAIRY_FREE))
        assertNull(diet(RecipeDiet.VEGETARIAN, RecipeDiet.GLUTEN_FREE))
        assertNull(diet(RecipeDiet.VEGAN, RecipeDiet.GLUTEN_FREE))
        assertNull(diet(RecipeDiet.DAIRY_FREE, RecipeDiet.GLUTEN_FREE))
    }

    @Test
    fun theLegacyShapeIsSentWheneverItSaysExactlyTheSame() {
        val simple = RecipeConfig(maxMinutes = 60, mealTypes = setOf(RecipeMealType.DINNER), diets = setOf(RecipeDiet.VEGAN))
        val wire = wireConfigFor(simple, "nb", takesMultiChoice = true)
        assertEquals(WireConfig(maxMinutes = 60, mealType = RecipeMealType.DINNER, diet = RecipeDiet.VEGAN, language = "nb"), wire)
        val json = RecipeJson.encodeToString(WireConfig.serializer(), wire!!)
        assertTrue(json.contains("\"mealTypes\":null") && json.contains("\"minMinutes\":null"), json)
        // A request kept by the version before (its config a RecipeConfig) reads back unchanged.
        val kept = """{"difficulty":"easy","detail":"standard","servings":2,"maxMinutes":30,"mealType":"any","diet":"none","language":"en"}"""
        assertEquals(WireConfig(maxMinutes = 30, mealType = RecipeMealType.ANY, diet = RecipeDiet.NONE, language = "en"), RecipeJson.decodeFromString(WireConfig.serializer(), kept))
    }

    @Test
    fun aRangeGoesInTheMultiChoiceShapeOnlyToAServerThatTakesIt() {
        val range = RecipeConfig(minMinutes = 20, maxMinutes = 45)
        assertEquals(WireConfig(minMinutes = 20, maxMinutes = 45, mealTypes = emptyList(), diets = emptyList(), language = "en"), wireConfigFor(range, "en", takesMultiChoice = true))
        assertEquals(WireConfig(maxMinutes = 30, mealType = RecipeMealType.ANY, diet = RecipeDiet.NONE, language = "en"), wireConfigFor(range, "en", takesMultiChoice = false))
    }

    // --- Past-date warnings by item index ------------------------------------------------------

    private val today = LocalDate(2026, 11, 14)
    private val ticked = listOf(WireIngredient("Bread", "2026-11-12", 1), WireIngredient("Milk", "2026-11-20", 1), WireIngredient("Cream", "2026-11-13", 1))
    private val optional = listOf(WireIngredient("Yoghurt", "2026-11-10", 1), WireIngredient("Eggs", null, 6))

    private fun dish(vararg ingredients: RecipeIngredient) = Recipe(title = "Dish", ingredients = ingredients.toList())

    @Test
    fun aDishNamesExactlyThePastDateItemsItUsesTickedOrOptional() {
        val recipe = dish(
            RecipeIngredient("1", "milk", true, pantryIndex = 1),
            RecipeIngredient("1", "cream", true, pantryIndex = 2),
            RecipeIngredient("1", "yoghurt", true, optionalIndex = 0),
            RecipeIngredient("1", "onion"),
            RecipeIngredient("1", "ghost", true, pantryIndex = 9)
        )
        assertTrue(recipe.hasItemIndices)
        assertEquals(listOf(PastDateItem("Cream", 1), PastDateItem("Yoghurt", 4)), recipe.pastDateByIndex(ticked, optional, today))
        assertEquals(false, dish(RecipeIngredient("1", "milk", true)).hasItemIndices, "an older server's answer")
    }

    @Test
    fun aMenuGetsEachCoursesOwnShareAndTheUnionOnTop() {
        fun day(date: Int, vararg courses: Recipe) =
            MenuDay(LocalDate(2026, 11, date), uses = listOf("Bread"), courses = courses.mapIndexed { i, r -> MenuCourse(MenuCourseKind.entries[i], r) })
        val menu = Menu(
            listOf(
                day(14, dish(RecipeIngredient("1", "bread", true, pantryIndex = 0)), dish(RecipeIngredient("1", "eggs", true, optionalIndex = 1))),
                day(15, dish(RecipeIngredient("1", "milk", true, pantryIndex = 1)), dish(RecipeIngredient("1", "sugar"))),
                day(16, dish(RecipeIngredient("1", "milk", true, pantryIndex = 1)), dish(RecipeIngredient("1", "yoghurt", true, optionalIndex = 0)))
            )
        )
        val exact = menu.withPastDateByIndex(ticked, optional, today)!!
        assertEquals(listOf(PastDateItem("Bread", 2)), exact.days[0].courses[0].recipe.pastDate)
        assertEquals(emptyList(), exact.days[0].courses[1].recipe.pastDate)
        assertEquals(listOf(PastDateItem("Yoghurt", 4)), exact.days[2].courses[1].recipe.pastDate)
        assertEquals(listOf(PastDateItem("Bread", 2), PastDateItem("Yoghurt", 4)), exact.pastDate, "Cream is used by no dish")
        val old = Menu(listOf(day(14, dish(RecipeIngredient("1", "bread", true)))))
        assertNull(old.withPastDateByIndex(ticked, optional, today), "without indices: the days' uses, as before")
    }

    // --- Shopping list -------------------------------------------------------------------------

    @Test
    fun namesAreTidiedForAList() {
        assertEquals("Red onion", tidyShoppingName("  red   onion, thinly sliced "))
        assertEquals("Garlic", tidyShoppingName("garlic, minced."))
        assertEquals("Сметана", tidyShoppingName("сметана"))
        assertEquals("Crème fraîche", tidyShoppingName("crème fraîche;"))
        assertNull(tidyShoppingName(" , chopped"))
    }

    @Test
    fun theDialogListsWhatIsNotFromThePantryOnceWithStaplesLastAndNoWater() {
        val menu = listOf(
            dish(
                RecipeIngredient("200 g", "pasta", true, pantryIndex = 0),
                RecipeIngredient("2 tbsp", "olive oil", staple = true),
                RecipeIngredient("1", "red onion, diced"),
                RecipeIngredient("1 l", "water", staple = true),
                RecipeIngredient("", "Salt", staple = true)
            ),
            dish(
                RecipeIngredient("1 tbsp", "Olive oil", staple = true),
                RecipeIngredient("1", "Lemon"),
                RecipeIngredient("2", "red onion")
            )
        )
        val rows = shoppingCandidates(menu, listedNames = listOf("  LEMON "))
        assertEquals(listOf("Red onion", "Lemon", "Olive oil", "Salt"), rows.map { it.name })
        assertEquals(listOf(false, true, false, false), rows.map { it.alreadyListed })
        assertEquals(setOf("red onion", "olive oil", "salt"), defaultShoppingSelection(rows), "everything ticked but what is on the list already")
    }

    @Test
    fun withoutTheStapleMarkEveryNonPantryIngredientIsListed() {
        val rows = shoppingCandidates(listOf(dish(RecipeIngredient("1 l", "Water"), RecipeIngredient("", "salt"))), emptyList())
        assertEquals(listOf("Water", "Salt"), rows.map { it.name })
        assertTrue(isPlainWater("Тёплая вода") && isPlainWater("Kaltes Wasser") && !isPlainWater("Watercress"))
    }
}
