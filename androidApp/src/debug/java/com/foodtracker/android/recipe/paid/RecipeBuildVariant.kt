package com.foodtracker.android.recipe.paid

import android.content.Context
import android.util.Base64
import com.foodtracker.android.BuildConfig
import com.foodtracker.recipe.RECIPE_PACKS
import com.foodtracker.recipe.RecipeStore
import com.foodtracker.recipe.StorePack
import com.foodtracker.recipe.StorePurchase
import com.foodtracker.recipe.StoreUpdate
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.AppCheckProvider
import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.AppCheckToken
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID

private val usesEmulator: Boolean get() = BuildConfig.RECIPE_EMULATOR_HOST.isNotEmpty()

/**
 * Debug builds attest with the debug provider: it logs a token ("Enter this debug secret into
 * the allow list...") to register under App Check > Manage debug tokens. That token stays in
 * the console and on this machine, never in the repo or CI logs. Release and benchmark builds
 * use Play Integrity (see their own copy of this file).
 *
 * Against the Firebase emulators (-PrecipeEmulatorHost), App Check tokens are decoded but not
 * verified, so an unsigned stand-in is made on the phone instead and nothing reaches Google.
 */
internal fun recipeAppCheckProviderFactory(): AppCheckProviderFactory =
    if (usesEmulator) EmulatorAppCheckProviderFactory else DebugAppCheckProviderFactory.getInstance()

private object EmulatorAppCheckProviderFactory : AppCheckProviderFactory {
    override fun create(firebaseApp: FirebaseApp): AppCheckProvider = AppCheckProvider { Tasks.forResult(emulatorToken(firebaseApp)) }
}

private fun emulatorToken(app: FirebaseApp): AppCheckToken {
    val now = System.currentTimeMillis()
    val expires = now + 60 * 60_000L
    fun part(json: String) = Base64.encodeToString(json.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    val header = part("""{"alg":"none","typ":"JWT"}""")
    val payload = part(
        """{"sub":"${app.options.applicationId}","aud":["projects/${app.options.projectId}"],""" +
            """"iat":${now / 1000},"exp":${expires / 1000},"jti":"${UUID.randomUUID()}"}"""
    )
    val token = "$header.$payload.emulator"
    return object : AppCheckToken() {
        override fun getToken(): String = token
        override fun getExpireTimeMillis(): Long = expires
    }
}

/**
 * Against the emulators, a store that needs no Play: its purchases carry `fake-<pack>-<id>`
 * tokens, which the Functions emulator's fake Play verifier (EMULATOR_FAKES=true) grants. Null
 * otherwise, and in release builds, which have no such store at all.
 */
internal fun emulatorRecipeStore(@Suppress("UNUSED_PARAMETER") context: Context): RecipeStore? =
    if (usesEmulator) EmulatorRecipeStore() else null

private class EmulatorRecipeStore : RecipeStore {
    private val _updates = MutableSharedFlow<StoreUpdate>(extraBufferCapacity = 4)
    override val updates: Flow<StoreUpdate> = _updates.asSharedFlow()
    override val isSupported: Boolean = true

    // The store's sample prices for 10 / 50 / 100 recipes.
    override suspend fun packs(): List<StorePack> = RECIPE_PACKS.map { (id, recipes) ->
        val micros = when (recipes) {
            10 -> 3_990_000L
            50 -> 10_990_000L
            else -> 18_990_000L
        }
        StorePack(id, recipes, "\$" + "%.2f".format(java.util.Locale.US, micros / 1_000_000.0), micros, "USD")
    }

    override suspend fun buy(productId: String): Boolean {
        _updates.tryEmit(StoreUpdate.Purchases(listOf(StorePurchase("fake-$productId-${UUID.randomUUID()}", listOf(productId), 1, pending = false))))
        return true
    }

    override suspend fun purchases(): List<StorePurchase> = emptyList()
}

