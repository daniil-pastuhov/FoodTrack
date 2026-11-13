package com.foodtracker.android.di

import androidx.room.Room
import com.foodtracker.android.R
import com.foodtracker.android.data.local.FoodTrackerDatabase
import com.foodtracker.android.data.local.RoomCategoryPreferenceRepository
import com.foodtracker.android.data.local.RoomLocalProductRepository
import com.foodtracker.android.data.local.RoomPantryRepository
import com.foodtracker.android.data.local.RoomProductNameRepository
import com.foodtracker.android.data.local.RoomShoppingListRepository
import com.foodtracker.android.data.local.RoomSavedRecipeRepository
import com.foodtracker.android.data.local.RoomShoppingSectionRepository
import com.foodtracker.android.recipe.GeminiNanoRecipeModel
import com.foodtracker.android.settings.AndroidRecipeConfigRepository
import com.foodtracker.android.settings.AndroidSettingsRepository
import com.foodtracker.android.widget.WidgetUpdater
import com.foodtracker.api.OpenFoodFactsApi
import com.foodtracker.android.recipe.paid.FirebaseRecipeAccount
import com.foodtracker.android.recipe.paid.FirebaseRecipeBackend
import com.foodtracker.android.recipe.paid.PlayRecipeStore
import com.foodtracker.android.recipe.paid.RecipeFirebase
import com.foodtracker.android.recipe.paid.emulatorRecipeStore
import com.foodtracker.android.recipe.paid.recipePaidRouteBuildAllowed
import com.foodtracker.android.settings.AndroidRecipePaidRepository
import com.foodtracker.recipe.NoRecipeAccount
import com.foodtracker.recipe.NotAvailableRecipeBackend
import com.foodtracker.recipe.NotAvailableRecipeStore
import com.foodtracker.recipe.OnDeviceRecipeModel
import com.foodtracker.recipe.RecipeAccount
import com.foodtracker.recipe.RecipeBackend
import com.foodtracker.recipe.RecipePaidRepository
import com.foodtracker.recipe.RecipeStore
import com.foodtracker.recipe.RecipeConfigRepository
import com.foodtracker.recipe.RecipeStateHolder
import com.foodtracker.recipe.SavedRecipeRepository
import com.foodtracker.repository.CategoryPreferenceRepository
import com.foodtracker.repository.LocalProductRepository
import com.foodtracker.repository.PantryRepository
import com.foodtracker.repository.ProductNameRepository
import com.foodtracker.repository.ShoppingListRepository
import com.foodtracker.repository.ShoppingSectionRepository
import com.foodtracker.repository.SettingsRepository
import com.foodtracker.viewmodel.PantryStateHolder
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.util.Locale

val appModule = module {
    single {
        Room.databaseBuilder(androidContext(), FoodTrackerDatabase::class.java, FoodTrackerDatabase.DB_NAME)
            // Adds the essential column + shopping list table in place — a real Migration,
            // not a destructive fallback, so existing users don't lose their pantry data.
            .addMigrations(
                FoodTrackerDatabase.MIGRATION_1_2,
                FoodTrackerDatabase.MIGRATION_2_3,
                FoodTrackerDatabase.MIGRATION_3_4,
                FoodTrackerDatabase.MIGRATION_4_5,
                FoodTrackerDatabase.MIGRATION_5_6,
                FoodTrackerDatabase.MIGRATION_6_7,
                FoodTrackerDatabase.MIGRATION_7_8,
                FoodTrackerDatabase.MIGRATION_8_9,
                FoodTrackerDatabase.MIGRATION_9_10,
                FoodTrackerDatabase.MIGRATION_10_11,
                FoodTrackerDatabase.MIGRATION_11_12,
                FoodTrackerDatabase.MIGRATION_12_13,
                FoodTrackerDatabase.MIGRATION_13_14
            )
            .build()
    }
    single { get<FoodTrackerDatabase>().pantryDao() }
    single { get<FoodTrackerDatabase>().shoppingListDao() }
    single { get<FoodTrackerDatabase>().productNameDao() }
    single { get<FoodTrackerDatabase>().localProductDao() }
    single { get<FoodTrackerDatabase>().categoryPreferenceDao() }
    single { get<FoodTrackerDatabase>().shoppingSectionDao() }
    single { get<FoodTrackerDatabase>().savedRecipeDao() }
    single<PantryRepository> { RoomPantryRepository(get()) }
    single<ShoppingListRepository> { RoomShoppingListRepository(get()) }
    single<ProductNameRepository> { RoomProductNameRepository(get()) }
    single<LocalProductRepository> { RoomLocalProductRepository(get()) }
    single<CategoryPreferenceRepository> { RoomCategoryPreferenceRepository(get()) }
    single<ShoppingSectionRepository> { RoomShoppingSectionRepository(get()) }
    single<SettingsRepository> { AndroidSettingsRepository(androidContext()) }
    single<SavedRecipeRepository> { RoomSavedRecipeRepository(get()) }
    single<RecipeConfigRepository> { AndroidRecipeConfigRepository(androidContext()) }
    single<OnDeviceRecipeModel> { GeminiNanoRecipeModel() }
    single { OpenFoodFactsApi() }
    // Depends on PantryStateHolder rather than the repositories: see WidgetUpdater for why.
    single { WidgetUpdater(androidContext(), get()) }
    single {
        PantryStateHolder(
            repository = get(),
            settingsRepository = get(),
            shoppingListRepository = get(),
            productNameRepository = get(),
            localProductRepository = get(),
            categoryPreferenceRepository = get(),
            shoppingSectionRepository = get(),
            uncategorizedLabel = androidContext().getString(R.string.shopping_list_uncategorized),
            openFoodFactsApi = get()
        )
    }
    // The paid route. Release builds that can't pass App Check (forks, sideloads) get the
    // stand-ins, so the tab shows only the free routes and nothing here is ever created. Where
    // it is allowed, nothing reaches our server before a purchase starts either.
    single { RecipeFirebase(androidContext()) }
    single<RecipePaidRepository> { AndroidRecipePaidRepository(androidContext()) }
    single<RecipeBackend> {
        if (recipePaidRouteBuildAllowed(androidContext())) FirebaseRecipeBackend(get(), isAvailable = true) else NotAvailableRecipeBackend
    }
    single<RecipeStore> {
        if (recipePaidRouteBuildAllowed(androidContext())) {
            emulatorRecipeStore(androidContext()) ?: PlayRecipeStore(androidContext())
        } else {
            NotAvailableRecipeStore
        }
    }
    single<RecipeAccount> {
        if (recipePaidRouteBuildAllowed(androidContext())) FirebaseRecipeAccount(androidContext(), get()) else NoRecipeAccount
    }
    single {
        RecipeStateHolder(
            pantry = get<PantryStateHolder>().uiState,
            onDeviceModel = get(),
            savedRecipeRepository = get(),
            configRepository = get(),
            backend = get(),
            store = get(),
            account = get(),
            paidRepository = get(),
            appLanguage = { Locale.getDefault().toLanguageTag() }
        )
    }
}
