package com.foodtracker.android.ui.screens

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.foodtracker.android.R
import com.foodtracker.recipe.AccountKind
import com.foodtracker.recipe.AccountState
import com.foodtracker.recipe.SignInProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Android signs in with Google only; an account can still carry Apple, added on an iPhone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GoogleOnlySignInTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val google = setOf(SignInProvider.GOOGLE)
    private val both = setOf(SignInProvider.GOOGLE, SignInProvider.APPLE)

    @Test
    fun anAccountWithBothSignInsIsShownByNameAndOffersNothingToAdd() {
        assertEquals("Google, Apple", signInProvidersText(both))
        assertEquals(emptySet<SignInProvider>(), signInsToAdd(offered = google, account = both))
        assertEquals(R.string.recipe_signed_in_google_and_apple, signedInHint(both))
    }

    @Test
    fun appleIsNeverOfferedToAdd() {
        assertEquals(emptySet<SignInProvider>(), signInsToAdd(offered = google, account = google))
        // Even if a build were to list it.
        assertEquals(emptySet<SignInProvider>(), signInsToAdd(offered = both, account = google))
        assertEquals(R.string.recipe_signed_in_google, signedInHint(google))
    }

    @Test
    fun anAppleOnlyAccountIsOfferedGoogle() {
        val apple = setOf(SignInProvider.APPLE)
        assertEquals(google, signInsToAdd(offered = google, account = apple))
        assertEquals(R.string.recipe_signed_in_add_google, signedInHint(apple))
    }

    @Test
    fun onlyAnAccountWithAppleGetsTheAppleLinkNoteWhenDeleted() {
        assertTrue(deleteLeavesAppleLink(AccountState(AccountKind.SIGNED_IN, both)))
        assertFalse(deleteLeavesAppleLink(AccountState(AccountKind.SIGNED_IN, google)))
        assertFalse(deleteLeavesAppleLink(AccountState(AccountKind.ANONYMOUS)))
    }

    @Test
    @Config(sdk = [34], qualifiers = "en")
    fun theEnglishWordingNamesGoogleForAndroidAndAppleForIphone() {
        assertEquals(
            "Sign in with Google. Your recipes follow you to a new phone, Android or iPhone, wherever you sign in with Google. We keep your account ID and email address.",
            context.getString(R.string.recipe_choice_account_body)
        )
        assertEquals(
            "Signed in with Apple on an iPhone? Add Google sign-in there, in Recipe settings, to use your recipes on this phone.",
            context.getString(R.string.recipe_apple_on_iphone_hint)
        )
        assertEquals("Already have recipes in an account? Sign in to use them here.", context.getString(R.string.recipe_have_account_hint))
        assertEquals(
            "Sign in with Google on another phone, Android or iPhone, or with Apple on an iPhone, to use them there.",
            context.getString(R.string.recipe_signed_in_google_and_apple)
        )
        assertEquals("Signed in with Google, Apple", context.getString(R.string.recipe_signed_in_with, signInProvidersText(both)))
        val note = context.getString(R.string.recipe_delete_apple_note)
        assertTrue(note, note.startsWith("This account is also linked to your Apple Account. Deleting it here removes the account and its recipes, but not that link."))
        assertTrue(note, "iPhone app" in note && "Sign in with Apple" in note)
    }

    @Test
    fun everyLanguageHasTheNewLinesAndNoneOffersAppleOnAndroid() {
        val expected = mapOf(
            "en" to "Signed in with Apple on an iPhone?",
            "ru" to "Входили через Apple на iPhone?",
            "nb" to "Logget inn med Apple på en iPhone?"
        )
        for ((language, start) in expected) {
            val configuration = android.content.res.Configuration(context.resources.configuration)
                .apply { setLocale(java.util.Locale.forLanguageTag(language)) }
            val localized = context.createConfigurationContext(configuration)
            val hint = localized.getString(R.string.recipe_apple_on_iphone_hint)
            assertTrue(hint, hint.startsWith(start))
            val choice = localized.getString(R.string.recipe_choice_account_body)
            assertTrue("$language: $choice", "Google" in choice && "Apple" !in choice)
            val note = localized.getString(R.string.recipe_delete_apple_note)
            assertTrue("$language: $note", "iPhone" in note && "Apple" in note)
            for (id in listOf(R.string.recipe_signed_in_google, R.string.recipe_signed_in_google_and_apple, R.string.recipe_signed_in_add_google)) {
                assertTrue(localized.getString(id), "Google" in localized.getString(id))
            }
        }
    }
}
