package com.foodtracker.model

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class ShoppingListEntryTest {

    @Test
    fun testPantryItemShoppingMatchKeyPrefersBarcode() {
        val item = PantryItem(
            id = "1",
            name = "Cheddar Cheese",
            barcode = "1234567890123",
            expiryDate = LocalDate(2026, 10, 1),
            addedDate = LocalDate(2026, 9, 1)
        )
        assertEquals("1234567890123", item.shoppingMatchKey())
    }

    @Test
    fun testPantryItemShoppingMatchKeyFallsBackToNormalizedName() {
        val item = PantryItem(
            id = "1",
            name = "  Cheddar Cheese  ",
            expiryDate = LocalDate(2026, 10, 1),
            addedDate = LocalDate(2026, 9, 1)
        )
        assertEquals("cheddar cheese", item.shoppingMatchKey())
    }

    @Test
    fun testShoppingListEntryMatchKeyMirrorsPantryItem() {
        val withBarcode = ShoppingListEntry(
            id = "e1",
            productName = "Cheddar Cheese",
            barcode = "1234567890123",
            addedDate = LocalDate(2026, 9, 1)
        )
        assertEquals("1234567890123", withBarcode.matchKey())

        val withoutBarcode = ShoppingListEntry(
            id = "e2",
            productName = "  Cheddar Cheese  ",
            addedDate = LocalDate(2026, 9, 1)
        )
        assertEquals("cheddar cheese", withoutBarcode.matchKey())
    }
}
