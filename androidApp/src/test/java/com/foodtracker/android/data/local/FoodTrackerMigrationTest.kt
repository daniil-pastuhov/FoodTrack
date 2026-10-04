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
import org.junit.Assert.assertNull
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
            .addMigrations(
                FoodTrackerDatabase.MIGRATION_1_2,
                FoodTrackerDatabase.MIGRATION_2_3,
                FoodTrackerDatabase.MIGRATION_3_4,
                FoodTrackerDatabase.MIGRATION_4_5,
                FoodTrackerDatabase.MIGRATION_5_6,
                FoodTrackerDatabase.MIGRATION_6_7,
                FoodTrackerDatabase.MIGRATION_7_8,
                FoodTrackerDatabase.MIGRATION_8_9,
                FoodTrackerDatabase.MIGRATION_9_10,
                FoodTrackerDatabase.MIGRATION_10_11
            )
            .build()

        val migratedItem = db.pantryDao().getById("existing-item")
        assertNotNull("existing pantry item must survive the migration", migratedItem)
        assertEquals("Cheddar Cheese", migratedItem!!.name)
        assertTrue("essential must default to true for pre-existing rows", migratedItem.essential)
        assertTrue(
            "notifications must stay on for pre-existing rows",
            migratedItem.notificationsEnabled
        )

        val shoppingList = db.shoppingListDao().getAllFlow().first()
        assertEquals(0, shoppingList.size)

        db.close()
    }

    @Test
    fun migrate2To3DropsStatusAndBackfillsQuantity() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        dbFile = context.getDatabasePath("migration-test-2-3.db")
        dbFile.delete()

        // Build the version-2 schema by hand (still has the never-used `status` column,
        // no `quantity`/shopping-list `category`) and seed one row, as a real v2 install would have.
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
                    notes TEXT,
                    essential INTEGER NOT NULL DEFAULT 1
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
                    put("essential", 1)
                }
            )
            db.execSQL(
                """
                CREATE TABLE shopping_list_entries (
                    id TEXT NOT NULL PRIMARY KEY,
                    productName TEXT NOT NULL,
                    barcode TEXT,
                    addedDateEpochDays INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.version = 2
        }

        val db = Room.databaseBuilder(context, FoodTrackerDatabase::class.java, dbFile.absolutePath)
            .addMigrations(
                FoodTrackerDatabase.MIGRATION_2_3,
                FoodTrackerDatabase.MIGRATION_3_4,
                FoodTrackerDatabase.MIGRATION_4_5,
                FoodTrackerDatabase.MIGRATION_5_6,
                FoodTrackerDatabase.MIGRATION_6_7,
                FoodTrackerDatabase.MIGRATION_7_8,
                FoodTrackerDatabase.MIGRATION_8_9,
                FoodTrackerDatabase.MIGRATION_9_10,
                FoodTrackerDatabase.MIGRATION_10_11
            )
            .build()

        val migratedItem = db.pantryDao().getById("existing-item")
        assertNotNull("existing pantry item must survive the migration", migratedItem)
        assertEquals("Cheddar Cheese", migratedItem!!.name)
        assertEquals("quantity must default to 1 for pre-existing rows", 1, migratedItem.quantity)

        db.close()
    }

    @Test
    fun migrate4To5AddsSortOrderAndBackfillsExistingOrder() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        dbFile = context.getDatabasePath("migration-test-4-5.db")
        dbFile.delete()

        // Build the version-4 schema by hand (no sortOrder column on either table) and seed
        // three shopping list rows out of insertion order, exactly as a real v4 install would
        // have accumulated them.
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
                    quantity INTEGER NOT NULL DEFAULT 1,
                    notes TEXT,
                    essential INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE shopping_list_entries (
                    id TEXT NOT NULL PRIMARY KEY,
                    productName TEXT NOT NULL,
                    barcode TEXT,
                    category TEXT,
                    quantity INTEGER NOT NULL DEFAULT 1,
                    addedDateEpochDays INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE known_product_names (
                    normalizedName TEXT NOT NULL PRIMARY KEY,
                    displayName TEXT NOT NULL,
                    lastUsedAtEpochMillis INTEGER NOT NULL
                )
                """.trimIndent()
            )
            // Inserted oldest-first, so pre-migration order (addedDateEpochDays DESC) is
            // Milk (20002), Eggs (20001), Bread (20000) — the reverse of insertion order.
            listOf(
                Triple("bread", "Bread", 20000),
                Triple("eggs", "Eggs", 20001),
                Triple("milk", "Milk", 20002)
            ).forEach { (id, name, addedDateEpochDays) ->
                db.insert(
                    "shopping_list_entries",
                    null,
                    ContentValues().apply {
                        put("id", id)
                        put("productName", name)
                        put("barcode", null as String?)
                        put("category", null as String?)
                        put("quantity", 1)
                        put("addedDateEpochDays", addedDateEpochDays)
                    }
                )
            }
            db.version = 4
        }

        val db = Room.databaseBuilder(context, FoodTrackerDatabase::class.java, dbFile.absolutePath)
            .addMigrations(
                FoodTrackerDatabase.MIGRATION_4_5,
                FoodTrackerDatabase.MIGRATION_5_6,
                FoodTrackerDatabase.MIGRATION_6_7,
                FoodTrackerDatabase.MIGRATION_7_8,
                FoodTrackerDatabase.MIGRATION_8_9,
                FoodTrackerDatabase.MIGRATION_9_10,
                FoodTrackerDatabase.MIGRATION_10_11
            )
            .build()

        val migrated = db.shoppingListDao().getAllFlow().first()
        assertEquals(
            "sortOrder must reproduce the pre-migration addedDateEpochDays DESC order",
            listOf("milk", "eggs", "bread"),
            migrated.map { it.id }
        )

        db.close()
    }

    @Test
    fun migrate5To6BackfillsKnownProductNamesFromInventory() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        dbFile = context.getDatabasePath("migration-test-5-6.db")
        dbFile.delete()

        // Build the version-5 schema by hand and seed pantry items — including a duplicate
        // name (two "Milk" rows) and a name that's already known from prior shopping-list
        // activity, with an existing sortOrder that must survive untouched.
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
                    quantity INTEGER NOT NULL DEFAULT 1,
                    notes TEXT,
                    essential INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE shopping_list_entries (
                    id TEXT NOT NULL PRIMARY KEY,
                    productName TEXT NOT NULL,
                    barcode TEXT,
                    category TEXT,
                    quantity INTEGER NOT NULL DEFAULT 1,
                    addedDateEpochDays INTEGER NOT NULL,
                    sortOrder REAL NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE known_product_names (
                    normalizedName TEXT NOT NULL PRIMARY KEY,
                    displayName TEXT NOT NULL,
                    lastUsedAtEpochMillis INTEGER NOT NULL,
                    sortOrder REAL DEFAULT NULL
                )
                """.trimIndent()
            )
            listOf(
                Triple("item-1", "Milk", 20000),
                Triple("item-2", "Milk", 20005), // duplicate name, later date should win
                Triple("item-3", "Eggs", 20001)
            ).forEach { (id, name, addedDateEpochDays) ->
                db.insert(
                    "pantry_items",
                    null,
                    ContentValues().apply {
                        put("id", id)
                        put("name", name)
                        put("brand", null as String?)
                        put("barcode", null as String?)
                        put("imageUrl", null as String?)
                        put("expiryDateEpochDays", 20100)
                        put("addedDateEpochDays", addedDateEpochDays)
                        put("storageLocation", "Fridge")
                        put("category", null as String?)
                        put("quantity", 1)
                        put("notes", null as String?)
                        put("essential", 1)
                    }
                )
            }
            // "eggs" is already known with a manually-dragged sortOrder — the backfill must
            // leave it alone rather than clobbering it.
            db.insert(
                "known_product_names",
                null,
                ContentValues().apply {
                    put("normalizedName", "eggs")
                    put("displayName", "Eggs")
                    put("lastUsedAtEpochMillis", 123L)
                    put("sortOrder", 7.0)
                }
            )
            db.version = 5
        }

        val db = Room.databaseBuilder(context, FoodTrackerDatabase::class.java, dbFile.absolutePath)
            .addMigrations(
                FoodTrackerDatabase.MIGRATION_5_6,
                FoodTrackerDatabase.MIGRATION_6_7,
                FoodTrackerDatabase.MIGRATION_7_8,
                FoodTrackerDatabase.MIGRATION_8_9,
                FoodTrackerDatabase.MIGRATION_9_10,
                FoodTrackerDatabase.MIGRATION_10_11
            )
            .build()

        val knownNames = db.productNameDao().getRecentNamesFlow().first()
        assertTrue("backfilled inventory name must be suggested", knownNames.contains("Milk"))

        assertEquals(
            "pre-existing known name's sortOrder must survive the backfill untouched",
            7.0,
            db.productNameDao().getSortOrder("eggs")
        )

        db.close()
    }

    @Test
    fun migrate6To7BackfillsLocalProductsFromInventory() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        dbFile = context.getDatabasePath("migration-test-6-7.db")
        dbFile.delete()

        // Build the version-6 schema by hand (no local_products table yet) and seed pantry
        // items, including one with no barcode (must be skipped) and a duplicate barcode
        // (INSERT OR IGNORE keeps the first row it sees).
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
                    quantity INTEGER NOT NULL DEFAULT 1,
                    notes TEXT,
                    essential INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE shopping_list_entries (
                    id TEXT NOT NULL PRIMARY KEY,
                    productName TEXT NOT NULL,
                    barcode TEXT,
                    category TEXT,
                    quantity INTEGER NOT NULL DEFAULT 1,
                    addedDateEpochDays INTEGER NOT NULL,
                    sortOrder REAL NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE known_product_names (
                    normalizedName TEXT NOT NULL PRIMARY KEY,
                    displayName TEXT NOT NULL,
                    lastUsedAtEpochMillis INTEGER NOT NULL,
                    sortOrder REAL DEFAULT NULL
                )
                """.trimIndent()
            )
            listOf(
                Triple("item-1", "Custom Snack", "0000001111111"),
                Triple("item-2", "No Barcode Item", null),
                Triple("item-3", "Duplicate Barcode Item", "0000001111111")
            ).forEach { (id, name, barcode) ->
                db.insert(
                    "pantry_items",
                    null,
                    ContentValues().apply {
                        put("id", id)
                        put("name", name)
                        put("brand", null as String?)
                        put("barcode", barcode)
                        put("imageUrl", null as String?)
                        put("expiryDateEpochDays", 20100)
                        put("addedDateEpochDays", 20000)
                        put("storageLocation", "Fridge")
                        put("category", null as String?)
                        put("quantity", 1)
                        put("notes", null as String?)
                        put("essential", 1)
                    }
                )
            }
            db.version = 6
        }

        val db = Room.databaseBuilder(context, FoodTrackerDatabase::class.java, dbFile.absolutePath)
            .addMigrations(
                FoodTrackerDatabase.MIGRATION_6_7,
                FoodTrackerDatabase.MIGRATION_7_8,
                FoodTrackerDatabase.MIGRATION_8_9,
                FoodTrackerDatabase.MIGRATION_9_10,
                FoodTrackerDatabase.MIGRATION_10_11
            )
            .build()

        val cached = db.localProductDao().getByBarcode("0000001111111")
        assertNotNull("pantry item with a barcode must be backfilled into the local cache", cached)
        assertEquals("Custom Snack", cached!!.name)

        db.close()
    }

    @Test
    fun migrate8To9BackfillsShelfLifeFromInventory() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        dbFile = context.getDatabasePath("migration-test-8-9.db")
        dbFile.delete()

        // Version-8 schema by hand, seeded so each barcode exercises one branch of the
        // backfill's WHERE/ORDER BY: a plain one, one whose newest row is the value that must
        // win, one whose only span is negative, and one whose only span is implausibly long.
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
                    quantity INTEGER NOT NULL DEFAULT 1,
                    notes TEXT,
                    essential INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE shopping_list_entries (
                    id TEXT NOT NULL PRIMARY KEY,
                    productName TEXT NOT NULL,
                    barcode TEXT,
                    category TEXT,
                    quantity INTEGER NOT NULL DEFAULT 1,
                    addedDateEpochDays INTEGER NOT NULL,
                    sortOrder REAL NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE known_product_names (
                    normalizedName TEXT NOT NULL PRIMARY KEY,
                    displayName TEXT NOT NULL,
                    lastUsedAtEpochMillis INTEGER NOT NULL,
                    sortOrder REAL DEFAULT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE local_products (
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
                CREATE TABLE category_preferences (
                    originName TEXT NOT NULL PRIMARY KEY,
                    displayName TEXT NOT NULL,
                    sortOrder REAL NOT NULL DEFAULT 0,
                    enabled INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent()
            )

            fun seedProduct(barcode: String) = db.insert(
                "local_products",
                null,
                ContentValues().apply {
                    put("barcode", barcode)
                    put("name", "Product $barcode")
                    put("brand", "Brand")
                    put("imageUrl", "https://example.invalid/$barcode.png")
                    put("category", "Dairy")
                }
            )

            fun seedItem(id: String, barcode: String, addedDay: Int, expiryDay: Int) = db.insert(
                "pantry_items",
                null,
                ContentValues().apply {
                    put("id", id)
                    put("name", "Item $id")
                    put("brand", null as String?)
                    put("barcode", barcode)
                    put("imageUrl", null as String?)
                    put("expiryDateEpochDays", expiryDay)
                    put("addedDateEpochDays", addedDay)
                    put("storageLocation", "Fridge")
                    put("category", null as String?)
                    put("quantity", 1)
                    put("notes", null as String?)
                    put("essential", 1)
                }
            )

            listOf("1111", "2222", "3333", "4444", "5555").forEach(::seedProduct)
            seedItem("a", "1111", addedDay = 20000, expiryDay = 20014)          // 14 days
            seedItem("b", "2222", addedDay = 20000, expiryDay = 20030)          // older, 30 days
            seedItem("c", "2222", addedDay = 20050, expiryDay = 20055)          // newest, 5 days
            seedItem("d", "3333", addedDay = 20000, expiryDay = 19990)          // added expired
            seedItem("e", "4444", addedDay = 20000, expiryDay = 20000 + 4000)   // implausible
            // 5555 has no pantry item at all.
            db.version = 8
        }

        val db = Room.databaseBuilder(context, FoodTrackerDatabase::class.java, dbFile.absolutePath)
            .addMigrations(FoodTrackerDatabase.MIGRATION_8_9, FoodTrackerDatabase.MIGRATION_9_10, FoodTrackerDatabase.MIGRATION_10_11)
            .build()
        val dao = db.localProductDao()

        assertEquals(
            "a single past purchase must seed that barcode's shelf life",
            14,
            dao.getShelfLifeDays("1111")
        )
        assertEquals(
            "the most recently added purchase wins over older ones",
            5,
            dao.getShelfLifeDays("2222")
        )
        assertNull(
            "an item added already expired says nothing about a fresh one's shelf life",
            dao.getShelfLifeDays("3333")
        )
        assertNull(
            "an implausibly long span must not be seeded - the app would refuse to learn it",
            dao.getShelfLifeDays("4444")
        )
        assertNull("a barcode never added has nothing to learn from", dao.getShelfLifeDays("5555"))

        // The backfill must not disturb the cached product details it writes alongside.
        val untouched = dao.getByBarcode("1111")
        assertNotNull(untouched)
        assertEquals("Product 1111", untouched!!.name)
        assertEquals("Brand", untouched.brand)

        db.close()
    }
}
