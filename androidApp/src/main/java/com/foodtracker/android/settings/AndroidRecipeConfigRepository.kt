package com.foodtracker.android.settings

import android.content.Context
import com.foodtracker.recipe.RecipeConfig
import com.foodtracker.recipe.RecipeConfigRepository
import com.foodtracker.recipe.RecipeJson
import com.foodtracker.recipe.RecipeOptions

/**
 * The Recipe tab's form, under its own key beside `AppSettings` rather than inside it, so the
 * backup format (which serialises `AppSettings`) is untouched. Unknown or broken values fall
 * back to the defaults, field by field where the JSON is still readable.
 */
class AndroidRecipeConfigRepository(context: Context) : RecipeConfigRepository {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun get(): RecipeConfig {
        val json = prefs.getString(KEY_RECIPE_CONFIG, null) ?: return RecipeConfig()
        return runCatching { RecipeJson.decodeFromString(RecipeConfig.serializer(), json) }
            .getOrDefault(RecipeConfig())
            .normalized()
    }

    override fun save(config: RecipeConfig) {
        prefs.edit()
            .putString(KEY_RECIPE_CONFIG, RecipeJson.encodeToString(RecipeConfig.serializer(), config))
            .apply()
    }

    /** "Send all products" and whether the introduction was seen: a key of their own, read
     *  field by field with defaults, so nothing stored earlier is lost or misread. */
    override fun getOptions(): RecipeOptions {
        val json = prefs.getString(KEY_RECIPE_OPTIONS, null) ?: return RecipeOptions()
        return runCatching { RecipeJson.decodeFromString(RecipeOptions.serializer(), json) }.getOrDefault(RecipeOptions())
    }

    override fun saveOptions(options: RecipeOptions) {
        prefs.edit()
            .putString(KEY_RECIPE_OPTIONS, RecipeJson.encodeToString(RecipeOptions.serializer(), options))
            .apply()
    }

    companion object {
        /** The same file as [AndroidSettingsRepository], a different key. */
        private const val PREFS_NAME = "foodtracker_settings"
        private const val KEY_RECIPE_CONFIG = "recipe_config"
        private const val KEY_RECIPE_OPTIONS = "recipe_options"
    }
}
