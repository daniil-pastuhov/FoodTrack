package com.foodtracker.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One table per receipt layout, following `DateOcrParserTest`'s shape.
 *
 * Each fixture is what OCR would hand back for a whole receipt — header, body and footer — so
 * the tests exercise the subtraction the parser actually does, rather than feeding it lines
 * already known to be products.
 */
class ReceiptParserTest {

    // --- UK-style supermarket: price column right-aligned, VAT letter suffix ----------------

    private val ukReceipt = listOf(
        "TESCO EXTRA",
        "123 HIGH STREET",
        "01/03/2026  14:32",
        "MLK SEMI 2PT              1.25",
        "2 x BROWN BREAD           2.30",
        "CHEDDAR MATURE            3.49 A",
        "BANANAS LOOSE             0.89",
        "TOTAL                     7.93",
        "VISA DEBIT                7.93",
        "VAT NO 220430231",
        "THANK YOU FOR SHOPPING"
    )

    @Test
    fun ukReceiptKeepsOnlyProductLines() {
        val names = ReceiptParser.parseLines(ukReceipt).map { it.name }
        assertEquals(
            listOf("MLK SEMI 2PT", "BROWN BREAD", "CHEDDAR MATURE", "BANANAS LOOSE"),
            names
        )
    }

    @Test
    fun ukReceiptReadsLeadingQuantity() {
        val bread = ReceiptParser.parseLines(ukReceipt).first { it.name == "BROWN BREAD" }
        assertEquals(2, bread.quantity)
    }

    @Test
    fun singleUnitLinesDefaultToQuantityOne() {
        val milk = ReceiptParser.parseLines(ukReceipt).first { it.name == "MLK SEMI 2PT" }
        assertEquals(1, milk.quantity)
    }

    @Test
    fun keepsTheRawLineForEachProduct() {
        val cheddar = ReceiptParser.parseLines(ukReceipt).first { it.name == "CHEDDAR MATURE" }
        assertEquals("CHEDDAR MATURE            3.49 A", cheddar.rawText)
    }

    // --- Norwegian layout: comma decimals, weighed goods on their own line ------------------

    private val norwegianReceipt = listOf(
        "REMA 1000",
        "Storgata 12, 0155 OSLO",
        "Org.nr 919 000 000",
        "MELK LETT 1L              19,90",
        "BRØD GROVT                32,50",
        "EPLE RØDT",
        "0,412 kg @ 29,90/kg       12,32",
        "OST NORVEGIA              89,00",
        "Totalt                   153,72",
        "Bankaxept                153,72",
        "MVA 15%                   20,05",
        "Takk for handelen!"
    )

    @Test
    fun norwegianReceiptKeepsOnlyProductLines() {
        val names = ReceiptParser.parseLines(norwegianReceipt).map { it.name }
        assertEquals(
            listOf("MELK LETT 1L", "BRØD GROVT", "EPLE RØDT", "OST NORVEGIA"),
            names
        )
    }

    @Test
    fun weighedGoodsLineIsDroppedRatherThanBecomingItsOwnProduct() {
        val names = ReceiptParser.parseLines(norwegianReceipt).map { it.name }
        assertTrue(names.none { it.contains("kg") }, names.toString())
    }

    @Test
    fun handlesCommaDecimalPrices() {
        val milk = ReceiptParser.parseLines(norwegianReceipt).first()
        assertEquals("MELK LETT 1L", milk.name)
    }

    // --- Russian layout: Cyrillic, "N x price" form -----------------------------------------

    private val russianReceipt = listOf(
        "ПЯТЁРОЧКА",
        "ул. Ленина, 5",
        "ИНН 7707083893",
        "02.03.2026 18:04",
        "МОЛОКО 3.2%              89,90",
        "3 x ЙОГУРТ               149,70",
        "ХЛЕБ БОРОДИНСКИЙ          45,00",
        "ИТОГО                    284,60",
        "КАРТА                    284,60",
        "НДС 20%                   47,43",
        "СПАСИБО ЗА ПОКУПКУ"
    )

    @Test
    fun russianReceiptKeepsOnlyProductLines() {
        val names = ReceiptParser.parseLines(russianReceipt).map { it.name }
        assertEquals(listOf("МОЛОКО 3.2%", "ЙОГУРТ", "ХЛЕБ БОРОДИНСКИЙ"), names)
    }

    @Test
    fun russianReceiptReadsLeadingQuantity() {
        val yoghurt = ReceiptParser.parseLines(russianReceipt).first { it.name == "ЙОГУРТ" }
        assertEquals(3, yoghurt.quantity)
    }

    // --- Per-line behaviour ------------------------------------------------------------------

    @Test
    fun dropsBlankAndWhitespaceOnlyLines() {
        assertNull(ReceiptParser.parseLine(""))
        assertNull(ReceiptParser.parseLine("    "))
    }

    @Test
    fun dropsLinesThatAreJustCodesOrTimestamps() {
        assertNull(ReceiptParser.parseLine("2026-03-01"))
        assertNull(ReceiptParser.parseLine("14:32"))
        assertNull(ReceiptParser.parseLine("*** 4411 8890 ***"))
    }

    @Test
    fun dropsAPriceOnlyLine() {
        assertNull(ReceiptParser.parseLine("   12,50"))
    }

    @Test
    fun readsTrailingQuantityForm() {
        val line = ReceiptParser.parseLine("ORANGE JUICE x2      4.00")
        assertEquals("ORANGE JUICE", line?.name)
        assertEquals(2, line?.quantity)
    }

    @Test
    fun scoresAMostlyNumericRemnantBelowTheThreshold() {
        val line = ReceiptParser.parseLine("A1 22 33")
        // Either rejected outright or scored too low to be offered — both are correct, and
        // which one it is shouldn't be pinned by the test.
        assertTrue(line == null || line.confidence < ReceiptParser.MIN_CONFIDENCE, line.toString())
    }

    @Test
    fun scoresAPlainProductNameWellAboveTheThreshold() {
        val line = ReceiptParser.parseLine("CHEDDAR MATURE            3.49")
        assertTrue((line?.confidence ?: 0f) > 0.8f, line.toString())
    }

    @Test
    fun implausibleLeadingNumberIsNotTreatedAsAQuantity() {
        // A pack size, not "250 of these".
        val line = ReceiptParser.parseLine("250 G BUTTER              2.10")
        assertEquals(1, line?.quantity)
    }

    @Test
    fun anEmptyReceiptYieldsNothing() {
        assertEquals(emptyList(), ReceiptParser.parseLines(emptyList()))
    }

    @Test
    fun aReceiptOfOnlyFurnitureYieldsNothing() {
        val lines = listOf("TOTAL 12.00", "VISA 12.00", "VAT 2.00", "THANK YOU")
        assertEquals(emptyList(), ReceiptParser.parseLines(lines))
    }
}
