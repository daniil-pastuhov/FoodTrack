package com.foodtracker.android.ui.screens

import com.foodtracker.android.ui.design.LarderGroupPosition
import com.foodtracker.model.ShoppingListEntry
import com.foodtracker.model.ShoppingSection
import com.foodtracker.util.DateUtils
import com.foodtracker.viewmodel.ShoppingRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which rows join into one group, and where section headers get their gap. */
class ShoppingRowLayoutTest {

    private fun header(id: String) = ShoppingRow.Header(ShoppingSection(id, id, sortOrder = 0.0), 0)

    private fun item(id: String) = ShoppingRow.Item(
        ShoppingListEntry(id = id, productName = id, addedDate = DateUtils.today()),
        "section"
    )

    @Test
    fun aRowAloneUnderItsHeaderIsASingleGroup() {
        val rows = listOf(header("a"), item("1"), header("b"))
        assertEquals(LarderGroupPosition.Single, shoppingGroupPosition(rows, 1))
    }

    @Test
    fun rowsBetweenHeadersFormFirstMiddleLast() {
        val rows = listOf(header("a"), item("1"), item("2"), item("3"), header("b"))
        assertEquals(LarderGroupPosition.First, shoppingGroupPosition(rows, 1))
        assertEquals(LarderGroupPosition.Middle, shoppingGroupPosition(rows, 2))
        assertEquals(LarderGroupPosition.Last, shoppingGroupPosition(rows, 3))
    }

    @Test
    fun theLastRowOfTheListClosesItsGroup() {
        val rows = listOf(header("a"), item("1"), item("2"))
        assertEquals(LarderGroupPosition.Last, shoppingGroupPosition(rows, 2))
    }

    @Test
    fun aHeaderGetsAGapOnlyAfterRows() {
        val rows = listOf(header("a"), item("1"), header("b"), header("c"))
        assertFalse(shoppingHeaderHasGap(rows, 0))
        assertTrue(shoppingHeaderHasGap(rows, 2))
        assertFalse(shoppingHeaderHasGap(rows, 3))
    }
}
