package com.foodtracker.recipe

import com.foodtracker.model.PantryItem
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecipePromptBuilderTest {

    private val today = LocalDate(2026, 1, 10)

    private val strings = RecipePromptStrings(
        intro = "INTRO",
        itemFormat = { name, expiry -> "$name ($expiry)" },
        otherItemsIntro = "ALSO:",
        otherItemsOmitted = { count -> "(+$count more)" },
        daysRemainingLabel = { days -> "${days}d" },
        preferencesIntro = "KEEP:",
        difficulty = { "difficulty=${it.name}" },
        detail = { "detail=${it.name}" },
        servings = { "servings=$it" },
        maxMinutes = { "minutes=$it" },
        mealType = { "meal=${it.name}" },
        diet = { "diet=${it.name}" },
        allergens = { "allergens=$it" },
        language = { "language=$it" }
    )

    private fun item(name: String, expiresInDays: Int) = PantryItem(
        id = name,
        name = name,
        expiryDate = LocalDate.fromEpochDays(today.toEpochDays() + expiresInDays),
        addedDate = today
    )

    @Test
    fun listsItemsSoonestExpiryFirst() {
        val prompt = buildRecipePrompt(
            strings = strings,
            today = today,
            items = listOf(item("Cheese", 9), item("Milk", 1), item("Bread", 4))
        )

        val order = listOf("Milk", "Bread", "Cheese").map { prompt.indexOf(it) }
        assertTrue(order.all { it >= 0 }, "every item should appear: $prompt")
        assertEquals(order.sorted(), order, "items should be ordered soonest-expiry first")
    }

    @Test
    fun rendersDaysRemainingThroughTheSuppliedLabel() {
        val prompt = buildRecipePrompt(
            strings = strings,
            today = today,
            items = listOf(item("Milk", 2))
        )

        assertTrue(prompt.contains("- Milk (2d)"), prompt)
    }

    @Test
    fun alreadyExpiredItemsReportNegativeDays() {
        val prompt = buildRecipePrompt(
            strings = strings,
            today = today,
            items = listOf(item("Yoghurt", -3))
        )

        assertTrue(prompt.contains("- Yoghurt (-3d)"), prompt)
    }

    @Test
    fun omitsOtherItemsSectionWhenThereAreNone() {
        val prompt = buildRecipePrompt(
            strings = strings,
            today = today,
            items = listOf(item("Milk", 1))
        )

        assertFalse(prompt.contains("ALSO:"), prompt)
    }

    @Test
    fun listsOtherItemsAlphabeticallyByName() {
        val prompt = buildRecipePrompt(
            strings = strings,
            today = today,
            items = listOf(item("Milk", 1)),
            otherItems = listOf(item("Rice", 200), item("Flour", 300))
        )

        assertTrue(prompt.contains("ALSO: Flour, Rice"), prompt)
        assertFalse(prompt.contains("more)"), "nothing was dropped, so no overflow note: $prompt")
    }

    @Test
    fun truncatesOtherItemsAtTheCharacterCapAndReportsTheRemainder() {
        // Names are padded to a known width so the cap lands at a predictable count rather than
        // depending on how long the test's own strings happen to be.
        val nameLength = 20
        val perEntry = nameLength + 2 // ", " separator
        val totalItems = (MAX_OTHER_ITEMS_CHARS / perEntry) + 50
        val others = (0 until totalItems).map { index ->
            item(index.toString().padStart(nameLength, 'a'), 200)
        }

        val prompt = buildRecipePrompt(
            strings = strings,
            today = today,
            items = listOf(item("Milk", 1)),
            otherItems = others
        )

        val section = prompt.substringAfter("ALSO: ").substringBefore(" (+")
        assertTrue(
            section.length <= MAX_OTHER_ITEMS_CHARS,
            "other-items section must stay within the cap, was ${section.length}"
        )

        val included = section.split(", ").size
        assertTrue(included < totalItems, "some items should have been dropped")
        assertTrue(prompt.contains("(+${totalItems - included} more)"), prompt.takeLast(100))
    }

    @Test
    fun keepsEveryOtherItemWhenTheyAllFit() {
        val others = (0 until 5).map { item("other$it", 200) }

        val prompt = buildRecipePrompt(
            strings = strings,
            today = today,
            items = listOf(item("Milk", 1)),
            otherItems = others
        )

        others.forEach { assertTrue(prompt.contains(it.name), prompt) }
        assertFalse(prompt.contains("more)"), prompt)
    }

    @Test
    fun withoutConfigOrAllergensThereIsNoPreferencesBlock() {
        val prompt = buildRecipePrompt(strings = strings, today = today, items = listOf(item("Milk", 1)))

        assertFalse(prompt.contains("KEEP:"), prompt)
    }

    @Test
    fun configAddsOneLinePerSettingInAFixedOrder() {
        val prompt = buildRecipePrompt(
            strings = strings,
            today = today,
            items = listOf(item("Milk", 1)),
            config = RecipeConfig(
                difficulty = RecipeDifficulty.AMBITIOUS,
                detail = RecipeDetail.STEP_BY_STEP,
                servings = 4,
                maxMinutes = 60,
                mealType = RecipeMealType.DINNER,
                diet = RecipeDiet.VEGAN,
                language = "de"
            )
        )

        assertTrue(
            prompt.endsWith(
                "KEEP:\n- difficulty=AMBITIOUS\n- detail=STEP_BY_STEP\n- servings=4\n- minutes=60" +
                    "\n- meal=DINNER\n- diet=VEGAN\n- language=de"
            ),
            prompt
        )
        assertTrue(prompt.indexOf("- Milk") < prompt.indexOf("KEEP:"), "items come before the form: $prompt")
    }

    @Test
    fun anyMealNoDietAnyTimeAndTheAppLanguageAddNoLines() {
        val prompt = buildRecipePrompt(
            strings = strings,
            today = today,
            items = listOf(item("Milk", 1)),
            config = RecipeConfig(maxMinutes = null, mealType = RecipeMealType.ANY, diet = RecipeDiet.NONE, language = null)
        )

        assertFalse(prompt.contains("minutes="), prompt)
        assertFalse(prompt.contains("meal="), prompt)
        assertFalse(prompt.contains("diet="), prompt)
        assertFalse(prompt.contains("language="), prompt)
        assertTrue(prompt.contains("- servings=2"), prompt)
    }

    @Test
    fun outOfRangeConfigIsBroughtBackInBeforeItReachesThePrompt() {
        val prompt = buildRecipePrompt(
            strings = strings,
            today = today,
            items = listOf(item("Milk", 1)),
            config = RecipeConfig(servings = 40, maxMinutes = 45, language = "xx")
        )

        assertTrue(prompt.contains("- servings=12"), prompt)
        assertTrue(prompt.contains("- minutes=30"), prompt)
        assertFalse(prompt.contains("language="), prompt)
    }

    @Test
    fun allergensAreAlwaysAppliedCleanedAndSorted() {
        val prompt = buildRecipePrompt(
            strings = strings,
            today = today,
            items = listOf(item("Milk", 1)),
            allergens = listOf(" peanuts ", "Milk", "", "milk", "celery")
        )

        assertTrue(prompt.endsWith("KEEP:\n- allergens=celery, Milk, peanuts"), prompt)
    }

    @Test
    fun allergensComeLastAfterTheForm() {
        val prompt = buildRecipePrompt(
            strings = strings,
            today = today,
            items = listOf(item("Milk", 1)),
            config = RecipeConfig(language = "ru"),
            allergens = listOf("gluten")
        )

        assertTrue(prompt.endsWith("- language=ru\n- allergens=gluten"), prompt)
    }
}
