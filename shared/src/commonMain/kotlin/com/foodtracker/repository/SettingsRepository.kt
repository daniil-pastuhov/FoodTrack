package com.foodtracker.repository

import com.foodtracker.model.AppSettings

interface SettingsRepository {
    fun getSettings(): AppSettings
    fun saveSettings(settings: AppSettings)
}
