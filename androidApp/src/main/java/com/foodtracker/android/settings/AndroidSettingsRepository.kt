package com.foodtracker.android.settings

import android.content.Context
import com.foodtracker.model.AppSettings
import com.foodtracker.repository.SettingsRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AndroidSettingsRepository(context: Context) : SettingsRepository {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getSettings(): AppSettings {
        val json = prefs.getString(KEY_SETTINGS, null) ?: return AppSettings()
        return try {
            Json.decodeFromString<AppSettings>(json)
        } catch (e: Exception) {
            AppSettings()
        }
    }

    override fun saveSettings(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_SETTINGS, Json.encodeToString(settings))
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "foodtracker_settings"
        private const val KEY_SETTINGS = "app_settings"
    }
}
