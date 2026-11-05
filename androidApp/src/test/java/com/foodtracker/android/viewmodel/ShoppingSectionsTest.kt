package com.foodtracker.android.viewmodel

import com.foodtracker.android.perf.FakeCategoryPreferenceRepository
import com.foodtracker.android.perf.FakeLocalProductRepository
import com.foodtracker.android.perf.FakePantryRepository
import com.foodtracker.android.perf.FakeProductNameRepository
import com.foodtracker.android.perf.FakeSettingsRepository
import com.foodtracker.android.perf.FakeShoppingListRepository
import com.foodtracker.android.perf.FakeShoppingSectionRepository
import com.foodtracker.backup.RestoreMode
import com.foodtracker.model.AppSettings
import com.foodtracker.model.DEFAULT_SHOPPING_SECTION_ID
import com.foodtracker.model.ShoppingListEntry
import com.foodtracker.model.ShoppingSection
import com.foodtracker.share.ShoppingListShare
import com.foodtracker.util.DateUtils
import com.foodtracker.viewmodel.PantryStateHolder
import com.foodtracker.viewmodel.ShoppingRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

/** The shopping list's user-made sections: moving entries between them, remembering a
 *  product's section, and keeping entries safe when a section goes away. */
class ShoppingSectionsTest {

    private class Fixture(
        entries: List<ShoppingListEntry> = emptyList(),
        sections: List<ShoppingSection> = emptyList()
    ) {
        val shopping = FakeShoppingListRepository(entries)
        val sectionRepo = FakeShoppingSectionRepository(sections, shopping)
        val names = FakeProductNameRepository()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val stateHolder = PantryStateHolder(
            repository = FakePantryRepository(),
            settingsRepository = FakeSettingsRepository(AppSettings()),
            shoppingListRepository = shopping,
            productNameRepository = names,
            localProductRepository = FakeLocalProductRepository(),
            categoryPreferenceRepository = FakeCategoryPreferenceRepository(),
            shoppingSectionRepository = sectionRepo,
            uncategorizedLabel = "Other",
            coroutineScope = scope,
            computationContext = EmptyCoroutineContext
        )

        val rows: List<String>
            get() = stateHolder.uiState.value.shoppingRows.map {
                when (it) {
                    is ShoppingRow.Header -> "# ${it.section.id}"
                    is ShoppingRow.Item -> it.entry.productName
                }
            }

        fun entry(name: String) = shopping.entriesFlow.value.single { it.productName == name }

        fun close() = scope.cancel()
    }

    private fun entry(id: String, name: String, sortOrder: Double, sectionId: String? = null) = ShoppingListEntry(
        id = id,
        productName = name,
        addedDate = DateUtils.today(),
        sortOrder = sortOrder,
        sectionId = sectionId
    )

    private val dairy = ShoppingSection("dairy", "Dairy", sortOrder = 0.0)
    private val bakery = ShoppingSection("bakery", "Bakery", sortOrder = 1.0)

    @Test
    fun newEntriesLandInTheDefaultSectionRegardlessOfCategory() {
        val fixture = Fixture(sections = listOf(dairy))
        try {
            fixture.stateHolder.addManualShoppingListEntry("Milk")
            assertEquals(listOf("# default", "Milk", "# dairy"), fixture.rows)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun addingANewNameDoesNotReportADuplicate() {
        val fixture = Fixture()
        try {
            val duplicates = mutableListOf<String>()
            fixture.stateHolder.addManualShoppingListEntry("Milk") { duplicates += it }
            assertEquals(listOf("# default", "Milk"), fixture.rows)
            assertEquals(emptyList<String>(), duplicates)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun addingANameAlreadyOnTheListReportsItAsWrittenThereAndAddsNothing() {
        val fixture = Fixture(entries = listOf(entry("a", "Oat milk", 1.0)))
        try {
            val duplicates = mutableListOf<String>()
            fixture.stateHolder.addManualShoppingListEntry("  OAT MILK ") { duplicates += it }
            assertEquals(listOf("# default", "Oat milk"), fixture.rows)
            assertEquals(listOf("Oat milk"), duplicates)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun aBlankNameIsNeitherAddedNorReported() {
        val fixture = Fixture()
        try {
            val duplicates = mutableListOf<String>()
            fixture.stateHolder.addManualShoppingListEntry("   ") { duplicates += it }
            assertEquals(listOf("# default"), fixture.rows)
            assertEquals(emptyList<String>(), duplicates)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun movingAnEntryToAnotherSectionPlacesItBetweenItsNewNeighbours() {
        val fixture = Fixture(
            entries = listOf(
                entry("1", "Milk", 1.0),
                entry("2", "Cheese", 2.0, "dairy"),
                entry("3", "Yogurt", 3.0, "dairy")
            ),
            sections = listOf(dairy)
        )
        try {
            fixture.stateHolder.moveShoppingListEntry("1", "dairy", beforeId = "2", afterId = "3")
            val milk = fixture.entry("Milk")
            assertEquals("dairy", milk.sectionId)
            // The fake keeps insertion order (Room orders by sortOrder), so check the key itself.
            assertTrue(milk.sortOrder > 2.0 && milk.sortOrder < 3.0)
            assertEquals("# dairy", fixture.rows[1])
        } finally {
            fixture.close()
        }
    }

    @Test
    fun aReAddedProductGoesBackToItsLastSectionAndPosition() {
        val fixture = Fixture(
            entries = listOf(entry("1", "Milk", 1.0), entry("2", "Cheese", 2.0, "dairy")),
            sections = listOf(dairy)
        )
        try {
            fixture.stateHolder.moveShoppingListEntry("1", "dairy", beforeId = "2", afterId = null)
            val remembered = fixture.entry("Milk").sortOrder
            fixture.stateHolder.deleteShoppingListEntry("1")

            fixture.stateHolder.addManualShoppingListEntry("milk")

            val readded = fixture.entry("milk")
            assertEquals("dairy", readded.sectionId)
            assertEquals(remembered, readded.sortOrder, 0.0)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun aProductWhoseSectionWasDeletedIsReAddedToTheDefaultSection() {
        val fixture = Fixture(entries = listOf(entry("1", "Milk", 1.0)), sections = listOf(dairy))
        try {
            fixture.stateHolder.moveShoppingListEntry("1", "dairy", null, null)
            fixture.stateHolder.deleteShoppingListEntry("1")
            fixture.stateHolder.deleteShoppingSection("dairy")

            fixture.stateHolder.addManualShoppingListEntry("Milk")

            assertNull(fixture.entry("Milk").sectionId)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun movingBackToTheDefaultSectionIsRemembered() {
        val fixture = Fixture(entries = listOf(entry("1", "Milk", 1.0, "dairy")), sections = listOf(dairy))
        try {
            fixture.stateHolder.moveShoppingListEntryToSection("1", DEFAULT_SHOPPING_SECTION_ID)
            assertNull(fixture.entry("Milk").sectionId)
            assertNull(fixture.names.sectionIds["milk"])
        } finally {
            fixture.close()
        }
    }

    @Test
    fun deletingASectionMovesItsEntriesToTheDefaultSection() {
        val fixture = Fixture(
            entries = listOf(entry("1", "Milk", 1.0, "dairy"), entry("2", "Bread", 2.0, "bakery")),
            sections = listOf(dairy, bakery)
        )
        try {
            fixture.stateHolder.deleteShoppingSection("dairy")
            assertEquals(listOf("# default", "Milk", "# bakery", "Bread"), fixture.rows)
            assertNull(fixture.entry("Milk").sectionId)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun theDefaultSectionCannotBeDeletedRenamedOrReordered() {
        val fixture = Fixture(entries = listOf(entry("1", "Milk", 1.0)), sections = listOf(dairy, bakery))
        try {
            fixture.stateHolder.deleteShoppingSection(DEFAULT_SHOPPING_SECTION_ID)
            fixture.stateHolder.renameShoppingSection(DEFAULT_SHOPPING_SECTION_ID, "Mine")
            fixture.stateHolder.reorderShoppingSections(listOf("bakery", DEFAULT_SHOPPING_SECTION_ID, "dairy"))

            val sections = fixture.stateHolder.uiState.value.shoppingSections
            assertEquals(listOf(DEFAULT_SHOPPING_SECTION_ID, "bakery", "dairy"), sections.map { it.id })
            assertEquals("", sections.first().name)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun aNewSectionGoesLastAndCollapsingIsPersisted() {
        val fixture = Fixture(entries = listOf(entry("1", "Milk", 1.0, "dairy")), sections = listOf(dairy, bakery))
        try {
            fixture.stateHolder.createShoppingSection("  Pharmacy ")
            fixture.stateHolder.setShoppingSectionCollapsed("dairy", true)

            val sections = fixture.stateHolder.uiState.value.shoppingSections
            assertEquals(listOf("", "Dairy", "Bakery", "Pharmacy"), sections.map { it.name })
            assertTrue(sections.single { it.id == "dairy" }.collapsed)
            assertEquals(listOf("# default", "# dairy", "# bakery", sections.last().let { "# ${it.id}" }), fixture.rows)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun anImportFilesEntriesIntoSectionsByName() {
        val fixture = Fixture(sections = listOf(dairy))
        try {
            val sender = listOf(
                entry("a", "Milk", 1.0, "s1"),
                entry("b", "Aspirin", 2.0, "s2"),
                entry("c", "Soap", 3.0)
            )
            val senderSections = listOf(ShoppingSection("s1", "dairy"), ShoppingSection("s2", "Pharmacy"))

            fixture.stateHolder.importShoppingList(ShoppingListShare.toPayload(sender, senderSections))

            assertEquals("matched case-insensitively", "dairy", fixture.entry("Milk").sectionId)
            val pharmacy = fixture.stateHolder.uiState.value.shoppingSections.single { it.name == "Pharmacy" }
            assertEquals(pharmacy.id, fixture.entry("Aspirin").sectionId)
            assertNull(fixture.entry("Soap").sectionId)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun aBackupCarriesSectionsAndRestoresThem() {
        val source = Fixture(entries = listOf(entry("1", "Milk", 1.0, "dairy")), sections = listOf(dairy))
        val json = try {
            var exported: String? = null
            source.stateHolder.exportBackup { encoded, _ -> exported = encoded }
            requireNotNull(exported)
        } finally {
            source.close()
        }

        val target = Fixture()
        try {
            target.stateHolder.restoreBackup(json, RestoreMode.MERGE) { _, _ -> }
            assertEquals(listOf("# default", "# dairy", "Milk"), target.rows)
        } finally {
            target.close()
        }
    }

    @Test
    fun moveToSectionAppendsAfterTheSectionsLastEntry() {
        val fixture = Fixture(
            entries = listOf(
                entry("1", "Milk", 1.0),
                entry("2", "Cheese", 2.0, "dairy"),
                entry("3", "Yogurt", 3.0, "dairy")
            ),
            sections = listOf(dairy)
        )
        try {
            fixture.stateHolder.moveShoppingListEntryToSection("1", "dairy")
            val milk = fixture.entry("Milk")
            assertEquals("dairy", milk.sectionId)
            assertTrue("after Yogurt, the section's last entry", milk.sortOrder > 3.0)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun renamingASectionTrimsTheName() {
        val fixture = Fixture(sections = listOf(dairy))
        try {
            fixture.stateHolder.renameShoppingSection("dairy", "  Milk & cheese  ")
            assertEquals("Milk & cheese", fixture.stateHolder.uiState.value.shoppingSections.single { it.id == "dairy" }.name)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun aMergeRestoreKeepsASectionTheDeviceAlreadyHas() {
        val source = Fixture(sections = listOf(dairy.copy(name = "Dairy (old)")))
        val json = try {
            var exported: String? = null
            source.stateHolder.exportBackup { encoded, _ -> exported = encoded }
            requireNotNull(exported)
        } finally {
            source.close()
        }

        val target = Fixture(sections = listOf(dairy.copy(name = "Dairy", collapsed = true)))
        try {
            target.stateHolder.restoreBackup(json, RestoreMode.MERGE) { _, _ -> }
            val kept = target.stateHolder.uiState.value.shoppingSections.single { it.id == "dairy" }
            assertEquals("Dairy", kept.name)
            assertTrue(kept.collapsed)
        } finally {
            target.close()
        }
    }

    @Test
    fun aReplaceRestoreSwapsInTheBackupsSectionsAndNeverExportsTheDefault() {
        val source = Fixture(sections = listOf(dairy))
        var document: com.foodtracker.backup.BackupDocument? = null
        val json = try {
            var exported: String? = null
            source.stateHolder.exportBackup { encoded, doc -> exported = encoded; document = doc }
            requireNotNull(exported)
        } finally {
            source.close()
        }
        assertEquals(listOf("dairy"), requireNotNull(document).shoppingSections.map { it.id })

        val target = Fixture(sections = listOf(bakery))
        try {
            target.stateHolder.restoreBackup(json, RestoreMode.REPLACE) { _, _ -> }
            assertEquals(
                listOf(DEFAULT_SHOPPING_SECTION_ID, "dairy"),
                target.stateHolder.uiState.value.shoppingSections.map { it.id }
            )
        } finally {
            target.close()
        }
    }
}
