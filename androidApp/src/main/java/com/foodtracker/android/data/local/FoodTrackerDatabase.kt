package com.foodtracker.android.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PantryItemEntity::class, ShoppingListEntryEntity::class],
    version = 2,
    exportSchema = false
)
abstract class FoodTrackerDatabase : RoomDatabase() {
    abstract fun pantryDao(): PantryDao
    abstract fun shoppingListDao(): ShoppingListDao

    companion object {
        const val DB_NAME = "foodtracker.db"

        // Adds the Essential flag to existing pantry items and the new shopping list
        // table in place, so upgrading the app does not wipe a user's existing pantry.
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pantry_items ADD COLUMN essential INTEGER NOT NULL DEFAULT 1")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS shopping_list_entries (
                        id TEXT NOT NULL PRIMARY KEY,
                        productName TEXT NOT NULL,
                        barcode TEXT,
                        addedDateEpochDays INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
