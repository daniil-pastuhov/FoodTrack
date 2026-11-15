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
        // The same table as iosApp/RecipeConfigCheck/main.swift ("tidy names").
        val cases = listOf(
            "  red   onion, thinly sliced " to listOf("Red onion"),
            "garlic, minced." to listOf("Garlic"),
            "сметана" to listOf("Сметана"),
            "crème fraîche;" to listOf("Crème fraîche"),
            " , chopped" to emptyList(),
            "Salt, pepper, chilli flakes" to listOf("Salt", "Pepper", "Chilli flakes"),
            "Salt, pepper, olive oil" to listOf("Salt", "Pepper", "Olive oil"),
            "Tomatoes, peeled and diced small" to listOf("Tomatoes"),
            "Salt, pepper or chilli flakes" to listOf("Salt", "Pepper or chilli flakes"),
            "Butter or olive oil" to listOf("Butter or olive oil"),
            "Salt, to taste" to listOf("Salt"),
            "Salt and pepper to taste" to listOf("Salt and pepper"),
            "Garlic, 2 cloves, minced" to listOf("Garlic"),
            "Chicken thighs, boneless and skinless" to listOf("Chicken thighs"),
            "Cheese, such as cheddar" to listOf("Cheese"),
            "Parsley (optional)" to listOf("Parsley"),
            "Lemon, zest and juice" to listOf("Lemon"),
            "Соль, перец по вкусу" to listOf("Соль", "Перец"),
            "Лук, мелко нарезанный" to listOf("Лук"),
            "Морковь, натёртая на тёрке" to listOf("Морковь"),
            "Укроп, петрушка, зелёный лук" to listOf("Укроп", "Петрушка", "Зелёный лук"),
            "Чеснок, 2 зубчика" to listOf("Чеснок"),
            "Løk, finhakket" to listOf("Løk"),
            "Salt, pepper, olivenolje" to listOf("Salt", "Pepper", "Olivenolje"),
            "Hvitløk, presset" to listOf("Hvitløk"),
            "Gulrøtter, skrelt og i terninger" to listOf("Gulrøtter"),
            "Salt og pepper etter smak" to listOf("Salt og pepper"),
            "Rømme eller crème fraîche" to listOf("Rømme eller crème fraîche"),
            "Sal, pimienta, aceite de oliva" to listOf("Sal", "Pimienta", "Aceite de oliva"),
            "Cebolla, picada finamente" to listOf("Cebolla"),
            "Sal y pimienta al gusto" to listOf("Sal y pimienta"),
            "Ajo, 2 dientes" to listOf("Ajo"),
            "Nata o leche" to listOf("Nata o leche"),
            "Zwiebel, fein gehackt" to listOf("Zwiebel"),
            "Salz, Pfeffer, Olivenöl" to listOf("Salz", "Pfeffer", "Olivenöl"),
            "Salz und Pfeffer nach Geschmack" to listOf("Salz und Pfeffer"),
            "Karotten, geschält und gewürfelt" to listOf("Karotten"),
            "Oignon, émincé" to listOf("Oignon"),
            "Sel, poivre, huile d'olive" to listOf("Sel", "Poivre", "Huile d'olive"),
            "Sel et poivre au goût" to listOf("Sel et poivre"),
            "Ail, 2 gousses, haché" to listOf("Ail"),
            "Sel, petits pois" to listOf("Sel", "Petits pois"),
            "Lök, finhackad" to listOf("Lök"),
            "Salt, peppar, olivolja" to listOf("Salt", "Peppar", "Olivolja"),
            "Salt och peppar efter smak" to listOf("Salt och peppar"),
            "Morötter, skalade och tärnade" to listOf("Morötter"),
            "Løg, finthakket" to listOf("Løg"),
            "Salt, peber, olivenolie" to listOf("Salt", "Peber", "Olivenolie"),
            "Salt og peber efter smag" to listOf("Salt og peber"),
            "Gulerødder, skrællet og skåret i tern" to listOf("Gulerødder")
        )
        for ((raw, expected) in cases) assertEquals(expected, tidyShoppingNames(raw), raw)
    }

    @Test
    fun aListOfItemsInOneIngredientBecomesSeparateRows() {
        val rows = shoppingCandidates(listOf(dish(RecipeIngredient("", "Salt, pepper, olive oil", staple = true), RecipeIngredient("", "salt"))), listOf("Pepper"))
        assertEquals(listOf("Salt", "Pepper", "Olive oil"), rows.map { it.name })
        assertEquals(listOf(false, true, false), rows.map { it.alreadyListed })
    }

    @Test
    fun theButtonIsOfferedOnlyWhileSomethingCanBeAdded() {
        val recipe = listOf(dish(RecipeIngredient("200 g", "pasta", true, pantryIndex = 0), RecipeIngredient("1", "Lemon"), RecipeIngredient("", "salt", staple = true)))
        assertTrue(hasShoppingToAdd(shoppingCandidates(recipe, emptyList())))
        assertTrue(hasShoppingToAdd(shoppingCandidates(recipe, listOf("lemon"))))
        assertEquals(false, hasShoppingToAdd(shoppingCandidates(recipe, listOf("Lemon ", "SALT"))), "everything is on the list")
        assertEquals(false, hasShoppingToAdd(shoppingCandidates(listOf(dish(RecipeIngredient("1", "pasta", true, pantryIndex = 0))), emptyList())), "nothing to buy")
        assertEquals(false, hasShoppingToAdd(shoppingCandidates(listOf(dish(RecipeIngredient("1 l", "water", staple = true))), emptyList())), "only water")
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
        assertTrue(isPlainWater("Тёплая вода") && isPlainWater("Kaltes Wasser") && isPlainWater("Koldt vand") && !isPlainWater("Watercress"))
    }
}
