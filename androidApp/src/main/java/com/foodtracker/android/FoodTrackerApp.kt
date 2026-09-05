package com.foodtracker.android

import android.app.Application
import com.foodtracker.android.di.appModule
import com.foodtracker.android.widget.WidgetUpdater
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.get

class FoodTrackerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Create notification channel for expiry alerts
        com.foodtracker.android.notification.NotificationHelper.createNotificationChannel(this)

        startKoin {
            androidContext(this@FoodTrackerApp)
            modules(appModule)
        }

        get<WidgetUpdater>(WidgetUpdater::class.java).start()
    }
}
