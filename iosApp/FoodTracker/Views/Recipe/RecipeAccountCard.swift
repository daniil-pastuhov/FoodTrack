import SwiftUI

/// Recipe settings' account card: no account, recipes on this phone, or an account signed in
/// with Apple or Google, with signing in, adding the other sign-in, signing out and deleting.
struct RecipeAccountCard: View {
    @ObservedObject var model: RecipeViewModel

    @State private var confirmingSignOut = false
    @State private var confirmingDelete = false

    private var credits: RecipeCredits { model.credits }

    var body: some View {
        RecipeFormCard {
            switch credits.account {
            case .none:
                noAccount
            case .anonymous:
                onThisPhone
            case .signedIn(_, let providers, let email):
                signedIn(providers: providers, email: email)
            }
        }
        .larderDialog(
            L10n.recipeSignOutTitle,
            isPresented: $confirmingSignOut,
            message: L10n.recipeSignOutMessage(credits.balance ?? 0),
            actions: [.cancel(L10n.cancelButton), .destructive(L10n.recipeSignOut) { credits.signOut() }]
        )
        .larderDialog(
            L10n.recipeDeleteTitle,
            isPresented: $confirmingDelete,
            message: L10n.recipeDeleteMessage(credits.balance ?? 0),
            actions: [
                .cancel(L10n.cancelButton),
                .destructive(L10n.recipeDeleteAccount) { Task { await credits.deleteAccount() } },
            ]
        )
        // Only when the server answered `appleLinkRemoved: false` after deleting.
        .larderDialog(
            L10n.recipeAppleLinkLeftTitle,
            isPresented: Binding(get: { credits.appleLinkLeft }, set: { credits.appleLinkLeft = $0 }),
            message: L10n.recipeAppleLinkLeftMessage,
            actions: [.init(L10n.recipeOK)]
        )
        .larderDialog(
            item: Binding(get: { credits.pendingMerge }, set: { if $0 == nil { credits.pendingMerge = nil } }),
            title: { _ in L10n.recipeMergeTitle },
            message: { L10n.recipeMergeMessage(account: $0.accountBalance, install: $0.installBalance) },
            actions: { _ in
                [
                    .cancel(L10n.cancelButton) { Task { await credits.cancelMerge() } },
                    .init(L10n.recipeMergeConfirm) { Task { await credits.confirmMerge() } },
                ]
            }
        )
    }

    private func heading(_ title: String, icon: LarderIconName) -> some View {
        HStack(spacing: LarderSpace.s2) {
            LarderIcon(icon).foregroundStyle(LarderColor.primary)
            Text(title)
                .larderFont(.titleSm)
                .foregroundStyle(LarderColor.onSurface)
                .accessibilityAddTraits(.isHeader)
        }
    }

    private func note(_ text: String) -> some View {
        Text(text)
            .larderFont(.bodySm)
            .foregroundStyle(LarderColor.onSurfaceVariant)
            .fixedSize(horizontal: false, vertical: true)
    }

    private func count(_ value: Int, label: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: LarderSpace.s2) {
            Text("\(value)")
                .larderFont(.headline)
                .monospacedDigit()
                .foregroundStyle(LarderColor.onSurface)
            Text(label)
                .larderFont(.body)
                .foregroundStyle(LarderColor.onSurfaceVariant)
        }
        .accessibilityElement(children: .combine)
    }

    // MARK: States

    @ViewBuilder
    private var noAccount: some View {
        heading(L10n.recipeSettingsAccount, icon: .userRound)
        note(L10n.recipeSettingsNoAccount)
        if credits.isPaidRouteAvailable {
            // Someone with recipes in an account, on a new phone, signs in here without buying.
            note(L10n.recipeHaveAccountHint)
            RecipeSignInButtons(credits: credits) { _ in }
        }
    }

    @ViewBuilder
    private var onThisPhone: some View {
        heading(L10n.recipeYourRecipes, icon: .smartphone)
        count(credits.balance ?? 0, label: L10n.recipeOnPhoneLabel(credits.balance ?? 0))
        note(L10n.recipeOnPhoneMessage)
        RecipeSignInButtons(credits: credits) { _ in }
        if credits.canBuy {
            LarderButton(L10n.recipeBuy, icon: .ticket, variant: .outline, fullWidth: true) {
                model.showsBuySheet = true
            }
        } else if credits.showsPurchasesBlocked {
            note(L10n.recipePurchasesBlocked)
        }
    }

    @ViewBuilder
    private func signedIn(providers: [RecipeSignInProvider], email: String?) -> some View {
        let missing = Self.missingProvider(providers, googleAvailable: GoogleSignInSupport.isConfigured)
        heading(L10n.recipeYourRecipes, icon: .userRound)
        count(credits.balance ?? 0, label: L10n.recipeInAccountLabel(credits.balance ?? 0))
        ForEach(providers, id: \.self) { provider in
            HStack(spacing: LarderSpace.s3) {
                LarderIcon(provider == .apple ? .apple : .circleUserRound)
                    .foregroundStyle(LarderColor.onSurfaceVariant)
                VStack(alignment: .leading, spacing: 0) {
                    Text(L10n.recipeSignedIn(with: provider))
                        .larderFont(.label)
                        .foregroundStyle(LarderColor.onSurface)
                    if let email {
                        // Apple's relay address is shown as it is.
                        Text(email)
                            .larderFont(.bodySm)
                            .foregroundStyle(LarderColor.onSurfaceVariant)
                            .textSelection(.enabled)
                    }
                }
            }
            .accessibilityElement(children: .combine)
        }
        note(L10n.recipeSignedInHint(missing: missing))
        if let missing {
            RecipeSignInButtons(credits: credits, only: missing) { _ in }
                .accessibilityLabel(L10n.recipeAddSignIn(missing))
        }
        HStack(spacing: LarderSpace.s2) {
            LarderButton(L10n.recipeSignOut, variant: .outline, fullWidth: true) { confirmingSignOut = true }
            LarderButton(L10n.recipeDeleteAccount, variant: .dangerText, fullWidth: true) { confirmingDelete = true }
        }
        .disabled(credits.isSigningIn)
    }

    /// The sign-in worth adding: Google on an iPhone signed in with Apple (so an Android phone
    /// reaches the same recipes), Apple on one signed in with Google.
    static func missingProvider(_ providers: [RecipeSignInProvider], googleAvailable: Bool) -> RecipeSignInProvider? {
        if !providers.contains(.google), googleAvailable { return .google }
        if !providers.contains(.apple) { return .apple }
        return nil
    }
}
