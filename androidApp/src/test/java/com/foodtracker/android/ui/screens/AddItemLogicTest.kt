package com.foodtracker.android.ui.screens

import com.foodtracker.model.ProductInfo
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddItemLogicTest {
    private val today = LocalDate(2026, 11, 4)

    private val fields = AddItemFields(
        name = "Mature cheddar",
        brand = "Wyke Farms",
        barcode = "5012345678900",
        imageUrl = null,
        expiryDate = LocalDate(2026, 11, 24),
        storageLocation = "Fridge",
        category = "Dairy",
        quantity = 1,
        notes = "",
        essential = true,
        notificationsEnabled = true
    )

    @Test
    fun `quick dates count 3, 7, 14 and 30 days from today`() {
        assertEquals(
            listOf(
                LocalDate(2026, 11, 7),
                LocalDate(2026, 11, 11),
                LocalDate(2026, 11, 18),
                LocalDate(2026, 12, 4)
            ),
            QuickDate.entries.map { it.date(today) }
        )
    }

    @Test
    fun `a quick date crosses the end of the year`() {
        assertEquals(LocalDate(2027, 1, 14), QuickDate.OneMonth.date(LocalDate(2026, 12, 15)))
    }

    @Test
    fun `only the chip that matches the date is selected`() {
        val selected = QuickDate.entries.filter { it.matches(LocalDate(2026, 11, 11), today) }
        assertEquals(listOf(QuickDate.OneWeek), selected)
    }

    @Test
    fun `no chip is selected for a date none of them gives`() {
        assertTrue(QuickDate.entries.none { it.matches(LocalDate(2026, 11, 24), today) })
    }

    @Test
    fun `a chip stops matching when the day rolls over`() {
        val date = QuickDate.OneWeek.date(today)
        assertFalse(QuickDate.OneWeek.matches(date, LocalDate(2026, 11, 5)))
    }

    @Test
    fun `each field counts as a change`() {
        listOf(
            fields.copy(name = "Cheddar"),
            fields.copy(brand = ""),
            fields.copy(barcode = ""),
            fields.copy(imageUrl = "file:///photo.jpg"),
            fields.copy(expiryDate = LocalDate(2026, 11, 25)),
            fields.copy(storageLocation = "Freezer"),
            fields.copy(category = "Cheese"),
            fields.copy(quantity = 2),
            fields.copy(notes = "Opened"),
            fields.copy(essential = false),
            fields.copy(notificationsEnabled = false)
        ).forEach { assertNotEquals(fields, it) }
    }

    @Test
    fun `the header needs a photo or a barcode`() {
        assertFalse(showsAddItemHero(null, ""))
        assertFalse(showsAddItemHero("", "  "))
        assertTrue(showsAddItemHero("file:///photo.jpg", ""))
        assertTrue(showsAddItemHero(null, "5012345678900"))
    }

    @Test
    fun `category suggestions match anywhere, in any case`() {
        assertEquals(
            listOf("Dairy", "Non-dairy"),
            categorySuggestions("dair", listOf("Dairy", "Bakery", "Non-dairy"))
        )
    }

    @Test
    fun `nothing is suggested before typing`() {
        assertEquals(emptyList<String>(), categorySuggestions("  ", listOf("Dairy")))
    }

    @Test
    fun `the category already typed is not offered back`() {
        assertEquals(emptyList<String>(), categorySuggestions("DAIRY", listOf("Dairy")))
    }

    @Test
    fun `category suggestions stop at five, without repeats`() {
        val labels = listOf("A1", "A1", "A2", "A3", "A4", "A5", "A6")
        assertEquals(listOf("A1", "A2", "A3", "A4", "A5"), categorySuggestions("a", labels))
    }

    @Test
    fun `a scanned product belongs to the form with its barcode`() {
        val product = ProductInfo(barcode = "5012345678900", name = "Mature cheddar")
        assertEquals(product, scannedProductFor("5012345678900", product))
    }

    @Test
    fun `the last scan does not follow a blank form`() {
        val product = ProductInfo(barcode = "5012345678900", name = "Mature cheddar")
        assertNull(scannedProductFor(null, product))
        assertNull(scannedProductFor("", product))
    }

    @Test
    fun `another product's scan does not apply`() {
        val product = ProductInfo(barcode = "5012345678900", name = "Mature cheddar")
        assertNull(scannedProductFor("5060012345670", product))
    }
}
