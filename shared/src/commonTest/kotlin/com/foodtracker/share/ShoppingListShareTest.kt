package com.foodtracker.share

import com.foodtracker.model.ShoppingListEntry
import com.foodtracker.model.ShoppingSection
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShoppingListShareTest {

    private fun entry(
        name: String,
        quantity: Int = 1,
        category: String? = null,
        sectionId: String? = null
    ) = ShoppingListEntry(
        id = "id_$name",
        productName = name,
        category = category,
        quantity = quantity,
        addedDate = LocalDate(2026, 9, 23),
        sectionId = sectionId
    )

    private val dairy = ShoppingSection(id = "s_dairy", name = "Dairy", sortOrder = 0.0)
    private val bakery = ShoppingSection(id = "s_bakery", name = "Bakery", sortOrder = 1.0)
    private val sections = listOf(bakery, dairy)

    private val list = listOf(
        entry("Milk", quantity = 2, category = "en:milk", sectionId = "s_dairy"),
        entry("Bread", sectionId = "s_bakery"),
        entry("Soap"),
        entry("Cheese", sectionId = "s_dairy")
    )

    @Test
    fun textGroupsBySectionWithTheDefaultSectionFirstAndUnlabeled() {
        val text = ShoppingListShare.toText(list, title = "Shopping list", sections = sections)
        assertEquals(
            """
            Shopping list

            - Soap

            Dairy
            - Milk (2)
            - Cheese

            Bakery
            - Bread
            """.trimIndent(),
            text
        )
    }

    @Test
    fun textOfAnEmptyListIsJustTheTitle() {
        assertEquals("Shopping list", ShoppingListShare.toText(emptyList(), "Shopping list"))
    }

    @Test
    fun anEntryInADeletedSectionIsSharedWithTheDefaultSection() {
        val text = ShoppingListShare.toText(
            listOf(entry("Milk", sectionId = "gone")),
            title = "Shopping list",
            sections = sections
        )
        assertEquals("Shopping list\n\n- Milk", text)
    }

    @Test
    fun textUsesTheSectionLabelRatherThanTheStoredName() {
        // A section converted from a built-in category stores its English key; the shared text
        // has to show it the way the list does.
        val text = ShoppingListShare.toText(
            listOf(entry("Milk", sectionId = "s_dairy")),
            title = "Shopping list",
            sections = sections,
            sectionLabel = { if (it.name == "Dairy") "Meieri" else it.name }
        )
        assertTrue(text.contains("Meieri"), text)
        assertFalse(text.contains("Dairy"), text)
    }

    @Test
    fun aPayloadRoundTripsThroughParseWithSectionNames() {
        val parsed = ShoppingListShare.parse(ShoppingListShare.toPayload(list, sections))
        assertEquals(
            listOf(
                ImportedShoppingEntry("Milk", 2, "en:milk", section = "Dairy"),
                ImportedShoppingEntry("Bread", 1, null, section = "Bakery"),
                ImportedShoppingEntry("Soap", 1, null, section = null),
                ImportedShoppingEntry("Cheese", 1, null, section = "Dairy")
            ),
            parsed
        )
    }

    @Test
    fun aBlankSectionNameInAPayloadMeansTheDefaultSection() {
        // Otherwise the receiver would create, or match, a section with an empty name.
        val raw = ShoppingListShare.PAYLOAD_PREFIX + """{"v":1,"e":[{"n":"Milk","s":"  "}]}"""
        assertEquals(listOf(ImportedShoppingEntry("Milk", 1, null, section = null)), ShoppingListShare.parse(raw))
    }

    @Test
    fun aPayloadFromBeforeSectionsImportsIntoTheDefaultSection() {
        val old = ShoppingListShare.PAYLOAD_PREFIX + """{"v":1,"e":[{"n":"Milk","c":"Dairy"}]}"""
        assertEquals(listOf(ImportedShoppingEntry("Milk", 1, "Dairy", section = null)), ShoppingListShare.parse(old))
    }

    @Test
    fun exportedTextRoundTripsBackIntoTheSameItems() {
        val text = ShoppingListShare.toText(list, title = "Shopping list", sections = sections)
        val parsed = ShoppingListShare.parse(text)
        // Section headings must not come back as items.
        assertEquals(listOf("Soap", "Milk", "Cheese", "Bread"), parsed.map { it.productName })
        assertEquals(listOf(1, 2, 1, 1), parsed.map { it.quantity })
    }

    @Test
    fun aHandTypedListWithNoBulletsTreatsEveryLineAsAnItem() {
        val parsed = ShoppingListShare.parse("Milk\nBread\n\nEggs (6)")
        assertEquals(listOf("Milk", "Bread", "Eggs"), parsed.map { it.productName })
        assertEquals(6, parsed.last().quantity)
    }

    @Test
    fun bulletVariantsAreAccepted() {
        val parsed = ShoppingListShare.parse("* Milk\n• Bread\n- Eggs")
        assertEquals(listOf("Milk", "Bread", "Eggs"), parsed.map { it.productName })
    }

    @Test
    fun aParenthesisedSuffixThatIsNotANumberStaysPartOfTheName() {
        val parsed = ShoppingListShare.parse("- Milk (organic)")
        assertEquals("Milk (organic)", parsed.single().productName)
        assertEquals(1, parsed.single().quantity)
    }

    @Test
    fun onlyOurOwnPayloadsAreRecognisedAsPayloads() {
        assertTrue(ShoppingListShare.isPayload(ShoppingListShare.toPayload(list)))
        // A plain product barcode must not be mistaken for a shared list, or scanning groceries
        // would start importing them as shopping list entries.
        assertFalse(ShoppingListShare.isPayload("7038010000000"))
        assertFalse(ShoppingListShare.isPayload("https://example.invalid"))
        assertFalse(ShoppingListShare.isPayload("foodtracker:sl:v2:{}"))
    }

    @Test
    fun aMalformedPayloadYieldsNothingRatherThanThrowing() {
        // It arrived off a camera, so a partial read is expected rather than exceptional.
        assertEquals(emptyList(), ShoppingListShare.parse(ShoppingListShare.PAYLOAD_PREFIX + "{not json"))
        assertEquals(emptyList(), ShoppingListShare.parse(ShoppingListShare.PAYLOAD_PREFIX))
    }

    @Test
    fun blankAndEmptyInputYieldNothing() {
        assertEquals(emptyList(), ShoppingListShare.parse(""))
        assertEquals(emptyList(), ShoppingListShare.parse("   \n\n  "))
    }

    @Test
    fun importedQuantitiesAreClampedToSomethingSane() {
        val parsed = ShoppingListShare.parse("- Milk (0)")
        assertEquals(1, parsed.single().quantity)
    }
}
