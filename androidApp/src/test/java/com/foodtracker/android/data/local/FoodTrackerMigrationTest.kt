package com.foodtracker.android.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Proves MIGRATION_1_2 upgrades a real version-1 database in place — the shape an existing
 * user's device actually has — without dropping the `pantry_items` table, so app updates
 * don't wipe pantry data (see AppModule.kt: this replaced a destructive-fallback migration).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FoodTrackerMigrationTest {

    private lateinit var dbFile: File

    @After
    fun tearDown() {
        dbFile.delete()
    }

    @Test
    fun migrate1To2PreservesExistingPantryItemsAndAddsShoppingList() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        dbFile = context.getDatabasePath("migration-test.db")
        dbFile.delete()

        // Build the version-1 schema by hand (id/name/.../notes, no `essential` column,
        // no shopping_list_entries table) and seed one row, exactly as a real v1 install would have.
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE pantry_items (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    brand TEXT,
                    barcode TEXT,
                    imageUrl TEXT,
                    expiryDateEpochDays INTEGER NOT NULL,
                    addedDateEpochDays INTEGER NOT NULL,
                    storageLocation TEXT NOT NULL,
                    category TEXT,
                    status TEXT NOT NULL,
                    notes TEXT
                )
                """.trimIndent()
            )
            db.insert(
                "pantry_items",
                null,
                ContentValues().apply {
                    put("id", "existing-item")
                    put("name", "Cheddar Cheese")
                    put("brand", null as String?)
                    put("barcode", null as String?)
                    put("imageUrl", null as String?)
                    put("expiryDateEpochDays", 20000)
                    put("addedDateEpochDays", 19990)
                    put("storageLocation", "Fridge")
                    put("category", null as String?)
                    put("status", "ACTIVE")
                    put("notes", null as String?)
                }
            )
            db.version = 1
        }

        val db = Room.databaseBuilder(context, FoodTrackerDatabase::class.java, dbFile.absolutePath)
            .addMigrations(FoodTrackerDatabase.MIGRATION_1_2)
            .build()

        val migratedItem = db.pantryDao().getById("existing-item")
        assertNotNull("existing pantry item must survive the migration", migratedItem)
        assertEquals("Cheddar Cheese", migratedItem!!.name)
        assertTrue("essential must default to true for pre-existing rows", migratedItem.essential)

        val shoppingList = db.shoppingListDao().getAllFlow().first()
        assertEquals(0, shoppingList.size)

        db.close()
    }
}
