package com.foodtracker.android

import android.app.Application
import com.foodtracker.android.settings.AndroidSettingsRepository
import com.foodtracker.repository.InMemoryPantryRepository
import com.foodtracker.viewmodel.PantryStateHolder

class FoodTrackerApp : Application() {

    lateinit var stateHolder: PantryStateHolder
        private set

    override fun onCreate() {
        super.onCreate()
        // Create notification channel for expiry alerts
        com.foodtracker.android.notification.NotificationHelper.createNotificationChannel(this)

        // Initialize state holder with the in-memory repository (no remote backend).
        stateHolder = PantryStateHolder(
            repository = InMemoryPantryRepository(),
            settingsRepository = AndroidSettingsRepository(this)
        )
    }
}
