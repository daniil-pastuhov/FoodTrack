package com.foodtracker.recipe

import kotlinx.serialization.Serializable

// An allergen list is data concerning health (GDPR Art. 9). It goes to our server, and on to
// Google's model, only with the person's explicit consent: asked once, the first time a
// server-written recipe or menu would carry it, recorded on this phone with the time and the
// wording's version, and withdrawable in Recipe settings. Withdrawn or refused, the server route is
// blocked while allergens are set: a recipe written without them could be unsafe, so they are
// never left out instead. The phone's own model and the shared prompt send nothing to us and
// need no consent. iOS has the same rules in `AllergenConsent.swift`.

/** The version of the consent wording (en/ru/nb `recipe_allergen_consent_*`). Raising it asks
 *  everyone again, whatever they answered before. */
const val ALLERGEN_CONSENT_VERSION = 1

/** The person's answer: [agreed] or refused/withdrawn, when, and to which wording. */
@Serializable
data class AllergenConsentRecord(val agreed: Boolean, val atMillis: Long, val version: Int)

/** What a server request with this allergen list needs first. */
enum class AllergenConsentStep {
    /** No allergens set: nothing health-related is sent, no flag. */
    NOT_NEEDED,

    /** Agreed to the current wording: sent, with `allergenConsent: true`. */
    GRANTED,

    /** Never asked, or asked with an older wording: ask before sending. */
    ASK,

    /** Refused or withdrawn: not sent; the person is told where to change it. */
    DECLINED
}

fun allergenConsentStep(
    hasAllergens: Boolean,
    record: AllergenConsentRecord?,
    version: Int = ALLERGEN_CONSENT_VERSION
): AllergenConsentStep = when {
    !hasAllergens -> AllergenConsentStep.NOT_NEEDED
    record == null || record.version != version -> AllergenConsentStep.ASK
    record.agreed -> AllergenConsentStep.GRANTED
    else -> AllergenConsentStep.DECLINED
}

/** The record once the allergen list is known to be [hasAllergens]: a refusal is forgotten when
 *  the list is emptied, so allergens set later are asked about again. A consent stays. */
fun allergenConsentAfterListChange(record: AllergenConsentRecord?, hasAllergens: Boolean): AllergenConsentRecord? =
    if (!hasAllergens && record?.agreed == false) null else record

/** The request's `allergenConsent` for [allergens]: true when there are any (the request is only
 *  built after consent), absent otherwise. */
fun allergenConsentFlag(allergens: List<String>): Boolean? = if (allergens.isEmpty()) null else true

/** Whether Recipe settings shows sending allergens as on. */
fun allergenConsentGiven(record: AllergenConsentRecord?, version: Int = ALLERGEN_CONSENT_VERSION): Boolean =
    record != null && record.agreed && record.version == version
