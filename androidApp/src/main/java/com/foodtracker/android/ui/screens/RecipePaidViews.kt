package com.foodtracker.android.ui.screens

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.foodtracker.android.R
import com.foodtracker.android.ui.design.GoogleSignInButton
import com.foodtracker.android.ui.design.LarderButton
import com.foodtracker.android.ui.design.LarderButtonStyle
import com.foodtracker.android.ui.design.LarderCallout
import com.foodtracker.android.ui.design.LarderCalloutKind
import com.foodtracker.android.ui.design.LarderDialog
import com.foodtracker.android.ui.design.LarderIcon
import com.foodtracker.android.ui.design.LarderIcons
import com.foodtracker.android.ui.design.LarderSheet
import com.foodtracker.android.ui.navigation.LocalLarderSnackbars
import com.foodtracker.android.ui.navigation.LocalReviewPrompter
import com.foodtracker.android.ui.theme.LarderRadius
import com.foodtracker.android.ui.theme.LarderSize
import com.foodtracker.android.ui.theme.LarderSpace
import com.foodtracker.android.ui.theme.LarderTheme
import com.foodtracker.recipe.BuySheet
import com.foodtracker.recipe.ConsentPrompt
import com.foodtracker.recipe.MergePrompt
import com.foodtracker.recipe.PaidEvent
import com.foodtracker.recipe.PurchaseRejection
import com.foodtracker.recipe.RecipeCosts
import com.foodtracker.recipe.RecipeStateHolder
import com.foodtracker.recipe.RecipeUiState
import com.foodtracker.recipe.SignInProvider
import com.foodtracker.recipe.StorePack
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency

/** The provider's own name, the same in every language. */
internal fun signInProviderName(provider: SignInProvider): String = when (provider) {
    SignInProvider.GOOGLE -> "Google"
    SignInProvider.APPLE -> "Apple"
}

/** "N recipes", with the language's plural. */
internal fun recipeCount(resources: Resources, count: Int): String =
    resources.getQuantityString(R.plurals.recipe_cost_recipes, count, count)

/** What a pack is and what a menu takes from it, with the server's costs. */
internal fun recipePacksBody(resources: Resources, costs: RecipeCosts): String =
    resources.getString(R.string.recipe_packs_body, recipeCount(resources, costs.menu2), recipeCount(resources, costs.menu3))

/** The store price of one recipe in a pack, worked out from the pack's store price and never
 *  hard-coded: "£0.16 each". */
internal fun recipePackEachPrice(resources: Resources, pack: StorePack): String? {
    if (pack.recipes <= 0 || pack.priceMicros <= 0) return null
    val currency = runCatching { Currency.getInstance(pack.currencyCode) }.getOrNull() ?: return null
    val each = BigDecimal.valueOf(pack.priceMicros)
        .divide(BigDecimal.valueOf(1_000_000L * pack.recipes), currency.defaultFractionDigits.coerceAtLeast(0), RoundingMode.HALF_UP)
    val locale = resources.configuration.locales[0]
    val format = NumberFormat.getCurrencyInstance(locale).apply { this.currency = currency }
    return resources.getString(R.string.recipe_pack_each, format.format(each))
}

/** The count in the bar: what is left of the packs, and the way to buy more. */
@Composable
internal fun RecipeBalanceChip(balance: Int, onClick: (() -> Unit)?) {
    // "Buy more" only where the chip buys: not while buying is off (blocked, paused, waiting).
    val description = stringResource(if (onClick != null) R.string.recipe_balance_description else R.string.recipe_balance_only_description, balance)
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .padding(end = LarderSpace.s1)
            .heightIn(min = LarderSize.touch)
            .clip(RoundedCornerShape(percent = 50))
            .background(scheme.primaryContainer)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = LarderSpace.s3),
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s1),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LarderIcon(LarderIcons.Ticket, contentDescription = null, size = 18.dp, tint = scheme.onPrimaryContainer)
        Text(text = balance.toString(), style = MaterialTheme.typography.labelLarge, color = scheme.onPrimaryContainer)
    }
}

/**
 * Everything about packs and the account that floats over a screen: the pack sheet, the
 * rejected-purchase dialog, the "join these recipes" question, and the snackbars for what the
 * purchase and the account just did. Shown by the Recipe tab and Recipe settings, the two places
 * a purchase or a sign-in starts.
 */
@Composable
internal fun RecipePaidOverlays(state: RecipeUiState, stateHolder: RecipeStateHolder) {
    val paid = state.paid
    val snackbars = LocalLarderSnackbars.current
    val resources = LocalContext.current.resources
    val event = paid.event
    val reviewPrompter = LocalReviewPrompter.current
    LaunchedEffect(event) {
        reviewPrompter?.onPaidEvent(event)
        val message = when (event) {
            is PaidEvent.RecipesAdded -> resources.getQuantityString(R.plurals.recipe_added, event.count, event.count)
            PaidEvent.SignInFailed -> resources.getString(R.string.recipe_sign_in_failed)
            PaidEvent.SignInInUse -> resources.getString(R.string.recipe_sign_in_in_use)
            PaidEvent.AccountDeleted -> resources.getString(R.string.recipe_account_deleted)
            PaidEvent.AccountActionFailed -> resources.getString(R.string.recipe_account_action_failed)
            PaidEvent.AccountBusy -> resources.getString(R.string.recipe_account_busy)
            is PaidEvent.Merged -> recipeMergedMessage(resources, event.moved)
            is PaidEvent.EarlierSaved ->
                resources.getString(if (event.menu) R.string.recipe_earlier_menu_saved else R.string.recipe_earlier_recipe_saved)
            is PaidEvent.EarlierRefused -> resources.getQuantityString(R.plurals.recipe_earlier_refused, event.taken, event.taken)
            null -> null
        }
        if (message != null) {
            stateHolder.consumePaidEvent()
            snackbars.show(message, null, null)
        }
    }

    paid.sheet?.let { sheet ->
        BuyRecipesSheet(
            sheet = sheet,
            providers = paid.providers,
            costs = paid.costs,
            actions = BuySheetActions(
                onSelect = stateHolder::selectPack,
                onContinue = stateHolder::continuePurchase,
                onWithoutAccount = stateHolder::buyWithoutAccount,
                onWithAccount = stateHolder::buyWithAccount,
                onBack = stateHolder::backToPacks,
                onRetry = stateHolder::openPacks,
                onDismiss = stateHolder::dismissPacks
            )
        )
    }

    paid.rejection?.let { rejection ->
        LarderDialog(
            title = stringResource(R.string.recipe_rejected_title),
            text = stringResource(
                when (rejection) {
                    PurchaseRejection.REFUSED -> R.string.recipe_rejected_body
                    PurchaseRejection.WRONG_BUILD -> R.string.recipe_rejected_build_body
                }
            ),
            confirmLabel = stringResource(R.string.recipe_ok),
            onConfirm = stateHolder::dismissRejectedPurchase,
            onDismissRequest = stateHolder::dismissRejectedPurchase
        )
    }

    // Allergens are health data: what is sent, why, to whom, and that it isn't kept, before the
    // first server request that carries them (AllergenConsent.kt). Dismissing is not agreeing.
    paid.consentPrompt?.let { prompt ->
        LarderDialog(
            title = stringResource(R.string.recipe_allergen_consent_title),
            text = stringResource(R.string.recipe_allergen_consent_body, isolatedList(state.allergens.filter { it.isNotBlank() })),
            confirmLabel = stringResource(
                if (prompt == ConsentPrompt.BEFORE_WRITING) R.string.recipe_allergen_consent_agree else R.string.recipe_allergen_consent_agree_only
            ),
            onConfirm = stateHolder::agreeToAllergenConsent,
            onDismissRequest = stateHolder::declineAllergenConsent,
            dismissLabel = stringResource(R.string.recipe_cancel)
        )
    }

    // Only when the server answered `appleLinkRemoved: false`: the one way left is Apple's own.
    if (paid.appleLinkLeft) {
        LarderDialog(
            title = stringResource(R.string.recipe_apple_link_left_title),
            text = stringResource(R.string.recipe_apple_link_left_body),
            confirmLabel = stringResource(R.string.recipe_ok),
            onConfirm = stateHolder::dismissAppleLinkNotice,
            onDismissRequest = stateHolder::dismissAppleLinkNotice
        )
    }

    paid.merge?.let { prompt ->
        MergeDialog(prompt, onConfirm = stateHolder::confirmMerge, onCancel = stateHolder::cancelMerge)
    }
}

/** What a join of this install into an account moved: recipes, or a debt after a store refund. */
internal fun recipeMergedMessage(resources: Resources, moved: Int): String =
    if (moved >= 0) {
        resources.getQuantityString(R.plurals.recipe_merged_moved, moved, moved)
    } else {
        resources.getQuantityString(R.plurals.recipe_merged_debt, -moved, -moved)
    }

@Composable
private fun MergeDialog(prompt: MergePrompt, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val text = prompt.accountBalance?.let { account ->
        stringResource(R.string.recipe_merge_body, account, prompt.phoneBalance, account + prompt.phoneBalance)
    } ?: stringResource(R.string.recipe_merge_body_unknown, prompt.phoneBalance)
    LarderDialog(
        title = stringResource(R.string.recipe_merge_title),
        text = text,
        confirmLabel = stringResource(R.string.recipe_merge_confirm),
        onConfirm = onConfirm,
        onDismissRequest = onCancel,
        dismissLabel = stringResource(R.string.recipe_cancel),
        dismissOnClickOutside = false
    )
}

internal class BuySheetActions(
    val onSelect: (String) -> Unit = {},
    val onContinue: () -> Unit = {},
    val onWithoutAccount: () -> Unit = {},
    val onWithAccount: (SignInProvider) -> Unit = {},
    val onBack: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onDismiss: () -> Unit = {}
)

/** The pack sheet: prices from the store, then the first purchase's choice, then the store's own
 *  payment sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BuyRecipesSheet(sheet: BuySheet, providers: Set<SignInProvider>, costs: RecipeCosts, actions: BuySheetActions) {
    val title = if (sheet is BuySheet.Choice) stringResource(R.string.recipe_choice_title) else stringResource(R.string.recipe_buy)
    LarderSheet(onDismissRequest = actions.onDismiss, title = title) {
        BuySheetBody(sheet, providers, actions, costs)
    }
}

@Composable
internal fun BuySheetBody(sheet: BuySheet, providers: Set<SignInProvider>, actions: BuySheetActions, costs: RecipeCosts = RecipeCosts()) {
    when (sheet) {
        BuySheet.Loading -> PacksList(packs = null, selected = null, purchasing = false, costs = costs, actions = actions)
        is BuySheet.Packs -> PacksList(sheet.packs, sheet.selected, sheet.purchasing, costs, actions)
        is BuySheet.Choice -> StorageChoice(sheet, providers, actions)
        BuySheet.StoreUnavailable -> {
            LarderCallout(
                kind = LarderCalloutKind.Warning,
                icon = LarderIcons.WifiOff,
                title = stringResource(R.string.recipe_store_error_title),
                message = stringResource(R.string.recipe_store_error_body),
                actionLabel = stringResource(R.string.recipe_try_again),
                onAction = actions.onRetry
            )
            SheetActions {
                LarderButton(text = stringResource(R.string.recipe_close), onClick = actions.onDismiss, style = LarderButtonStyle.Text)
            }
        }
    }
}

@Composable
private fun PacksList(packs: List<StorePack>?, selected: String?, purchasing: Boolean, costs: RecipeCosts, actions: BuySheetActions) {
    val resources = LocalContext.current.resources
    Text(
        text = recipePacksBody(resources, costs),
        modifier = Modifier.padding(bottom = LarderSpace.s4),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Column(
        modifier = Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(LarderSpace.s2)
    ) {
        packs?.forEach { pack ->
            PackRow(
                name = pluralStringResource(R.plurals.recipe_pack_name, pack.recipes, pack.recipes),
                each = recipePackEachPrice(resources, pack),
                price = pack.price,
                selected = pack.productId == selected,
                enabled = !purchasing,
                onClick = { actions.onSelect(pack.productId) }
            )
        }
    }
    SheetActions {
        LarderButton(text = stringResource(R.string.recipe_cancel), onClick = actions.onDismiss, style = LarderButtonStyle.Text)
        LarderButton(
            text = stringResource(R.string.continue_button),
            onClick = actions.onContinue,
            enabled = packs != null,
            loading = packs == null || purchasing
        )
    }
}

@Composable
private fun PackRow(name: String, each: String?, price: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(LarderRadius.md)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(if (selected) scheme.primaryContainer else scheme.surface, shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else scheme.outlineVariant, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = LarderSpace.s4, vertical = LarderSpace.s3),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, style = MaterialTheme.typography.titleMedium, color = if (selected) scheme.onPrimaryContainer else scheme.onSurface)
            each?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant)
            }
        }
        Text(text = price, style = MaterialTheme.typography.titleMedium, color = if (selected) scheme.onPrimaryContainer else scheme.onSurface)
    }
}

/** Where recipes should live, asked once, before any money moves: both outcomes spelled out. */
@Composable
private fun StorageChoice(sheet: BuySheet.Choice, providers: Set<SignInProvider>, actions: BuySheetActions) {
    val busy = sheet.signingIn != null
    Text(
        text = stringResource(R.string.recipe_choice_body),
        modifier = Modifier.padding(bottom = LarderSpace.s4),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Column(verticalArrangement = Arrangement.spacedBy(LarderSpace.s3)) {
        ChoiceCard(
            icon = LarderIcons.Smartphone,
            title = stringResource(R.string.recipe_choice_phone_title),
            body = stringResource(R.string.recipe_choice_phone_body),
            onClick = if (busy) null else actions.onWithoutAccount
        )
        // Without Google configured (a build from a google-services.json without its web
        // client) there is no sign-in to offer, so no account option either.
        if (SignInProvider.GOOGLE in providers) {
            ChoiceCard(
                icon = LarderIcons.UserRound,
                title = stringResource(R.string.recipe_choice_account_title),
                body = stringResource(R.string.recipe_choice_account_body),
                onClick = null
            ) {
                SignInButtons(providers, signingIn = sheet.signingIn, enabled = !busy, onSignIn = actions.onWithAccount)
                AppleOnIphoneHint()
            }
        }
    }
    SheetActions {
        LarderButton(text = stringResource(R.string.recipe_back), onClick = actions.onBack, style = LarderButtonStyle.Text, enabled = !busy)
    }
}

/**
 * The sign-in Android offers: Google's branded [GoogleSignInButton], full width, alone. There is no
 * Sign in with Apple on Android: Apple's guidelines allow a custom button only with Apple's logo
 * artwork, which can't be redistributed in this public repository, and no button without it. An
 * account with Apple attached on an iPhone signs in here with its Google sign-in.
 */
@Composable
internal fun SignInButtons(
    providers: Set<SignInProvider>,
    signingIn: SignInProvider?,
    enabled: Boolean,
    onSignIn: (SignInProvider) -> Unit,
    accessibilityLabel: String? = null
) {
    if (SignInProvider.GOOGLE !in providers) return
    GoogleSignInButton(
        onClick = { onSignIn(SignInProvider.GOOGLE) },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        loading = signingIn == SignInProvider.GOOGLE,
        accessibilityLabel = accessibilityLabel
    )
}

/** For someone whose recipes are in an account they made with Apple on an iPhone: that sign-in
 *  doesn't work here until Google is added to the account there. */
@Composable
internal fun AppleOnIphoneHint() {
    Text(
        text = stringResource(R.string.recipe_apple_on_iphone_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ChoiceCard(
    icon: LarderIcons,
    title: String,
    body: String,
    onClick: (() -> Unit)?,
    extra: (@Composable () -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(LarderRadius.md)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.surface, shape)
            .border(1.dp, scheme.outlineVariant, shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(LarderSpace.s4),
        verticalArrangement = Arrangement.spacedBy(LarderSpace.s2)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2), verticalAlignment = Alignment.CenterVertically) {
            LarderIcon(icon, contentDescription = null, tint = scheme.primary)
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
        }
        Text(text = body, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        extra?.invoke()
    }
}

@Composable
private fun SheetActions(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = LarderSpace.s4),
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2, Alignment.End),
        verticalAlignment = Alignment.CenterVertically
    ) { content() }
}

// --- Previews ---------------------------------------------------------------------------------

private val previewPacks = listOf(
    StorePack("recipes_10", 10, "$3.99", 3_990_000, "USD"),
    StorePack("recipes_50", 50, "$10.99", 10_990_000, "USD"),
    StorePack("recipes_100", 100, "$18.99", 18_990_000, "USD")
)

@Composable
private fun SheetPreview(sheet: BuySheet) = LarderTheme {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(modifier = Modifier.padding(LarderSpace.s4)) {
            BuySheetBody(sheet, setOf(SignInProvider.GOOGLE), BuySheetActions())
        }
    }
}

@PreviewLightDark
@Composable
internal fun BuyPacksPreview() = SheetPreview(BuySheet.Packs(previewPacks, "recipes_50"))

@PreviewLightDark
@Composable
internal fun BuyChoicePreview() = SheetPreview(BuySheet.Choice(previewPacks, "recipes_50"))

@PreviewLightDark
@Composable
internal fun BuyChoiceSigningInPreview() = SheetPreview(BuySheet.Choice(previewPacks, "recipes_50", signingIn = SignInProvider.GOOGLE))

@PreviewLightDark
@Composable
internal fun BuyVerifyingPreview() = SheetPreview(BuySheet.Packs(previewPacks, "recipes_50", purchasing = true))

@PreviewLightDark
@Composable
internal fun BuyStoreErrorPreview() = SheetPreview(BuySheet.StoreUnavailable)

@PreviewLightDark
@Composable
internal fun RecipeBalanceChipPreview() = LarderTheme {
    Surface(color = MaterialTheme.colorScheme.background) { RecipeBalanceChip(12) {} }
}
