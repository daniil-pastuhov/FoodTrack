package com.foodtracker.android.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PantryItemEntity::class, ShoppingListEntryEntity::class],
    version = 3,
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

        // Drops the never-set `status` column (SQLite can't ALTER TABLE...DROP COLUMN
        // reliably across all device SQLite versions, so the table is recreated) and adds
        // `quantity` to pantry items and `category`/`quantity` to shopping list entries.
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE pantry_items_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        brand TEXT,
                        barcode TEXT,
                        imageUrl TEXT,
                        expiryDateEpochDays INTEGER NOT NULL,
                        addedDateEpochDays INTEGER NOT NULL,
                        storageLocation TEXT NOT NULL,
                        category TEXT,
                        quantity INTEGER NOT NULL DEFAULT 1,
                        notes TEXT,
                        essential INTEGER NOT NULL DEFAULT 1
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO pantry_items_new
                        (id, name, brand, barcode, imageUrl, expiryDateEpochDays, addedDateEpochDays,
                         storageLocation, category, quantity, notes, essential)
                    SELECT
                        id, name, brand, barcode, imageUrl, expiryDateEpochDays, addedDateEpochDays,
                        storageLocation, category, 1, notes, essential
                    FROM pantry_items
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE pantry_items")
                db.execSQL("ALTER TABLE pantry_items_new RENAME TO pantry_items")

                db.execSQL("ALTER TABLE shopping_list_entries ADD COLUMN category TEXT")
                db.execSQL("ALTER TABLE shopping_list_entries ADD COLUMN quantity INTEGER NOT NULL DEFAULT 1")
            }
        }
    }
}
