package com.foodtracker.android.ui.screens

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.foodtracker.model.ExpiryStatus
import com.foodtracker.model.PantryItem
import com.foodtracker.viewmodel.HomeLede
import com.foodtracker.viewmodel.HomeSection
import com.foodtracker.viewmodel.HomeSectionTitle
import com.foodtracker.viewmodel.PantryRow
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeWordingTest {

    private val resources: Resources
        get() = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    /** A Friday. */
    private val friday = LocalDate(2026, 11, 6)

    @Test
    @Config(sdk = [34], qualifiers = "en")
    fun testEnglishLedeSpellsSmallNumbers() {
        assertEquals("One thing to use by tomorrow", homeLedeText(resources, HomeLede.UseByTomorrow(1)))
        assertEquals("Three things to use by tomorrow", homeLedeText(resources, HomeLede.UseByTomorrow(3)))
        assertEquals("Ten things to use by tomorrow", homeLedeText(resources, HomeLede.UseByTomorrow(10)))
        assertEquals("11 things to use by tomorrow", homeLedeText(resources, HomeLede.UseByTomorrow(11)))
    }

    @Test
    @Config(sdk = [34], qualifiers = "en")
    fun testEnglishPastDateAgreesWithItsCount() {
        assertEquals("One thing is past its date", homeLedeText(resources, HomeLede.PastDate(1)))
        assertEquals("Two things are past their date", homeLedeText(resources, HomeLede.PastDate(2)))
    }

    @Test
    @Config(sdk = [34], qualifiers = "en")
    fun testEnglishCalmSentences() {
        assertEquals(
            "Next up: Semi-skimmed milk on Friday",
            homeLedeText(resources, HomeLede.NextUp("Semi-skimmed milk", friday))
        )
        assertEquals("Nothing needs using this week", homeLedeText(resources, HomeLede.NothingThisWeek))
        assertNull(homeLedeText(resources, HomeLede.None))
    }

    @Test
    @Config(sdk = [34], qualifiers = "ru")
    fun testRussianLedeUsesAllThreePluralForms() {
        assertEquals("Один продукт лучше использовать до завтра", homeLedeText(resources, HomeLede.UseByTomorrow(1)))
        assertEquals("Три продукта лучше использовать до завтра", homeLedeText(resources, HomeLede.UseByTomorrow(3)))
        assertEquals("Пять продуктов лучше использовать до завтра", homeLedeText(resources, HomeLede.UseByTomorrow(5)))
        assertEquals("21 продукт лучше использовать до завтра", homeLedeText(resources, HomeLede.UseByTomorrow(21)))
    }

    @Test
    @Config(sdk = [34], qualifiers = "ru")
    fun testRussianWeekdayTakesItsPreposition() {
        assertEquals("Дальше: Молоко в пятницу", homeLedeText(resources, HomeLede.NextUp("Молоко", friday)))
        assertEquals(
            "Дальше: Молоко во вторник",
            homeLedeText(resources, HomeLede.NextUp("Молоко", LocalDate(2026, 11, 3)))
        )
    }

    @Test
    @Config(sdk = [34], qualifiers = "en")
    fun testRecipeNamesStayShort() {
        assertEquals("Milk", recipeNames(resources, listOf("Milk")))
        assertEquals("Milk and Eggs", recipeNames(resources, listOf("Milk", "Eggs")))
        assertEquals("Milk, Eggs and Oats", recipeNames(resources, listOf("Milk", "Eggs", "Oats")))
        assertEquals("Milk, Eggs and 2 others", recipeNames(resources, listOf("Milk", "Eggs", "Oats", "Rice")))
        assertEquals("Milk, Eggs and 3 others", recipeNames(resources, listOf("Milk", "Eggs", "Oats", "Rice", "Tea")))
    }

    private fun row(id: String) = PantryRow(
        item = PantryItem(id = id, name = id, expiryDate = friday, addedDate = friday),
        daysRemaining = 1,
        status = ExpiryStatus.EXPIRING_SOON,
        categoryDisplayName = null,
        sortName = id
    )

    private val sections = listOf(
        HomeSection(HomeSectionTitle.UseFirst, listOf(row("a"))),
        HomeSection(HomeSectionTitle.Later, listOf(row("b")))
    )

    @Test
    fun testPaneFallsBackToTheFirstRow() {
        assertEquals("a", homePaneItemId(null, sections, searching = false, selecting = false))
        assertEquals("a", homePaneItemId("gone", sections, searching = false, selecting = false))
    }

    @Test
    fun testPaneKeepsThePickedRowWhileItIsListed() {
        assertEquals("b", homePaneItemId("b", sections, searching = false, selecting = false))
        assertEquals("b", homePaneItemId("b", sections, searching = true, selecting = false))
    }

    @Test
    fun testPaneIsBlankWhileSelectingAndUntilAResultIsPicked() {
        assertNull(homePaneItemId("b", sections, searching = false, selecting = true))
        assertNull(homePaneItemId(null, sections, searching = true, selecting = false))
        assertNull(homePaneItemId("gone", sections, searching = true, selecting = false))
    }
}
