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
import com.foodtracker.recipe.MenuItemsHint
import com.foodtracker.recipe.Recipe
import com.foodtracker.android.R
import com.foodtracker.recipe.RecipeConfig
import com.foodtracker.recipe.legacyWireConfig
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
    @Config(sdk = [34], qualifiers = "en")
    fun newEnglishPluralsAgreeWithTheirCount() {
        val r = context.resources
        assertEquals("1 recipe left", r.getQuantityString(R.plurals.recipe_balance_left, 1, 1))
        assertEquals("3 recipes left", r.getQuantityString(R.plurals.recipe_balance_left, 3, 3))
        assert(r.getQuantityString(R.plurals.recipe_allergens_too_many, 1, 1).contains("at most 1 allergen,"))
        assert(r.getQuantityString(R.plurals.recipe_allergens_too_many, 20, 20).contains("at most 20 allergens,"))
        assert(r.getQuantityString(R.plurals.recipe_refused_menu, 4, 4).startsWith("Couldn't make a menu"))
        assert(r.getQuantityString(R.plurals.recipe_refused_menu, 1, 1).startsWith("Couldn't make a menu"))
    }

    @Test
    @Config(sdk = [34], qualifiers = "ru")
    fun newRussianPluralsAgreeWithTheirCount() {
        val r = context.resources
        assert(r.getQuantityString(R.plurals.recipe_allergens_too_many, 20, 20).contains("20 аллергенов"))
        assert(r.getQuantityString(R.plurals.recipe_allergens_too_many, 3, 3).contains("3 аллергенов"))
        assert(r.getQuantityString(R.plurals.recipe_allergens_too_many, 21, 21).contains("21 аллергена"))
    }

    @Test
    @Config(sdk = [34], qualifiers = "nb")
    fun norwegianBusyAndRateLimitSayNothingWasTaken() {
        val r = context.resources
        assertEquals("En annen oppskrift skrives fortsatt. Prøv igjen om et minutt. Ingen oppskrifter ble trukket.", r.getString(R.string.recipe_failed_server_busy))
        assertEquals("For mange oppskrifter på kort tid. Prøv igjen om et minutt. Ingen oppskrifter ble trukket.", r.getString(R.string.recipe_failed_rate_limited))
    }

    @Test
    @Config(sdk = [34], qualifiers = "en")
    fun aJoinSaysWhatMovedOrWhatWasOwed() {
        val resources = context.resources
        assertEquals("12 recipes moved from this device to your account", recipeMergedMessage(resources, 12))
        assertEquals("1 recipe moved from this device to your account", recipeMergedMessage(resources, 1))
        assertEquals("0 recipes moved from this device to your account", recipeMergedMessage(resources, 0))
        assertEquals("This device owed 1 recipe after a store refund. It was taken from your account.", recipeMergedMessage(resources, -1))
        assertEquals("This device owed 3 recipes after a store refund. They were taken from your account.", recipeMergedMessage(resources, -3))
    }

    @Test
    @Config(sdk = [34], qualifiers = "ru")
    fun aRussianJoinUsesTheirPlurals() {
        val resources = context.resources
        assertEquals("После возврата денег в магазине это устройство было должно 3 рецепта. Они списаны с вашего аккаунта.", recipeMergedMessage(resources, -3))
        assertEquals("5 рецептов перенесены с этого устройства в ваш аккаунт", recipeMergedMessage(resources, 5))
        assertEquals("1 рецепт перенесён с этого устройства в ваш аккаунт", recipeMergedMessage(resources, 1))
        assertEquals("2 рецепта перенесены с этого устройства в ваш аккаунт", recipeMergedMessage(resources, 2))
        assertEquals(
            "2 рецепта на этом устройстве куплены в другом аккаунте. Войдите в аккаунт, с которым покупали, чтобы их добавить.",
            resources.getQuantityString(R.plurals.recipe_elsewhere_body, 2, 2)
        )
    }

    @Test
    @Config(sdk = [34], qualifiers = "nb")
    fun theNorwegianBusyStateIsCalm() {
        assertEquals("Serveren vår er opptatt", context.getString(R.string.recipe_writing_busy_title))
        assertEquals("Denne enheten skyldte 2 oppskrifter etter en refusjon i butikken. De ble trukket fra kontoen din.", recipeMergedMessage(context.resources, -2))
    }

    @Test
    @Config(sdk = [34], qualifiers = "en")
    fun busyAndRateLimitedSayWhyAndThatNothingWasTaken() {
        assertEquals(
            "Another recipe is still being written. Try again in a minute. No recipes were taken.",
            context.getString(R.string.recipe_failed_server_busy)
        )
        assertEquals(
            "Too many recipes in a short time. Try again in a minute. No recipes were taken.",
            context.getString(R.string.recipe_failed_rate_limited)
        )
        assertEquals(
            "Nothing was written: a recipe can take at most 20 allergens, and more are set. Remove some under Allergen warnings in Settings, then try again. No recipes were taken.",
            context.resources.getQuantityString(R.plurals.recipe_allergens_too_many, 20, 20)
        )
    }

    @Test
    @Config(sdk = [34], qualifiers = "ru")
    fun tooManyAllergensInRussian() {
        assertEquals(
            "Ничего не составлено: рецепт может учесть не больше 20 аллергенов, а задано больше. Уберите лишние в настройках, в разделе «Предупреждения об аллергенах», и попробуйте снова. Рецепты не списаны.",
            context.resources.getQuantityString(R.plurals.recipe_allergens_too_many, 20, 20)
        )
        assertEquals("Другой рецепт ещё составляется. Попробуйте через минуту. Рецепты не списаны.", context.getString(R.string.recipe_failed_server_busy))
    }

    @Test
    @Config(sdk = [34], qualifiers = "nb")
    fun tooManyAllergensInNorwegian() {
        assertEquals(
            "Ingenting ble skrevet: en oppskrift kan ta hensyn til høyst 20 allergener, og flere er lagt inn. Fjern noen under Allergivarsler i Innstillinger, og prøv igjen. Ingen oppskrifter ble trukket.",
            context.resources.getQuantityString(R.plurals.recipe_allergens_too_many, 20, 20)
        )
        assertEquals("For mange oppskrifter på kort tid. Prøv igjen om et minutt. Ingen oppskrifter ble trukket.", context.getString(R.string.recipe_failed_rate_limited))
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
    }

    @Test
    @Config(sdk = [34], qualifiers = "en")
    fun withAMenuSetTableThePacksTextGivesItsCheapestAndDearest() {
        val table = mapOf(1 to mapOf(1 to 1, 2 to 2), 4 to mapOf(3 to 6))
        assertEquals(
            "One recipe is one dish written by our server. A menu set takes 1 to 6 recipes, depending on its days and courses a day. They don't expire.",
            recipePacksBody(context.resources, RecipeCosts(1, 4, 5, menuSets = table))
        )
    }

    @Test
    @Config(sdk = [34], qualifiers = "en")
    fun aMenuWithNoDaysIsStillTitledForOneDay() {
        assertEquals("Menu for one day", menuTitle(context.resources, Menu(emptyList())))
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
    fun theBlockedBuyerLineInEveryLanguage() {
        val keys = listOf("en" to "Buying recipes isn't available for this account", "ru" to "Покупка рецептов недоступна", "nb" to "Kjøp av oppskrifter er ikke tilgjengelig")
        for ((language, start) in keys) {
            val configuration = android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(language)) }
            val text = context.createConfigurationContext(configuration).getString(R.string.recipe_purchases_blocked)
            org.junit.Assert.assertTrue(text, text.startsWith(start))
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "en")
    fun theDeleteDialogSaysWhatTheServerKeeps() {
        assertEquals(
            "Your 5 unused recipes will be lost and can't be refunded or restored. Your sign-in and email address are deleted from our server; " +
                "a record of the purchases stays, no longer linked to you. Any reports you sent are kept for a limited time, also no longer linked to you. " +
                "Saved recipes and your pantry stay on this device.",
            context.resources.getQuantityString(R.plurals.recipe_delete_body, 5, 5)
        )
    }

    @Test
    fun everyDeletionDialogSaysReportsAreKeptInEveryLanguage() {
        // deleteAccount keeps the account's reports, without its uid, until they expire (detachReports).
        val expected = mapOf(
            "en" to "Any reports you sent are kept for a limited time, also no longer linked to you.",
            "ru" to "Ваши жалобы, если они были, хранятся ещё ограниченное время, тоже без связи с вами.",
            "nb" to "Eventuelle rapporter du har sendt, blir liggende en begrenset tid, også uten kobling til deg.",
            "es" to "Las denuncias que hayas enviado se conservan durante un tiempo limitado, también sin vínculo contigo.",
            "de" to "Eventuelle Meldungen von dir bleiben eine begrenzte Zeit erhalten, ebenfalls nicht mehr mit dir verknüpft.",
            "fr" to "Les éventuels signalements que vous avez envoyés sont conservés pendant une durée limitée, eux aussi sans lien avec vous.",
            "sv" to "Eventuella rapporter du har skickat sparas en begränsad tid, även de utan koppling till dig.",
            "da" to "Eventuelle rapporter, du har sendt, bliver gemt i en begrænset periode og er heller ikke længere knyttet til dig."
        )
        for ((language, sentence) in expected) {
            val configuration = android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(language)) }
            val resources = context.createConfigurationContext(configuration).resources
            for (text in listOf(
                resources.getQuantityString(R.plurals.recipe_delete_body, 5, 5),
                resources.getQuantityString(R.plurals.recipe_delete_device_body, 1, 1),
                resources.getString(R.string.recipe_delete_device_body_none),
            )) org.junit.Assert.assertTrue("$language: $text", sentence in text)
        }
    }

    @Test
    fun deletingThisDevicesDataSaysWhatIsLostAndKeptInEveryLanguage() {
        // Each: the count, that the purchases' record stays, and that the pantry stays.
        val expected = mapOf(
            "en" to listOf("Your 5 unused recipes will be lost", "a record of the purchases stays", "your pantry stay on this device"),
            "ru" to listOf("Ваши 5 неиспользованных рецептов пропадут", "запись о покупках останется", "запасы останутся на устройстве"),
            "nb" to listOf("De 5 ubrukte oppskriftene dine går tapt", "en oversikt over kjøpene blir liggende", "matlageret blir på denne enheten")
        )
        for ((language, parts) in expected) {
            val configuration = android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(language)) }
            val resources = context.createConfigurationContext(configuration).resources
            val body = resources.getQuantityString(R.plurals.recipe_delete_device_body, 5, 5)
            for (part in parts) org.junit.Assert.assertTrue("$language: $body", part in body)
            val none = resources.getString(R.string.recipe_delete_device_body_none)
            org.junit.Assert.assertTrue("$language: $none", parts.last() in none)
            for (text in listOf(body, none, resources.getString(R.string.recipe_delete_device_data), resources.getString(R.string.recipe_delete_waits_for_purchase))) {
                org.junit.Assert.assertFalse("$language: no credits or tokens: $text", Regex("(?i)credit|token|кредит|токен|kreditt").containsMatchIn(text))
            }
        }
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
        // A menu saved before menu sets: three days, read from the menu itself.
        assertEquals("Menu for 3 days", savedRecipeTitle(context.resources, saved))
        val oneDay = SavedRecipe.of("m1", "Menu for one day", Menu(listOf(MenuDay(LocalDate(2026, 11, 4), courses = listOf(MenuCourse(MenuCourseKind.MAIN, recipe))))), 1L)
        assertEquals("Menu for one day", savedRecipeTitle(context.resources, oneDay))
        // Written by the server: no source wording.
        assertEquals(listOf("1 dish"), savedRecipeDetails(context.resources, oneDay))
        assertEquals("One course a day for 2, using what expires first.", context.resources.getQuantityString(R.plurals.recipe_menu_summary, 1, 1, 2))
        assertEquals(
            "Not enough for one day. Tick a few more items, or ask for a single recipe. No recipes were taken.",
            context.resources.getQuantityString(R.plurals.recipe_not_enough_items_menu, 1, 1)
        )
        assertEquals(true, context.resources.getQuantityString(R.plurals.recipe_not_enough_items_menu, 4, 4).startsWith("Not enough for 4 days."))
        assertEquals(listOf("9 dishes"), savedRecipeDetails(context.resources, saved))
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
            config = legacyWireConfig(RecipeConfig(maxMinutes = null), "nb")!!,
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
        assertEquals("any", config["mealType"])
        assertEquals(true, config.containsKey("diets") && config["diets"] == null)
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

    @Test
    @Config(sdk = [34], qualifiers = "en")
    fun theFewItemsHintAndThePhoneModelNoteReadAsWritten() {
        val r = context.resources
        assertEquals(
            "Only 1 item for 3 dishes. Most of this menu will need shopping.",
            recipeMenuFewItems(r, MenuItemsHint(1, 3))
        )
        assertEquals(
            "Only 5 items for 12 dishes. Most of this menu will need shopping.",
            recipeMenuFewItems(r, MenuItemsHint(5, 12))
        )
        assertEquals("Still weak at cooking. Nothing leaves this device.", r.getString(R.string.recipe_on_device_caption))
        assertEquals("For an AI app you already use", r.getString(R.string.recipe_share_caption))
        assertEquals("Other ways", r.getString(R.string.recipe_other_ways))
    }

    @Test
    @Config(sdk = [34], qualifiers = "ru")
    fun theRussianFewItemsHintAgreesWithItsCount() {
        val r = context.resources
        assert(recipeMenuFewItems(r, MenuItemsHint(1, 3)).startsWith("Только 1 продукт для 3 блюд."))
        assert(recipeMenuFewItems(r, MenuItemsHint(2, 6)).startsWith("Только 2 продукта для 6 блюд."))
        assert(recipeMenuFewItems(r, MenuItemsHint(5, 12)).startsWith("Только 5 продуктов для 12 блюд."))
    }
}
