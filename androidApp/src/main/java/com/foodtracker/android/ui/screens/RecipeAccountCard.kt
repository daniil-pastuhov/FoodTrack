package com.foodtracker.android.ui.screens

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
 * an account; or in an account, with sign-out and deletion. The consequence of "on this phone"
 * is repeated here, where the recipes are shown, with the way out of it.
 */
@Composable
internal fun AccountCard(paid: PaidUiState, actions: AccountActions) {
    val account = paid.account
    var confirmingSignOut by rememberSaveable { mutableStateOf(false) }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    val balance = paid.balance ?: 0

    when (account.kind) {
        AccountKind.NONE -> RecipeFormCard(title = stringResource(R.string.recipe_account), icon = LarderIcons.UserRound) {
            AccountText(stringResource(R.string.recipe_account_none))
        }
        AccountKind.ANONYMOUS -> RecipeFormCard(title = stringResource(R.string.recipe_your_recipes), icon = LarderIcons.Ticket) {
            BalanceLine(balance, pluralStringResource(R.plurals.recipe_on_phone_label, balance))
            AccountText(stringResource(R.string.recipe_on_phone_body))
            SignInButtons(paid.providers, signingIn = null, enabled = !paid.accountBusy, onSignIn = actions.onSignIn)
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
        }
        AccountKind.SIGNED_IN -> RecipeFormCard(title = stringResource(R.string.recipe_your_recipes), icon = LarderIcons.Ticket) {
            BalanceLine(balance, pluralStringResource(R.plurals.recipe_in_account_label, balance))
            val signedInWith = account.providers.sortedBy { it.ordinal }.firstOrNull()
            Column {
                signedInWith?.let {
                    Text(
                        text = stringResource(R.string.recipe_signed_in_with, account.providers.sortedBy { p -> p.ordinal }.joinToString(", ") { p -> signInProviderName(p) }),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                account.email?.let {
                    Text(text = it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val missing = paid.providers - account.providers
            AccountText(
                stringResource(
                    when {
                        missing.isEmpty() -> R.string.recipe_signed_in_both
                        SignInProvider.APPLE in missing -> R.string.recipe_signed_in_add_apple
                        else -> R.string.recipe_signed_in_add_google
                    }
                )
            )
            // Apple on an iPhone and Google on an Android phone are two accounts unless joined here.
            missing.sortedBy { it.ordinal }.forEach { provider ->
                LarderButton(
                    text = stringResource(R.string.recipe_add_sign_in, signInProviderName(provider)),
                    onClick = { actions.onSignIn(provider) },
                    modifier = Modifier.fillMaxWidth(),
                    style = LarderButtonStyle.Outline,
                    enabled = !paid.accountBusy,
                    leadingIcon = if (provider == SignInProvider.APPLE) LarderIcons.Apple else LarderIcons.CircleUserRound
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
                    enabled = !paid.accountBusy,
                    loading = paid.accountBusy
                )
            }
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
}

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

private val bothProviders = setOf(SignInProvider.GOOGLE, SignInProvider.APPLE)

@Composable
private fun AccountPreview(paid: PaidUiState) = LarderTheme {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.padding(LarderSpace.s4)) { AccountCard(paid, AccountActions()) }
    }
}

@Preview
@Composable
internal fun AccountNonePreview() = AccountPreview(PaidUiState(available = true, providers = bothProviders))

@PreviewLightDark
@Composable
internal fun AccountOnPhonePreview() = AccountPreview(
    PaidUiState(available = true, balance = 12, account = AccountState(AccountKind.ANONYMOUS), providers = bothProviders)
)

@PreviewLightDark
@Composable
internal fun AccountSignedInPreview() = AccountPreview(
    PaidUiState(
        available = true,
        balance = 12,
        account = AccountState(AccountKind.SIGNED_IN, setOf(SignInProvider.GOOGLE), "someone@example.com"),
        providers = bothProviders
    )
)

@Preview(locale = "ru")
@Composable
internal fun AccountSignedInRussianPreview() = AccountPreview(
    PaidUiState(
        available = true,
        balance = 12,
        account = AccountState(AccountKind.SIGNED_IN, setOf(SignInProvider.APPLE), "k7x2m9@privaterelay.appleid.com"),
        providers = bothProviders
    )
)
