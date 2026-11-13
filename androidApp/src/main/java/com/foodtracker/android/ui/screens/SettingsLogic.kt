package com.foodtracker.android.ui.screens

import com.foodtracker.android.util.isExistingLocation
import com.foodtracker.model.AppSettings
import com.foodtracker.model.ShelfLife
import com.foodtracker.model.StorePlace

/** The longest default shelf life a category can have, about 10 years. */
const val MAX_SHELF_LIFE_DAYS = ShelfLife.MAX_LEARNABLE_DAYS

/** What the Days field of the shelf-life dialog holds. */
sealed interface ShelfLifeInput {
    /** Empty: saving removes the default, as Clear does. */
    data object Cleared : ShelfLifeInput
    data class Days(val days: Int) : ShelfLifeInput
    data object OutOfRange : ShelfLifeInput
}

fun parseShelfLife(text: String): ShelfLifeInput {
    if (text.isBlank()) return ShelfLifeInput.Cleared
    val days = text.trim().toIntOrNull() ?: return ShelfLifeInput.OutOfRange
    return if (days in 0..MAX_SHELF_LIFE_DAYS) ShelfLifeInput.Days(days) else ShelfLifeInput.OutOfRange
}

/** The result of pressing Add on a new location, or the tick on a rename. */
sealed interface LocationNameCheck {
    /** Nothing to do: the field is empty, or a rename left the name as it was. */
    data object NoChange : LocationNameCheck
    data class Taken(val name: String) : LocationNameCheck
    data class Accepted(val name: String) : LocationNameCheck
}

fun checkNewLocation(
    locations: List<String>,
    input: String,
    label: (String) -> String
): LocationNameCheck {
    val name = input.trim()
    return when {
        name.isEmpty() -> LocationNameCheck.NoChange
        isExistingLocation(locations, name, label) -> LocationNameCheck.Taken(name)
        else -> LocationNameCheck.Accepted(name)
    }
}

/** The field starts out showing the translated name, so confirming it unchanged must keep the
 *  built-in key rather than store its translation. Typing the location's own key is unchanged
 *  too, not a clash with itself. */
fun checkLocationRename(
    location: String,
    locations: List<String>,
    input: String,
    label: (String) -> String
): LocationNameCheck {
    val name = input.trim()
    return when {
        name.isEmpty() || name == location || name == label(location) -> LocationNameCheck.NoChange
        isExistingLocation(locations - location, name, label) -> LocationNameCheck.Taken(name)
        else -> LocationNameCheck.Accepted(name)
    }
}

fun AppSettings.withLocationRenamed(location: String, newName: String): AppSettings = copy(
    storageLocations = storageLocations.map { if (it == location) newName else it },
    defaultStorageLocation = if (defaultStorageLocation == location) newName else defaultStorageLocation,
    lastUsedStorageLocation = if (lastUsedStorageLocation == location) newName else lastUsedStorageLocation
)

/** What Undo needs to put a removed location back where it was. */
data class RemovedLocation(
    val name: String,
    val index: Int,
    val wasDefault: Boolean,
    val wasLastUsed: Boolean
)

/** Takes [location] out, moving the default to the first one left. Null when it isn't there or
 *  is the last one, which can't be removed. */
fun AppSettings.removingLocation(location: String): Pair<AppSettings, RemovedLocation>? {
    val index = storageLocations.indexOf(location)
    if (index < 0 || storageLocations.size <= 1) return null
    val remaining = storageLocations - location
    val wasDefault = defaultStorageLocation == location
    val wasLastUsed = lastUsedStorageLocation == location
    val updated = copy(
        storageLocations = remaining,
        defaultStorageLocation = if (wasDefault) remaining.first() else defaultStorageLocation,
        lastUsedStorageLocation = if (wasLastUsed) null else lastUsedStorageLocation
    )
    return updated to RemovedLocation(location, index, wasDefault, wasLastUsed)
}

/** Works on the settings as they are now, so anything changed since the removal stays changed.
 *  If the same name was added again in the meantime, there is nothing to put back. */
fun AppSettings.restoringLocation(removed: RemovedLocation): AppSettings {
    if (removed.name in storageLocations) return this
    val locations = storageLocations.toMutableList()
    locations.add(removed.index.coerceAtMost(locations.size), removed.name)
    return copy(
        storageLocations = locations,
        defaultStorageLocation = if (removed.wasDefault) removed.name else defaultStorageLocation,
        lastUsedStorageLocation = if (removed.wasLastUsed) removed.name else lastUsedStorageLocation
    )
}

/** Allergens are stored lowercased: matching ignores case anyway, and this stops "Milk" and
 *  "milk" reading as two entries. Blank input and duplicates leave the set as it is. */
fun Set<String>.addingAllergen(input: String): Set<String> {
    val entry = input.trim().lowercase()
    return if (entry.isEmpty()) this else this + entry
}

/** By position rather than id: older installs can hold several shops with the same id. */
fun AppSettings.removingStore(index: Int): Pair<AppSettings, StorePlace>? {
    val store = stores.getOrNull(index) ?: return null
    return copy(stores = stores.filterIndexed { i, _ -> i != index }) to store
}

fun AppSettings.restoringStore(store: StorePlace, index: Int): AppSettings {
    val updated = stores.toMutableList()
    updated.add(index.coerceIn(0, updated.size), store)
    return copy(stores = updated)
}

/** The list with the row at [from] moved [by] places, or the same list if that's off either end. */
fun <T> List<T>.movingRow(from: Int, by: Int): List<T> {
    val to = from + by
    if (from !in indices || to !in indices) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}
