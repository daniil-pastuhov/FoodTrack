package com.foodtracker.android.settings

import android.content.Context
import com.foodtracker.recipe.RecipeJson
import com.foodtracker.recipe.RecipePaidRepository
import com.foodtracker.recipe.RecipePaidSnapshot

/**
 * What the paid route keeps between launches: the last count, purchases the server hasn't
 * confirmed, and a generation whose answer was lost. Its own file, outside the app settings and
 * the backup, and written synchronously: a purchase that was paid for must not be forgotten
 * because the app was killed straight after.
 */
class AndroidRecipePaidRepository(context: Context) : RecipePaidRepository {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun get(): RecipePaidSnapshot {
        val json = prefs.getString(KEY_SNAPSHOT, null) ?: return RecipePaidSnapshot()
        return runCatching { RecipeJson.decodeFromString(RecipePaidSnapshot.serializer(), json) }.getOrDefault(RecipePaidSnapshot())
    }

    override fun save(snapshot: RecipePaidSnapshot) {
        prefs.edit().putString(KEY_SNAPSHOT, RecipeJson.encodeToString(RecipePaidSnapshot.serializer(), snapshot)).commit()
    }

    private companion object {
        const val PREFS_NAME = "recipe_paid"
        const val KEY_SNAPSHOT = "snapshot"
    }
}
