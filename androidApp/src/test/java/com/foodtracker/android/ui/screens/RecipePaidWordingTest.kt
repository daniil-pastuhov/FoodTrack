package com.foodtracker.android.ui.screens

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.foodtracker.android.recipe.paid.toCallableData
import com.foodtracker.android.recipe.paid.toJsonElement
import com.foodtracker.android.settings.AndroidRecipePaidRepository
import com.foodtracker.recipe.GenerateReply
import com.foodtracker.recipe.GenerateRequest
import com.foodtracker.recipe.GenerateStatus
import com.foodtracker.recipe.Menu
import com.foodtracker.recipe.MenuCourse
import com.foodtracker.recipe.MenuCourseKind
import com.foodtracker.recipe.MenuDay
import com.foodtracker.recipe.Recipe
import com.foodtracker.android.R
import com.foodtracker.recipe.RecipeConfig
import com.foodtracker.recipe.RecipeCosts
import com.foodtracker.recipe.RecipeJson
import com.foodtracker.recipe.RecipePaidSnapshot
import com.foodtracker.recipe.SavedRecipe
import com.foodtracker.recipe.StorePack
import com.foodtracker.recipe.UnverifiedPurchase
import com.foodtracker.recipe.WireIngredient
import com.foodtracker.recipe.WireKind
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecipePaidWordingTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    @Config(sdk = [34], qualifiers = "en-rGB")
    fun thePricePerRecipeIsWorkedOutFromTheStorePrice() {
        val each = recipePackEachPrice(context.resources, StorePack("recipes_50", 50, "£7.99", 7_990_000, "GBP"))
        assertEquals("£0.16 each", each)
        assertNull(recipePackEachPrice(context.resources, StorePack("recipes_10", 10, "?", 0, "GBP")))
    }

    @Test
    @Config(sdk = [34], qualifiers = "ru")
    fun russianCountsUseTheirOwnPlurals() {
        val resources = context.resources
        assertEquals("Добавлено 50 рецептов", resources.getQuantityString(com.foodtracker.android.R.plurals.recipe_added, 50, 50))
        assertEquals("Сохранено. Осталось 11 рецептов", resources.getQuantityString(com.foodtracker.android.R.plurals.recipe_saved_left, 11, 11))
        assertEquals("1 рецепт", resources.getQuantityString(com.foodtracker.android.R.plurals.recipe_cost_recipes, 1, 1))
        assertEquals("3 рецепта", resources.getQuantityString(com.foodtracker.android.R.plurals.recipe_cost_recipes, 3, 3))
    }

    @Test
    @Config(sdk = [34], qualifiers = "en")
    fun theMenuCostsAreTheServersInEnglish() {
        val resources = context.resources
        val costs = RecipeCosts(1, 4, 5)
        assertEquals(
            "One recipe is one dish written by our server. A menu for 3 days takes 4 recipes with two courses a day, 5 recipes with three. They don't expire.",
            recipePacksBody(resources, costs)
        )
        assertEquals("A menu takes 5 recipes", resources.getString(R.string.recipe_menu_short_title, recipeCount(resources, costs.menu3)))
        assertEquals(
            "A menu is written on our server and takes 4 recipes from a pack.",
            resources.getString(R.string.recipe_menu_hint, recipeCount(resources, costs.menu2))
        )
    }

    @Test
    @Config(sdk = [34], qualifiers = "ru")
    fun theMenuCostsTakeRussianPlurals() {
        val resources = context.resources
        assertEquals(
            "Один рецепт — одно блюдо от нашего сервера. Меню на 3 дня стоит 4 рецепта при двух блюдах в день и 5 рецептов при трёх. Срок у рецептов не ограничен.",
            recipePacksBody(resources, RecipeCosts(1, 4, 5))
        )
        assertEquals("Для меню нужно 21 рецепт", resources.getString(R.string.recipe_menu_short_title, recipeCount(resources, 21)))
    }

    @Test
    @Config(sdk = [34], qualifiers = "nb")
    fun theMenuCostsInNorwegian() {
        val resources = context.resources
        assertEquals(
            "Én oppskrift er én rett skrevet av serveren vår. En meny for 3 dager bruker 4 oppskrifter med to retter om dagen, 5 oppskrifter med tre. De utløper ikke.",
            recipePacksBody(resources, RecipeCosts(1, 4, 5))
        )
        assertEquals("En meny bruker 1 oppskrift", resources.getString(R.string.recipe_menu_short_title, recipeCount(resources, 1)))
    }

    @Test
    @Config(sdk = [34], qualifiers = "en")
    fun theDeleteDialogSaysWhatTheServerKeeps() {
        assertEquals(
            "Your 5 unused recipes will be lost and can't be refunded or restored. Your sign-in and email address are deleted from our server; " +
                "a record of the purchases stays, no longer linked to you. Saved recipes and your pantry stay on this phone.",
            context.resources.getQuantityString(R.plurals.recipe_delete_body, 5, 5)
        )
    }

    @Test
    @Config(sdk = [34], qualifiers = "en")
    fun aSavedMenuShowsItsDishesAndTheAppsTitle() {
        val recipe = Recipe(title = "Soup", minutes = 20, steps = listOf("Cook."))
        val menu = Menu(
            (4..6).map { day ->
                MenuDay(LocalDate(2026, 11, day), listOf("bread"), MenuCourseKind.entries.map { MenuCourse(it, recipe) })
            }
        )
        val saved = SavedRecipe.of("m", "Menu for three days", menu, 1L)
        assertEquals("Menu for three days", savedRecipeTitle(context.resources, saved))
        assertEquals(listOf("9 dishes", "From your pack"), savedRecipeDetails(context.resources, saved))
        val label = menuDayLabel(context.resources, LocalDate(2026, 11, 4), LocalDate(2026, 11, 4))
        assertEquals(true, label.startsWith("Today, ") && "Wed" in label && "4" in label)
        assertEquals(false, menuDayLabel(context.resources, LocalDate(2026, 11, 4), today = null).startsWith("Today"))
    }

    @Test
    fun theRequestGoesOverTheWireAsTheContractNamesIt() {
        val request = GenerateRequest(
            requestId = "8f7d3c1e-5b2a-4c6d-9e0f-1a2b3c4d5e6f",
            kind = WireKind.MENU,
            courses = 2,
            config = RecipeConfig(maxMinutes = null, language = "nb"),
            ingredients = listOf(WireIngredient("Pasta", "2026-11-20", 1)),
            allergens = listOf("peanut"),
            today = "2026-11-13"
        )
        @Suppress("UNCHECKED_CAST")
        val data = RecipeJson.encodeToJsonElement(GenerateRequest.serializer(), request).toCallableData() as Map<String, Any?>
        assertEquals("menu", data["kind"])
        assertEquals(2, data["courses"])
        val config = data["config"] as Map<*, *>
        assertEquals("easy", config["difficulty"])
        assertEquals("standard", config["detail"])
        assertEquals(null, config["maxMinutes"])
        assertEquals(true, config.containsKey("maxMinutes"))
        assertEquals("nb", config["language"])
        assertEquals("2026-11-20", (data["ingredients"] as List<Map<*, *>>).single()["expiresOn"])
    }

    @Test
    fun theReplyReadsBackFromWhatTheCallableSdkHandsOver() {
        // The SDK gives maps, lists, and numbers that may be doubles.
        val raw = mapOf(
            "status" to "DONE",
            "balance" to 9.0,
            "charged" to true,
            "recipe" to mapOf(
                "title" to "Toast",
                "summary" to "",
                "minutes" to 10,
                "servings" to 2,
                "difficulty" to "easy",
                "ingredients" to listOf(mapOf("amount" to "", "name" to "salt", "fromPantry" to false)),
                "steps" to listOf("Toast.")
            )
        )
        val reply = RecipeJson.decodeFromJsonElement(GenerateReply.serializer(), raw.toJsonElement())
        assertEquals(GenerateStatus.DONE, reply.status)
        assertEquals(9, reply.balance)
        assertEquals("Toast", reply.recipe?.title)
        assertNull(reply.recipe?.tip)
    }

    @Test
    fun whatThePaidRouteKeepsSurvivesARestart() {
        val first = AndroidRecipePaidRepository(context)
        first.save(RecipePaidSnapshot(balance = 7, unverified = listOf(UnverifiedPurchase("tok", 50)), paymentPending = true, costs = RecipeCosts(1, 4, 6)))
        val read = AndroidRecipePaidRepository(context).get()
        assertEquals(7, read.balance)
        assertEquals(RecipeCosts(1, 4, 6), read.costs)
        assertEquals(listOf(UnverifiedPurchase("tok", 50)), read.unverified)
        assertEquals(true, read.paymentPending)
    }
}
