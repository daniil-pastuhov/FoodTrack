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
            RecipeConfig(servings = 4, maxMinutes = 15, mealType = RecipeMealType.DINNER, diet = RecipeDiet.VEGAN, language = "de"),
            listOf("peanuts")
        )

        assertTrue(prompt, prompt.contains("- Milk ("))
        assertTrue(prompt, prompt.contains("Please keep to this:\n- Easy to make, for a beginner"))
        assertTrue(prompt, prompt.contains("- Servings: 4"))
        assertTrue(prompt, prompt.contains("- Ready in 15 minutes or less"))
        assertTrue(prompt, prompt.contains("- Meal: Dinner"))
        assertTrue(prompt, prompt.contains("- Diet: Vegan"))
        assertTrue(prompt, prompt.contains("- Write the recipes in German"))
        assertTrue(prompt, prompt.endsWith("- Nothing that contains: peanuts"))
    }

    @Test
    @Config(sdk = [34], qualifiers = "ru")
    fun russianPromptIsRussian() {
        val prompt = buildRecipePrompt(context, listOf(item("Молоко", 1)), RecipeConfig(detail = RecipeDetail.BRIEF), emptyList())

        assertTrue(prompt, prompt.contains("Пожалуйста, учти:"))
        assertTrue(prompt, prompt.contains("- Коротко, в несколько шагов"))
        assertTrue(prompt, prompt.contains("- Порций: 2"))
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
                RecipeConfig(detail = RecipeDetail.STEP_BY_STEP, mealType = RecipeMealType.DINNER, diet = RecipeDiet.VEGAN, language = "de"),
                appLanguage = "en"
            )
        )
        assertEquals("15 min", recipeDurationLabel(resources, 15))
        assertEquals("1 h", recipeDurationLabel(resources, 60))
        assertEquals("1 h 10 min", recipeDurationLabel(resources, 70))
        assertEquals("Same as the app (Norsk bokmål)", recipeLanguageLabel(resources, null, "nb"))
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
