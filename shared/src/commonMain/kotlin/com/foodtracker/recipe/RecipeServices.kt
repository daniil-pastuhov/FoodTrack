package com.foodtracker.recipe

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

/** One pantry item as the server sees it: a name and how many days it has left. */
data class RecipeRequestIngredient(val name: String, val daysLeft: Int)

/** A request for the server route. The server derives the cost from [kind]. */
data class RecipeRequest(
    /** A UUID, so a retry after a lost connection is never charged twice. */
    val requestId: String,
    val kind: SavedRecipeKind,
    /** 2 or 3 for a menu, null for a recipe. */
    val courses: Int?,
    val config: RecipeConfig,
    val language: String,
    val ingredients: List<RecipeRequestIngredient>,
    val allergens: List<String>
)

sealed interface RecipeBackendResult {
    data class WrittenRecipe(val recipe: Recipe, val balance: Int) : RecipeBackendResult
    data class WrittenMenu(val menu: Menu, val balance: Int) : RecipeBackendResult
    data object Offline : RecipeBackendResult

    /** Nothing was taken. */
    data object Failed : RecipeBackendResult
    data object NotAvailable : RecipeBackendResult
}

/** The paid route: our server writing a recipe from a pack. */
interface RecipeBackend {
    /** False when this build or this moment can't use the server at all. */
    val isAvailable: Boolean
    suspend fun generate(request: RecipeRequest): RecipeBackendResult
}

/** Recipes bought in packs, and the store and account behind them. */
interface CreditsGateway {
    /** Recipes left, or null when there is no paid route or the count isn't known yet. */
    val balance: StateFlow<Int?>

    /** Whether packs can be offered on this build and device. */
    val canBuy: Boolean
}

/** The paid route before it exists: never available, never called. */
object NotAvailableRecipeBackend : RecipeBackend {
    override val isAvailable: Boolean = false
    override suspend fun generate(request: RecipeRequest): RecipeBackendResult = RecipeBackendResult.NotAvailable
}

/** No packs, no balance. */
object NotAvailableCreditsGateway : CreditsGateway {
    override val balance: StateFlow<Int?> = MutableStateFlow<Int?>(null).asStateFlow()
    override val canBuy: Boolean = false
}
