package com.foodtracker.perf

import com.foodtracker.model.CategoryPreference
import com.foodtracker.model.PantryItem
import com.foodtracker.model.ShoppingListEntry
import com.foodtracker.util.DateUtils
import kotlinx.datetime.LocalDate
import kotlin.random.Random

/**
 * Deterministic sample data for performance measurement, shared by the on-device benchmark
 * seeder (`androidApp/src/benchmark/.../PerfSeedReceiver`) and the JVM microbenchmarks
 * (`androidApp/src/test/.../perf/`).
 *
 * Everything is generated from a fixed [SEED], so a "before" run and an "after" run compare
 * identical inputs — without that, list length and the category/expiry mix alone would move the
 * numbers more than the code changes do. Nothing here ships in a debug or release build; it
 * exists in `shared` only so both harnesses can't drift apart.
 *
 * Dates are the one exception to determinism: they are generated relative to [DateUtils.today]
 * so that the expired/expiring-soon/fresh split stays realistic no matter when the benchmark
 * runs. The *offsets* are fixed, so the split itself is stable.
 */
object PerfDataset {

    const val SEED = 20260907

    /** Category origin values, deliberately more than a real pantry has, so the O(items ×
     *  preferences) lookup in category resolution is actually visible in the measurement. */
    const val CATEGORY_COUNT = 12

    private val STORAGE_LOCATIONS = listOf("Fridge", "Freezer", "Pantry")

    private val NOUNS = listOf(
        "Milk", "Yoghurt", "Cheddar", "Sourdough", "Rice", "Pasta", "Tomatoes", "Basil",
        "Chicken", "Salmon", "Eggs", "Butter", "Olive Oil", "Coffee", "Oat Milk", "Hummus",
        "Carrots", "Spinach", "Blueberries", "Almonds", "Lentils", "Chickpeas", "Honey", "Jam"
    )

    private val QUALIFIERS = listOf(
        "Organic", "Free Range", "Extra Mature", "Low Fat", "Wholegrain", "Smoked",
        "Fresh", "Frozen", "Reduced Salt", "Single Origin"
    )

    private val BRANDS = listOf(
        "Tine", "Q-Meieriene", "Prior", "First Price", "Eldorado", "Rema 1000", null
    )

    fun categoryPreferences(count: Int = CATEGORY_COUNT): List<CategoryPreference> =
        (0 until count).map { index ->
            CategoryPreference(
                originName = "perf-origin-$index",
                displayName = "Perf Category $index",
                sortOrder = index.toDouble(),
                // A couple of disabled categories, so the "folds into Other" branch of
                // resolveCategoryDisplayName is exercised rather than always taking the
                // happy path.
                enabled = index % 5 != 4
            )
        }

    /**
     * [count] pantry items with a realistic expiry spread: roughly 15% already expired, 15%
     * expiring within the default 3-day window, and the rest fresh — so the three status
     * filters and the three dashboard counters all have meaningful work to do.
     */
    fun pantryItems(count: Int, today: LocalDate = DateUtils.today()): List<PantryItem> {
        val random = Random(SEED)
        val categories = categoryPreferences()
        val todayEpochDay = today.toEpochDays().toInt()

        return (0 until count).map { index ->
            val dayOffset = when (index % 20) {
                0, 1, 2 -> -random.nextInt(1, 60)       // expired
                3, 4, 5 -> random.nextInt(0, 4)         // expiring today / soon
                else -> random.nextInt(4, 240)          // fresh
            }
            val name = buildString {
                if (index % 3 == 0) {
                    append(QUALIFIERS[random.nextInt(QUALIFIERS.size)])
                    append(' ')
                }
                append(NOUNS[random.nextInt(NOUNS.size)])
                append(" #")
                append(index)
            }
            PantryItem(
                id = "perf-item-$index",
                name = name,
                brand = BRANDS[random.nextInt(BRANDS.size)],
                barcode = "70${(index + 1_000_000)}",
                // ~40% carry an image, matching the mix of scanned vs. hand-entered products.
                // The URL is intentionally unreachable: Coil failing fast is the same amount of
                // composition work as Coil succeeding, and it keeps the benchmark offline.
                imageUrl = if (index % 5 < 2) "https://invalid.localhost/perf/$index.jpg" else null,
                expiryDate = LocalDate.fromEpochDays(todayEpochDay + dayOffset),
                addedDate = LocalDate.fromEpochDays(todayEpochDay - random.nextInt(0, 90)),
                storageLocation = STORAGE_LOCATIONS[index % STORAGE_LOCATIONS.size],
                // Every 7th item has no category at all, so the UNCATEGORIZED_ORIGIN path runs.
                category = if (index % 7 == 6) null else categories[index % categories.size].originName,
                quantity = 1 + (index % 4),
                notes = null,
                essential = index % 3 == 0
            )
        }
    }

    fun shoppingListEntries(count: Int, today: LocalDate = DateUtils.today()): List<ShoppingListEntry> {
        val random = Random(SEED + 1)
        val categories = categoryPreferences()
        val todayEpochDay = today.toEpochDays().toInt()

        return (0 until count).map { index ->
            ShoppingListEntry(
                id = "perf-shopping-$index",
                productName = "${NOUNS[random.nextInt(NOUNS.size)]} #$index",
                barcode = if (index % 3 == 0) "80${(index + 2_000_000)}" else null,
                category = if (index % 6 == 5) null else categories[index % categories.size].originName,
                quantity = 1 + (index % 3),
                addedDate = LocalDate.fromEpochDays(todayEpochDay - random.nextInt(0, 30)),
                sortOrder = index.toDouble()
            )
        }
    }
}
