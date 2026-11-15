package com.foodtracker.recipe

import com.foodtracker.model.ExpiryStatus
import com.foodtracker.model.PantryItem
import com.foodtracker.viewmodel.PantryRow
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The menu form's "few items for this menu" hint; the same cases as iOS `RecipeConfigCheck`. */
class MenuItemsHintTest {

    @Test
    fun theHintShowsWithFewerItemsThanHalfTheDishesRoundedUp() {
        // 1 × 1: one dish, and one item is always enough.
        assertNull(menuItemsHint(ticked = 1, optional = 0, days = 1, courses = 1))
        assertEquals(MenuItemsHint(1, 3), menuItemsHint(ticked = 1, optional = 0, days = 1, courses = 3))
        assertNull(menuItemsHint(ticked = 2, optional = 0, days = 1, courses = 3))
        assertEquals(MenuItemsHint(2, 6), menuItemsHint(ticked = 2, optional = 0, days = 3, courses = 2))
        assertNull(menuItemsHint(ticked = 3, optional = 0, days = 3, courses = 2))
        assertEquals(MenuItemsHint(5, 12), menuItemsHint(ticked = 5, optional = 0, days = 4, courses = 3))
        assertNull(menuItemsHint(ticked = 6, optional = 0, days = 4, courses = 3))
    }

    @Test
    fun itemsSentWithSendAllProductsCountAndNothingTickedSaysNothing() {
        assertNull(menuItemsHint(ticked = 2, optional = 10, days = 4, courses = 3))
        assertEquals(MenuItemsHint(5, 12), menuItemsHint(ticked = 2, optional = 3, days = 4, courses = 3))
        // Nothing ticked has its own hint, and the form can't be sent anyway.
        assertNull(menuItemsHint(ticked = 0, optional = 10, days = 4, courses = 3))
        assertNull(menuItemsHint(ticked = 0, optional = 0, days = 4, courses = 3))
    }

    private val today = LocalDate(2026, 11, 15)

    private fun row(id: String, days: Int, name: String = "Item $id"): PantryRow {
        val item = PantryItem(
            id = id,
            name = name,
            expiryDate = LocalDate.fromEpochDays(today.toEpochDays() + days),
            addedDate = today,
            quantity = 1
        )
        return PantryRow(item, days, ExpiryStatus.compute(days, 3), null, id)
    }

    private val sets = RecipeCosts(menuSets = (1..4).associateWith { days -> (1..3).associateWith { courses -> days * courses } })

    private fun state(
        ticked: Int,
        others: Int,
        sendAll: Boolean = false,
        takesOptional: Boolean = false,
        mode: RecipeMode = RecipeMode.MENU,
        menuSets: Boolean = true
    ): RecipeUiState {
        val rows = (0 until ticked + others).map { row("$it", it) }
        return RecipeUiState(
            isLoading = false,
            rows = rows,
            selection = RecipeSelection.Chosen(rows.take(ticked).mapTo(HashSet()) { it.id }),
            mode = mode,
            days = 4,
            courses = 3,
            options = RecipeOptions(sendAllItems = sendAll),
            paid = PaidUiState(
                available = true,
                balance = 50,
                costs = if (menuSets) sets else RecipeCosts(),
                menuSets = menuSets,
                serverTakesOptional = takesOptional
            )
        )
    }

    @Test
    fun theFormCountsTheOtherItemsOnlyWhenTheyWouldBeSent() {
        assertNull(state(ticked = 2, others = 10, sendAll = true, takesOptional = true).menuItemsHint)
        // Off, or a server that doesn't read them: only the ticked items are used.
        assertEquals(MenuItemsHint(2, 12), state(ticked = 2, others = 10, sendAll = false, takesOptional = true).menuItemsHint)
        assertEquals(MenuItemsHint(2, 12), state(ticked = 2, others = 10, sendAll = true, takesOptional = false).menuItemsHint)
        assertEquals(MenuItemsHint(4, 12), state(ticked = 2, others = 2, sendAll = true, takesOptional = true).menuItemsHint)
    }

    @Test
    fun theFormUsesTheMenuSetAskedForAndNeverWarnsInRecipeMode() {
        assertNull(state(ticked = 1, others = 0, mode = RecipeMode.RECIPE).menuItemsHint)
        assertEquals(MenuItemsHint(1, 12), state(ticked = 1, others = 0).menuItemsHint)
        // 4 days of 3 chosen, from a server without menu sets: the 3-day menu of 3 is asked for.
        assertEquals(MenuItemsHint(4, 9), state(ticked = 4, others = 0, menuSets = false).menuItemsHint)
        assertNull(state(ticked = 5, others = 0, menuSets = false).menuItemsHint)
    }
}
