package com.foodtracker.android.recipe.paid

import com.foodtracker.recipe.SignInProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android offers Google sign-in only, but an account can carry Apple too, added on an iPhone. */
class GoogleOnlyAccountTest {

    @Test
    fun anAccountWithAppleAddedOnAnIphoneShowsBothProviders() {
        assertEquals(
            setOf(SignInProvider.GOOGLE, SignInProvider.APPLE),
            signInProvidersOf(listOf("firebase", "apple.com", "google.com"))
        )
        assertEquals(setOf(SignInProvider.GOOGLE), signInProvidersOf(listOf("firebase", "google.com")))
        assertEquals(emptySet<SignInProvider>(), signInProvidersOf(listOf("firebase", "password")))
    }

    @Test
    fun deletionSignsInAgainWithGoogleEvenWhenAppleIsAttached() {
        assertEquals(SignInProvider.GOOGLE, deletionReauthProvider(listOf("firebase", "apple.com", "google.com")))
        assertEquals(SignInProvider.GOOGLE, deletionReauthProvider(listOf("google.com")))
        assertNull("Apple alone can't sign in again on Android", deletionReauthProvider(listOf("apple.com")))
    }

    @Test
    fun anAppleAttachedAccountNoLongerAlwaysSignsInAgainOnlyAnOldSignInDoes() {
        val now = 10_000_000L
        assertFalse(signInTooOldForDeletion(now - 60_000L, now))
        assertTrue(signInTooOldForDeletion(now - 5 * 60_000L, now))
        assertTrue("unknown counts as too old", signInTooOldForDeletion(null, now))
    }
}
