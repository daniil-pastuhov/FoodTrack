package com.foodtracker.android.recipe.paid

import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/** Builds from Google Play attest with Play Integrity. Debug builds have their own copy. */
internal fun recipeAppCheckProviderFactory(): AppCheckProviderFactory = PlayIntegrityAppCheckProviderFactory.getInstance()

/** No stand-in store outside debug builds: Play Billing only. */
internal fun emulatorRecipeStore(@Suppress("UNUSED_PARAMETER") context: android.content.Context): com.foodtracker.recipe.RecipeStore? = null
