package com.foodtracker.android.ui.screens

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.foodtracker.android.ui.design.LarderIcons
import com.foodtracker.recipe.RecipeJson
import com.foodtracker.recipe.TasteStepKind
import com.foodtracker.recipe.TasteSummary
import com.foodtracker.recipe.TasteTone
import com.foodtracker.recipe.tasteQuizOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class TasteProfileViewsTest {

    private val resources get() = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun theSummaryReadsAsTheDesignHasIt() {
        assertEquals("Italian, Nordic · Medium · 5 more", tasteSummaryText(resources, TasteSummary(listOf("Italian", "Nordic"), "Medium", 5)))
        assertEquals("Italian", tasteSummaryText(resources, TasteSummary(listOf("Italian"), null, 0)))
        assertEquals("Hot · 2 more", tasteSummaryText(resources, TasteSummary(emptyList(), "Hot", 2)))
        assertEquals("no definition in this language", "6 answers", tasteSummaryText(resources, TasteSummary(emptyList(), null, 6)))
        assertEquals("1 answer", tasteSummaryText(resources, TasteSummary(emptyList(), null, 1)))
        assertNull(tasteSummaryText(resources, null))
    }

    @Config(qualifiers = "ru")
    @Test
    fun answersArePluralInRussian() {
        assertEquals("5 ответов", tasteSummaryText(resources, TasteSummary(emptyList(), null, 5)))
        assertEquals("2 новых вопроса", resources.getQuantityString(com.foodtracker.android.R.plurals.taste_new_questions, 2, 2))
    }

    @Test
    fun aStepIconIsFoundByItsLucideNameOrFallsBackToUtensils() {
        assertEquals(LarderIcons.HeartOff, tasteStepIcon("heart-off"))
        assertEquals(LarderIcons.CookingPot, tasteStepIcon("cooking-pot"))
        assertEquals(LarderIcons.Globe, tasteStepIcon("globe"))
        assertEquals(LarderIcons.Utensils, tasteStepIcon("rocket-ship"))
        assertEquals(LarderIcons.Utensils, tasteStepIcon(null))
    }

    /** The debug demo's bundled definitions read as the design's six steps in every language. */
    @Test
    fun theDebugSampleDefinitionsDecode() {
        val assets = listOf(File("src/debug/assets"), File("androidApp/src/debug/assets")).first { it.isDirectory }
        for (language in listOf("en", "ru", "nb", "es", "de", "fr", "sv", "da")) {
            val quiz = tasteQuizOf(RecipeJson.parseToJsonElement(File(assets, "taste_quiz_$language.json").readText()))
            assertNotNull(language, quiz)
            checkNotNull(quiz)
            assertEquals(language, listOf("cuisines", "heat", "flavours", "favourites", "avoid", "methods"), quiz.steps.map { it.id })
            assertEquals(language, TasteStepKind.SINGLE, quiz.step("heat")?.kind)
            assertEquals(language, TasteTone.AVOID, quiz.step("avoid")?.tone)
            assertEquals(language, 3, quiz.step("favourites")?.groups?.size)
            assertEquals(language, 3, quiz.conflicts.size)
            quiz.steps.forEach { step -> assertFalse("$language ${step.icon}", tasteStepIcon(step.icon) == LarderIcons.Utensils) }
        }
    }
}
