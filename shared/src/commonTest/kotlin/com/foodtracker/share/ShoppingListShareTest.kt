package com.foodtracker.share

import com.foodtracker.model.ShoppingListEntry
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShoppingListShareTest {

    private fun entry(name: String, quantity: Int = 1, category: String? = null) = ShoppingListEntry(
        id = "id_$name",
        productName = name,
        category = category,
        quantity = quantity,
        addedDate = LocalDate(2026, 9, 23)
    )

    private val list = listOf(
        entry("Milk", quantity = 2, category = "Dairy"),
        entry("Cheese", category = "Dairy"),
        entry("Bread")
    )

    @Test
    fun textGroupsByCategoryWithUncategorizedLast() {
        val text = ShoppingListShare.toText(list, title = "Shopping list", uncategorizedLabel = "Other")
        assertEquals(
            """
            Shopping list

            Dairy
            - Milk (2)
            - Cheese

            Other
            - Bread
            """.trimIndent(),
            text
        )
    }

    @Test
    fun textOfAnEmptyListIsJustTheTitle() {
        assertEquals("Shopping list", ShoppingListShare.toText(emptyList(), "Shopping list", "Other"))
    }

    @Test
    fun textUsesTheResolvedCategoryLabelRatherThanTheRawValue() {
        // The list renders renamed categories, so the shared text has to as well - otherwise it
        // exposes raw Open Food Facts values the user has never seen.
        val text = ShoppingListShare.toText(
            listOf(entry("Milk", category = "en:dairy")),
            title = "Shopping list",
            uncategorizedLabel = "Other",
            categoryLabel = { if (it == "en:dairy") "Meieri" else it }
        )
        assertTrue(text.contains("Meieri"), text)
        assertFalse(text.contains("en:dairy"), text)
    }

    @Test
    fun aPayloadRoundTripsThroughParse() {
        val parsed = ShoppingListShare.parse(ShoppingListShare.toPayload(list))
        assertEquals(
            listOf(
                ImportedShoppingEntry("Milk", 2, "Dairy"),
                ImportedShoppingEntry("Cheese", 1, "Dairy"),
                ImportedShoppingEntry("Bread", 1, null)
            ),
            parsed
        )
    }

    @Test
    fun exportedTextRoundTripsBackIntoTheSameItems() {
        val text = ShoppingListShare.toText(list, title = "Shopping list", uncategorizedLabel = "Other")
        val parsed = ShoppingListShare.parse(text)
        // Category headers must not come back as items, and categories are deliberately not
        // recovered - the receiving device resolves those from the product itself.
        assertEquals(listOf("Milk", "Cheese", "Bread"), parsed.map { it.productName })
        assertEquals(listOf(2, 1, 1), parsed.map { it.quantity })
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
