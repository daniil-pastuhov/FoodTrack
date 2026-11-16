package com.foodtracker.android.ui.screens

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.foodtracker.android.settings.AndroidRecipePaidRepository
import com.foodtracker.recipe.RecipePaidSnapshot
import com.foodtracker.recipe.WelcomeAnswer
import com.foodtracker.recipe.WelcomeHint
import com.foodtracker.recipe.WelcomeKind
import com.foodtracker.recipe.WelcomeMenuCover
import com.foodtracker.recipe.WelcomeOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Free recipes for signing in, in words: an offer names no numeral for one and a numeral
 *  otherwise, a confirmation always has one, and the plurals agree in every language. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WelcomeWordingTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    @Config(sdk = [34], qualifiers = "en")
    fun englishOffersAndOutcomes() {
        val r = context.resources
        assertEquals("Sign in and get 3 free recipes", welcomeOfferLabel(r, WelcomeHint(3, WelcomeKind.SIGN_IN)))
        assertEquals("Sign in and get a free recipe", welcomeOfferLabel(r, WelcomeHint(1, WelcomeKind.SIGN_IN)))
        assertEquals("Get 3 free recipes", welcomeOfferLabel(r, WelcomeHint(3, WelcomeKind.CLAIM)))
        assertEquals("Get a free recipe", welcomeOfferLabel(r, WelcomeHint(1, WelcomeKind.CLAIM)))
        assertEquals("Or sign in and get 3 free recipes", welcomeBuyLabel(r, WelcomeHint(3, WelcomeKind.SIGN_IN)))
        assertEquals("Or get a free recipe", welcomeBuyLabel(r, WelcomeHint(1, WelcomeKind.CLAIM)))
        // A confirmation sits beside the count: always the numeral.
        assertEquals("1 free recipe added", welcomeOutcomeMessage(r, WelcomeOutcome.Granted(1)))
        assertEquals("3 free recipes added", welcomeOutcomeMessage(r, WelcomeOutcome.Granted(3)))
        assertEquals("Free recipes aren't available right now. You're signed in; nothing else has changed.", welcomeOutcomeMessage(r, WelcomeOutcome.Off(true)))
        assertEquals("Free recipes aren't available right now. Nothing has changed.", welcomeOutcomeMessage(r, WelcomeOutcome.Off(false)))
        assertEquals("Signed in, but your free recipes couldn't be added yet.", welcomeOutcomeMessage(r, WelcomeOutcome.NotReached))
        assertNull(welcomeOutcomeMessage(r, WelcomeOutcome.Already))
    }

    @Test
    @Config(sdk = [34], qualifiers = "en")
    fun englishMenuLinesAndSettingsNotes() {
        val r = context.resources
        assertEquals("Enough for this menu.", welcomeMenuLine(r, WelcomeMenuCover.ENOUGH))
        assertEquals("Enough for single recipes or a smaller menu, not this one.", welcomeMenuLine(r, WelcomeMenuCover.SMALLER_MENU))
        assertEquals("Enough for single recipes, not a menu.", welcomeMenuLine(r, WelcomeMenuCover.SINGLE_ONLY))
        assertEquals(
            "Sign in and get 3 free recipes from our server, once per Google account.",
            welcomeSettingsNote(r, WelcomeHint(3, WelcomeKind.SIGN_IN), onThisDevice = false)
        )
        assertEquals("Signing in also gets you 3 free recipes.", welcomeSettingsNote(r, WelcomeHint(3, WelcomeKind.SIGN_IN), onThisDevice = true))
        assertEquals("This account can get a free recipe from our server.", welcomeSettingsNote(r, WelcomeHint(1, WelcomeKind.CLAIM), onThisDevice = false))
        assertEquals("Sign in to get 3 free recipes and try it.", welcomeIntroSentence(r, WelcomeHint(3, WelcomeKind.SIGN_IN)))
    }

    @Test
    @Config(sdk = [34], qualifiers = "ru")
    fun russianTakesOneFewAndMany() {
        val r = context.resources
        assertEquals("Войдите и получите бесплатный рецепт", welcomeOfferLabel(r, WelcomeHint(1, WelcomeKind.SIGN_IN)))
        assertEquals("Войдите и получите 3 бесплатных рецепта", welcomeOfferLabel(r, WelcomeHint(3, WelcomeKind.SIGN_IN)))
        assertEquals("Войдите и получите 5 бесплатных рецептов", welcomeOfferLabel(r, WelcomeHint(5, WelcomeKind.SIGN_IN)))
        assertEquals("Добавлен 1 бесплатный рецепт", welcomeOutcomeMessage(r, WelcomeOutcome.Granted(1)))
        assertEquals("Добавлено 3 бесплатных рецепта", welcomeOutcomeMessage(r, WelcomeOutcome.Granted(3)))
        assertEquals("Добавлено 5 бесплатных рецептов", welcomeOutcomeMessage(r, WelcomeOutcome.Granted(5)))
    }

    @Test
    @Config(sdk = [34], qualifiers = "de")
    fun germanSingularAndPlural() {
        val r = context.resources
        assertEquals("Melde dich an und erhalte ein kostenloses Rezept", welcomeOfferLabel(r, WelcomeHint(1, WelcomeKind.SIGN_IN)))
        assertEquals("3 kostenlose Rezepte holen", welcomeOfferLabel(r, WelcomeHint(3, WelcomeKind.CLAIM)))
        assertEquals("1 kostenloses Rezept hinzugefügt", welcomeOutcomeMessage(r, WelcomeOutcome.Granted(1)))
    }

    @Test
    fun theCachedOfferAndClaimsAreKeptBesideTheOtherRecipeState() {
        val repository = AndroidRecipePaidRepository(context)
        val snapshot = RecipePaidSnapshot(
            welcomeOffer = WelcomeAnswer(3, 1_000L),
            welcomeAccount = WelcomeAnswer(0, 2_000L),
            welcomeClaimed = listOf("key")
        )
        repository.save(snapshot)
        assertEquals(snapshot, AndroidRecipePaidRepository(context).get())
    }
}
