import AuthenticationServices
import GoogleSignInSwift
import StoreKit
import SwiftUI

/// "Buy recipes": the three packs at the store's prices, then, the first time only, where the
/// recipes should live. Payment itself is the store's own sheet.
struct BuyRecipesSheet: View {
    @ObservedObject var model: RecipeViewModel
    let onClose: () -> Void

    @Environment(\.purchase) private var purchase
    @State private var step: Step = .packs
    @State private var selectedID: String?
    @State private var storeError = false

    private enum Step { case packs, choice }

    private var credits: RecipeCredits { model.credits }

    var body: some View {
        Group {
            switch step {
            case .packs: packs
            case .choice: RecipePurchaseChoice(model: model, onBack: { step = .packs }, onChosen: buy)
            }
        }
        .task { await credits.loadPacksIfNeeded() }
        .interactiveDismissDisabled(credits.isConfirming)
    }

    // MARK: Packs

    private var packs: some View {
        LarderSheet(L10n.recipeBuy) {
            if storeError || credits.packs == .unreachable || credits.packs == .notOffered {
                LarderCallout(
                    .warning,
                    icon: .wifiOff,
                    title: L10n.recipeStoreErrorTitle,
                    message: L10n.recipeStoreErrorMessage,
                    actionTitle: L10n.settingsTryAgain,
                    action: {
                        storeError = false
                        Task { await credits.reloadPacks() }
                    }
                )
                LarderButton(L10n.recipeClose, variant: .outline, fullWidth: true, action: onClose)
            } else if case .loaded(let packs) = credits.packs {
                Text(L10n.recipeBuyMessage)
                    .larderFont(.body)
                    .foregroundStyle(LarderColor.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                LarderGroup {
                    ForEach(packs) { pack in
                        packRow(pack, isSelected: pack.id == (selectedID ?? Self.defaultSelection(packs)))
                    }
                }
                buttons(selected: packs.first { $0.id == (selectedID ?? Self.defaultSelection(packs)) })
            } else {
                LarderSpinner()
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, LarderSpace.s8)
            }
        }
    }

    /// The middle pack, as most people buy it.
    static func defaultSelection(_ packs: [RecipePack]) -> String? {
        packs.count >= 3 ? packs[1].id : packs.first?.id
    }

    private func packRow(_ pack: RecipePack, isSelected: Bool) -> some View {
        Button { selectedID = pack.id } label: {
            HStack(spacing: LarderSpace.s3) {
                LarderTick(isOn: isSelected, label: L10n.recipeCost(pack.credits)) { selectedID = pack.id }
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(L10n.recipeCost(pack.credits))
                        .larderFont(.titleSm)
                        .foregroundStyle(LarderColor.onSurface)
                    Text(L10n.recipePackEach(pack.pricePerRecipe))
                        .larderFont(.bodySm)
                        .foregroundStyle(LarderColor.onSurfaceVariant)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Text(pack.displayPrice)
                    .larderFont(.label)
                    .monospacedDigit()
                    .foregroundStyle(LarderColor.onSurface)
            }
            .padding(.vertical, LarderSpace.s3)
            .padding(.horizontal, LarderSpace.s4)
            .frame(minHeight: LarderSize.touch)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(credits.isConfirming)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    private func buttons(selected: RecipePack?) -> some View {
        HStack(spacing: LarderSpace.s2) {
            LarderButton(L10n.cancelButton, variant: .outline, fullWidth: true, action: onClose)
                .disabled(credits.isConfirming)
            if credits.isConfirming {
                HStack(spacing: LarderSpace.s2) {
                    LarderSpinner()
                    Text(L10n.recipeAdding).larderFont(.label)
                }
                .foregroundStyle(LarderColor.onSurfaceVariant)
                .frame(maxWidth: .infinity, minHeight: LarderSize.touch)
                .accessibilityElement(children: .combine)
            } else {
                LarderButton(L10n.recipeContinue, fullWidth: true) {
                    guard let selected else { return }
                    selectedID = selected.id
                    if credits.needsPurchaseChoice {
                        step = .choice
                    } else {
                        buy()
                    }
                }
                .disabled(selected == nil)
            }
        }
        .padding(.top, LarderSpace.s2)
    }

    // MARK: Buying

    private func buy() {
        guard case .loaded(let packs) = credits.packs,
              let pack = packs.first(where: { $0.id == (selectedID ?? Self.defaultSelection(packs)) }) else { return }
        step = .packs
        let purchase = purchase
        Task {
            let outcome = await credits.buy(pack) { product in try await purchase(product) }
            switch outcome {
            case .added, .notAddedYet, .pending, .rejected:
                onClose()
            case .storeUnavailable:
                storeError = true
            case .cancelled:
                break
            }
        }
    }
}

/// "Where should your recipes live?": asked once, before the first purchase, with both
/// consequences spelled out before any money moves.
private struct RecipePurchaseChoice: View {
    @ObservedObject var model: RecipeViewModel
    let onBack: () -> Void
    let onChosen: () -> Void

    var body: some View {
        LarderSheet(L10n.recipeChoiceTitle) {
            Text(L10n.recipeChoiceMessage)
                .larderFont(.body)
                .foregroundStyle(LarderColor.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)

            Button {
                model.credits.chosePurchaseLocation()
                onChosen()
            } label: {
                option(icon: .smartphone, title: L10n.recipeChoicePhoneTitle, message: L10n.recipeChoicePhoneMessage) {
                    EmptyView()
                }
            }
            .buttonStyle(.plain)
            .disabled(model.credits.isSigningIn)

            option(
                icon: .userRound,
                title: L10n.recipeChoiceAccountTitle,
                message: L10n.recipeChoiceAccountMessage(googleAvailable: GoogleSignInSupport.isConfigured)
            ) {
                RecipeSignInButtons(credits: model.credits) { signedIn in
                    if signedIn {
                        model.credits.chosePurchaseLocation()
                        onChosen()
                    }
                }
            }

            LarderButton(L10n.backButton, variant: .text, action: onBack)
                .frame(maxWidth: .infinity)
        }
    }

    private func option<Extra: View>(
        icon: LarderIconName,
        title: String,
        message: String,
        @ViewBuilder extra: () -> Extra
    ) -> some View {
        VStack(alignment: .leading, spacing: LarderSpace.s2) {
            HStack(spacing: LarderSpace.s2) {
                LarderIcon(icon).foregroundStyle(LarderColor.primary)
                Text(title)
                    .larderFont(.titleSm)
                    .foregroundStyle(LarderColor.onSurface)
            }
            Text(message)
                .larderFont(.bodySm)
                .foregroundStyle(LarderColor.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
            extra()
        }
        .padding(LarderSpace.s4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(LarderColor.surface, in: RoundedRectangle(cornerRadius: LarderRadius.md))
        .overlay {
            RoundedRectangle(cornerRadius: LarderRadius.md).strokeBorder(LarderColor.outlineVariant, lineWidth: 1)
        }
        .contentShape(RoundedRectangle(cornerRadius: LarderRadius.md))
    }
}

/// Apple's and Google's own buttons. Google's shows only once the project has a Google client
/// for iOS (see `GoogleSignInSupport`). `only` limits it to one provider, as "Add Google
/// sign-in" does.
struct RecipeSignInButtons: View {
    @ObservedObject var credits: RecipeCredits
    var only: RecipeSignInProvider? = nil
    let onFinished: (Bool) -> Void

    @Environment(\.colorScheme) private var colorScheme
    @State private var appleRequest = AppleSignInRequest()

    var body: some View {
        VStack(spacing: LarderSpace.s2) {
            if only == nil || only == .apple {
                SignInWithAppleButton(.signIn) { request in
                    appleRequest.configure(request)
                } onCompletion: { result in
                    let request = appleRequest
                    Task {
                        let signedIn = await credits.signIn { try request.credential(from: result) }
                        onFinished(signedIn)
                    }
                }
                .signInWithAppleButtonStyle(colorScheme == .dark ? .white : .black)
                .frame(height: LarderSize.touch)
            }
            if only == .google, GoogleSignInSupport.isConfigured {
                // Adding Google to an account: the design's own button, as Google's says "Sign in".
                LarderButton(L10n.recipeAddSignIn(.google), icon: .circleUserRound, variant: .outline, fullWidth: true) {
                    signInWithGoogle()
                }
            } else if only == nil, GoogleSignInSupport.isConfigured {
                GoogleSignInButton(scheme: colorScheme == .dark ? .dark : .light, style: .wide) {
                    signInWithGoogle()
                }
                .frame(height: LarderSize.touch)
            }
        }
        .disabled(credits.isSigningIn)
        .opacity(credits.isSigningIn ? 0.6 : 1)
    }

    private func signInWithGoogle() {
        Task {
            let signedIn = await credits.signIn { try await GoogleSignInSupport.signIn() }
            onFinished(signedIn)
        }
    }
}
