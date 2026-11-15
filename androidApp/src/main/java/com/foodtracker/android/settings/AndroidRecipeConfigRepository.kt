package com.foodtracker.android.settings

import android.content.Context
import com.foodtracker.recipe.RecipeConfig
import com.foodtracker.recipe.RecipeConfigRepository
import com.foodtracker.recipe.RecipeJson
import com.foodtracker.recipe.RecipeOptions
import com.foodtracker.recipe.TasteProfile
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import com.foodtracker.recipe.recipeConfigFromStoredJson

/**
 * The Recipe tab's form, under its own key beside `AppSettings` rather than inside it, so the
 * backup format (which serialises `AppSettings`) is untouched. Unknown or broken values fall
 * back to the defaults, field by field where the JSON is still readable.
 */
class AndroidRecipeConfigRepository(context: Context) : RecipeConfigRepository {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun get(): RecipeConfig {
        val json = prefs.getString(KEY_RECIPE_CONFIG, null) ?: return RecipeConfig()
        // Also a config saved by a version with one meal and one diet (recipeConfigFromStoredJson).
        return recipeConfigFromStoredJson(json)
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

    /** The taste profile: its own key, kept exactly as saved (a change of quiz never rewrites it;
     *  what is shown and sent is filtered against the current definition). Nothing readable is an
     *  empty profile. Not in backups. */
    override fun getTaste(): TasteProfile {
        val json = prefs.getString(KEY_RECIPE_TASTE, null) ?: return TasteProfile()
        return runCatching { RecipeJson.decodeFromString(TasteProfile.serializer(), json) }.getOrDefault(TasteProfile())
    }

    override fun saveTaste(taste: TasteProfile) {
        prefs.edit().apply {
            if (taste.isEmpty) remove(KEY_RECIPE_TASTE) else putString(KEY_RECIPE_TASTE, RecipeJson.encodeToString(TasteProfile.serializer(), taste))
        }.apply()
    }

    /** The last good quiz definition per language, as the server's JSON. */
    override fun getTasteQuiz(language: String): String? = prefs.getString(KEY_RECIPE_TASTE_QUIZ + language, null)

    override fun saveTasteQuiz(language: String, json: String) {
        prefs.edit().putString(KEY_RECIPE_TASTE_QUIZ + language, json).apply()
    }

    /** The quiz steps the person has been through, apart from the answers (kept without a profile
     *  too). Nothing readable is none. */
    override fun getTasteSeen(): List<String> {
        val json = prefs.getString(KEY_RECIPE_TASTE_SEEN, null) ?: return emptyList()
        return runCatching { RecipeJson.decodeFromString(ListSerializer(String.serializer()), json) }.getOrDefault(emptyList())
    }

    override fun saveTasteSeen(seen: List<String>) {
        prefs.edit().apply {
            if (seen.isEmpty()) remove(KEY_RECIPE_TASTE_SEEN) else putString(KEY_RECIPE_TASTE_SEEN, RecipeJson.encodeToString(ListSerializer(String.serializer()), seen))
        }.apply()
    }

    companion object {
        /** The same file as [AndroidSettingsRepository], a different key. */
        private const val PREFS_NAME = "foodtracker_settings"
        private const val KEY_RECIPE_CONFIG = "recipe_config"
        private const val KEY_RECIPE_OPTIONS = "recipe_options"
        internal const val KEY_RECIPE_TASTE = "recipe_taste"
        internal const val KEY_RECIPE_TASTE_SEEN = "recipe_taste_seen"

        /** Followed by the language code: `recipe_taste_quiz_de`. */
        internal const val KEY_RECIPE_TASTE_QUIZ = "recipe_taste_quiz_"
    }
}
