package com.foodtracker.android.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShoppingSectionOrderTest {
    private val ids = listOf("a", "b", "c")

    @Test
    fun movesUpOnePlace() = assertEquals(listOf("b", "a", "c"), movedSectionOrder(ids, "b", -1))

    @Test
    fun movesDownOnePlace() = assertEquals(listOf("a", "c", "b"), movedSectionOrder(ids, "b", 1))

    @Test
    fun firstCanMoveDown() = assertEquals(listOf("b", "a", "c"), movedSectionOrder(ids, "a", 1))

    @Test
    fun firstCannotMoveUp() = assertNull(movedSectionOrder(ids, "a", -1))

    @Test
    fun lastCannotMoveDown() = assertNull(movedSectionOrder(ids, "c", 1))

    @Test
    fun missingSectionIsNull() = assertNull(movedSectionOrder(ids, "z", 1))

    @Test
    fun aloneCannotMove() {
        assertNull(movedSectionOrder(listOf("a"), "a", 1))
        assertNull(movedSectionOrder(listOf("a"), "a", -1))
    }

    @Test
    fun zeroDeltaIsNull() = assertNull(movedSectionOrder(ids, "b", 0))
}
