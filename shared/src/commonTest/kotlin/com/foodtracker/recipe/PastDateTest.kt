package com.foodtracker.recipe

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Which items a written recipe or menu warns about, worked out from what was sent. */
class PastDateTest {

    private val today = LocalDate(2026, 11, 14)

    @Test
    fun onlyItemsWithADateBeforeTheDayAskedArePast() {
        val items = listOf(
            WireIngredient("Hamburgare", "2026-06-22", 2),
            WireIngredient("Milk", "2026-11-14", 1),
            WireIngredient("Rice", null, 1),
            WireIngredient("Broken", "not a date", 1),
            WireIngredient("Yoghurt", "2026-11-13", 1)
        )
        assertEquals(listOf(PastDateItem("Hamburgare", 145), PastDateItem("Yoghurt", 1)), pastDateOf(items, today))
    }

    @Test
    fun eachMenuDayWarnsAboutThePastDateItemsItUses() {
        val dish = Recipe(title = "Dish", steps = listOf("Cook."))
        val menu = Menu(
            listOf(
                MenuDay(LocalDate(2026, 11, 14), uses = listOf("hamburgare", "Bread"), courses = listOf(MenuCourse(MenuCourseKind.MAIN, dish))),
                MenuDay(LocalDate(2026, 11, 15), uses = listOf("Bread", "Old basil"), courses = listOf(MenuCourse(MenuCourseKind.MAIN, dish))),
                MenuDay(LocalDate(2026, 11, 16), uses = listOf("Bread"), courses = listOf(MenuCourse(MenuCourseKind.MAIN, dish)))
            )
        )
        val ticked = listOf(PastDateItem("Hamburgare", 145), PastDateItem("Tomatpuré", 66))
        val optional = listOf(PastDateItem("Old basil", 3), PastDateItem("Unused", 9))
        val warned = menu.withPastDate(ticked, optional)

        assertEquals(listOf(PastDateItem("Hamburgare", 145)), warned.days[0].courses.single().recipe.pastDate)
        assertEquals(listOf(PastDateItem("Old basil", 3)), warned.days[1].courses.single().recipe.pastDate)
        assertTrue(warned.days[2].courses.single().recipe.pastDate.isEmpty())
        // Every ticked one for the whole menu, even one no day names; optional ones only when used.
        assertEquals(ticked + PastDateItem("Old basil", 3), warned.pastDate)
    }

    @Test
    fun aSavedRowFromBeforeTheWarningReadsAsNone() {
        val saved = savedRecipeFromPayload("a", SavedRecipeKind.RECIPE, "T", 1L, RecipeSource.SERVER, """{"title":"T","steps":["s"]}""")
        assertEquals(emptyList(), saved?.recipe?.pastDate)
        val withWarning = SavedRecipe.of("b", Recipe(title = "T", steps = listOf("s"), pastDate = listOf(PastDateItem("Milk", 3))), 1L)
        val back = savedRecipeFromPayload("b", SavedRecipeKind.RECIPE, "T", 1L, RecipeSource.DEVICE, withWarning.payloadJson())
        assertEquals(listOf(PastDateItem("Milk", 3)), back?.recipe?.pastDate, "kept with the dates as they were when it was written")
    }
}
