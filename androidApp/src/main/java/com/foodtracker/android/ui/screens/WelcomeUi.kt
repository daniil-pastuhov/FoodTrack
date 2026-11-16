package com.foodtracker.android.ui.screens

import android.content.res.Resources
import android.provider.Settings
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import com.foodtracker.android.ui.theme.LarderSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.foodtracker.android.R
import com.foodtracker.android.ui.design.LarderButton
import com.foodtracker.android.ui.design.LarderButtonStyle
import com.foodtracker.android.ui.design.LarderDialog
import com.foodtracker.android.ui.design.LarderDivider
import com.foodtracker.android.ui.design.LarderGroup
import com.foodtracker.android.ui.design.LarderIcon
import com.foodtracker.android.ui.design.LarderIcons
import com.foodtracker.android.ui.design.LarderSheet
import com.foodtracker.android.ui.design.LarderSpinner
import com.foodtracker.android.ui.design.LarderThumb
import com.foodtracker.android.ui.theme.LarderRadius
import com.foodtracker.android.ui.theme.LarderSpace
import com.foodtracker.android.ui.theme.LarderTheme
import com.foodtracker.recipe.SignInProvider
import com.foodtracker.recipe.WelcomeHint
import com.foodtracker.recipe.WelcomeKind
import com.foodtracker.recipe.WelcomeMenuCover
import com.foodtracker.recipe.WelcomeOutcome
import com.foodtracker.recipe.WelcomeSheet

/*
 * Free recipes for signing in, as Android draws them (docs/design/src/pages/welcome.html). The rules
 * are shared (WelcomeRecipes.kt); here only the words and the pieces. An offer names no numeral for
 * one ("a free recipe", the `_one` strings) and a numeral otherwise; a confirmation always has one.
 */

/** [count] free recipes in words: [one] for exactly one (no numeral), else [plural]. */
internal fun welcomeText(resources: Resources, count: Int, @StringRes one: Int, @PluralsRes plural: Int): String =
    if (count == 1) resources.getString(one) else resources.getQuantityString(plural, count, count)

/** The hint's label: "Sign in and get 3 free recipes" signed out, "Get 3 free recipes" signed in. */
internal fun welcomeOfferLabel(resources: Resources, hint: WelcomeHint): String = when (hint.kind) {
    WelcomeKind.SIGN_IN -> welcomeText(resources, hint.recipes, R.string.welcome_sign_in_one, R.plurals.welcome_sign_in)
    WelcomeKind.CLAIM -> welcomeText(resources, hint.recipes, R.string.welcome_get_one, R.plurals.welcome_get)
}

/** The Buy sheet's quiet alternative under the packs. */
internal fun welcomeBuyLabel(resources: Resources, hint: WelcomeHint): String = when (hint.kind) {
    WelcomeKind.SIGN_IN -> welcomeText(resources, hint.recipes, R.string.welcome_buy_sign_in_one, R.plurals.welcome_buy_sign_in)
    WelcomeKind.CLAIM -> welcomeText(resources, hint.recipes, R.string.welcome_buy_get_one, R.plurals.welcome_buy_get)
}

/** The sentence "How recipes work" adds to its packs row. */
internal fun welcomeIntroSentence(resources: Resources, hint: WelcomeHint): String = when (hint.kind) {
    WelcomeKind.SIGN_IN -> welcomeText(resources, hint.recipes, R.string.welcome_intro_sign_in_one, R.plurals.welcome_intro_sign_in)
    WelcomeKind.CLAIM -> welcomeText(resources, hint.recipes, R.string.welcome_intro_account_one, R.plurals.welcome_intro_account)
}

/** The menu set's quiet line under the offer's row: what the free recipes cover, without their
 *  number (the row's title already says it). */
internal fun welcomeMenuLine(resources: Resources, cover: WelcomeMenuCover): String = resources.getString(
    when (cover) {
        WelcomeMenuCover.ENOUGH -> R.string.welcome_menu_enough
        WelcomeMenuCover.SMALLER_MENU -> R.string.welcome_menu_smaller
        WelcomeMenuCover.SINGLE_ONLY -> R.string.welcome_menu_single
    }
)

/** Recipe settings' note in the account card: signed out with nothing on this device, with recipes
 *  on this device, or signed in. */
internal fun welcomeSettingsNote(resources: Resources, hint: WelcomeHint, onThisDevice: Boolean): String = when {
    hint.kind == WelcomeKind.CLAIM -> welcomeText(resources, hint.recipes, R.string.welcome_settings_account_one, R.plurals.welcome_settings_account)
    onThisDevice -> welcomeText(resources, hint.recipes, R.string.welcome_settings_on_phone_one, R.plurals.welcome_settings_on_phone)
    else -> welcomeText(resources, hint.recipes, R.string.welcome_settings_sign_in_one, R.plurals.welcome_settings_sign_in)
}

/** The snackbar for a claim's outcome; null for ALREADY, which is a dialog. */
internal fun welcomeOutcomeMessage(resources: Resources, outcome: WelcomeOutcome): String? = when (outcome) {
    is WelcomeOutcome.Granted -> resources.getQuantityString(R.plurals.welcome_granted, outcome.count, outcome.count)
    is WelcomeOutcome.Off -> resources.getString(if (outcome.signedInNow) R.string.welcome_off_signed_in_now else R.string.welcome_off)
    WelcomeOutcome.NotReached -> resources.getString(R.string.welcome_claim_failed)
    WelcomeOutcome.Already -> null
}

/** The system's "Remove animations" (animator duration scale 0): the hint then only cross-fades. */
@Composable
internal fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) == 0f
    }
}

/** The hint's own motion (spec section 6): grows from no height while fading in, about 280 ms on the
 *  standard ease, and the reverse when it goes; only a fade with reduced motion. Shown from the
 *  start, it doesn't animate. */
private const val WELCOME_MOTION_MILLIS = 280

/**
 * [content] for [hint], entering and leaving with the hint's motion. The last hint is kept while it
 * leaves, so its words don't vanish before its row has closed.
 */
@Composable
internal fun AnimatedWelcome(hint: WelcomeHint?, content: @Composable (WelcomeHint) -> Unit) {
    val reduced = rememberReducedMotion()
    var last by remember { mutableStateOf(hint) }
    if (hint != null) last = hint
    val spec = tween<Float>(WELCOME_MOTION_MILLIS, easing = FastOutSlowInEasing)
    AnimatedVisibility(
        visible = hint != null,
        enter = if (reduced) fadeIn(spec) else expandVertically(tween(WELCOME_MOTION_MILLIS, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) + fadeIn(spec),
        exit = if (reduced) fadeOut(spec) else shrinkVertically(tween(WELCOME_MOTION_MILLIS, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top) + fadeOut(spec)
    ) {
        last?.let { content(it) }
    }
}

/** The offer on the form: an outline button with a gift, never filled. While a one-tap claim runs
 *  it spins and says what is happening; everything else stays usable. */
@Composable
internal fun WelcomeOfferButton(hint: WelcomeHint, claiming: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val resources = LocalContext.current.resources
    if (!claiming) {
        LarderButton(
            text = welcomeOfferLabel(resources, hint),
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            style = LarderButtonStyle.Outline,
            leadingIcon = LarderIcons.Gift
        )
        return
    }
    // The button's own outline and size, with a small spinner and what is happening in words
    // (LarderButton's loading state shows the spinner alone).
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = LarderSize.control)
            .border(1.dp, scheme.outline, CircleShape)
            .padding(horizontal = LarderSpace.s5, vertical = LarderSpace.s2)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WelcomeSpinner()
        Text(text = stringResource(R.string.welcome_claiming), style = MaterialTheme.typography.labelLarge, color = scheme.primary)
    }
}

/** The offer as the first row of the Recipe form's "Other ways" card: the gift in an accent tile,
 *  the offer as its title, [supporting] under it (a menu's line), and a chevron, as it opens the
 *  sign-in sheet. While a one-tap claim runs, a spinner in the tile and what is happening, and no
 *  taps. */
@Composable
internal fun WelcomeOfferRow(hint: WelcomeHint, claiming: Boolean, tiles: Boolean, supporting: String?, onClick: () -> Unit) {
    val resources = LocalContext.current.resources
    RouteRow(
        icon = LarderIcons.Gift,
        title = if (claiming) stringResource(R.string.welcome_claiming) else welcomeOfferLabel(resources, hint),
        supporting = if (claiming) null else supporting?.let(::AnnotatedString),
        onClick = onClick,
        tiles = tiles,
        chevron = true,
        accent = true,
        busy = claiming
    )
}

@Composable
private fun WelcomeSpinner() {
    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
}

/** Recipe settings' note: a quiet bordered line with a gift. */
@Composable
internal fun WelcomeNote(text: String) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(LarderRadius.md)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, scheme.outlineVariant, shape)
            .padding(horizontal = LarderSpace.s3, vertical = LarderSpace.s3),
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2)
    ) {
        LarderIcon(LarderIcons.Gift, contentDescription = null, size = 18.dp, tint = scheme.primary, modifier = Modifier.padding(top = 1.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
    }
}

/** A row in the "How recipes work" style, whose supporting text can carry an emphasised sentence. */
@Composable
internal fun WelcomeInfoRow(icon: LarderIcons, title: String, supporting: AnnotatedString) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = LarderSpace.s4, vertical = LarderSpace.s2),
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s3),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LarderThumb(imageUrl = null, fallbackIcon = icon)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(text = supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** [before], then [emphasis] in the label weight (so it can be found), then [after]. */
internal fun emphasised(before: String, emphasis: String, after: String): AnnotatedString = buildAnnotatedString {
    append(before)
    if (before.isNotEmpty()) append(' ')
    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(emphasis) }
    if (after.isNotEmpty()) append(' ').append(after)
}

/**
 * The sign-in step before a claim: what signing in gives and means, then Google's button (Android
 * offers no Apple) with the line for people who use Apple on an iPhone, then Not now. With a number
 * of 0 (the refresh before it said so) it is a plain sign-in that promises nothing. After the
 * sign-in, the buttons give way to "Adding your free recipes…".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WelcomeSignInSheet(sheet: WelcomeSheet, providers: Set<SignInProvider>, onSignIn: (SignInProvider) -> Unit, onDismiss: () -> Unit) {
    LarderSheet(onDismissRequest = onDismiss) {
        WelcomeSignInBody(sheet, providers, onSignIn, onDismiss)
    }
}

@Composable
internal fun WelcomeSignInBody(sheet: WelcomeSheet, providers: Set<SignInProvider>, onSignIn: (SignInProvider) -> Unit, onDismiss: () -> Unit) {
    val resources = LocalContext.current.resources
    val scheme = MaterialTheme.colorScheme
    val offer = sheet.recipes > 0
    Column(verticalArrangement = Arrangement.spacedBy(LarderSpace.s3)) {
        if (offer) {
            Box(
                modifier = Modifier.size(56.dp).background(scheme.primaryContainer, RoundedCornerShape(LarderRadius.md)),
                contentAlignment = Alignment.Center
            ) {
                LarderIcon(LarderIcons.Gift, contentDescription = null, size = 28.dp, tint = scheme.onPrimaryContainer)
            }
        }
        Text(
            text = if (offer) welcomeText(resources, sheet.recipes, R.string.welcome_get_one, R.plurals.welcome_get) else stringResource(R.string.recipe_choice_account_title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            text = if (offer) {
                welcomeText(resources, sheet.recipes, R.string.welcome_sheet_lede_one, R.plurals.welcome_sheet_lede)
            } else {
                stringResource(R.string.recipe_choice_account_body)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant
        )
        if (offer) {
            LarderGroup {
                WelcomeInfoRow(LarderIcons.Sparkles, stringResource(R.string.welcome_sheet_server_title), AnnotatedString(stringResource(R.string.welcome_sheet_server_body)))
                LarderDivider()
                WelcomeInfoRow(LarderIcons.UserRound, stringResource(R.string.welcome_sheet_account_title), AnnotatedString(stringResource(R.string.welcome_sheet_account_body)))
                LarderDivider()
                WelcomeInfoRow(LarderIcons.ShieldCheck, stringResource(R.string.welcome_sheet_privacy_title), AnnotatedString(stringResource(R.string.welcome_sheet_privacy_body)))
            }
            Text(text = stringResource(R.string.welcome_once), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
        if (sheet.adding) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(LarderSpace.s3, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                WelcomeSpinner()
                Text(text = stringResource(R.string.welcome_adding), style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
            }
        } else {
            // A failed sign-in is said here, in the sheet that stays open for another try: a
            // snackbar would sit under the sheet's scrim.
            if (sheet.signInFailed) {
                Text(
                    text = stringResource(R.string.recipe_sign_in_failed),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.error
                )
            }
            SignInButtons(providers, signingIn = if (sheet.signingIn) SignInProvider.GOOGLE else null, enabled = !sheet.signingIn, onSignIn = onSignIn)
            AppleOnIphoneHint()
        }
        if (sheet.adding) {
            // Off meanwhile, as plain quiet text (a disabled text button would draw a filled pill).
            Text(
                text = stringResource(R.string.welcome_not_now),
                modifier = Modifier.fillMaxWidth().heightIn(min = LarderSize.control).padding(top = LarderSpace.s3),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurface.copy(alpha = 0.38f),
                textAlign = TextAlign.Center
            )
        } else {
            LarderButton(
                text = stringResource(R.string.welcome_not_now),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                style = LarderButtonStyle.Text
            )
        }
    }
}

/** ALREADY: why nothing arrived, that they are now signed in, and the way on. */
@Composable
internal fun WelcomeAlreadyDialog(onClose: () -> Unit, onPacks: (() -> Unit)?) {
    LarderDialog(
        title = stringResource(R.string.welcome_already_title),
        text = stringResource(R.string.welcome_already_body),
        confirmLabel = onPacks?.let { stringResource(R.string.recipe_intro_packs_action) },
        onConfirm = { onPacks?.invoke() },
        onDismissRequest = onClose,
        dismissLabel = stringResource(R.string.recipe_close)
    )
}

// --- Previews ---------------------------------------------------------------------------------

@Composable
private fun WelcomeSheetPreview(sheet: WelcomeSheet) = LarderTheme {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(modifier = Modifier.padding(LarderSpace.s4)) {
            WelcomeSignInBody(sheet, setOf(SignInProvider.GOOGLE), {}, {})
        }
    }
}

@PreviewLightDark
@Composable
internal fun WelcomeSignInPreview() = WelcomeSheetPreview(WelcomeSheet(3))

@Preview
@Composable
internal fun WelcomeSignInAddingPreview() = WelcomeSheetPreview(WelcomeSheet(3, adding = true))

@Preview(locale = "ru")
@Composable
internal fun WelcomeSignInRussianPreview() = WelcomeSheetPreview(WelcomeSheet(5))
