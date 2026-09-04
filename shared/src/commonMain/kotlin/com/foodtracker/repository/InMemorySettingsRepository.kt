package com.foodtracker.repository

import com.foodtracker.model.AppSettings

class InMemorySettingsRepository(
    initialSettings: AppSettings = AppSettings()
) : SettingsRepository {

    private var settings: AppSettings = initialSettings

    override fun getSettings(): AppSettings = settings

    override fun saveSettings(settings: AppSettings) {
        this.settings = settings
    }
}
