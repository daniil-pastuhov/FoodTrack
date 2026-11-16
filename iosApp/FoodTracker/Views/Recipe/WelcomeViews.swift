import SwiftUI

// Free recipes for signing in: the offer's button, the note in Recipe settings and the sign-in
// sheet. What shows where is `RecipeCredits.welcomeHint` (`WelcomeRecipes.hint`); with no hint
// nothing here is drawn and every place stays as it was. Design: docs/design "Free recipes".

/// The offer as a button: "Sign in and get 3 free recipes" (opens the sign-in sheet) or, signed
/// in, "Get 3 free recipes" (claims at once, showing "Getting your free recipes…" meanwhile).
/// Outline by default, never the filled primary; a gift, not the ticket, so it doesn't read as
/// another way to buy.
struct WelcomeOfferButton: View {
    @ObservedObject var model: RecipeViewModel
    @ObservedObject var credits: RecipeCredits
    let hint: WelcomeHint
    var variant: LarderButton.Variant = .outline
    var label: String? = nil
    /// Run instead of the offer's own action (the Buy sheet closes first).
    var action: (() -> Void)? = nil

    init(model: RecipeViewModel, hint: WelcomeHint, variant: LarderButton.Variant = .outline, label: String? = nil, action: (() -> Void)? = nil) {
        self.model = model
        credits = model.credits
        self.hint = hint
        self.variant = variant
        self.label = label
        self.action = action
    }

    var body: some View {
        if credits.isClaimingWelcome {
            HStack(spacing: LarderSpace.s2) {
                LarderSpinner(size: 18, lineWidth: 2, color: LarderColor.onSurfaceVariant)
                Text(L10n.welcomeClaiming)
                    .larderFont(.label)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .foregroundStyle(LarderColor.onSurfaceVariant)
            .padding(.vertical, LarderSpace.s2)
            .padding(.horizontal, LarderSpace.s5)
            .frame(maxWidth: .infinity, minHeight: LarderSize.control)
            .overlay {
                if variant == .outline { Capsule().strokeBorder(LarderColor.outlineVariant, lineWidth: 1) }
            }
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.updatesFrequently)
        } else {
            LarderButton(
                label ?? L10n.welcomeOffer(hint),
                icon: .gift,
                variant: variant,
                isLoading: model.openingWelcomeSheet,
                fullWidth: true
            ) {
                if let action { action() } else { model.takeWelcomeOffer() }
            }
            .disabled(credits.isSigningIn)
        }
    }
}

/// The offer as a row of the Recipe form's "Other ways": the gift's tile, "Sign in and get 3 free
/// recipes" (or "Get 3 free recipes"), an optional supporting line (a menu's "Enough for this
/// menu.") and a chevron. While the claim runs, a spinner in the tile and "Getting your free
/// recipes…", not tappable. Android: `WelcomeOfferRow`.
struct WelcomeOfferRow: View {
    @ObservedObject var model: RecipeViewModel
    @ObservedObject var credits: RecipeCredits
    let hint: WelcomeHint
    var supporting: String? = nil
    var showsTile = true

    init(model: RecipeViewModel, hint: WelcomeHint, supporting: String? = nil, showsTile: Bool = true) {
        self.model = model
        credits = model.credits
        self.hint = hint
        self.supporting = supporting
        self.showsTile = showsTile
    }

    var body: some View {
        if credits.isClaimingWelcome {
            RecipeWayRow(title: L10n.welcomeClaiming, titleColor: LarderColor.onSurfaceVariant, supporting: supporting, showsTile: showsTile) {
                LarderSpinner(size: 20, lineWidth: 2, color: LarderColor.onPrimaryContainer)
                    .frame(width: 40, height: 40)
                    .background(LarderColor.primaryContainer, in: RoundedRectangle(cornerRadius: LarderRadius.md))
            }
            .accessibilityAddTraits(.updatesFrequently)
        } else {
            RecipeWayRow(
                title: L10n.welcomeOffer(hint),
                titleColor: LarderColor.primary,
                supporting: supporting,
                chevron: true,
                showsTile: showsTile,
                action: { model.takeWelcomeOffer() }
            ) {
                if model.openingWelcomeSheet {
                    LarderSpinner(size: 20, lineWidth: 2, color: LarderColor.onPrimaryContainer)
                        .frame(width: 40, height: 40)
                        .background(LarderColor.primaryContainer, in: RoundedRectangle(cornerRadius: LarderRadius.md))
                } else {
                    RecipeWayTile(icon: .gift, background: LarderColor.primaryContainer, foreground: LarderColor.onPrimaryContainer)
                }
            }
            // A second tap while the sheet opens is ignored by the model.
            .disabled(credits.isSigningIn)
        }
    }
}

/// The offer's note in a card: a gift and one line, in a quiet bordered box (Recipe settings).
struct WelcomeNote: View {
    let text: String

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: LarderSpace.s3) {
            LarderIcon(.gift, size: .custom(18))
                .foregroundStyle(LarderColor.primary)
                .alignmentGuide(.firstTextBaseline) { $0[VerticalAlignment.center] + 5 }
            Text(text)
                .larderFont(.bodySm)
                .foregroundStyle(LarderColor.onSurface)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(LarderSpace.s3)
        .background(LarderColor.surface, in: RoundedRectangle(cornerRadius: LarderRadius.md))
        .overlay {
            RoundedRectangle(cornerRadius: LarderRadius.md).strokeBorder(LarderColor.outlineVariant, lineWidth: 1)
        }
        .accessibilityElement(children: .combine)
    }
}

/// How the offer enters and leaves wherever it lives: it grows from nothing while it fades in
/// (the views around it slide under the same animation, `RecipeCredits.welcomeMotion`); with
/// Reduce Motion, a plain cross-fade.
struct WelcomeTransition: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content.transition(reduceMotion ? .opacity : .opacity.combined(with: .welcomeGrow))
    }
}

private struct WelcomeGrowModifier: ViewModifier {
    let shown: Bool

    func body(content: Content) -> some View {
        // Collapses the row's height (not a scale): its own content stays at full size and is
        // clipped while the space opens.
        content
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxHeight: shown ? nil : 0, alignment: .top)
            .clipped()
    }
}

extension AnyTransition {
    static var welcomeGrow: AnyTransition {
        .modifier(active: WelcomeGrowModifier(shown: false), identity: WelcomeGrowModifier(shown: true))
    }
}

extension View {
    func welcomeTransition() -> some View { modifier(WelcomeTransition()) }
}

/// "Get 3 free recipes": what signing in gives and means, then the existing sign-in buttons
/// (Apple and Google). Opened from an offer with a number, which it keeps while open; at 0 (the
/// number asked again right before opening said so) a plain sign-in that promises nothing. A
/// cancelled sign-in leaves it as it was; a failed one says so and it stays. Once signed in the
/// buttons give way to "Adding your free recipes…" while the claim runs, then it closes and the
/// outcome shows on the form.
struct WelcomeSignInSheet: View {
    @ObservedObject var model: RecipeViewModel
    @ObservedObject var credits: RecipeCredits
    let recipes: Int
    let onClose: () -> Void

    init(model: RecipeViewModel, recipes: Int, onClose: @escaping () -> Void) {
        self.model = model
        credits = model.credits
        self.recipes = recipes
        self.onClose = onClose
    }

    var body: some View {
        LarderSheet {
            if recipes > 0 {
                LarderIcon(.gift, size: .large)
                    .foregroundStyle(LarderColor.onPrimaryContainer)
                    .frame(width: 48, height: 48)
                    .background(LarderColor.primaryContainer, in: RoundedRectangle(cornerRadius: LarderRadius.md))
                    .accessibilityHidden(true)
                title(L10n.welcomeOfferGet(recipes))
                lede(L10n.welcomeSheetMessage(recipes))
                LarderGroup {
                    OnboardingRow(.sparkles, title: L10n.welcomeSheetServerTitle, supporting: L10n.welcomeSheetServerBody)
                    OnboardingRow(.userRound, title: L10n.welcomeSheetAccountTitle, supporting: L10n.welcomeSheetAccountBody)
                    OnboardingRow(.shieldCheck, title: L10n.welcomeSheetPrivacyTitle, supporting: L10n.welcomeSheetPrivacyBody)
                }
                Text(L10n.welcomeOnce)
                    .larderFont(.bodySm)
                    .foregroundStyle(LarderColor.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
            } else {
                title(L10n.recipeChoiceAccountTitle)
                lede(L10n.recipeChoiceAccountMessage(googleAvailable: GoogleSignInSupport.isConfigured))
            }
            Group {
                if credits.isClaimingWelcome {
                    HStack(spacing: LarderSpace.s2) {
                        LarderSpinner(size: 20, lineWidth: 2)
                        Text(L10n.welcomeAdding)
                            .larderFont(.label)
                            .foregroundStyle(LarderColor.onSurfaceVariant)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .frame(maxWidth: .infinity, minHeight: LarderSize.touch * 2 + LarderSpace.s2)
                    .accessibilityElement(children: .combine)
                    .accessibilityAddTraits(.updatesFrequently)
                } else {
                    // Claims by the number the sheet opened with: a plain sign-in (0) never claims.
                    RecipeSignInButtons(credits: credits, quietCancel: true, claims: recipes > 0) { signedIn in
                        // ALREADY (from the server, or an account that claimed here before, with
                        // no call): the dialog shows over this sheet, which closes with it.
                        if signedIn && !credits.welcomeAlready { onClose() }
                    }
                }
            }
            .padding(.top, LarderSpace.s2)
            // Here rather than on the form, which can't present it while this sheet is up.
            .larderDialog(
                L10n.welcomeAlreadyTitle,
                isPresented: Binding(get: { credits.welcomeAlready }, set: { credits.welcomeAlready = $0 }),
                message: L10n.welcomeAlreadyMessage,
                actions: credits.canBuy
                    ? [
                        .cancel(L10n.recipeClose) { onClose() },
                        .init(L10n.recipeIntroPacksAction) {
                            onClose()
                            model.welcomeToPacks()
                        },
                    ]
                    : [.cancel(L10n.recipeClose) { onClose() }]
            )
            LarderButton(L10n.welcomeNotNow, variant: .text, fullWidth: true, action: onClose)
                .disabled(credits.isClaimingWelcome)
        }
        .interactiveDismissDisabled(credits.isClaimingWelcome)
        // Signing in to an account that already holds recipes while this phone holds some too
        // asks first, here as in Recipe settings; the claim follows the join.
        .larderDialog(
            item: Binding(get: { credits.pendingMerge }, set: { if $0 == nil { credits.pendingMerge = nil } }),
            title: { _ in L10n.recipeMergeTitle },
            message: { L10n.recipeMergeMessage(account: $0.accountBalance, install: $0.installBalance) },
            actions: { _ in
                [
                    .cancel(L10n.cancelButton) { Task { await credits.cancelMerge() } },
                    .init(L10n.recipeMergeConfirm) {
                        Task {
                            await credits.confirmMerge()
                            if credits.account.isSignedIn { onClose() }
                        }
                    },
                ]
            }
        )
    }

    private func title(_ text: String) -> some View {
        Text(text)
            .larderFont(.title)
            .foregroundStyle(LarderColor.onSurface)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityAddTraits(.isHeader)
    }

    private func lede(_ text: String) -> some View {
        Text(text)
            .larderFont(.body)
            .foregroundStyle(LarderColor.onSurfaceVariant)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// "How recipes work"'s packs row with the offer's sentence set in the label weight, so it can be
/// found: the row's text, then the sentence, then that the free ways stay.
struct WelcomeIntroPacksRow: View {
    let title: String
    let message: String
    let offer: String
    let freeStays: String

    var body: some View {
        HStack(spacing: LarderSpace.s3) {
            LarderThumb(imageReference: nil, placeholderIcon: .sparkles)
            VStack(alignment: .leading, spacing: 0) {
                Text(title).larderFont(.body).foregroundStyle(LarderColor.onSurface)
                (Text(message + " ") + Text(offer).foregroundColor(LarderColor.onSurface).fontWeight(.semibold) + Text(" " + freeStays))
                    .larderFont(.bodySm)
                    .foregroundStyle(LarderColor.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, LarderSpace.s2)
        .padding(.horizontal, LarderSpace.s4)
        .frame(minHeight: 56)
        .accessibilityElement(children: .combine)
    }
}
