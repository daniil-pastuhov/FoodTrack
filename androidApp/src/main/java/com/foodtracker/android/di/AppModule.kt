package com.foodtracker.android.di

import androidx.room.Room
import com.foodtracker.android.data.local.FoodTrackerDatabase
import com.foodtracker.android.data.local.RoomPantryRepository
import com.foodtracker.android.settings.AndroidSettingsRepository
import com.foodtracker.api.OpenFoodFactsApi
import com.foodtracker.repository.PantryRepository
import com.foodtracker.repository.SettingsRepository
import com.foodtracker.viewmodel.PantryStateHolder
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val appModule = module {
    single {
        Room.databaseBuilder(androidContext(), FoodTrackerDatabase::class.java, FoodTrackerDatabase.DB_NAME)
            .build()
    }
    single { get<FoodTrackerDatabase>().pantryDao() }
    single<PantryRepository> { RoomPantryRepository(get()) }
    single<SettingsRepository> { AndroidSettingsRepository(androidContext()) }
    single { OpenFoodFactsApi() }
    single {
        PantryStateHolder(
            repository = get(),
            settingsRepository = get(),
            openFoodFactsApi = get()
        )
    }
}
