package com.foodtracker.android.perf

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.room.withTransaction
import com.foodtracker.android.data.local.FoodTrackerDatabase
import com.foodtracker.android.data.local.toEntity
import com.foodtracker.perf.PerfDataset
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Replaces the on-device database with [PerfDataset]'s deterministic contents so a macrobenchmark
 * measures the same workload every run.
 *
 * This lives in `src/benchmark/`, an AGP per-build-type source set, so it is compiled into the
 * `benchmark` build type **only** — it does not exist in debug or release builds, and can't be
 * triggered on a real user's install.
 *
 * The work runs synchronously on the receiver's thread rather than via `goAsync()`, because the
 * benchmark invokes this with `am broadcast -W` and relies on that returning once seeding is
 * actually finished. Strict-mode disk complaints are irrelevant here.
 */
class PerfSeedReceiver : BroadcastReceiver(), KoinComponent {

    private val database: FoodTrackerDatabase by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val itemCount = intent.getIntExtra(EXTRA_ITEM_COUNT, DEFAULT_ITEM_COUNT)
        val shoppingCount = intent.getIntExtra(EXTRA_SHOPPING_COUNT, DEFAULT_SHOPPING_COUNT)

        val elapsed = runBlocking {
            val start = System.currentTimeMillis()
            // One transaction for the whole seed: 1000 individual upserts would each be their
            // own fsync and take longer than the benchmark it's setting up.
            database.withTransaction {
                database.pantryDao().clearAll()
                database.shoppingListDao().clearAll()

                PerfDataset.categoryPreferences().forEach {
                    database.categoryPreferenceDao().upsert(it.toEntity())
                }
                PerfDataset.pantryItems(itemCount).forEach {
                    database.pantryDao().upsert(it.toEntity())
                }
                PerfDataset.shoppingListEntries(shoppingCount).forEach {
                    database.shoppingListDao().upsert(it.toEntity())
                }
            }
            System.currentTimeMillis() - start
        }

        // The benchmark greps logcat for this line to confirm seeding actually happened rather
        // than silently no-oping against a stale database.
        Log.i(TAG, "$SEEDED_MARKER items=$itemCount shopping=$shoppingCount inMs=$elapsed")
        resultData = "$SEEDED_MARKER items=$itemCount"
    }

    companion object {
        const val TAG = "PerfSeed"
        const val ACTION = "com.foodtracker.android.PERF_SEED"
        const val EXTRA_ITEM_COUNT = "count"
        const val EXTRA_SHOPPING_COUNT = "shopping"
        const val DEFAULT_ITEM_COUNT = 1000
        const val DEFAULT_SHOPPING_COUNT = 40
        const val SEEDED_MARKER = "PERF_SEED_OK"
    }
}
