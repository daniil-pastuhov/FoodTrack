package com.foodtracker.recipe

import kotlin.test.Test
import kotlin.test.assertEquals

/** [optionalWireIngredients]: the server's caps on "Send all products" (CONTRACT.md "Optional
 *  ingredients"), applied before sending so that what is sent is what the server uses. */
class OptionalIngredientsTest {

    private fun item(name: String) = WireIngredient(name, null, null)

    @Test
    fun keepsTheOrderGivenAndLeavesOutNamesAlreadyInTheRequest() {
        val kept = optionalWireIngredients(listOf(item("Bread")), listOf(item("Milk"), item("bread"), item("Eggs"), item("MILK")))
        assertEquals(listOf("Milk", "Eggs"), kept.map { it.name })
    }

    @Test
    fun tickedAndOptionalTogetherStayWithinThirtyNames() {
        val ticked = (1..25).map { item("Ticked $it") }
        val kept = optionalWireIngredients(ticked, (1..30).map { item("Spare $it") })
        assertEquals(MAX_REQUEST_TOTAL_NAMES - ticked.size, kept.size)
        assertEquals("Spare 1", kept.first().name)
    }

    @Test
    fun andWithinTheCharacterBudgetSkippingWhatDoesntFit() {
        val forty = "a".repeat(20) + " " + "b".repeat(19)
        val ticked = (1..25).map { item(forty) }
        // 25 × 40 = 1,000 code points: a 40-long name brings it to 1,040, a second would pass 1,050.
        val kept = optionalWireIngredients(ticked, listOf(item("c".repeat(20) + " " + "d".repeat(19)), item("e".repeat(20) + " " + "f".repeat(19)), item("Egg")))
        assertEquals(listOf("c".repeat(20) + " " + "d".repeat(19), "Egg"), kept.map { it.name })
    }

    @Test
    fun countsCodePointsNotUtf16Units() {
        // 20 rare Han characters outside the BMP: 20 code points, 40 UTF-16 units.
        val han = (0 until 20).joinToString("") { charArrayOf((0xD840).toChar(), (0xDC00 + it).toChar()).concatToString() }
        val ticked = (1..25).map { item("x".repeat(40)) } + item("y".repeat(15))
        val kept = optionalWireIngredients(ticked, listOf(item(han)))
        assertEquals(listOf(han), kept.map { it.name }, "1,015 + 20 code points fit; counted as 40 UTF-16 units it would not")
    }
}
