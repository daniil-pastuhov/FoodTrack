package com.foodtracker.recipe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AllergenConsentTest {
    private val agreed = AllergenConsentRecord(agreed = true, atMillis = 10, version = ALLERGEN_CONSENT_VERSION)
    private val refused = AllergenConsentRecord(agreed = false, atMillis = 10, version = ALLERGEN_CONSENT_VERSION)

    @Test
    fun noAllergensNeedNoStepWhateverWasAnswered() {
        for (record in listOf(null, agreed, refused, agreed.copy(version = 0))) {
            assertEquals(AllergenConsentStep.NOT_NEEDED, allergenConsentStep(false, record))
        }
    }

    @Test
    fun allergensAreAskedAboutUntilAnsweredForThisWording() {
        assertEquals(AllergenConsentStep.ASK, allergenConsentStep(true, null))
        assertEquals(AllergenConsentStep.ASK, allergenConsentStep(true, agreed.copy(version = ALLERGEN_CONSENT_VERSION - 1)))
        assertEquals(AllergenConsentStep.ASK, allergenConsentStep(true, refused.copy(version = ALLERGEN_CONSENT_VERSION - 1)))
        // A version from a later build is not this wording either.
        assertEquals(AllergenConsentStep.ASK, allergenConsentStep(true, agreed.copy(version = ALLERGEN_CONSENT_VERSION + 1)))
        assertEquals(AllergenConsentStep.GRANTED, allergenConsentStep(true, agreed))
        assertEquals(AllergenConsentStep.DECLINED, allergenConsentStep(true, refused))
    }

    @Test
    fun onlyARefusalIsForgottenAndOnlyWhenTheListIsEmpty() {
        assertNull(allergenConsentAfterListChange(refused, hasAllergens = false))
        assertEquals(refused, allergenConsentAfterListChange(refused, hasAllergens = true))
        assertEquals(agreed, allergenConsentAfterListChange(agreed, hasAllergens = false))
        assertNull(allergenConsentAfterListChange(null, hasAllergens = false))
    }

    @Test
    fun theFlagGoesOnlyWithAllergens() {
        assertNull(allergenConsentFlag(emptyList()))
        assertEquals(true, allergenConsentFlag(listOf("peanut")))
    }

    @Test
    fun theSwitchShowsOnOnlyForAConsentToThisWording() {
        assertTrue(allergenConsentGiven(agreed))
        assertFalse(allergenConsentGiven(refused))
        assertFalse(allergenConsentGiven(null))
        assertFalse(allergenConsentGiven(agreed.copy(version = 0)))
    }

    @Test
    fun theFlagIsNotPartOfWhatARequestIdIsBoundTo() {
        val base = GenerateRequest(
            requestId = "a",
            kind = WireKind.RECIPE,
            config = RecipeConfig(),
            ingredients = listOf(WireIngredient("Bread", null, 1)),
            allergens = listOf("peanut"),
            today = "2026-11-14"
        )
        assertTrue(base.copy(requestId = "b", allergenConsent = true).sameAskAs(base))
        assertFalse(base.copy(allergens = listOf("milk")).sameAskAs(base))
    }

    @Test
    fun theFlagIsSentAsJsonTrue() {
        val json = RecipeJson.encodeToString(
            GenerateRequest.serializer(),
            GenerateRequest("a", WireKind.RECIPE, null, RecipeConfig(), emptyList(), listOf("peanut"), "2026-11-14", allergenConsent = true)
        )
        assertTrue("\"allergenConsent\":true" in json, json)
    }
}
