package com.foodtracker.recipe

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnDeviceRecipePromptTest {

    private val config = RecipeConfig()

    @Test
    fun promptListsItemsSoonestFirstAndAsksForJsonInTheChosenLanguage() {
        val prompt = buildOnDeviceRecipePrompt(
            items = listOf(RecipePromptItem("Salmon", 3), RecipePromptItem("Bread", -1), RecipePromptItem("Spinach", 0)),
            config = config.copy(diet = RecipeDiet.PESCATARIAN, maxMinutes = 15),
            languageCode = "nb",
            allergens = listOf("peanuts")
        )

        val order = listOf("Bread", "Spinach", "Salmon").map { prompt.indexOf("- $it") }
        assertEquals(order.sorted(), order, prompt)
        assertTrue(prompt.contains("1 day past its date"), prompt)
        assertTrue(prompt.contains("Diet: pescatarian"), prompt)
        assertTrue(prompt.contains("Total time: 15 minutes or less"), prompt)
        assertTrue(prompt.contains("Never use anything containing: peanuts"), prompt)
        assertTrue(prompt.contains("Norwegian Bokmål"), prompt)
        assertTrue(prompt.contains("Reply with JSON only"), prompt)
    }

    @Test
    fun promptKeepsToTheModelsLimits() {
        val items = (0 until 50).map { RecipePromptItem("x".repeat(100) + it, it) }
        val prompt = buildOnDeviceRecipePrompt(items, config, "en", emptyList())

        assertEquals(MAX_RECIPE_INGREDIENTS, prompt.lines().count { it.startsWith("- xxx") })
        assertFalse(prompt.contains("x".repeat(MAX_RECIPE_INGREDIENT_NAME + 1)), "names are cut")
    }

    @Test
    fun namesAreTrimmedDatesReadRightTheSoonestAreKeptAndTheMealIsSaid() {
        val items = (0 until 40).map { RecipePromptItem("item $it", 40 - it) } +
            RecipePromptItem("  Old bread  ", -3) + RecipePromptItem("x".repeat(MAX_RECIPE_INGREDIENT_NAME) + "  ", 0)
        val prompt = buildOnDeviceRecipePrompt(items, config.copy(mealType = RecipeMealType.BREAKFAST), "en", emptyList())

        assertTrue(prompt.contains("- Old bread (3 days past its date)"), prompt)
        assertTrue(prompt.contains("- " + "x".repeat(MAX_RECIPE_INGREDIENT_NAME) + " ("), "trimmed before the cut")
        assertTrue(prompt.contains("- item 39 ("), "the soonest are kept when there are more than fit")
        assertFalse(prompt.contains("- item 0 ("), "the latest are the ones left out")
        assertTrue(prompt.contains("Meal: breakfast"), prompt)
    }

    @Test
    fun namesInAnyScriptPassThroughAndAllergensAreNeverCut() {
        val longAllergen = "Schalenfrüchte und Erdnüsse sowie Sesamsamen"
        val emojiAtTheCut = "x".repeat(MAX_RECIPE_INGREDIENT_NAME - 1) + "🍎"
        val prompt = buildOnDeviceRecipePrompt(
            items = listOf(RecipePromptItem("Γιαούρτι στραγγιστό", 1), RecipePromptItem("חלב 3%", 2), RecipePromptItem("牛奶 1L", 3), RecipePromptItem(emojiAtTheCut, 4)),
            config = config,
            languageCode = "en",
            allergens = listOf("φιστίκια", longAllergen)
        )

        for (name in listOf("Γιαούρτι στραγγιστό", "חלב 3%", "牛奶 1L")) assertTrue(prompt.contains("- $name ("), name)
        assertTrue(prompt.contains("- " + "x".repeat(MAX_RECIPE_INGREDIENT_NAME - 1) + " ("), "an emoji isn't split in half")
        assertTrue(prompt.contains(longAllergen), "an allergen is never shortened")
        assertTrue(prompt.contains("φιστίκια"))
    }

    @Test
    fun anyTimeAndNoDietAddNoRule() {
        val prompt = buildOnDeviceRecipePrompt(
            listOf(RecipePromptItem("Milk", 1)),
            config.copy(maxMinutes = null),
            "en",
            emptyList()
        )

        assertFalse(prompt.contains("Total time"), prompt)
        assertFalse(prompt.contains("Diet:"), prompt)
        assertFalse(prompt.contains("Never use"), prompt)
    }

    private val reply = """
        Here is your recipe:
        ```json
        {"title":" Spinach toast ","summary":"Quick.","minutes":10,"servings":2,"difficulty":"easy",
         "ingredients":[{"amount":"4 slices","name":"Sourdough bread","fromPantry":true},
                        {"amount":"","name":"Baby spinach","fromPantry":false},
                        {"amount":"1 tbsp","name":"Olive oil","fromPantry":false},
                        {"amount":"","name":"  ","fromPantry":false}],
         "steps":["Toast the bread.",""," Wilt the spinach. "],"tip":"","extra":"ignored"}
        ```
    """.trimIndent()

    @Test
    fun readsAReplyWrappedInProseAndAFence() {
        val recipe = assertNotNull(parseRecipeReply(reply, RecipeSource.DEVICE, config, listOf("Baby spinach")))

        assertEquals("Spinach toast", recipe.title)
        assertEquals(listOf("Toast the bread.", "Wilt the spinach."), recipe.steps)
        assertEquals(3, recipe.ingredients.size, "blank ingredients are dropped")
        assertTrue(recipe.ingredients[1].fromPantry, "a name matching a ticked item counts as from the pantry")
        assertFalse(recipe.ingredients[2].fromPantry)
        assertNull(recipe.tip, "an empty tip is no tip")
        assertEquals(RecipeSource.DEVICE, recipe.source)
    }

    @Test
    fun aShorterIngredientNameStillMatchesTheTickedItem() {
        val recipe = assertNotNull(
            parseRecipeReply(
                """{"title":"T","steps":["a"],"ingredients":[{"name":"Eggs"},{"name":"Free-range eggs, beaten"}]}""",
                RecipeSource.DEVICE,
                config,
                listOf("Free-range eggs")
            )
        )
        assertTrue(recipe.ingredients.all { it.fromPantry }, recipe.ingredients.toString())
    }

    @Test
    fun rejectsWhatIsNotARecipe() {
        assertNull(parseRecipeReply("Sorry, I can't help with that.", RecipeSource.DEVICE, config, emptyList()))
        assertNull(parseRecipeReply("{not json}", RecipeSource.DEVICE, config, emptyList()))
        assertNull(parseRecipeReply("""{"title":"","steps":["a"],"ingredients":[{"name":"b"}]}""", RecipeSource.DEVICE, config, emptyList()))
        assertNull(parseRecipeReply("""{"title":"T","steps":[],"ingredients":[{"name":"b"}]}""", RecipeSource.DEVICE, config, emptyList()))
        assertNull(parseRecipeReply("""{"title":"T","steps":["a"],"ingredients":[]}""", RecipeSource.DEVICE, config, emptyList()))
        assertNull(parseRecipeReply("""{"summary":"no title"}""", RecipeSource.DEVICE, config, emptyList()))
    }

    @Test
    fun servingsOutOfRangeFallBackToTheForm() {
        val recipe = assertNotNull(
            parseRecipeReply(
                """{"title":"T","servings":0,"minutes":-5,"steps":["a"],"ingredients":[{"name":"b"}]}""",
                RecipeSource.DEVICE,
                config.copy(servings = 4),
                emptyList()
            )
        )
        assertEquals(4, recipe.servings)
        assertEquals(0, recipe.minutes)
    }

    @Test
    fun configAndRecipesUseTheAgreedWireNames() {
        val json = RecipeJson.encodeToString(
            RecipeConfig.serializer(),
            RecipeConfig(detail = RecipeDetail.STEP_BY_STEP, diet = RecipeDiet.GLUTEN_FREE, maxMinutes = null, language = "uk")
        )
        assertTrue(json.contains("\"detail\":\"stepByStep\""), json)
        assertTrue(json.contains("\"diet\":\"glutenFree\""), json)
        assertTrue(json.contains("\"mealType\":\"any\""), json)
        assertTrue(json.contains("\"language\":\"uk\""), json)
        assertTrue(json.contains("\"maxMinutes\":null"), "any time must survive a round trip: $json")
        assertEquals(RecipeConfig(maxMinutes = null), RecipeJson.decodeFromString(RecipeConfig.serializer(), RecipeJson.encodeToString(RecipeConfig.serializer(), RecipeConfig(maxMinutes = null))))

        val menu = Menu(
            listOf(
                MenuDay(
                    date = LocalDate(2026, 11, 4),
                    uses = listOf("Bread"),
                    courses = listOf(MenuCourse(MenuCourseKind.MAIN, Recipe(title = "T", steps = listOf("a"))))
                )
            )
        )
        val menuJson = RecipeJson.encodeToString(Menu.serializer(), menu)
        assertTrue(menuJson.contains("\"date\":\"2026-11-04\""), menuJson)
        assertTrue(menuJson.contains("\"course\":\"main\""), menuJson)
        assertEquals(menu, RecipeJson.decodeFromString(Menu.serializer(), menuJson))
    }

    @Test
    fun savedPayloadsRoundTripAndABadOneReadsAsNothing() {
        val recipe = Recipe(title = "T", steps = listOf("a"), tip = "t", source = RecipeSource.DEVICE)
        val saved = SavedRecipe.of("id", recipe, 5L)

        val back = savedRecipeFromPayload("id", saved.kind, saved.title, 5L, saved.source, saved.payloadJson())
        assertEquals(saved, back)
        assertNull(savedRecipeFromPayload("id", SavedRecipeKind.RECIPE, "T", 5L, RecipeSource.DEVICE, "{oops"))
    }

    @Test
    fun appLanguageMapsToARecipeLanguage() {
        assertEquals("nb", appRecipeLanguage("no"))
        assertEquals("nb", appRecipeLanguage("nb-NO"))
        assertEquals("ru", appRecipeLanguage("ru"))
        assertEquals("en", appRecipeLanguage("ja"))
        assertEquals("de", RecipeConfig(language = "de").resolvedLanguage("ru"))
        assertEquals("ru", RecipeConfig(language = null).resolvedLanguage("ru"))
    }
}
