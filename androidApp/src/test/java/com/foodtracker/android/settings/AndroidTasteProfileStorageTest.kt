package com.foodtracker.android.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.foodtracker.backup.BackupDocument
import com.foodtracker.backup.BackupSerializer
import com.foodtracker.recipe.RecipeConfig
import com.foodtracker.recipe.TasteProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The taste profile under its own key beside the recipe config (iOS: `com.foodtracker.recipe_taste`). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidTasteProfileStorageTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("foodtracker_settings", Context.MODE_PRIVATE)

    @Before
    fun clear() {
        prefs.edit().clear().commit()
    }

    @Test
    fun aFreshInstallHasAnEmptyProfile() {
        assertTrue(AndroidRecipeConfigRepository(context).getTaste().isEmpty)
    }

    private val sample = TasteProfile(
        answers = mapOf("cuisines" to listOf("thai", "italian"), "heat" to listOf("medium"), "gone" to listOf("x")),
        version = "v1"
    )

    @Test
    fun theProfileRoundTripsExactlyAsSavedUnderItsOwnKey() {
        AndroidRecipeConfigRepository(context).saveTaste(sample)
        assertEquals("kept as saved: a definition change never rewrites it", sample, AndroidRecipeConfigRepository(context).getTaste())
        assertTrue(prefs.contains("recipe_taste"))
        assertFalse("never inside the config", prefs.contains("recipe_config"))
        assertEquals(RecipeConfig(), AndroidRecipeConfigRepository(context).get())
    }

    @Test
    fun anEmptyProfileRemovesTheKey() {
        val repository = AndroidRecipeConfigRepository(context)
        repository.saveTaste(sample)
        repository.saveTaste(TasteProfile())
        assertFalse(prefs.contains("recipe_taste"))
        assertTrue(repository.getTaste().isEmpty)
    }

    @Test
    fun brokenDataReadsAsNoProfile() {
        prefs.edit().putString("recipe_taste", "not json").commit()
        assertTrue(AndroidRecipeConfigRepository(context).getTaste().isEmpty)
        prefs.edit().putString("recipe_taste", """{"answers":{"a":["b"]},"later":true}""").commit()
        assertEquals(TasteProfile(mapOf("a" to listOf("b"))), AndroidRecipeConfigRepository(context).getTaste())
    }

    @Test
    fun theSeenStepsAreKeptApartFromTheAnswers() {
        val repository = AndroidRecipeConfigRepository(context)
        assertEquals(emptyList<String>(), repository.getTasteSeen())
        repository.saveTasteSeen(listOf("cuisines", "heat"))
        repository.saveTaste(TasteProfile())
        assertEquals("kept without a profile", listOf("cuisines", "heat"), AndroidRecipeConfigRepository(context).getTasteSeen())
        assertTrue(prefs.contains("recipe_taste_seen"))
        prefs.edit().putString("recipe_taste_seen", "nonsense").commit()
        assertEquals(emptyList<String>(), repository.getTasteSeen())
    }

    @Test
    fun theQuizIsCachedPerLanguage() {
        val repository = AndroidRecipeConfigRepository(context)
        assertNull(repository.getTasteQuiz("de"))
        repository.saveTasteQuiz("de", """{"version":"d"}""")
        repository.saveTasteQuiz("en", """{"version":"e"}""")
        assertEquals("""{"version":"d"}""", AndroidRecipeConfigRepository(context).getTasteQuiz("de"))
        assertEquals("""{"version":"e"}""", AndroidRecipeConfigRepository(context).getTasteQuiz("en"))
        assertTrue(prefs.contains("recipe_taste_quiz_de"))
    }

    @Test
    fun theProfileIsNotPartOfABackup() {
        AndroidRecipeConfigRepository(context).saveTaste(TasteProfile(mapOf("cuisines" to listOf("georgian"), "avoid" to listOf("offal"))))
        val backup = BackupSerializer.encode(BackupDocument(settings = AndroidSettingsRepository(context).getSettings()))
        assertFalse(backup.contains("georgian"))
        assertFalse(backup.contains("offal"))
    }
}
