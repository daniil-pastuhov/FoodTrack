package com.foodtracker.android.ui.screens

import com.foodtracker.model.AppSettings
import com.foodtracker.model.DefaultStorageLocations
import com.foodtracker.model.StorePlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SettingsLogicTest {

    private val translated = mapOf(
        DefaultStorageLocations.FRIDGE to "Холодильник",
        DefaultStorageLocations.FREEZER to "Морозилка"
    )
    private val label: (String) -> String = { translated[it] ?: it }
    private val locations = listOf(DefaultStorageLocations.FRIDGE, DefaultStorageLocations.FREEZER, "Cellar")

    @Test
    fun `an empty shelf life clears the default`() {
        assertEquals(ShelfLifeInput.Cleared, parseShelfLife(""))
        assertEquals(ShelfLifeInput.Cleared, parseShelfLife("  "))
    }

    @Test
    fun `shelf life accepts both ends of the range`() {
        assertEquals(ShelfLifeInput.Days(0), parseShelfLife("0"))
        assertEquals(ShelfLifeInput.Days(3650), parseShelfLife("3650"))
    }

    @Test
    fun `a shelf life past the end is refused, not capped`() {
        assertEquals(ShelfLifeInput.OutOfRange, parseShelfLife("3651"))
        assertEquals(ShelfLifeInput.OutOfRange, parseShelfLife("9999"))
    }

    @Test
    fun `a shelf life too long for an Int is out of range rather than a crash`() {
        assertEquals(ShelfLifeInput.OutOfRange, parseShelfLife("99999999999"))
    }

    @Test
    fun `a new location takes the trimmed name`() {
        assertEquals(LocationNameCheck.Accepted("Garage"), checkNewLocation(locations, "  Garage ", label))
    }

    @Test
    fun `a blank new location does nothing`() {
        assertEquals(LocationNameCheck.NoChange, checkNewLocation(locations, "   ", label))
    }

    @Test
    fun `a new location clashing with a shown built-in name is taken`() {
        assertEquals(LocationNameCheck.Taken("Холодильник"), checkNewLocation(locations, "Холодильник", label))
    }

    @Test
    fun `confirming a built-in's translated name unchanged keeps the key`() {
        val check = checkLocationRename(DefaultStorageLocations.FRIDGE, locations, "Холодильник ", label)
        assertEquals(LocationNameCheck.NoChange, check)
    }

    @Test
    fun `typing a location's own key is not a clash with itself`() {
        val check = checkLocationRename(DefaultStorageLocations.FRIDGE, locations, DefaultStorageLocations.FRIDGE, label)
        assertEquals(LocationNameCheck.NoChange, check)
    }

    @Test
    fun `renaming onto another location is taken`() {
        assertEquals(LocationNameCheck.Taken("Морозилка"), checkLocationRename("Cellar", locations, "Морозилка", label))
    }

    @Test
    fun `a rename carries the default and last used along`() {
        val settings = AppSettings(
            storageLocations = locations,
            defaultStorageLocation = "Cellar",
            lastUsedStorageLocation = "Cellar"
        )
        val renamed = settings.withLocationRenamed("Cellar", "Garage")
        assertEquals(listOf(DefaultStorageLocations.FRIDGE, DefaultStorageLocations.FREEZER, "Garage"), renamed.storageLocations)
        assertEquals("Garage", renamed.defaultStorageLocation)
        assertEquals("Garage", renamed.lastUsedStorageLocation)
    }

    @Test
    fun `the last location can't be removed`() {
        assertNull(AppSettings(storageLocations = listOf("Cellar")).removingLocation("Cellar"))
    }

    @Test
    fun `removing the default moves it to the first left, and undo puts everything back`() {
        val settings = AppSettings(
            storageLocations = locations,
            defaultStorageLocation = DefaultStorageLocations.FRIDGE,
            lastUsedStorageLocation = DefaultStorageLocations.FRIDGE
        )
        val (removed, undo) = settings.removingLocation(DefaultStorageLocations.FRIDGE)!!
        assertEquals(DefaultStorageLocations.FREEZER, removed.defaultStorageLocation)
        assertNull(removed.lastUsedStorageLocation)
        assertEquals(settings, removed.restoringLocation(undo))
    }

    @Test
    fun `undoing a removal keeps a change made since`() {
        val settings = AppSettings(storageLocations = locations, defaultStorageLocation = "Cellar")
        val (removed, undo) = settings.removingLocation(DefaultStorageLocations.FREEZER)!!
        val restored = removed.copy(expiringSoonDays = 7).restoringLocation(undo)
        assertEquals(7, restored.expiringSoonDays)
        assertEquals(locations, restored.storageLocations)
        assertEquals("Cellar", restored.defaultStorageLocation)
    }

    @Test
    fun `undo lands at the end when the list has since shrunk`() {
        val undo = RemovedLocation("Cellar", index = 5, wasDefault = false, wasLastUsed = false)
        val settings = AppSettings(storageLocations = listOf("A"), defaultStorageLocation = "A")
        assertEquals(listOf("A", "Cellar"), settings.restoringLocation(undo).storageLocations)
    }

    @Test
    fun `undo does nothing once the name is back`() {
        val settings = AppSettings(storageLocations = locations, defaultStorageLocation = "Cellar")
        val undo = RemovedLocation("Cellar", index = 0, wasDefault = false, wasLastUsed = false)
        assertSame(settings, settings.restoringLocation(undo))
    }

    @Test
    fun `allergens are trimmed and lowercased, and duplicates change nothing`() {
        assertEquals(setOf("milk"), emptySet<String>().addingAllergen("  MILK "))
        assertEquals(setOf("milk"), setOf("milk").addingAllergen("Milk"))
    }

    @Test
    fun `a blank allergen is ignored`() {
        assertEquals(emptySet<String>(), emptySet<String>().addingAllergen("   "))
    }

    private fun store(label: String, id: String = "same") = StorePlace(id, label, 1.0, 2.0)

    @Test
    fun `removing a shop leaves others with the same id alone`() {
        val settings = AppSettings(stores = listOf(store("A"), store("B")))
        val (removed, shop) = settings.removingStore(0)!!
        assertEquals(listOf(store("B")), removed.stores)
        assertEquals(store("A"), shop)
    }

    @Test
    fun `removing a shop that isn't there does nothing`() {
        assertNull(AppSettings().removingStore(0))
    }

    @Test
    fun `undoing a shop removal puts it back in its place`() {
        val settings = AppSettings(stores = listOf(store("A"), store("B"), store("C")))
        val (removed, shop) = settings.removingStore(1)!!
        assertEquals(settings.stores, removed.restoringStore(shop, 1).stores)
    }

    @Test
    fun `undoing a shop removal lands at the end when the list has since shrunk`() {
        val settings = AppSettings(stores = listOf(store("A")))
        assertEquals(listOf("A", "B"), settings.restoringStore(store("B"), 4).stores.map { it.label })
    }

    @Test
    fun `move up and down shift one row`() {
        assertEquals(listOf("b", "a", "c"), listOf("a", "b", "c").movingRow(1, -1))
        assertEquals(listOf("a", "c", "b"), listOf("a", "b", "c").movingRow(1, 1))
    }

    @Test
    fun `moving off either end changes nothing`() {
        val rows = listOf("a", "b")
        assertSame(rows, rows.movingRow(0, -1))
        assertSame(rows, rows.movingRow(1, 1))
    }
}
