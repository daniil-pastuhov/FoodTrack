package com.foodtracker.backup

import com.foodtracker.model.AppSettings
import com.foodtracker.model.CachedProduct
import com.foodtracker.model.CategoryPreference
import com.foodtracker.model.PantryItem
import com.foodtracker.model.ShoppingListEntry
import com.foodtracker.model.ShoppingSection
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A full export of everything the app stores locally.
 *
 * Pantry data lives only on the device — there is no backend, no login and no sync — so this
 * file is the only thing standing between a lost or replaced phone and a lost pantry. It is also
 * the only way data crosses between the Android and iOS apps, which keep entirely separate
 * databases.
 *
 * **Photos are not included.** A photographed item's `imageUrl` is a local file path; the paths
 * are kept (so a restore onto the same device still finds its photos) but the image files
 * themselves are not embedded, because base64 inside a JSON document would multiply a backup's
 * size by orders of magnitude for data the user can retake. A restore onto a different device
 * shows those items with the usual placeholder icon.
 */
@Serializable
data class BackupDocument(
    /** Bumped only on a breaking change. [BackupSerializer.decode] refuses a version it doesn't
     *  understand rather than silently dropping fields it can't read. */
    val version: Int = CURRENT_VERSION,
    val exportedAtEpochMillis: Long = 0L,
    val items: List<PantryItem> = emptyList(),
    val shoppingList: List<ShoppingListEntry> = emptyList(),
    /** The user's shopping-list sections (never the default one, which every install has).
     *  Absent from backups made before sections existed, which restore into the default. */
    val shoppingSections: List<ShoppingSection> = emptyList(),
    val categoryPreferences: List<CategoryPreference> = emptyList(),
    val products: List<CachedProduct> = emptyList(),
    val knownProductNames: List<String> = emptyList(),
    val settings: AppSettings = AppSettings()
) {
    /** Nothing worth restoring. An empty-but-valid backup is a real thing (a new install that
     *  exported immediately), so callers say so rather than reporting a broken file. */
    val isEmpty: Boolean
        get() = items.isEmpty() && shoppingList.isEmpty() && shoppingSections.isEmpty() && products.isEmpty() &&
                categoryPreferences.isEmpty() && knownProductNames.isEmpty()

    companion object {
        const val CURRENT_VERSION = 1
    }
}

/** How a restore treats what is already on the device. */
enum class RestoreMode {
    /**
     * Adds what's missing and leaves everything else alone. Existing pantry items keep their
     * current state, and settings are not touched — they are this device's preferences, not the
     * backup's. The safe default: nothing the user has can be lost by choosing it.
     */
    MERGE,

    /**
     * Wipes local data and restores the backup's, settings included. Chosen when the backup is
     * the intended truth — a new phone, or undoing a mess.
     */
    REPLACE
}

/** What a restore did, so the UI can report it rather than just saying "done". */
data class RestoreSummary(
    val itemsRestored: Int = 0,
    val shoppingEntriesRestored: Int = 0,
    val productsRestored: Int = 0,
    val categoriesRestored: Int = 0,
    val settingsRestored: Boolean = false
) {
    val isEmpty: Boolean
        get() = itemsRestored == 0 && shoppingEntriesRestored == 0 &&
                productsRestored == 0 && categoriesRestored == 0
}

/** Why a backup file couldn't be read. Distinguished so the UI can tell the user something
 *  actionable instead of a generic failure. */
sealed interface BackupReadResult {
    data class Success(val document: BackupDocument) : BackupReadResult

    /** Not JSON, or not this app's JSON. */
    data object Malformed : BackupReadResult

    /** Written by a newer version of the app than this one. */
    data class UnsupportedVersion(val version: Int) : BackupReadResult
}

/**
 * Reads and writes [BackupDocument]s.
 *
 * Every model it touches was already `@Serializable` for other reasons (settings are stored as
 * JSON in SharedPreferences, the shared vocabulary crosses platforms), so the file format falls
 * out of the domain model rather than needing a parallel set of DTOs to drift from it.
 *
 * iOS reimplements this with `Codable` in `Backup/BackupDocument.swift`; the field names here
 * are therefore a cross-platform contract, and a rename breaks restoring an Android backup on
 * iOS and vice versa.
 */
object BackupSerializer {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        // Written out in full rather than omitting defaults: a backup is read by a different
        // app version, possibly on the other platform, and an absent field there is
        // indistinguishable from one that was deliberately set to its default.
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(document: BackupDocument): String = json.encodeToString(document)

    fun decode(raw: String): BackupReadResult {
        val document = try {
            json.decodeFromString<BackupDocument>(raw)
        } catch (_: Exception) {
            return BackupReadResult.Malformed
        }
        if (document.version > BackupDocument.CURRENT_VERSION) {
            return BackupReadResult.UnsupportedVersion(document.version)
        }
        return BackupReadResult.Success(document)
    }

    /** Filename for an export, stamped so successive backups don't overwrite each other in the
     *  user's file picker. [isoDate] is supplied by the caller because formatting a date needs
     *  platform APIs this module deliberately doesn't reach for. */
    fun suggestedFileName(isoDate: String): String = "foodtracker-backup-$isoDate.json"
}
