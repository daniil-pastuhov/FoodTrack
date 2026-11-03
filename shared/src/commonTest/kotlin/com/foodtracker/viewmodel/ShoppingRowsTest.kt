package com.foodtracker.viewmodel

import com.foodtracker.model.DEFAULT_SHOPPING_SECTION_ID
import com.foodtracker.model.ShoppingListEntry
import com.foodtracker.model.ShoppingSection
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class ShoppingRowsTest {

    private fun entry(id: String, sectionId: String? = null, category: String? = null) = ShoppingListEntry(
        id = id,
        productName = id,
        category = category,
        addedDate = LocalDate(2026, 11, 3),
        sectionId = sectionId
    )

    private fun ShoppingRow.label(): String = when (this) {
        is ShoppingRow.Header -> "# ${section.id} (${itemCount})"
        is ShoppingRow.Item -> entry.id
    }

    private val dairy = ShoppingSection("dairy", "Dairy", sortOrder = 1.0)
    private val bakery = ShoppingSection("bakery", "Bakery", sortOrder = 0.0)

    @Test
    fun theDefaultSectionComesFirstThenSectionsInTheirOrder() {
        val rows = PantryDerivation.buildShoppingRows(
            listOf(entry("milk", "dairy"), entry("soap"), entry("bread", "bakery")),
            listOf(dairy, bakery)
        )
        assertEquals(
            listOf("# default (1)", "soap", "# bakery (1)", "bread", "# dairy (1)", "milk"),
            rows.map { it.label() }
        )
    }

    @Test
    fun categoriesNoLongerGroupTheList() {
        val rows = PantryDerivation.buildShoppingRows(
            listOf(entry("milk", category = "Dairy"), entry("bread", category = "Bakery")),
            emptyList()
        )
        assertEquals(listOf("# default (2)", "milk", "bread"), rows.map { it.label() })
    }

    @Test
    fun aCollapsedSectionKeepsItsHeaderAndCountButNotItsItems() {
        val rows = PantryDerivation.buildShoppingRows(
            listOf(entry("milk", "dairy"), entry("cream", "dairy"), entry("bread", "bakery")),
            listOf(dairy.copy(collapsed = true), bakery)
        )
        assertEquals(listOf("# default (0)", "# bakery (1)", "bread", "# dairy (2)"), rows.map { it.label() })
    }

    @Test
    fun anEmptySectionStillHasAHeaderToDropOnto() {
        val rows = PantryDerivation.buildShoppingRows(emptyList(), listOf(dairy))
        assertEquals(listOf("# default (0)", "# dairy (0)"), rows.map { it.label() })
    }

    @Test
    fun itemsKeepTheirIncomingOrderWithinASection() {
        val rows = PantryDerivation.buildShoppingRows(
            listOf(entry("c", "dairy"), entry("a", "dairy"), entry("b", "dairy")),
            listOf(dairy)
        )
        assertEquals(listOf("c", "a", "b"), rows.filterIsInstance<ShoppingRow.Item>().map { it.entry.id })
    }

    @Test
    fun anEntryInAMissingSectionFallsBackToTheDefault() {
        val rows = PantryDerivation.buildShoppingRows(listOf(entry("milk", "gone")), listOf(dairy))
        val item = rows.filterIsInstance<ShoppingRow.Item>().single()
        assertEquals(DEFAULT_SHOPPING_SECTION_ID, item.sectionId)
    }

    @Test
    fun theCollapsedOverrideFoldsSectionsWithoutTheirStoredFlag() {
        // What the screen does while an entry is being dragged out of "dairy".
        val rows = PantryDerivation.buildShoppingRows(
            listOf(entry("soap"), entry("milk", "dairy"), entry("bread", "bakery")),
            listOf(dairy, bakery)
        ) { it.id != "dairy" }
        assertEquals(listOf("# default (1)", "# bakery (1)", "# dairy (1)", "milk"), rows.map { it.label() })
    }

    @Test
    fun aStoredDefaultSectionAppearsOnceAndCanBeCollapsed() {
        val storedDefault = ShoppingSection(DEFAULT_SHOPPING_SECTION_ID, "", -Double.MAX_VALUE, collapsed = true)
        val rows = PantryDerivation.buildShoppingRows(
            listOf(entry("soap"), entry("milk", "dairy")),
            listOf(dairy, storedDefault)
        )
        assertEquals(listOf("# default (1)", "# dairy (1)", "milk"), rows.map { it.label() })
    }

    @Test
    fun sectionsWithTheSameOrderAreSortedByName() {
        val rows = PantryDerivation.buildShoppingRows(
            emptyList(),
            listOf(ShoppingSection("z", "zebra", 1.0), ShoppingSection("a", "Apple", 1.0))
        )
        assertEquals(listOf("# default (0)", "# a (0)", "# z (0)"), rows.map { it.label() })
    }
}
