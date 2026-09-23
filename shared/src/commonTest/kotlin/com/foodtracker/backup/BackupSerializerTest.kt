package com.foodtracker.backup

import com.foodtracker.model.AppSettings
import com.foodtracker.model.CachedProduct
import com.foodtracker.model.CategoryPreference
import com.foodtracker.model.PantryItem
import com.foodtracker.model.ShoppingListEntry
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BackupSerializerTest {

    private val document = BackupDocument(
        exportedAtEpochMillis = 1_758_600_000_000L,
        items = listOf(
            PantryItem(
                id = "item-1",
                name = "Milk",
                brand = "Tine",
                barcode = "7038010000000",
                expiryDate = LocalDate(2026, 10, 1),
                addedDate = LocalDate(2026, 9, 23),
                storageLocation = "Fridge",
                category = "Dairy",
                quantity = 2,
                notes = "Top shelf",
                essential = true
            )
        ),
        shoppingList = listOf(
            ShoppingListEntry(id = "s-1", productName = "Bread", addedDate = LocalDate(2026, 9, 23))
        ),
        categoryPreferences = listOf(
            CategoryPreference("Dairy", "Meieri", sortOrder = 2.0, enabled = true, defaultShelfLifeDays = 10)
        ),
        products = listOf(
            CachedProduct("7038010000000", "Milk", "Tine", null, "Dairy", shelfLifeDays = 8)
        ),
        knownProductNames = listOf("Milk", "Bread"),
        settings = AppSettings(expiringSoonDays = 5)
    )

    @Test
    fun aDocumentRoundTripsThroughJsonUnchanged() {
        val decoded = BackupSerializer.decode(BackupSerializer.encode(document))
        assertEquals(document, assertIs<BackupReadResult.Success>(decoded).document)
    }

    @Test
    fun everyFieldSurvivesTheRoundTrip() {
        // A backup is the only copy of this data, so a field silently dropped by the format is
        // data loss rather than a cosmetic bug.
        val decoded = assertIs<BackupReadResult.Success>(
            BackupSerializer.decode(BackupSerializer.encode(document))
        ).document
        assertEquals("Top shelf", decoded.items.single().notes)
        assertEquals(2, decoded.items.single().quantity)
        assertEquals(10, decoded.categoryPreferences.single().defaultShelfLifeDays)
        assertEquals(8, decoded.products.single().shelfLifeDays)
        assertEquals(5, decoded.settings.expiringSoonDays)
        assertEquals(listOf("Milk", "Bread"), decoded.knownProductNames)
    }

    @Test
    fun defaultsAreWrittenOutRatherThanOmitted() {
        // The file is read by a different app version, possibly on the other platform, where an
        // absent field is indistinguishable from one deliberately set to its default.
        val json = BackupSerializer.encode(BackupDocument())
        assertTrue(json.contains("\"version\""), json)
        assertTrue(json.contains("\"settings\""), json)
    }

    @Test
    fun aMalformedFileIsReportedAsSuchRatherThanThrowing() {
        assertIs<BackupReadResult.Malformed>(BackupSerializer.decode("not json at all"))
        assertIs<BackupReadResult.Malformed>(BackupSerializer.decode(""))
        assertIs<BackupReadResult.Malformed>(BackupSerializer.decode("{\"items\": \"wrong type\"}"))
    }

    @Test
    fun aNewerBackupIsRefusedRatherThanPartiallyRead() {
        // Reading it with fields we don't understand would restore a subset and call it a
        // success, which is the one outcome worse than refusing.
        val future = BackupSerializer.encode(document.copy(version = BackupDocument.CURRENT_VERSION + 1))
        val result = assertIs<BackupReadResult.UnsupportedVersion>(BackupSerializer.decode(future))
        assertEquals(BackupDocument.CURRENT_VERSION + 1, result.version)
    }

    @Test
    fun unknownFieldsFromAnOlderOrSiblingVersionAreIgnored() {
        val withExtra = """{"version":1,"exportedAtEpochMillis":0,"somethingNew":42}"""
        assertIs<BackupReadResult.Success>(BackupSerializer.decode(withExtra))
    }

    @Test
    fun emptinessIsDistinguishedFromBeingBroken() {
        assertTrue(BackupDocument().isEmpty)
        assertFalse(document.isEmpty)
        // An empty-but-valid backup is a real thing (a fresh install that exported immediately).
        val decoded = assertIs<BackupReadResult.Success>(
            BackupSerializer.decode(BackupSerializer.encode(BackupDocument()))
        )
        assertTrue(decoded.document.isEmpty)
    }

    @Test
    fun theSuggestedFileNameIsStamped() {
        assertEquals("foodtracker-backup-2026-09-23.json", BackupSerializer.suggestedFileName("2026-09-23"))
    }

    @Test
    fun aRestoreSummaryKnowsWhenNothingHappened() {
        assertTrue(RestoreSummary().isEmpty)
        assertFalse(RestoreSummary(itemsRestored = 1).isEmpty)
        // settingsRestored alone is not "something restored" - replacing settings with identical
        // ones would otherwise report success on an empty file.
        assertTrue(RestoreSummary(settingsRestored = true).isEmpty)
    }
}
