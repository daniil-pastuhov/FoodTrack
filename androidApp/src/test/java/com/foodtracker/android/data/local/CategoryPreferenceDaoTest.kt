package com.foodtracker.android.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.foodtracker.model.CategoryPreference
import com.foodtracker.model.UNCATEGORIZED_ORIGIN
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
class CategoryPreferenceDaoTest {

    private lateinit var db: FoodTrackerDatabase
    private lateinit var repo: RoomCategoryPreferenceRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            FoodTrackerDatabase::class.java
        ).allowMainThreadQueries().build()
        repo = RoomCategoryPreferenceRepository(db.categoryPreferenceDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testRegisterIfAbsentCreatesEnabledDefaultRow() = runTest {
        assertEquals(emptyList<CategoryPreference>(), repo.getPreferencesFlow().first())

        repo.registerIfAbsent("en:dairy", 0.0)

        val registered = repo.getPreferencesFlow().first()
        assertEquals(1, registered.size)
        assertEquals(CategoryPreference(originName = "en:dairy", displayName = "en:dairy", sortOrder = 0.0, enabled = true), registered.first())
    }

    @Test
    fun testRegisterIfAbsentDoesNotOverwriteAnExistingRow() = runTest {
        repo.registerIfAbsent("en:dairy", 0.0)
        repo.updateEntry(CategoryPreference(originName = "en:dairy", displayName = "Dairy", sortOrder = 5.0, enabled = false))

        // Re-registering (as if the same product were scanned again) must not clobber the
        // user's rename/reorder/disable.
        repo.registerIfAbsent("en:dairy", 99.0)

        val preferences = repo.getPreferencesFlow().first()
        assertEquals(1, preferences.size)
        assertEquals("Dairy", preferences.first().displayName)
        assertEquals(5.0, preferences.first().sortOrder, 0.0)
        assertEquals(false, preferences.first().enabled)
    }

    @Test
    fun testUpdateEntryPersistsRenameReorderAndDisable() = runTest {
        repo.registerIfAbsent("en:dairy", 0.0)
        repo.updateEntry(CategoryPreference(originName = "en:dairy", displayName = "Dairy", sortOrder = 3.0, enabled = false))

        val updated = repo.getPreferencesFlow().first().first()
        assertEquals("Dairy", updated.displayName)
        assertEquals(3.0, updated.sortOrder, 0.0)
        assertEquals(false, updated.enabled)
    }

    @Test
    fun testGetAllFlowOrdersBySortOrderNotDisplayName() = runTest {
        repo.updateEntry(CategoryPreference(originName = "z-origin", displayName = "Aaa Last", sortOrder = 1.0))
        repo.updateEntry(CategoryPreference(originName = "a-origin", displayName = "Zzz First", sortOrder = 0.0))

        val ordered = repo.getPreferencesFlow().first()
        assertEquals(listOf("a-origin", "z-origin"), ordered.map { it.originName })
    }

    @Test
    fun testGetMaxSortOrder() = runTest {
        assertNull(repo.getMaxSortOrder())

        repo.updateEntry(CategoryPreference(originName = "a", displayName = "A", sortOrder = 2.0))
        repo.updateEntry(CategoryPreference(originName = "b", displayName = "B", sortOrder = 7.0))

        assertEquals(7.0, repo.getMaxSortOrder())
    }

    @Test
    fun testGetMaxSortOrderIgnoresTheUncategorizedSentinel() = runTest {
        // The "Other" bucket is parked at Double.MAX_VALUE so it sorts last. Counting it would
        // make the next registered category MAX_VALUE + 1.0 == MAX_VALUE, i.e. every category
        // sharing one sort order.
        repo.registerIfAbsent(UNCATEGORIZED_ORIGIN, Double.MAX_VALUE, "Other")
        repo.updateEntry(CategoryPreference(originName = "a", displayName = "A", sortOrder = 2.0))

        assertEquals(2.0, repo.getMaxSortOrder())
    }

    @Test
    fun testGetMaxSortOrderIsNullWhenOnlyTheSentinelExists() = runTest {
        repo.registerIfAbsent(UNCATEGORIZED_ORIGIN, Double.MAX_VALUE, "Other")

        assertNull(repo.getMaxSortOrder())
    }
}
