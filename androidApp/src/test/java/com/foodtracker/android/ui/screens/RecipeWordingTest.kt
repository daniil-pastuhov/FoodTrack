package com.foodtracker.android.ui.screens

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.foodtracker.android.settings.AndroidRecipeConfigRepository
import com.foodtracker.android.util.buildRecipePrompt
import com.foodtracker.model.PantryItem
import com.foodtracker.recipe.Recipe
import com.foodtracker.recipe.RecipeConfig
import com.foodtracker.recipe.RecipeDetail
import com.foodtracker.recipe.RecipeDiet
import com.foodtracker.recipe.RecipeIngredient
import com.foodtracker.recipe.RecipeMealType
import com.foodtracker.util.DateUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecipeWordingTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun item(name: String, days: Int) = DateUtils.today().let { today ->
        PantryItem(
            id = name,
            name = name,
            expiryDate = kotlinx.datetime.LocalDate.fromEpochDays(today.toEpochDays() + days),
            addedDate = today
        )
    }

    @Test
    @Config(sdk = [34], qualifiers = "en")
    fun englishPromptCarriesTheFormAndAllergens() {
        val prompt = buildRecipePrompt(
            context,
            listOf(item("Milk", 1)),
            RecipeConfig(servings = 4, maxMinutes = 15, mealTypes = setOf(RecipeMealType.DINNER), diets = setOf(RecipeDiet.VEGAN), language = "de"),
            listOf("peanuts")
        )

        assertTrue(prompt, prompt.contains("Please suggest one recipe I could make, using the ones closest to their expiration date first:"))
        assertTrue(prompt, prompt.contains("- Milk ("))
        assertTrue(prompt, prompt.contains("Please keep to this:\n- Easy to make, for a beginner"))
        assertTrue(prompt, prompt.contains("- Servings: 4"))
        assertTrue(prompt, prompt.contains("- Ready in 15 minutes or less"))
        assertTrue(prompt, prompt.contains("- Meal: Dinner"))
        assertTrue(prompt, prompt.contains("- Diet: Vegan"))
        assertTrue(prompt, prompt.contains("- Write the recipe in German"))
        assertTrue(prompt, prompt.endsWith("- Nothing that contains: peanuts"))
    }

    @Test
    @Config(sdk = [34], qualifiers = "en")
    fun englishPromptListsTheStaplesAfterTheItems() {
        val prompt = buildRecipePrompt(context, listOf(item("Milk", 1)), RecipeConfig(), emptyList(), staples = listOf("Salt", "Olive oil"))

        assertTrue(
            prompt,
            prompt.contains("\n\nI always have these basics at home too. Use any of them if the recipe needs them; none of them has to be used:\n- Salt\n- Olive oil\n\nPlease keep to this:")
        )
    }

    @Test
    @Config(sdk = [34], qualifiers = "en")
    fun staplesLinesInEnglish() {
        val resources = context.resources
        assertEquals("Your staple may be used too", resources.getQuantityString(com.foodtracker.android.R.plurals.recipe_staples_count, 1, 1))
        assertEquals("Your 4 staples may be used too", resources.getQuantityString(com.foodtracker.android.R.plurals.recipe_staples_count, 4, 4))
        assertEquals("1 staple", resources.getQuantityString(com.foodtracker.android.R.plurals.staples_count, 1, 1))
        assertEquals("20 staples", resources.getQuantityString(com.foodtracker.android.R.plurals.staples_count, 20, 20))
    }

    @Test
    @Config(sdk = [34], qualifiers = "ru")
    fun russianStaplesLineKeepsTheCount() {
        val resources = context.resources
        for (n in listOf(1, 3, 5, 21)) {
            assertEquals("Можно использовать и базовые продукты: $n", resources.getQuantityString(com.foodtracker.android.R.plurals.recipe_staples_count, n, n))
        }
        assertEquals("5 продуктов", resources.getQuantityString(com.foodtracker.android.R.plurals.staples_count, 5, 5))
    }

    @Test
    @Config(sdk = [34], qualifiers = "ru")
    fun russianPromptIsRussian() {
        val prompt = buildRecipePrompt(context, listOf(item("Молоко", 1)), RecipeConfig(detail = RecipeDetail.BRIEF), emptyList())

        assertTrue(prompt, prompt.contains("Предложи один рецепт, который я могу приготовить"))
        assertTrue(prompt, prompt.contains("Пожалуйста, учти:"))
        assertTrue(prompt, prompt.contains("- Коротко, в несколько шагов"))
        assertTrue(prompt, prompt.contains("- Порций: 2"))
    }

    @Test
    @Config(sdk = [34], qualifiers = "ru")
    fun russianLanguageLineIsSingular() {
        val prompt = buildRecipePrompt(context, listOf(item("Молоко", 1)), RecipeConfig(language = "de"), emptyList())

        assertTrue(prompt, prompt.contains("- Напиши рецепт на языке: "))
    }

    @Test
    @Config(sdk = [34], qualifiers = "nb")
    fun norwegianPromptAsksForOneRecipe() {
        val prompt = buildRecipePrompt(context, listOf(item("Melk", 1)), RecipeConfig(language = "de"), emptyList())

        assertTrue(prompt, prompt.contains("Foreslå én oppskrift jeg kan lage, og bruk først det som nærmer seg utløpsdatoen:"))
        assertTrue(prompt, prompt.contains("- Skriv oppskriften på "))
    }

    @Test
    @Config(sdk = [34], qualifiers = "en")
    fun optionsSummaryAndDurations() {
        val resources = context.resources
        assertEquals(
            "English · Standard detail · Any meal · No diet",
            recipeOptionsSummary(resources, RecipeConfig(), appLanguage = "en")
        )
        assertEquals(
            "Deutsch · Step by step · Dinner · Vegan",
            recipeOptionsSummary(
                resources,
                RecipeConfig(detail = RecipeDetail.STEP_BY_STEP, mealTypes = setOf(RecipeMealType.DINNER), diets = setOf(RecipeDiet.VEGAN), language = "de"),
                appLanguage = "en"
            )
        )
        assertEquals("15 min", recipeDurationLabel(resources, 15))
        assertEquals("1 h", recipeDurationLabel(resources, 60))
        assertEquals("1 h 10 min", recipeDurationLabel(resources, 70))
        assertEquals("Same as the app (Norsk bokmål)", recipeLanguageLabel(resources, null, "nb"))
        assertEquals(
            "Deutsch · Standard detail · Lunch, Dinner · Vegetarian, Gluten-free",
            recipeOptionsSummary(
                resources,
                RecipeConfig(mealTypes = setOf(RecipeMealType.DINNER, RecipeMealType.LUNCH), diets = setOf(RecipeDiet.GLUTEN_FREE, RecipeDiet.VEGETARIAN), language = "de"),
                appLanguage = "en"
            )
        )
        assertEquals("Any", recipeTimeRangeLabel(resources, null, null))
        assertEquals("up to 30 min", recipeTimeRangeLabel(resources, null, 30))
        assertEquals("at least 40 min", recipeTimeRangeLabel(resources, 40, null))
        assertEquals("20–45 min", recipeTimeRangeLabel(resources, 20, 45))
        assertEquals("30 min", recipeTimeRangeLabel(resources, 30, 30))
    }

    @Test
    @Config(sdk = [34], qualifiers = "en")
    fun sharedRecipeTextReadsInOrder() {
        val text = recipeShareText(
            context.resources,
            Recipe(
                title = "Toast",
                summary = "Quick.",
                minutes = 10,
                servings = 1,
                ingredients = listOf(RecipeIngredient("2 slices", "Bread"), RecipeIngredient("", "Salt")),
                steps = listOf("Toast it.", "Salt it."),
                tip = "Eat it warm."
            )
        )

        assertEquals(
            "Toast\n\nQuick.\n10 min · 1 serving · Easy\n\nIngredients\n- 2 slices Bread\n- Salt\n\n" +
                "Method\n1. Toast it.\n2. Salt it.\n\nTip: Eat it warm.",
            text
        )
    }

    @Test
    fun recipeConfigIsKeptUnderItsOwnKeyAndSurvivesBadData() {
        val repository = AndroidRecipeConfigRepository(context)
        assertEquals(RecipeConfig(), repository.get())

        val config = RecipeConfig(servings = 6, maxMinutes = null, language = "uk")
        repository.save(config)
        assertEquals(config, AndroidRecipeConfigRepository(context).get())

        val prefs = context.getSharedPreferences("foodtracker_settings", Context.MODE_PRIVATE)
        assertTrue("app settings stay a separate key", prefs.contains("recipe_config") && !prefs.contains("app_settings"))

        prefs.edit().putString("recipe_config", "{\"servings\":99,\"diet\":\"carnivore\",\"future\":1}").commit()
        assertEquals(RecipeConfig(servings = 12), repository.get())

        prefs.edit().putString("recipe_config", "not json").commit()
        assertEquals(RecipeConfig(), repository.get())
    }
}
