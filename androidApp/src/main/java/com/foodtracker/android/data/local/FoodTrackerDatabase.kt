package com.foodtracker.android.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.foodtracker.model.DEFAULT_SHOPPING_SECTION_ID

@Database(
    entities = [
        PantryItemEntity::class,
        ShoppingListEntryEntity::class,
        ProductNameEntity::class,
        LocalProductEntity::class,
        CategoryPreferenceEntity::class,
        ShoppingSectionEntity::class
    ],
    version = 13,
    exportSchema = false
)
abstract class FoodTrackerDatabase : RoomDatabase() {
    abstract fun pantryDao(): PantryDao
    abstract fun shoppingListDao(): ShoppingListDao
    abstract fun productNameDao(): ProductNameDao
    abstract fun localProductDao(): LocalProductDao
    abstract fun categoryPreferenceDao(): CategoryPreferenceDao
    abstract fun shoppingSectionDao(): ShoppingSectionDao

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

        // Adds smart default expiry: a per-barcode shelf life learned from what the user chose
        // last time, and a per-category fallback configured in Settings. Both feed
        // `ShelfLife.suggest`, which pre-fills the expiry date on a scanned item.
        //
        // The per-barcode column is backfilled from the most recently added pantry item
        // carrying that barcode, so an existing user gets suggestions for products they already
        // own immediately rather than only after adding each one again. Spans that
        // `ShelfLife.learnableShelfLife` would reject (negative, or longer than
        // MAX_LEARNABLE_DAYS = 10 * 365) are excluded by the WHERE clause, which leaves the
        // column null for that barcode rather than seeding a value the app would refuse to
        // learn itself.
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_products ADD COLUMN shelfLifeDays INTEGER")
                db.execSQL("ALTER TABLE category_preferences ADD COLUMN defaultShelfLifeDays INTEGER")
                db.execSQL(
                    """
                    UPDATE local_products SET shelfLifeDays = (
                        SELECT (p.expiryDateEpochDays - p.addedDateEpochDays)
                        FROM pantry_items p
                        WHERE p.barcode = local_products.barcode
                          AND p.expiryDateEpochDays >= p.addedDateEpochDays
                          AND (p.expiryDateEpochDays - p.addedDateEpochDays) <= 3650
                        ORDER BY p.addedDateEpochDays DESC, p.rowid DESC
                        LIMIT 1
                    )
                    """.trimIndent()
                )
            }
        }

        // Caches the nutritional detail Open Food Facts already returned but the app discarded:
        // Nutri-Score, NOVA group, allergens and ingredients. Cached rather than fetched on
        // demand so an allergen warning doesn't depend on the network being up at the moment it
        // matters. Nothing to backfill - these are columns no previous version ever had data
        // for, and they fill in as products are re-scanned.
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_products ADD COLUMN nutriScore TEXT")
                db.execSQL("ALTER TABLE local_products ADD COLUMN novaGroup INTEGER")
                db.execSQL("ALTER TABLE local_products ADD COLUMN allergens TEXT")
                db.execSQL("ALTER TABLE local_products ADD COLUMN ingredients TEXT")
            }
        }

        // Adds the per-item "remind me" switch. Defaults to on, so every existing item keeps
        // the reminder it was already getting.
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pantry_items ADD COLUMN notificationsEnabled INTEGER NOT NULL DEFAULT 1")
            }
        }

        // Remembers which storage location each cached product was last put in, so a re-scan
        // pre-selects it. Nothing to backfill: until a product is added again, the scan flow
        // falls back to a pantry item carrying the same barcode (see
        // PantryStateHolder.storageLocationForNewItem).
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_products ADD COLUMN storageLocation TEXT")
            }
        }

        // Replaces the shopping list's category grouping with user-made sections. Existing users
        // keep the grouping they see today: every category that currently groups shopping
        // entries becomes a section of the same (displayed) name, in the same order, and the
        // entries move into it. The resolution mirrors `resolveCategoryDisplayName` - a disabled
        // category, or no category, is the "Other" bucket, which is the default section now; a
        // category with no preference row shows (and becomes a section) under its raw value.
        //
        // Each product is also remembered in its new section, so re-adding it later lands it
        // there just like a product the user moved by hand.
        //
        // Ids are derived from the name (`migrated_<hex>`) rather than numbered, which keeps the
        // statement free of window functions older SQLite versions on the device don't have.
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS shopping_sections (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        sortOrder REAL NOT NULL,
                        collapsed INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO shopping_sections (id, name, sortOrder, collapsed) " +
                        "VALUES ('$DEFAULT_SHOPPING_SECTION_ID', '', ${-Double.MAX_VALUE}, 0)"
                )
                db.execSQL("ALTER TABLE shopping_list_entries ADD COLUMN sectionId TEXT")
                db.execSQL("ALTER TABLE known_product_names ADD COLUMN shoppingSectionId TEXT")

                db.execSQL(
                    """
                    CREATE TEMP TABLE migrated_section_map AS
                    SELECT e.id AS entryId,
                        CASE
                            WHEN p.originName IS NULL THEN e.category
                            WHEN p.enabled = 1 THEN p.displayName
                            ELSE NULL
                        END AS sectionName,
                        CASE
                            WHEN p.originName IS NULL THEN ${Double.MAX_VALUE}
                            ELSE p.sortOrder
                        END AS sectionOrder
                    FROM shopping_list_entries e
                    LEFT JOIN category_preferences p ON p.originName = e.category
                    WHERE e.category IS NOT NULL AND e.category != ''
                    """.trimIndent()
                )
                db.execSQL("DELETE FROM migrated_section_map WHERE sectionName IS NULL OR TRIM(sectionName) = ''")
                db.execSQL(
                    """
                    CREATE TEMP TABLE migrated_section_groups AS
                    SELECT sectionName AS name, MIN(sectionOrder) AS sectionOrder
                    FROM migrated_section_map
                    GROUP BY sectionName
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO shopping_sections (id, name, sortOrder, collapsed)
                    SELECT 'migrated_' || hex(g.name), g.name,
                        (SELECT COUNT(*) FROM migrated_section_groups o
                            WHERE o.sectionOrder < g.sectionOrder
                               OR (o.sectionOrder = g.sectionOrder AND o.name < g.name)),
                        0
                    FROM migrated_section_groups g
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE shopping_list_entries SET sectionId = (
                        SELECT 'migrated_' || hex(m.sectionName)
                        FROM migrated_section_map m
                        WHERE m.entryId = shopping_list_entries.id
                    )
                    """.trimIndent()
                )
                // Matched in Kotlin rather than with SQL LOWER(): normalizedName was written by
                // Kotlin's Unicode-aware lowercase(), and SQLite's LOWER() folds ASCII only, so
                // "Молоко" or "Ål" would never match their stored "молоко" / "ål".
                db.query("SELECT productName, sectionId FROM shopping_list_entries WHERE sectionId IS NOT NULL").use { cursor ->
                    while (cursor.moveToNext()) {
                        db.execSQL(
                            "UPDATE known_product_names SET shoppingSectionId = ? " +
                                "WHERE normalizedName = ? AND shoppingSectionId IS NULL",
                            arrayOf<Any?>(cursor.getString(1), cursor.getString(0).trim().lowercase())
                        )
                    }
                }
                db.execSQL("DROP TABLE migrated_section_map")
                db.execSQL("DROP TABLE migrated_section_groups")
            }
        }
    }
}
