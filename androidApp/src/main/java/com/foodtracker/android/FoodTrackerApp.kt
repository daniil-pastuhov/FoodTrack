package com.foodtracker.android

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.foodtracker.android.di.OFF_USER_AGENT
import com.foodtracker.android.di.appModule
import com.foodtracker.api.isOpenFoodFactsHost
import okhttp3.OkHttpClient
import com.foodtracker.android.widget.WidgetUpdater
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.get

class FoodTrackerApp : Application(), ImageLoaderFactory {

    /** Coil's loader: its defaults, plus the app's User-Agent on requests to Open Food Facts'
     *  picture hosts only (their API guidance asks for it; other hosts get Coil's own). */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient {
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    if (isOpenFoodFactsHost(request.url.host)) {
                        chain.proceed(request.newBuilder().header("User-Agent", OFF_USER_AGENT).build())
                    } else {
                        chain.proceed(request)
                    }
                }
                .build()
        }
        .build()

    override fun onCreate() {
        super.onCreate()
        // The activity in front, for Play's payment sheet and the sign-in flows.
        registerActivityLifecycleCallbacks(com.foodtracker.android.recipe.paid.CurrentActivity)
        // Create notification channel for expiry alerts
        com.foodtracker.android.notification.NotificationHelper.createNotificationChannel(this)

        startKoin {
            androidContext(this@FoodTrackerApp)
            modules(appModule)
        }

        get<WidgetUpdater>(WidgetUpdater::class.java).start()
    }
}
