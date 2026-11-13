package com.foodtracker.android.util

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.foodtracker.android.R
import com.foodtracker.model.CategoryPreference
import com.foodtracker.model.DefaultStorageLocations
import com.foodtracker.recognition.FoodLabels

/**
 * Names, in the device's language, for the storage locations and categories the app ships with.
 *
 * Both are stored under fixed English keys ([DefaultStorageLocations], `FoodLabels.CATEGORY_*`)
 * because items, settings and shelf-life lookups match on them, so they are translated on the way
 * to the screen and never stored translated. A location or category the user typed has no entry
 * here and is shown as typed. An explicit map for the same reason as in FoodLabelNames.kt: R8
 * can't see a by-name resource lookup.
 */
private val STORAGE_LOCATION_STRINGS: Map<String, Int> = mapOf(
    DefaultStorageLocations.FRIDGE to R.string.storage_location_fridge,
    DefaultStorageLocations.FREEZER to R.string.storage_location_freezer,
    DefaultStorageLocations.PANTRY to R.string.storage_location_pantry
)

private val CATEGORY_STRINGS: Map<String, Int> = mapOf(
    FoodLabels.CATEGORY_FRUIT to R.string.category_builtin_fruit,
    FoodLabels.CATEGORY_VEGETABLES to R.string.category_builtin_vegetables,
    FoodLabels.CATEGORY_BAKERY to R.string.category_builtin_bakery,
    FoodLabels.CATEGORY_DAIRY to R.string.category_builtin_dairy,
    FoodLabels.CATEGORY_PROTEIN to R.string.category_builtin_meat_fish,
    FoodLabels.CATEGORY_DRINKS to R.string.category_builtin_drinks,
    FoodLabels.CATEGORY_SWEETS to R.string.category_builtin_sweets,
    FoodLabels.CATEGORY_PANTRY to R.string.category_builtin_pantry
)

fun storageLocationLabel(resources: Resources, location: String): String =
    STORAGE_LOCATION_STRINGS[location]?.let(resources::getString) ?: location

@Composable
fun storageLocationLabel(location: String): String =
    storageLocationLabel(LocalContext.current.resources, location)

/** Whether [name] already names one of [locations], either exactly as stored or as shown. Without
 *  the second check, adding "Kjøleskap" next to the built-in "Fridge" would give two locations
 *  that show up under the same name. */
fun isExistingLocation(locations: List<String>, name: String, label: (String) -> String): Boolean =
    locations.any { it == name || label(it) == name }

/** The name to rename [location] to when the user confirms [input], or null to leave it as it
 *  is. The rename field starts out showing the translated name, so confirming it unchanged must
 *  not replace a built-in key with its translation: items stored under the key would no longer
 *  match the location. */
fun storageLocationRename(
    location: String,
    input: String,
    locations: List<String>,
    label: (String) -> String
): String? {
    val newName = input.trim()
    if (newName.isBlank() || newName == label(location) || isExistingLocation(locations, newName, label)) return null
    return newName
}

/** How a category name is shown: a built-in key ("Dairy") translated, anything else (a name the
 *  user typed or renamed to, an Open Food Facts category) as-is. Applied to the resolved display
 *  name, so a renamed built-in shows its new name and an untouched one follows the device's
 *  language, without the translation ever being written to the database. */
fun categoryLabel(resources: Resources, name: String): String =
    CATEGORY_STRINGS[name]?.let(resources::getString) ?: name

@Composable
fun categoryLabel(name: String): String = categoryLabel(LocalContext.current.resources, name)

/** What the item editor's category field shows for a stored [category]: the name it is shown
 *  under everywhere else, so the field never shows a raw English key. A disabled category is
 *  shown nowhere outside Settings, so it leaves the field blank (saving that unchanged still keeps
 *  it - see [categoryForFieldText]). */
fun categoryFieldText(
    category: String,
    preferences: List<CategoryPreference>,
    label: (String) -> String
): String {
    val preference = preferences.firstOrNull { it.originName == category } ?: return label(category)
    return if (preference.enabled) label(preference.displayName) else ""
}

/**
 * The category to store for what the item editor's field says when saving an item that had
 * [original] as its category.
 *
 * Unchanged text keeps [original]: several categories can share a display name (merged in
 * Settings), so mapping the name back could otherwise move the item to a different one just
 * because it was edited. Otherwise a built-in category's translated name maps to its English key
 * (what shelf-life defaults match on), a name shown for an existing category maps to that
 * category, and anything else is a new category, stored as typed. A blank field is no category,
 * unless it is the blank a disabled [original] shows.
 */
fun categoryForFieldText(
    text: String,
    original: String?,
    preferences: List<CategoryPreference>,
    label: (String) -> String
): String {
    if (original != null && text == categoryFieldText(original, preferences, label)) return original
    if (text.isBlank()) return ""
    CATEGORY_STRINGS.keys.firstOrNull { label(it) == text }?.let { return it }
    preferences.firstOrNull { label(it.displayName) == text }?.let { return it.originName }
    return text
}

/** The display name to store when the user renames a category to [text]. Picking a translated
 *  name ("Молочное") stands for the built-in key it translates ("Dairy"), so that renaming a
 *  category to it merges the two, as renaming to any other existing name does. */
fun categoryNameForInput(text: String, displayNames: List<String>, label: (String) -> String): String =
    displayNames.firstOrNull { label(it) == text } ?: text

/** The display name to rename a category currently shown as [current] to when the user
 *  confirms [input], or null to leave it alone. Confirming the name the field was prefilled
 *  with must change nothing, even when another category shows the same text: mapping it back
 *  could otherwise pick that other category's name, renaming or merging this one, or freezing
 *  an untouched built-in in today's language. */
fun categoryRename(
    current: String,
    input: String,
    displayNames: List<String>,
    label: (String) -> String
): String? {
    val text = input.trim()
    if (text.isBlank() || text == label(current)) return null
    val newName = categoryNameForInput(text, displayNames, label)
    return newName.takeUnless { it == current }
}
