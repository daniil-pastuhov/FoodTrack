package com.foodtracker.model

import kotlin.test.Test
import kotlin.test.assertEquals

class AppSettingsTest {

    private val settings = AppSettings(
        storageLocations = listOf("Fridge", "Freezer", "Pantry"),
        defaultStorageLocation = "Pantry"
    )

    @Test
    fun testNewItemUsesTheRememberedLocation() {
        assertEquals("Freezer", settings.copy(lastUsedStorageLocation = "Freezer").storageLocationForNewItem())
    }

    @Test
    fun testNewItemFallsBackToTheDefaultWhenNothingIsRememberedYet() {
        assertEquals("Pantry", settings.storageLocationForNewItem())
    }

    @Test
    fun testNewItemIgnoresTheRememberedLocationWhenTheFeatureIsOff() {
        val off = settings.copy(rememberLastStorageLocation = false, lastUsedStorageLocation = "Freezer")
        assertEquals("Pantry", off.storageLocationForNewItem())
    }

    @Test
    fun testNewItemIgnoresARememberedLocationThatNoLongerExists() {
        assertEquals("Pantry", settings.copy(lastUsedStorageLocation = "Garage").storageLocationForNewItem())
    }
}
