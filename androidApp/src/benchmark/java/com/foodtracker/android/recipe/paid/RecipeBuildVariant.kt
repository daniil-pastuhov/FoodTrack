package com.foodtracker.android.recipe.paid

import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/** As release (see src/release): the benchmark build never reaches the paid route, since it
 *  isn't installed from Google Play, but it has to compile the same code. */
internal fun recipeAppCheckProviderFactory(): AppCheckProviderFactory = PlayIntegrityAppCheckProviderFactory.getInstance()

/** No stand-in store outside debug builds: Play Billing only. */
internal fun emulatorRecipeStore(@Suppress("UNUSED_PARAMETER") context: android.content.Context): com.foodtracker.recipe.RecipeStore? = null
