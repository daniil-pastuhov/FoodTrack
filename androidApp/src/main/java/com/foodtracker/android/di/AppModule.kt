package com.foodtracker.android.di

import androidx.room.Room
import com.foodtracker.android.data.local.FoodTrackerDatabase
import com.foodtracker.android.data.local.RoomPantryRepository
import com.foodtracker.android.data.local.RoomProductNameRepository
import com.foodtracker.android.data.local.RoomShoppingListRepository
import com.foodtracker.android.settings.AndroidSettingsRepository
import com.foodtracker.android.widget.WidgetUpdater
import com.foodtracker.api.OpenFoodFactsApi
import com.foodtracker.repository.PantryRepository
import com.foodtracker.repository.ProductNameRepository
import com.foodtracker.repository.ShoppingListRepository
import com.foodtracker.repository.SettingsRepository
import com.foodtracker.viewmodel.PantryStateHolder
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val appModule = module {
    single {
        Room.databaseBuilder(androidContext(), FoodTrackerDatabase::class.java, FoodTrackerDatabase.DB_NAME)
            // Adds the essential column + shopping list table in place — a real Migration,
            // not a destructive fallback, so existing users don't lose their pantry data.
            .addMigrations(
                FoodTrackerDatabase.MIGRATION_1_2,
                FoodTrackerDatabase.MIGRATION_2_3,
                FoodTrackerDatabase.MIGRATION_3_4
            )
            .build()
    }
    single { get<FoodTrackerDatabase>().pantryDao() }
    single { get<FoodTrackerDatabase>().shoppingListDao() }
    single { get<FoodTrackerDatabase>().productNameDao() }
    single<PantryRepository> { RoomPantryRepository(get()) }
    single<ShoppingListRepository> { RoomShoppingListRepository(get()) }
    single<ProductNameRepository> { RoomProductNameRepository(get()) }
    single<SettingsRepository> { AndroidSettingsRepository(androidContext()) }
    single { OpenFoodFactsApi() }
    single { WidgetUpdater(androidContext(), get(), get()) }
    single {
        PantryStateHolder(
            repository = get(),
            settingsRepository = get(),
            shoppingListRepository = get(),
            productNameRepository = get(),
            openFoodFactsApi = get()
        )
    }
}
