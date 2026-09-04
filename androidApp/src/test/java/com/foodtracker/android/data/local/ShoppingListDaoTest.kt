package com.foodtracker.android.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.foodtracker.model.ShoppingListEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShoppingListDaoTest {

    private lateinit var db: FoodTrackerDatabase
    private lateinit var repo: RoomShoppingListRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            FoodTrackerDatabase::class.java
        ).allowMainThreadQueries().build()
        repo = RoomShoppingListRepository(db.shoppingListDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testRepositoryCrud() = runTest {
        val initial = repo.getEntriesFlow().first()
        assertEquals(0, initial.size)

        val entry = ShoppingListEntry(
            id = "entry-1",
            productName = "Cheddar Cheese",
            barcode = "1234567890123",
            addedDate = LocalDate(2026, 9, 3)
        )
        repo.addEntry(entry)

        val afterAdd = repo.getEntriesFlow().first()
        assertEquals(1, afterAdd.size)
        assertEquals("Cheddar Cheese", afterAdd.first().productName)

        repo.deleteEntry("entry-1")
        val afterDelete = repo.getEntriesFlow().first()
        assertEquals(0, afterDelete.size)
    }

    @Test
    fun testFindByBarcode() = runTest {
        assertNull(repo.findByBarcode("1234567890123"))

        repo.addEntry(
            ShoppingListEntry(
                id = "entry-1",
                productName = "Cheddar Cheese",
                barcode = "1234567890123",
                addedDate = LocalDate(2026, 9, 3)
            )
        )

        val found = repo.findByBarcode("1234567890123")
        assertEquals("entry-1", found?.id)
        assertNull(repo.findByBarcode("nonexistent"))
    }

    @Test
    fun testFindByNameIsCaseInsensitiveAndTrimmed() = runTest {
        repo.addEntry(
            ShoppingListEntry(
                id = "entry-1",
                productName = "Cheddar Cheese",
                barcode = null,
                addedDate = LocalDate(2026, 9, 3)
            )
        )

        assertEquals("entry-1", repo.findByName("  cheddar cheese  ")?.id)
        assertEquals("entry-1", repo.findByName("CHEDDAR CHEESE")?.id)
        assertNull(repo.findByName("Swiss Cheese"))
    }
}
