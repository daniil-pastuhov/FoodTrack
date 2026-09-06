package com.foodtracker.android.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PantryItemEntity::class,
        ShoppingListEntryEntity::class,
        ProductNameEntity::class,
        LocalProductEntity::class,
        CategoryPreferenceEntity::class
    ],
    version = 8,
    exportSchema = false
)
abstract class FoodTrackerDatabase : RoomDatabase() {
    abstract fun pantryDao(): PantryDao
    abstract fun shoppingListDao(): ShoppingListDao
    abstract fun productNameDao(): ProductNameDao
    abstract fun localProductDao(): LocalProductDao
    abstract fun categoryPreferenceDao(): CategoryPreferenceDao

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

        // Adds the shopping list's autosuggest history — a standalone table (rather than
        // reusing shopping_list_entries) so a name is still suggested after its entry is
        // checked off or deleted.
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS known_product_names (
                        normalizedName TEXT NOT NULL PRIMARY KEY,
                        displayName TEXT NOT NULL,
                        lastUsedAtEpochMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        // Adds manual drag-reorder support: a sortOrder column on shopping list entries (and,
        // separately, on known product names so a manually-dragged position is remembered per
        // product and restored the next time that name re-enters the list). Existing rows are
        // backfilled with a rank that reproduces their current addedDateEpochDays DESC order,
        // so upgrading doesn't visually shuffle anyone's list.
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shopping_list_entries ADD COLUMN sortOrder REAL NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    UPDATE shopping_list_entries SET sortOrder = (
                        SELECT COUNT(*) FROM shopping_list_entries AS t2
                        WHERE t2.addedDateEpochDays > shopping_list_entries.addedDateEpochDays
                           OR (t2.addedDateEpochDays = shopping_list_entries.addedDateEpochDays AND t2.rowid > shopping_list_entries.rowid)
                    )
                    """.trimIndent()
                )
                db.execSQL("ALTER TABLE known_product_names ADD COLUMN sortOrder REAL DEFAULT NULL")
            }
        }

        // Backfills the shopping list's autosuggest with every product name already sitting in
        // the pantry, so existing inventory suggests itself immediately instead of only after
        // being re-scanned/re-saved (which is the only other path that calls recordName).
        // INSERT OR IGNORE leaves any name already known (and its sortOrder) untouched; the
        // GROUP BY collapses duplicate pantry item names to one row, and SQLite's documented
        // bare-column/MAX() aggregate behavior picks the name/date from the same row that has
        // the max addedDateEpochDays in each group.
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO known_product_names (normalizedName, displayName, lastUsedAtEpochMillis)
                    SELECT LOWER(TRIM(name)), TRIM(name), MAX(addedDateEpochDays) * 86400000
                    FROM pantry_items
                    GROUP BY LOWER(TRIM(name))
                    """.trimIndent()
                )
            }
        }

        // Adds the local product cache: barcodes scanned/saved before (whether OFF had a
        // record or the user filled one in by hand) are looked up here first on a future scan,
        // working even for "custom" products the API has never heard of. Backfilled from every
        // pantry item that already has a barcode, so already-owned items are recognized
        // immediately rather than only after being re-scanned. INSERT OR IGNORE keeps the first
        // (lowest rowid) row per barcode when duplicates exist.
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS local_products (
                        barcode TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        brand TEXT,
                        imageUrl TEXT,
                        category TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO local_products (barcode, name, brand, imageUrl, category)
                    SELECT barcode, name, brand, imageUrl, category
                    FROM pantry_items
                    WHERE barcode IS NOT NULL
                    """.trimIndent()
                )
            }
        }

        // Adds per-category user preferences: a category can be renamed (its raw scanned value
        // is kept as originName so a rename applies retroactively, including to future scans of
        // the same product), reordered, or disabled (hidden everywhere without deleting the
        // category from the products that have it). Backfilled from every distinct category
        // already present on a pantry item or shopping list entry, ranked alphabetically, so
        // existing categories show up in Settings immediately rather than only after being
        // re-scanned.
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS category_preferences (
                        originName TEXT NOT NULL PRIMARY KEY,
                        displayName TEXT NOT NULL,
                        sortOrder REAL NOT NULL DEFAULT 0,
                        enabled INTEGER NOT NULL DEFAULT 1
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO category_preferences (originName, displayName, sortOrder, enabled)
                    SELECT combined.category, combined.category,
                        (SELECT COUNT(*) FROM (
                            SELECT category FROM pantry_items WHERE category IS NOT NULL
                            UNION
                            SELECT category FROM shopping_list_entries WHERE category IS NOT NULL
                        ) other WHERE other.category < combined.category),
                        1
                    FROM (
                        SELECT category FROM pantry_items WHERE category IS NOT NULL
                        UNION
                        SELECT category FROM shopping_list_entries WHERE category IS NOT NULL
                    ) combined
                    """.trimIndent()
                )
            }
        }
    }
}
