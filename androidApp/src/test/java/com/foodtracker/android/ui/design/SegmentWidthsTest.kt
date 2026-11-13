package com.foodtracker.android.ui.design

import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentWidthsTest {
    @Test
    fun fillSharesTheSpareWidthEqually() =
        assertEquals(listOf(130, 90, 60), segmentWidths(listOf(100, 60, 30), available = 280, fill = true))

    @Test
    fun aLongLabelKeepsItsWidthWhenItFits() {
        val widths = segmentWidths(listOf(200, 80, 40), available = 330, fill = true)
        assertEquals(330, widths.sum())
        assert(widths[0] >= 200) { widths.toString() }
    }

    @Test
    fun withoutFillTheSegmentsHugTheirLabels() =
        assertEquals(listOf(100, 60), segmentWidths(listOf(100, 60), available = 400, fill = false))

    @Test
    fun tooNarrowShrinksInProportionAndFillsExactly() {
        val widths = segmentWidths(listOf(200, 100, 100), available = 300, fill = true)
        assertEquals(300, widths.sum())
        assertEquals(listOf(150, 75, 75), widths)
    }

    @Test
    fun shrinkingWithUnevenWidthsStillFillsExactly() {
        // 200 into thirds leaves a rounding remainder, which must not leave a gap at the end.
        assertEquals(200, segmentWidths(listOf(100, 100, 100), available = 200, fill = true).sum())
    }

    @Test
    fun unboundedWidthKeepsIdealWidths() =
        assertEquals(listOf(10, 20), segmentWidths(listOf(10, 20), available = null, fill = true))

    @Test
    fun noSegmentsIsEmpty() = assertEquals(emptyList<Int>(), segmentWidths(emptyList(), 100, true))
}
