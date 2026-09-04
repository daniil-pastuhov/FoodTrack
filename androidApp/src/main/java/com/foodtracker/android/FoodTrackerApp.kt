package com.foodtracker.android

import android.app.Application
import com.foodtracker.android.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class FoodTrackerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Create notification channel for expiry alerts
        com.foodtracker.android.notification.NotificationHelper.createNotificationChannel(this)

        startKoin {
            androidContext(this@FoodTrackerApp)
            modules(appModule)
        }
    }
}
