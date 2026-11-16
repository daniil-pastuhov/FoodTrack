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
            config = config.copy(diets = setOf(RecipeDiet.PESCATARIAN), maxMinutes = 15),
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
        val prompt = buildOnDeviceRecipePrompt(items, config.copy(mealTypes = setOf(RecipeMealType.BREAKFAST)), "en", emptyList())

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
            RecipeConfig(detail = RecipeDetail.STEP_BY_STEP, diets = setOf(RecipeDiet.GLUTEN_FREE), maxMinutes = null, language = "uk")
        )
        assertTrue(json.contains("\"detail\":\"stepByStep\""), json)
        assertTrue(json.contains("\"diets\":[\"glutenFree\"]"), json)
        assertTrue(json.contains("\"mealTypes\":[]"), json)
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
        for (code in listOf("es", "de", "fr", "sv", "da")) assertEquals(code, appRecipeLanguage(code), code)
        assertEquals("da", appRecipeLanguage("da-DK"))
        assertEquals("es", appRecipeLanguage("es-419"))
        assertEquals("Dansk", recipeLanguageEndonym("da"))
        assertEquals("Danish", recipeLanguageEnglishName("da"))
        assertEquals("en", appRecipeLanguage("ja"))
        assertEquals("de", RecipeConfig(language = "de").resolvedLanguage("ru"))
        assertEquals("ru", RecipeConfig(language = null).resolvedLanguage("ru"))
    }

    @Test
    fun everyRecipeLanguageHasItsOwnEnglishName() {
        val names = mapOf(
            "en" to "English", "nb" to "Norwegian Bokmål", "ru" to "Russian", "de" to "German", "es" to "Spanish",
            "fr" to "French", "it" to "Italian", "pl" to "Polish", "sv" to "Swedish", "uk" to "Ukrainian", "da" to "Danish"
        )
        assertEquals(RECIPE_LANGUAGES.toSet(), names.keys, "every recipe language is named here")
        for ((code, name) in names) {
            assertEquals(name, recipeLanguageEnglishName(code), code)
            val prompt = buildOnDeviceRecipePrompt(listOf(RecipePromptItem("Milk", 1)), config, code, emptyList())
            assertTrue(prompt.contains("Write every text value in $name."), "$code: $prompt")
        }
        assertEquals("English", recipeLanguageEnglishName("ja"), "anything else is English")
    }

    @Test
    fun onlyAnItemPastItsDateAddsThePastDateSentence() {
        val sentence = "Items past their date are ones I have checked and chosen to use"
        fun prompt(vararg days: Int) = buildOnDeviceRecipePrompt(days.map { RecipePromptItem("Item $it", it) }, config, "en", emptyList())
        assertFalse(prompt(0).contains(sentence), "its date is today: not past it")
        assertFalse(prompt(0, 1, 5).contains(sentence))
        assertTrue(prompt(-1).contains(sentence))
        assertTrue(prompt(0, -1).contains(sentence))
        // An optional item past its date is no ticked one the cook chose.
        assertFalse(buildOnDeviceRecipePrompt(listOf(RecipePromptItem("Milk", 2)), config, "en", emptyList(), listOf(RecipePromptItem("Old bread", -3))).contains(sentence))
    }

    @Test
    fun tickedAndOptionalItemsTogetherKeepToTheCap() {
        val ticked = (0 until 25).map { RecipePromptItem("ticked $it", it) }
        val optional = (0 until 20).map { RecipePromptItem("optional $it", it) }
        val prompt = buildOnDeviceRecipePrompt(ticked, config, "en", emptyList(), optional)
        val lines = prompt.lines()
        assertEquals(25, lines.count { it.startsWith("- ticked ") })
        assertEquals(MAX_RECIPE_INGREDIENTS - 25, lines.count { it.startsWith("- optional ") }, "only the room the ticked items leave")
        assertTrue(prompt.contains("- optional 4 (") && !prompt.contains("- optional 5 ("), "the soonest optional ones")

        val full = buildOnDeviceRecipePrompt((0 until 40).map { RecipePromptItem("ticked $it", it) }, config, "en", emptyList(), optional)
        assertEquals(MAX_RECIPE_INGREDIENTS, full.lines().count { it.startsWith("- ticked ") })
        assertFalse(full.contains("- optional "), "no room left: no optional items")
        assertFalse(full.contains("I also have these at home"), full)
        assertTrue(full.contains("only for items from the list above."), full)
    }

    @Test
    fun thePantryFlagNamesOneListOrTwo() {
        val one = buildOnDeviceRecipePrompt(listOf(RecipePromptItem("Milk", 1)), config, "en", emptyList())
        assertTrue(one.contains("Set fromPantry to true only for items from the list above."), one)
        val two = buildOnDeviceRecipePrompt(listOf(RecipePromptItem("Milk", 1)), config, "en", emptyList(), listOf(RecipePromptItem("Rice", 90)))
        assertTrue(two.contains("Set fromPantry to true only for items from the lists above."), two)
        assertTrue(two.contains("I also have these at home") && two.contains("- Rice (90 days left)"), two)
    }

    private val staplesIntro = "\n\nI always have these basics too. Use any of them if the dish needs them; none is needed, and they are not items to use up:\n"

    @Test
    fun staplesAreListedLastAsBasicsThatAreNeverNeeded() {
        val prompt = buildOnDeviceRecipePrompt(
            items = listOf(RecipePromptItem("Bread", 1)),
            config = config,
            languageCode = "en",
            allergens = emptyList(),
            optional = listOf(RecipePromptItem("Rice", 90)),
            staples = listOf(" Salt ", "Olive oil")
        )
        assertTrue(prompt.contains("- Rice (90 days left)" + staplesIntro + "- Salt\n- Olive oil\n\nKeep to this:"), prompt)
    }

    @Test
    fun theFromPantrySentenceNamesTheListsThatArePantry() {
        fun closing(optional: Boolean, staples: Boolean) = buildOnDeviceRecipePrompt(
            items = listOf(RecipePromptItem("Bread", 1)),
            config = config,
            languageCode = "en",
            allergens = emptyList(),
            optional = if (optional) listOf(RecipePromptItem("Rice", 90)) else emptyList(),
            staples = if (staples) listOf("Salt") else emptyList()
        ).substringAfterLast("\n")
        assertEquals("Set fromPantry to true only for items from the list above. Leave tip empty if there is none.", closing(false, false))
        assertEquals("Set fromPantry to true only for items from the lists above. Leave tip empty if there is none.", closing(true, false))
        assertEquals("Set fromPantry to true only for items from the first list above. Leave tip empty if there is none.", closing(false, true))
        assertEquals("Set fromPantry to true only for items from the first two lists above. Leave tip empty if there is none.", closing(true, true))
    }

    @Test
    fun atMostTwentyStaplesEachCutAndNoneCountsTowardsTheItems() {
        val items = (0 until MAX_RECIPE_INGREDIENTS).map { RecipePromptItem("item $it", it) }
        val staples = (0 until 25).map { "basic $it" } + ("y".repeat(60))
        val prompt = buildOnDeviceRecipePrompt(items, config, "en", emptyList(), staples = staples)
        assertEquals(MAX_RECIPE_INGREDIENTS, prompt.lines().count { it.startsWith("- item ") })
        assertEquals(20, prompt.lines().count { it.startsWith("- basic ") })
        assertFalse(prompt.contains("- basic 20"), prompt)
        assertFalse(prompt.contains("- yyy"), "the 26th is past the cap")
    }

    @Test
    fun withoutStaplesThePromptIsAsBefore() {
        val before = buildOnDeviceRecipePrompt(listOf(RecipePromptItem("Bread", 1)), config, "en", emptyList())
        assertEquals(before, buildOnDeviceRecipePrompt(listOf(RecipePromptItem("Bread", 1)), config, "en", emptyList(), staples = emptyList()))
        assertFalse(before.contains("basics"), before)
    }

    @Test
    fun aReplyMarksTheIngredientsThatAreTheStaples() {
        val reply = """{"title":"Toast","ingredients":[{"amount":"2","name":"Bread","fromPantry":true},""" +
            """{"amount":"","name":"salt, to taste","fromPantry":false},{"amount":"1 tbsp","name":"Olive oil","fromPantry":false},""" +
            """{"amount":"","name":"Salt and pepper","fromPantry":false},{"amount":"1","name":"Garlic","fromPantry":false}],"steps":["Toast."]}"""
        val recipe = assertNotNull(parseRecipeReply(reply, RecipeSource.DEVICE, config, listOf("Bread"), stapleNames = listOf("Salt", "Olive oil")))
        assertEquals(listOf(null, "Salt", "Olive oil", null, null), recipe.ingredients.map { it.stapleName })
        assertTrue(recipe.ingredients.none { it.stapleName != null && it.fromPantry })
    }
}
