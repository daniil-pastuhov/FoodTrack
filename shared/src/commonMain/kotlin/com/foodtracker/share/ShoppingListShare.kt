package com.foodtracker.share

import com.foodtracker.model.ShoppingListEntry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One entry as it comes back from an import. Deliberately not a [ShoppingListEntry]: an imported
 * row has no id, no added date and no sort order of its own — those are assigned by the
 * receiving device when the entry is actually added, so that an import lands in that device's
 * own ordering rather than carrying a foreign one.
 */
data class ImportedShoppingEntry(
    val productName: String,
    val quantity: Int = 1,
    val category: String? = null
)

/**
 * Exports and imports the shopping list as text or as a QR payload.
 *
 * There is no backend and no sync, so this is the whole "share the list with whoever is doing
 * the shopping" story: text goes through the share sheet into any messaging app, and the QR
 * payload covers handing the list to someone standing next to you. Both directions are plain
 * data — nothing here touches a repository.
 *
 * iOS reimplements this by hand in `Sharing/ShoppingListShare.swift` (nothing under `iosApp/`
 * consumes this module), so the payload format below is a cross-platform contract: a change to
 * [PAYLOAD_PREFIX] or the JSON field names breaks scanning between an Android and an iOS device
 * and has to be made on both sides together.
 */
object ShoppingListShare {

    /**
     * Marks a QR code as one of ours. The scanner feeds every QR it sees through
     * [parsePayload] before treating the contents as a product barcode, so without a prefix a
     * shared list would be looked up against Open Food Facts as if it were an EAN.
     *
     * The version segment is what lets a future format change be detected rather than
     * mis-parsed: [parse] rejects a prefix it doesn't know instead of guessing.
     */
    const val PAYLOAD_PREFIX = "foodtracker:sl:v1:"

    /** Guard against pathological input - a pasted novel, or a QR payload built to blow up the
     *  receiving device. Well past any realistic shopping list. */
    private const val MAX_IMPORTED_ENTRIES = 500

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    // Short field names: a QR code's capacity is the binding constraint here, and "productName"
    // repeated 30 times is a meaningful share of it.
    @Serializable
    private data class PayloadEntry(
        @SerialName("n") val name: String,
        @SerialName("q") val quantity: Int = 1,
        @SerialName("c") val category: String? = null
    )

    @Serializable
    private data class Payload(
        @SerialName("v") val version: Int = 1,
        @SerialName("e") val entries: List<PayloadEntry> = emptyList()
    )

    /**
     * The list as human-readable text, grouped by category under [title].
     *
     * [categoryLabel] resolves an entry's raw category to what the user sees (the caller passes
     * the same resolution the list itself renders with), and [uncategorizedLabel] names the
     * bucket for entries with no category. Quantities above one are appended as "(2)", which is
     * also what [parseText] reads back.
     */
    fun toText(
        entries: List<ShoppingListEntry>,
        title: String,
        uncategorizedLabel: String,
        categoryLabel: (String?) -> String? = { it }
    ): String {
        if (entries.isEmpty()) return title

        val grouped = entries.groupBy { categoryLabel(it.category) }
        val builder = StringBuilder(title)
        // Named groups first, in the order they appear, with the uncategorized bucket last -
        // the same shape the on-screen list has.
        val orderedKeys = grouped.keys.filterNotNull().sortedBy { key ->
            entries.indexOfFirst { categoryLabel(it.category) == key }
        } + if (grouped.containsKey(null)) listOf(null) else emptyList()

        orderedKeys.forEach { key ->
            builder.append("\n\n").append(key ?: uncategorizedLabel)
            grouped[key].orEmpty().forEach { entry ->
                builder.append("\n- ").append(entry.productName)
                if (entry.quantity > 1) builder.append(" (").append(entry.quantity).append(')')
            }
        }
        return builder.toString()
    }

    /** The list as a QR payload: [PAYLOAD_PREFIX] followed by compact JSON. */
    fun toPayload(entries: List<ShoppingListEntry>): String {
        val payload = Payload(
            entries = entries.take(MAX_IMPORTED_ENTRIES).map {
                PayloadEntry(name = it.productName, quantity = it.quantity, category = it.category)
            }
        )
        return PAYLOAD_PREFIX + json.encodeToString(payload)
    }

    /** True when [raw] looks like one of our QR payloads, so a scanner can route it here rather
     *  than treating it as a product barcode. */
    fun isPayload(raw: String): Boolean = raw.trimStart().startsWith(PAYLOAD_PREFIX)

    /**
     * Parses [raw] as either a QR payload or pasted text, whichever it looks like. Returns an
     * empty list when nothing usable was found - callers show "nothing to import" rather than
     * distinguishing malformed from empty.
     */
    fun parse(raw: String): List<ImportedShoppingEntry> =
        if (isPayload(raw)) parsePayload(raw) else parseText(raw)

    /** Parses a QR payload produced by [toPayload]. A payload whose prefix is present but whose
     *  JSON is malformed yields an empty list rather than throwing - it reached us off a camera,
     *  so it may well be a partial read. */
    fun parsePayload(raw: String): List<ImportedShoppingEntry> {
        val body = raw.trimStart().removePrefix(PAYLOAD_PREFIX)
        val payload = try {
            json.decodeFromString<Payload>(body)
        } catch (_: Exception) {
            return emptyList()
        }
        return payload.entries.asSequence()
            .mapNotNull { it.toImported() }
            .take(MAX_IMPORTED_ENTRIES)
            .toList()
    }

    /**
     * Parses pasted text.
     *
     * Handles both text this app exported and a list someone typed by hand. The two need
     * different rules: an export has category headers, which are plain lines and must not
     * become items, while a hand-typed list is usually all plain lines and all of them are
     * items. So if *any* line is bulleted, only bulleted lines count; otherwise every non-blank
     * line does. A trailing "(2)" is read back as a quantity either way.
     *
     * Categories are deliberately not recovered from headers: the receiving device resolves a
     * category from the product itself, and importing a foreign display name would create a
     * duplicate category that happens to read the same.
     */
    fun parseText(raw: String): List<ImportedShoppingEntry> {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val bulleted = lines.filter { it.isBulleted() }
        val candidates = if (bulleted.isNotEmpty()) bulleted else lines
        return candidates.asSequence()
            .map { it.stripBullet() }
            .mapNotNull { it.toImportedLine() }
            .take(MAX_IMPORTED_ENTRIES)
            .toList()
    }

    private fun PayloadEntry.toImported(): ImportedShoppingEntry? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        return ImportedShoppingEntry(
            productName = trimmed,
            quantity = quantity.coerceIn(1, MAX_QUANTITY),
            category = category?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    private fun String.isBulleted(): Boolean =
        startsWith("- ") || startsWith("* ") || startsWith("• ")

    private fun String.stripBullet(): String =
        removePrefix("- ").removePrefix("* ").removePrefix("• ").trim()

    /** Splits a trailing "(2)" off the name and reads it as a quantity. A parenthesised suffix
     *  that isn't a plain number is left as part of the name - "Milk (organic)" is a product,
     *  not a quantity. */
    private fun String.toImportedLine(): ImportedShoppingEntry? {
        if (isEmpty()) return null
        val match = QUANTITY_SUFFIX.find(this)
        if (match != null) {
            val name = substring(0, match.range.first).trim()
            val quantity = match.groupValues[1].toIntOrNull()
            if (name.isNotEmpty() && quantity != null) {
                return ImportedShoppingEntry(name, quantity.coerceIn(1, MAX_QUANTITY))
            }
        }
        return ImportedShoppingEntry(this)
    }

    private const val MAX_QUANTITY = 999
    private val QUANTITY_SUFFIX = Regex("""\s*\((\d{1,3})\)\s*$""")
}
