package com.foodtracker.viewmodel

import com.foodtracker.model.PantryItem
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgendaTest {

    // 2026-09-23 is a Wednesday: this week runs to Sunday 09-27, next week to Sunday 10-04.
    private val wednesday = LocalDate(2026, 9, 23)

    private fun item(name: String, daysFromToday: Int, today: LocalDate = wednesday) = PantryItem(
        id = name,
        name = name,
        expiryDate = today.plus(DatePeriod(days = daysFromToday)),
        addedDate = today
    )

    private fun agenda(today: LocalDate, vararg items: PantryItem) =
        PantryDerivation.deriveBase(items.toList(), emptyList(), expiringSoonDays = 3, today = today).agenda

    private fun List<AgendaSection>.bucketOf(name: String): AgendaBucket =
        first { section -> section.rows.any { it.item.name == name } }.bucket

    @Test
    fun bucketsFollowCalendarWeeks() {
        val sections = agenda(
            wednesday,
            item("overdue", -1),
            item("today", 0),
            item("tomorrow", 1),
            item("sunday", 4),
            item("nextMonday", 5),
            item("nextSunday", 11),
            item("later", 12)
        )
        assertEquals(AgendaBucket.OVERDUE, sections.bucketOf("overdue"))
        assertEquals(AgendaBucket.TODAY, sections.bucketOf("today"))
        assertEquals(AgendaBucket.TOMORROW, sections.bucketOf("tomorrow"))
        assertEquals(AgendaBucket.THIS_WEEK, sections.bucketOf("sunday"))
        assertEquals(AgendaBucket.NEXT_WEEK, sections.bucketOf("nextMonday"))
        assertEquals(AgendaBucket.NEXT_WEEK, sections.bucketOf("nextSunday"))
        assertEquals(AgendaBucket.LATER, sections.bucketOf("later"))
        assertEquals(AgendaBucket.entries.toList(), sections.map { it.bucket })
    }

    @Test
    fun tomorrowOnASundayIsTomorrowNotNextWeek() {
        val sunday = LocalDate(2026, 9, 27)
        val sections = agenda(sunday, item("monday", 1, sunday), item("tuesday", 2, sunday))
        assertEquals(AgendaBucket.TOMORROW, sections.bucketOf("monday"))
        assertEquals(AgendaBucket.NEXT_WEEK, sections.bucketOf("tuesday"))
    }

    @Test
    fun emptyBucketsAreDropped() {
        val sections = agenda(wednesday, item("later", 30))
        assertEquals(listOf(AgendaBucket.LATER), sections.map { it.bucket })
        assertTrue(agenda(wednesday).isEmpty())
    }

    @Test
    fun rowsAreOrderedByDateThenName() {
        val sections = agenda(
            wednesday,
            item("b", -1),
            item("a", -1),
            item("c", -5)
        )
        assertEquals(listOf("c", "a", "b"), sections.single().rows.map { it.item.name })
    }
}
