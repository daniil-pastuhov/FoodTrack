import SwiftUI

/// The tab's own settings, separate from app Settings: everything about how recipes are written,
/// of which the quick form shows three. Changes save as they are made.
struct RecipeSettingsView: View {
    @ObservedObject var model: RecipeViewModel
    @ObservedObject private var settingsStore = SettingsStore.shared
    @State private var choosingLanguage = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: LarderSpace.s3) {
                RecipeFormCard {
                    heading(L10n.recipeSettingsCooking, icon: .chefHat)
                    Text(L10n.recipeSettingsCookingDesc)
                        .larderFont(.bodySm)
                        .foregroundStyle(LarderColor.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                    RecipeDifficultyField(selection: model.binding(\.difficulty))
                    RecipeFormField(label: L10n.recipeLabelDetail) {
                        LarderSegmented(selection: model.binding(\.detail), options: RecipeDetail.allCases, fill: true) {
                            L10n.recipeDetailName($0)
                        }
                    }
                    RecipeServingsField(servings: model.binding(\.servings))
                    RecipeTimeField(maxMinutes: model.binding(\.maxMinutes))
                }

                RecipeFormCard {
                    heading(L10n.recipeSettingsMealDiet, icon: .utensils)
                    RecipeFormField(label: L10n.recipeLabelMeal) {
                        RecipeChoiceChips(selection: model.binding(\.mealType), options: RecipeMealType.allCases) {
                            L10n.recipeMealName($0)
                        }
                    }
                    RecipeFormField(label: L10n.recipeLabelDiet) {
                        RecipeChoiceChips(selection: model.binding(\.diet), options: RecipeDiet.allCases) {
                            L10n.recipeDietName($0)
                        }
                    }
                    allergensNote
                    // Where the consent to send allergens to our server is seen and withdrawn.
                    if model.credits.isPaidRouteAvailable {
                        LarderSwitch(
                            L10n.recipeAllergenConsentSetting,
                            subtitle: allergenConsentSubtitle,
                            isOn: Binding(
                                get: { AllergenConsent.isGiven(model.allergenConsent) },
                                set: { model.setAllergenConsent($0) }
                            )
                        )
                    }
                }

                // What goes with a recipe besides the ticked items, said plainly: with it on,
                // every route gets the whole pantry (names, dates, quantities).
                RecipeFormCard {
                    heading(L10n.recipeIngredientsSettings, icon: .refrigerator)
                    LarderSwitch(
                        L10n.recipeSendAll,
                        subtitle: L10n.recipeSendAllMessage,
                        isOn: Binding(get: { model.options.sendAllItems }, set: { model.setSendAllItems($0) })
                    )
                }

                RecipeFormCard {
                    heading(L10n.recipeSettingsLanguage, icon: .languages)
                    Button { choosingLanguage = true } label: {
                        HStack(spacing: LarderSpace.s3) {
                            LarderIcon(.languages).foregroundStyle(LarderColor.onSurfaceVariant)
                            Text(L10n.recipeWriteIn)
                                .larderFont(.body)
                                .foregroundStyle(LarderColor.onSurface)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            Text(L10n.recipeLanguageName(model.config.language))
                                .larderFont(.label)
                                .foregroundStyle(LarderColor.onSurfaceVariant)
                                .multilineTextAlignment(.trailing)
                            LarderIcon(.chevronRight).foregroundStyle(LarderColor.onSurfaceVariant)
                        }
                        .frame(minHeight: LarderSize.control)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }

                RecipeAccountCard(model: model)

                LarderGroup {
                    LarderListItem(
                        L10n.recipeIntroTitle,
                        supporting: L10n.recipeIntroSettingsSupporting,
                        onTap: { model.showIntro() }
                    ) {
                        LarderIcon(.info).foregroundStyle(LarderColor.onSurfaceVariant)
                    } trailing: {
                        LarderIcon(.chevronRight)
                    }
                }
            }
            .padding(.horizontal, LarderSpace.s4)
            .padding(.top, LarderSpace.s2)
            .padding(.bottom, LarderSpace.s8)
            .frame(maxWidth: 640)
            .frame(maxWidth: .infinity)
        }
        .background(LarderColor.background.ignoresSafeArea())
        .navigationTitle(L10n.recipeSettingsButton)
        .navigationBarTitleDisplayMode(.inline)
        .shellChromeEffect(hideNav: true)
        .task { await model.credits.refreshBalance() }
        // Turning the switch on: the same consent step as before a request; agreeing only records it.
        .larderDialog(
            L10n.recipeAllergenConsentTitle,
            isPresented: Binding(get: { model.consentPrompt == .fromSettings }, set: { _ in }),
            message: L10n.recipeAllergenConsentMessage(settingsStore.settings.allergens.sorted()),
            actions: [
                .cancel(L10n.cancelButton) { model.declineAllergenConsent() },
                .init(L10n.recipeAllergenConsentAgreeOnly) { model.agreeToAllergenConsent() },
            ]
        )
        .larderSheet(
            isPresented: Binding(get: { model.intro == .fromSettings }, set: { if !$0, model.intro == .fromSettings { model.dismissIntro() } }),
            fitsContent: true
        ) {
            RecipeIntroSheet(model: model)
        }
        .larderSheet(isPresented: $choosingLanguage) {
            LanguagePicker(selection: model.config.language) { language in
                model.updateConfig { $0.language = language }
                choosingLanguage = false
            }
        }
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

    private var allergenConsentSubtitle: String {
        if let record = model.allergenConsent, AllergenConsent.isGiven(record) {
            return L10n.recipeAllergenConsentSettingOn(record.at)
        }
        return L10n.recipeAllergenConsentSettingOff
    }

    private var allergensNote: some View {
        HStack(alignment: .top, spacing: LarderSpace.s2) {
            LarderIcon(.circleAlert, size: .custom(18)).foregroundStyle(LarderColor.primary)
            Text(L10n.recipeAllergensNote(settingsStore.settings.allergens.sorted()))
                .larderFont(.bodySm)
                .foregroundStyle(LarderColor.onSurface)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(LarderSpace.s3)
        .overlay {
            RoundedRectangle(cornerRadius: LarderRadius.md).strokeBorder(LarderColor.outlineVariant, lineWidth: 1)
        }
    }
}

/// "Write recipes in": the app's language, or one of the others the server and the prompt
/// handle. The phone's own model knows fewer; where it doesn't know the one chosen, its route is
/// hidden and the prompt is the free route.
private struct LanguagePicker: View {
    let selection: RecipeLanguage?
    let onPick: (RecipeLanguage?) -> Void

    var body: some View {
        LarderSheet(L10n.recipeWriteIn) {
            LarderGroup {
                option(nil)
                ForEach(RecipeLanguage.allCases, id: \.self) { option($0) }
            }
        }
    }

    private func option(_ language: RecipeLanguage?) -> some View {
        let isSelected = selection == language
        return LarderListItem(L10n.recipeLanguageName(language), onTap: { onPick(language) }) {
            if isSelected {
                LarderIcon(.check).foregroundStyle(LarderColor.primary)
            }
        }
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}
