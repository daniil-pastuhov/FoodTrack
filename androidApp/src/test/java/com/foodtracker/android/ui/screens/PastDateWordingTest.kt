package com.foodtracker.android.ui.screens

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.foodtracker.android.util.buildRecipePrompt
import com.foodtracker.model.PantryItem
import com.foodtracker.recipe.Menu
import com.foodtracker.recipe.PastDateItem
import com.foodtracker.recipe.Recipe
import com.foodtracker.recipe.RecipeConfig
import com.foodtracker.util.DateUtils
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The red past-date warnings: on the form, on a written recipe and in what is shared. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PastDateWordingTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val items = listOf(PastDateItem("Hamburgare", 145), PastDateItem("Tomatpuré", 66))

    @Test
    @Config(sdk = [34], qualifiers = "en")
    fun englishWarnings() {
        val r = context.resources
        assertEquals(
            "Past their date: ⁨Hamburgare⁩ (145 days over), ⁨Tomatpuré⁩ (66 days over). Check they are still safe to eat before you cook.",
            pastDateWarning(r, items)
        )
        assertEquals(
            "Past its date: ⁨Milk⁩ (1 day over). Check it is still safe to eat before you cook.",
            pastDateWarning(r, listOf(PastDateItem("Milk", 1)))
        )
        assertNull(pastDateWarning(r, emptyList()))
        assertEquals(
            "4 ticked items are past their date, one by 145 days. Check they are still safe to eat, or untick them.",
            pastDateFormWarning(r, items + listOf(PastDateItem("A", 2), PastDateItem("B", 1)))
        )
        assertEquals(
            "A ticked item is past its date, by 1 day. Check it is still safe to eat, or untick it.",
            pastDateFormWarning(r, listOf(PastDateItem("Milk", 1)))
        )
        assertNull(pastDateFormWarning(r, emptyList()))
    }

    @Test
    @Config(sdk = [34], qualifiers = "ru")
    fun russianWarnings() {
        val r = context.resources
        assertEquals(
            "Срок годности истёк: ⁨Hamburgare⁩ (просрочено 145 дней назад), ⁨Tomatpuré⁩ (просрочено 66 дней назад). " +
                "Перед готовкой проверьте, что эти продукты ещё можно есть.",
            pastDateWarning(r, items)
        )
        assertEquals(
            "3 отмеченных продукта просрочены, самый старый — на 145 дней. Проверьте, что их ещё можно есть, или снимите отметку.",
            pastDateFormWarning(r, items + PastDateItem("A", 2))
        )
        assertEquals(
            "Отмеченный продукт просрочен на 21 день. Проверьте, что его ещё можно есть, или снимите отметку.",
            pastDateFormWarning(r, listOf(PastDateItem("A", 21)))
        )
    }

    @Test
    @Config(sdk = [34], qualifiers = "nb")
    fun norwegianWarnings() {
        val r = context.resources
        assertEquals(
            "Gått ut på dato: ⁨Hamburgare⁩ (utløpt for 145 dager siden), ⁨Tomatpuré⁩ (utløpt for 66 dager siden). " +
                "Sjekk at de fortsatt er trygge å spise før du lager mat.",
            pastDateWarning(r, items)
        )
        assertEquals(
            "2 avkryssede varer er gått ut på dato, én for 145 dager siden. Sjekk at de fortsatt er trygge å spise, eller fjern krysset.",
            pastDateFormWarning(r, items)
        )
    }

    @Test
    fun noCreditsOrTokensInAnyLanguage() {
        for (language in listOf("en", "ru", "nb")) {
            val r = context.createConfigurationContext(
                android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(language)) }
            ).resources
            for (text in listOfNotNull(pastDateWarning(r, items), pastDateFormWarning(r, items), pastDateFormWarning(r, items.take(1)))) {
                assertFalse("$language: $text", Regex("(?i)credit|token|кредит|токен|kreditt").containsMatchIn(text))
            }
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "en")
    fun theSharedTextCarriesTheWarning() {
        val recipe = Recipe(title = "Burger pasta", steps = listOf("Cook."), pastDate = items)
        val text = recipeShareText(context.resources, recipe)
        assertTrue(text, text.startsWith("Burger pasta\n\n⚠ Past their date: "))
        val menu = Menu(days = emptyList(), pastDate = items)
        assertTrue(menuShareText(context.resources, menu, DateUtils.today()).contains("⚠ Past their date: "))
        assertFalse(recipeShareText(context.resources, recipe.copy(pastDate = emptyList())).contains("⚠"))
    }

    @Test
    @Config(sdk = [34], qualifiers = "en")
    fun theSharedPromptSaysWhichTickedItemsArePastTheirDate() {
        val today = DateUtils.today()
        fun item(name: String, days: Int) = PantryItem(
            id = name,
            name = name,
            expiryDate = LocalDate.fromEpochDays(today.toEpochDays() + days),
            addedDate = today
        )
        val prompt = buildRecipePrompt(context, listOf(item("Milk", -3), item("Bread", 2), item("Cheese", -40)), RecipeConfig(), emptyList())
        assertTrue(prompt, prompt.contains("These items are past their date, and I have chosen to use them anyway: Cheese, Milk."))
        val fresh = buildRecipePrompt(context, listOf(item("Bread", 2)), RecipeConfig(), emptyList())
        assertFalse(fresh, fresh.contains("past their date"))
    }
}
