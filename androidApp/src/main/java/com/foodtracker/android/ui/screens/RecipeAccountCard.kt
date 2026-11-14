package com.foodtracker.android.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.foodtracker.android.R
import com.foodtracker.android.ui.design.LarderButton
import com.foodtracker.android.ui.design.LarderButtonStyle
import com.foodtracker.android.ui.design.LarderDialog
import com.foodtracker.android.ui.design.LarderIcons
import com.foodtracker.android.ui.theme.LarderSpace
import com.foodtracker.android.ui.theme.LarderTheme
import com.foodtracker.recipe.AccountKind
import com.foodtracker.recipe.AccountState
import com.foodtracker.recipe.PaidUiState
import com.foodtracker.recipe.SignInProvider

internal class AccountActions(
    val onSignIn: (SignInProvider) -> Unit = {},
    val onBuy: () -> Unit = {},
    val onSignOut: () -> Unit = {},
    val onDelete: () -> Unit = {}
)

/**
 * Where bought recipes are kept, in Recipe settings: nothing yet; on this phone, with the way into
 * an account and deletion of what our server holds for it; or in an account, with sign-out and
 * deletion. The consequence of "on this phone" is repeated here, where the recipes are shown,
 * with the way out of it. Nothing to delete is offered without a user: someone who never started
 * a purchase has nothing on our server.
 */
@Composable
internal fun AccountCard(paid: PaidUiState, actions: AccountActions) {
    val account = paid.account
    var confirmingSignOut by rememberSaveable { mutableStateOf(false) }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    var confirmingDeviceDelete by rememberSaveable { mutableStateOf(false) }
    val balance = paid.balance ?: 0
    // Below zero after a store refund of recipes already used: none of them is lost.
    val unused = balance.coerceAtLeast(0)

    when (account.kind) {
        AccountKind.NONE -> RecipeFormCard(title = stringResource(R.string.recipe_account), icon = LarderIcons.UserRound) {
            AccountText(stringResource(R.string.recipe_account_none))
            if (SignInProvider.GOOGLE in paid.providers) {
                // Someone with recipes in an account, on a new phone, signs in here without buying.
                AccountText(stringResource(R.string.recipe_have_account_hint))
                SignInButtons(paid.providers, signingIn = null, enabled = !paid.accountBusy, onSignIn = actions.onSignIn)
                AppleOnIphoneHint()
            }
        }
        AccountKind.ANONYMOUS -> RecipeFormCard(title = stringResource(R.string.recipe_your_recipes), icon = LarderIcons.Ticket) {
            BalanceLine(balance, pluralStringResource(R.plurals.recipe_on_phone_label, balance))
            AccountText(stringResource(R.string.recipe_on_phone_body))
            SignInButtons(paid.providers, signingIn = null, enabled = !paid.accountBusy, onSignIn = actions.onSignIn)
            if (SignInProvider.GOOGLE in paid.providers) AppleOnIphoneHint()
            if (paid.purchasesBlocked) {
                AccountText(stringResource(R.string.recipe_purchases_blocked))
            } else if (paid.canBuy) {
                LarderButton(
                    text = stringResource(R.string.recipe_buy),
                    onClick = actions.onBuy,
                    modifier = Modifier.fillMaxWidth(),
                    style = LarderButtonStyle.Text,
                    leadingIcon = LarderIcons.Ticket
                )
            }
            DeleteWaitsNote(paid)
            LarderButton(
                text = stringResource(R.string.recipe_delete_device_data),
                onClick = { confirmingDeviceDelete = true },
                style = LarderButtonStyle.DangerText,
                enabled = !paid.accountBusy && !paid.deletionWaitsForPurchase,
                loading = paid.accountBusy
            )
        }
        AccountKind.SIGNED_IN -> RecipeFormCard(title = stringResource(R.string.recipe_your_recipes), icon = LarderIcons.Ticket) {
            BalanceLine(balance, pluralStringResource(R.plurals.recipe_in_account_label, balance))
            Column {
                // By name, as text: an account can carry Apple (added on an iPhone) as well as
                // Google, and Android shows no Apple logo.
                if (account.providers.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.recipe_signed_in_with, signInProvidersText(account.providers)),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                account.email?.let {
                    Text(text = it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            AccountText(stringResource(signedInHint(account.providers)))
            // Only Google can be added on Android. Google's own button (its branding allows no
            // "Add ..." title); the hint above says what adding does, and the spoken label too.
            val add = signInsToAdd(paid.providers, account.providers)
            if (add.isNotEmpty()) {
                SignInButtons(
                    providers = add,
                    signingIn = null,
                    enabled = !paid.accountBusy,
                    onSignIn = actions.onSignIn,
                    accessibilityLabel = stringResource(R.string.recipe_add_sign_in, signInProviderName(SignInProvider.GOOGLE))
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2), verticalAlignment = Alignment.CenterVertically) {
                LarderButton(
                    text = stringResource(R.string.recipe_sign_out),
                    onClick = { confirmingSignOut = true },
                    style = LarderButtonStyle.Text,
                    enabled = !paid.accountBusy
                )
                LarderButton(
                    text = stringResource(R.string.recipe_delete_account),
                    onClick = { confirmingDelete = true },
                    style = LarderButtonStyle.DangerText,
                    enabled = !paid.accountBusy && !paid.deletionWaitsForPurchase,
                    loading = paid.accountBusy
                )
            }
            DeleteWaitsNote(paid)
        }
    }

    if (confirmingSignOut) {
        // A dialog, because the count on screen goes to zero, which looks like a loss unless it
        // is explained first.
        LarderDialog(
            title = stringResource(R.string.recipe_sign_out_title),
            text = pluralStringResource(R.plurals.recipe_sign_out_body, balance, balance),
            confirmLabel = stringResource(R.string.recipe_sign_out),
            onConfirm = {
                confirmingSignOut = false
                actions.onSignOut()
            },
            onDismissRequest = { confirmingSignOut = false },
            dismissLabel = stringResource(R.string.recipe_cancel)
        )
    }
    if (confirmingDelete) {
        LarderDialog(
            title = stringResource(R.string.recipe_delete_title),
            // Nothing about Apple here: the server removes Apple's link with the token it kept
            // from the iPhone sign-in, and only if it can't is the person told how (RecipePaidOverlays).
            text = pluralStringResource(R.plurals.recipe_delete_body, balance, balance),
            confirmLabel = stringResource(R.string.recipe_delete_account),
            onConfirm = {
                confirmingDelete = false
                actions.onDelete()
            },
            onDismissRequest = { confirmingDelete = false },
            dismissLabel = stringResource(R.string.recipe_cancel),
            destructive = true
        )
    }
    if (confirmingDeviceDelete) {
        LarderDialog(
            title = stringResource(R.string.recipe_delete_device_title),
            text = if (unused > 0) {
                pluralStringResource(R.plurals.recipe_delete_device_body, unused, unused)
            } else {
                stringResource(R.string.recipe_delete_device_body_none)
            },
            confirmLabel = stringResource(R.string.recipe_delete_device_confirm),
            onConfirm = {
                confirmingDeviceDelete = false
                actions.onDelete()
            },
            onDismissRequest = { confirmingDeviceDelete = false },
            dismissLabel = stringResource(R.string.recipe_cancel),
            destructive = true
        )
    }
}

/** Why deletion is off for now: a purchase of this account is paid for (or being paid) and
 *  not added yet. */
@Composable
private fun DeleteWaitsNote(paid: PaidUiState) {
    if (paid.deletionWaitsForPurchase) AccountText(stringResource(R.string.recipe_delete_waits_for_purchase))
}

/** "Google, Apple": the account's sign-ins by name, Google first. */
internal fun signInProvidersText(providers: Set<SignInProvider>): String =
    providers.sortedBy { it.ordinal }.joinToString(", ") { signInProviderName(it) }

/** The line under a signed-in account. Android signs in with Google only, so it names Google as
 *  the way in on another phone, and Apple only for an iPhone. */
@StringRes
internal fun signedInHint(providers: Set<SignInProvider>): Int = when {
    SignInProvider.GOOGLE !in providers -> R.string.recipe_signed_in_add_google
    SignInProvider.APPLE in providers -> R.string.recipe_signed_in_google_and_apple
    else -> R.string.recipe_signed_in_google
}

/** The sign-ins worth adding here: those this build offers (only ever Google on Android) that the
 *  account lacks. Apple is never offered, whatever the account carries. */
internal fun signInsToAdd(offered: Set<SignInProvider>, account: Set<SignInProvider>): Set<SignInProvider> =
    (offered - account) - SignInProvider.APPLE

@Composable
private fun BalanceLine(balance: Int, label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2), verticalAlignment = Alignment.Bottom) {
        Text(text = balance.toString(), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurface)
        Text(
            text = label,
            modifier = Modifier.padding(bottom = LarderSpace.s1),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AccountText(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private val offered = setOf(SignInProvider.GOOGLE)

@Composable
private fun AccountPreview(paid: PaidUiState) = LarderTheme {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.padding(LarderSpace.s4)) { AccountCard(paid, AccountActions()) }
    }
}

@Preview
@Composable
internal fun AccountNonePreview() = AccountPreview(PaidUiState(available = true, providers = offered))

@PreviewLightDark
@Composable
internal fun AccountOnPhonePreview() = AccountPreview(
    PaidUiState(available = true, balance = 12, account = AccountState(AccountKind.ANONYMOUS), providers = offered)
)

@PreviewLightDark
@Composable
internal fun AccountSignedInPreview() = AccountPreview(
    PaidUiState(
        available = true,
        balance = 12,
        account = AccountState(AccountKind.SIGNED_IN, setOf(SignInProvider.GOOGLE), "someone@example.com"),
        providers = offered
    )
)

/** Signed in with Google here, on an account that also has Apple, added on an iPhone. */
@PreviewLightDark
@Composable
internal fun AccountSignedInWithAppleTooPreview() = AccountPreview(
    PaidUiState(
        available = true,
        balance = 12,
        account = AccountState(AccountKind.SIGNED_IN, setOf(SignInProvider.GOOGLE, SignInProvider.APPLE), "k7x2m9@privaterelay.appleid.com"),
        providers = offered
    )
)

@Preview(locale = "ru")
@Composable
internal fun AccountSignedInRussianPreview() = AccountPreview(
    PaidUiState(
        available = true,
        balance = 12,
        account = AccountState(AccountKind.SIGNED_IN, setOf(SignInProvider.GOOGLE, SignInProvider.APPLE), "k7x2m9@privaterelay.appleid.com"),
        providers = offered
    )
)

@Preview(locale = "nb")
@Composable
internal fun AccountNoneNorwegianPreview() = AccountPreview(PaidUiState(available = true, providers = offered))
