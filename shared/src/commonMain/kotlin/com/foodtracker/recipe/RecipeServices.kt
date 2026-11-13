package com.foodtracker.recipe

import kotlinx.coroutines.flow.Flow

/*
 * What [RecipeStateHolder] needs from the platform, as plain interfaces so it stays free of
 * Android (and constructible on iOS, should iOS ever adopt `shared`).
 */

/** Where the Recipe tab's configuration is kept: its own key, outside `AppSettings`, so the
 *  backup format is untouched. */
interface RecipeConfigRepository {
    fun get(): RecipeConfig
    fun save(config: RecipeConfig)
}

/** Recipes and menus written in the app, kept on this phone only. */
interface SavedRecipeRepository {
    /** Newest first. */
    fun getAllFlow(): Flow<List<SavedRecipe>>
    suspend fun save(recipe: SavedRecipe)
    suspend fun delete(id: String)
    suspend fun clearAll()
}

/** Whether the phone's own model can write a recipe now. */
enum class OnDeviceAvailability {
    /** Not this phone, this Android version or this build. */
    UNSUPPORTED,

    /** Supported, but the model has to be downloaded first. */
    DOWNLOADABLE,

    /** Supported, and the model is still downloading. */
    DOWNLOADING,
    AVAILABLE
}

/** The phone's own language model: Gemini Nano on Android, Foundation Models on iOS. */
interface OnDeviceRecipeModel {
    suspend fun availability(): OnDeviceAvailability

    /** Whether the model is known to write well in [languageCode], one of [RECIPE_LANGUAGES].
     *  Where it isn't, the route is hidden and the shared prompt is the free path. */
    fun supportsLanguage(languageCode: String): Boolean

    /** Downloads the model when [availability] said [OnDeviceAvailability.DOWNLOADABLE] or
     *  [OnDeviceAvailability.DOWNLOADING]. Returns once it is ready (true) or failed (false). */
    suspend fun download(): Boolean

    /** Runs [prompt] and returns the model's raw text. Throws when the model fails. Cancelling
     *  the caller stops it. */
    suspend fun generate(prompt: String): String
}
