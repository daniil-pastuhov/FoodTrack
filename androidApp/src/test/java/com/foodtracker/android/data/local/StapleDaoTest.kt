package com.foodtracker.android.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.foodtracker.model.Staple
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StapleDaoTest {

    private lateinit var db: FoodTrackerDatabase
    private lateinit var repo: RoomStapleRepository

    private val salt = Staple("staple_1", "Salt", "salt", 1_763_300_000_000L)
    private val paprika = Staple("staple_2", "Smoked paprika", null, 1_763_300_100_000L)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            FoodTrackerDatabase::class.java
        ).allowMainThreadQueries().build()
        repo = RoomStapleRepository(db.stapleDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun upsertReadAndDelete() = runTest {
        assertTrue(repo.getAll().isEmpty())
        repo.upsert(paprika)
        repo.upsert(salt)
        assertEquals("oldest first", listOf(salt, paprika), repo.getAll())
        assertEquals(listOf(salt, paprika), repo.getStaplesFlow().first())

        repo.delete(salt.id)
        assertEquals(listOf(paprika), repo.getAll())
    }

    @Test
    fun upsertReplacesTheSameIdAndKeepsANullPreset() = runTest {
        repo.upsert(paprika)
        repo.upsert(paprika.copy(name = "Sweet paprika"))
        val stored = repo.getAll().single()
        assertEquals("Sweet paprika", stored.name)
        assertEquals(null, stored.presetId)
    }

    @Test
    fun upsertAllAndClearAll() = runTest {
        repo.upsertAll(listOf(salt, paprika))
        assertEquals(2, repo.getAll().size)
        repo.clearAll()
        assertTrue(repo.getAll().isEmpty())
        assertTrue(repo.getStaplesFlow().first().isEmpty())
    }
}
