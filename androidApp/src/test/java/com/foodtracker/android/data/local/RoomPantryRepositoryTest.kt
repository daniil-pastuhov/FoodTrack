package com.foodtracker.android.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.foodtracker.model.PantryItem
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
class RoomPantryRepositoryTest {

    private lateinit var db: FoodTrackerDatabase
    private lateinit var repo: RoomPantryRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            FoodTrackerDatabase::class.java
        ).allowMainThreadQueries().build()
        repo = RoomPantryRepository(db.pantryDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testRepositoryCrud() = runTest {
        val initial = repo.getItemsFlow().first()
        assertEquals(0, initial.size)

        val newItem = PantryItem(
            id = "test-1",
            name = "Cheddar Cheese",
            expiryDate = LocalDate(2026, 10, 1),
            addedDate = LocalDate(2026, 9, 3),
            storageLocation = "Fridge"
        )
        repo.saveItem(newItem)

        val afterSave = repo.getItemsFlow().first()
        assertEquals(1, afterSave.size)
        assertEquals("Cheddar Cheese", afterSave.first().name)

        repo.deleteItem("test-1")
        val afterDelete = repo.getItemsFlow().first()
        assertEquals(0, afterDelete.size)
        assertNull(repo.getItem("test-1"))
    }
}
