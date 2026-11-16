package com.foodtracker.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.foodtracker.android.R
import com.foodtracker.android.ui.design.LarderButton
import com.foodtracker.android.ui.design.LarderButtonStyle
import com.foodtracker.android.ui.design.LarderDivider
import com.foodtracker.android.ui.design.LarderGroup
import com.foodtracker.android.ui.design.LarderIcons
import com.foodtracker.android.ui.design.LarderSheet
import com.foodtracker.android.ui.theme.LarderSpace
import com.foodtracker.android.ui.theme.LarderTheme
import com.foodtracker.recipe.OnDeviceRoute
import com.foodtracker.recipe.PaidUiState
import com.foodtracker.recipe.RecipeUiState
import com.foodtracker.recipe.WelcomeHint
import com.foodtracker.recipe.WelcomeKind

/** What the introduction can honestly offer on this device and build, worked out from the state. */
internal data class RecipeIntroContent(
    /** The phone's own model is there (ready or still downloading). */
    val onDevice: Boolean,
    /** The paid route exists in this build and on this device. */
    val packs: Boolean,
    /** "See recipe packs": only where buying is offered right now. */
    val canBuy: Boolean,
    /** The store's own formatted price of the cheapest pack, when it has answered. */
    val lowestPrice: String?,
    /** Free recipes for signing in, as the sheet found them when it opened; null for none. */
    val welcome: WelcomeHint? = null
)

internal fun recipeIntroContent(state: RecipeUiState) = RecipeIntroContent(
    onDevice = state.onDeviceRoute == OnDeviceRoute.READY || state.onDeviceRoute == OnDeviceRoute.PREPARING,
    packs = state.paid.available,
    canBuy = state.paid.canBuy,
    lowestPrice = state.paid.lowestPrice,
    welcome = state.paid.welcome
)

/**
 * "How recipes work": one short sheet, shown by itself the first time the tab opens and from
 * Recipe settings after that. It names only the ways this device and build really offer, never a
 * price it doesn't have from the store, and has one way out that is just "Got it"; "See recipe
 * packs" is there only where buying is offered. Opening it calls nothing: no account, no App Check
 * token, nothing from our server.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun RecipeIntroSheet(state: RecipeUiState, onDismiss: () -> Unit, onPacks: () -> Unit, onWelcome: () -> Unit = {}) {
    // As it was when the sheet opened: an offer that arrives meanwhile waits for the next showing.
    val content = remember { recipeIntroContent(state) }
    LarderSheet(onDismissRequest = onDismiss, title = stringResource(R.string.recipe_intro_title)) {
        RecipeIntroBody(content, state.paid, onDismiss, onPacks, onWelcome)
    }
}

@Composable
internal fun RecipeIntroBody(content: RecipeIntroContent, paid: PaidUiState, onDismiss: () -> Unit, onPacks: () -> Unit, onWelcome: () -> Unit = {}) {
    val resources = LocalContext.current.resources
    Column(verticalArrangement = Arrangement.spacedBy(LarderSpace.s3)) {
        Text(
            text = stringResource(R.string.recipe_intro_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LarderGroup {
            OnboardingRow(
                LarderIcons.Share2,
                stringResource(R.string.recipe_intro_prompt_title),
                stringResource(R.string.recipe_intro_prompt_body)
            )
            if (content.onDevice) {
                LarderDivider()
                OnboardingRow(
                    LarderIcons.Smartphone,
                    stringResource(R.string.recipe_intro_device_title),
                    stringResource(R.string.recipe_intro_device_body) + " " + stringResource(R.string.recipe_on_device_weak)
                )
            }
            if (content.packs) {
                val costs = paid.costs
                val body = buildString {
                    // A menu set's range when the server writes them; else the 3-day menu.
                    val range = costs.menuSetRange
                    append(
                        if (range != null) {
                            stringResource(R.string.recipe_intro_packs_body_sets, recipeCount(resources, costs.recipe), range.first, range.last)
                        } else {
                            stringResource(
                                R.string.recipe_intro_packs_body,
                                recipeCount(resources, costs.recipe),
                                recipeCount(resources, costs.menu2),
                                recipeCount(resources, costs.menu3)
                            )
                        }
                    )
                    content.lowestPrice?.let { append(' ').append(stringResource(R.string.recipe_intro_packs_price, it)) }
                }
                val stays = stringResource(R.string.recipe_intro_free_stays)
                LarderDivider()
                val welcome = content.welcome
                if (welcome != null) {
                    // One sentence in the label weight, so it can be found, before the free ways line.
                    WelcomeInfoRow(LarderIcons.Sparkles, stringResource(R.string.recipe_intro_packs_title), emphasised(body, welcomeIntroSentence(resources, welcome), stays))
                } else {
                    OnboardingRow(LarderIcons.Sparkles, stringResource(R.string.recipe_intro_packs_title), "$body $stays")
                }
            }
        }
        Text(
            text = stringResource(R.string.recipe_intro_again),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = LarderSpace.s2, bottom = LarderSpace.s4),
            verticalArrangement = Arrangement.spacedBy(LarderSpace.s1)
        ) {
            LarderButton(text = stringResource(R.string.recipe_intro_start), onClick = onDismiss, modifier = Modifier.fillMaxWidth())
            // With an offer, its second action is the offer (packs stay a tap away from the count
            // and Buy); without one, See recipe packs where buying is offered.
            val welcome = content.welcome
            if (welcome != null) {
                LarderButton(
                    text = welcomeOfferLabel(resources, welcome),
                    onClick = onWelcome,
                    modifier = Modifier.fillMaxWidth(),
                    style = LarderButtonStyle.Text,
                    leadingIcon = LarderIcons.Gift
                )
            } else if (content.canBuy) {
                LarderButton(
                    text = stringResource(R.string.recipe_intro_packs_action),
                    onClick = onPacks,
                    modifier = Modifier.fillMaxWidth(),
                    style = LarderButtonStyle.Text
                )
            }
        }
    }
}

// --- Previews ---------------------------------------------------------------------------------

@Composable
private fun IntroPreview(content: RecipeIntroContent) = LarderTheme {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(modifier = Modifier.padding(LarderSpace.s4)) {
            RecipeIntroBody(content, PaidUiState(available = content.packs), {}, {})
        }
    }
}

@PreviewLightDark
@Composable
internal fun RecipeIntroEverythingPreview() = IntroPreview(RecipeIntroContent(onDevice = true, packs = true, canBuy = true, lowestPrice = "£2.99"))

@PreviewLightDark
@Composable
internal fun RecipeIntroWelcomePreview() =
    IntroPreview(RecipeIntroContent(onDevice = true, packs = true, canBuy = true, lowestPrice = "£2.99", welcome = WelcomeHint(1, WelcomeKind.SIGN_IN)))

@Preview
@Composable
internal fun RecipeIntroPromptOnlyPreview() = IntroPreview(RecipeIntroContent(onDevice = false, packs = false, canBuy = false, lowestPrice = null))

@Preview(locale = "ru")
@Composable
internal fun RecipeIntroRussianPreview() = IntroPreview(RecipeIntroContent(onDevice = false, packs = true, canBuy = true, lowestPrice = null))
