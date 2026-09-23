package com.foodtracker.model

import kotlin.test.Test
import kotlinx.serialization.json.Json
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

    @Test
    fun testStoreRemindersAreOffAndEmptyByDefault() {
        // Arming them is what gates asking for background location, so nothing here may be on
        // out of the box.
        assertEquals(false, AppSettings().storeRemindersEnabled)
        assertEquals(emptyList(), AppSettings().stores)
    }

    @Test
    fun testStoresSurviveASerializationRoundTrip() {
        val withStores = settings.copy(
            storeRemindersEnabled = true,
            stores = listOf(
                StorePlace(id = "s1", label = "Rema", latitude = 59.91, longitude = 10.75),
                StorePlace(id = "s2", label = "Kiwi", latitude = 59.92, longitude = 10.76, radiusMeters = 220.0)
            )
        )

        val decoded = Json.decodeFromString<AppSettings>(Json.encodeToString(withStores))

        assertEquals(withStores, decoded)
    }

    @Test
    fun testSettingsStoredBeforeStoresExistedStillDecode() {
        // Settings are persisted as JSON in prefs/UserDefaults, so an install that predates this
        // feature must keep loading rather than resetting to defaults.
        val legacy = """{"expiringSoonDays":5,"defaultStorageLocation":"Fridge"}"""

        val decoded = Json.decodeFromString<AppSettings>(legacy)

        assertEquals(5, decoded.expiringSoonDays)
        assertEquals("Fridge", decoded.defaultStorageLocation)
        assertEquals(emptyList(), decoded.stores)
        assertEquals(false, decoded.storeRemindersEnabled)
    }
}
