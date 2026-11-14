import SwiftUI

// Pieces the Recipe tab's quick form and Recipe settings share, built from Larder components.

extension RecipeViewModel {
    /// A binding to one configuration field, saved as it changes.
    func binding<Value>(_ keyPath: WritableKeyPath<RecipeConfig, Value>) -> Binding<Value> {
        Binding(
            get: { self.config[keyPath: keyPath] },
            set: { value in self.updateConfig { $0[keyPath: keyPath] = value } }
        )
    }
}

/// The small grey label over a control: "Difficulty".
struct RecipeFormLabel: View {
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .larderFont(.label)
            .foregroundStyle(LarderColor.onSurfaceVariant)
            .accessibilityAddTraits(.isHeader)
    }
}

/// A label over a control.
struct RecipeFormField<Control: View>: View {
    let label: String
    @ViewBuilder let control: Control

    var body: some View {
        VStack(alignment: .leading, spacing: LarderSpace.s2) {
            RecipeFormLabel(label)
            control
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// White card holding a few controls, as the design's form groups.
struct RecipeFormCard<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        LarderGroup {
            VStack(alignment: .leading, spacing: LarderSpace.s3) {
                content
            }
            .padding(LarderSpace.s4)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

struct RecipeDifficultyField: View {
    @Binding var selection: RecipeDifficulty

    var body: some View {
        RecipeFormField(label: L10n.recipeLabelDifficulty) {
            LarderSegmented(selection: $selection, options: RecipeDifficulty.allCases, fill: true) {
                L10n.recipeDifficultyName($0)
            }
        }
    }
}

struct RecipeServingsField: View {
    @Binding var servings: Int

    var body: some View {
        HStack(spacing: LarderSpace.s3) {
            RecipeFormLabel(L10n.recipeLabelServings)
                .frame(maxWidth: .infinity, alignment: .leading)
            LarderStepper(value: $servings, range: RecipeConfig.servingsRange, label: L10n.recipeLabelServings)
        }
        .frame(minHeight: LarderSize.touch)
    }
}

struct RecipeTimeField: View {
    @Binding var maxMinutes: Int?

    var body: some View {
        RecipeFormField(label: L10n.recipeLabelTime) {
            RecipeChoiceChips(selection: $maxMinutes, options: RecipeConfig.minuteOptions) {
                L10n.recipeTimeOption($0)
            }
        }
    }
}

/// One of a few chips, wrapping onto more rows as needed.
struct RecipeChoiceChips<Option: Hashable>: View {
    @Binding var selection: Option
    let options: [Option]
    let title: (Option) -> String

    var body: some View {
        FlowLayout(spacing: LarderSpace.s2, rowSpacing: LarderChip.wrapRowSpacing) {
            ForEach(options, id: \.self) { option in
                LarderChip(title(option), isSelected: option == selection) {
                    selection = option
                }
            }
        }
        .padding(.vertical, LarderSpace.s2)
    }
}

/// The tab's one primary action, pinned above the pill where Scan sits on Home: a wide block with
/// the cost said on the right ("Free"), so it reads as what it takes before it is tapped.
struct RecipeGoButton: View {
    let title: String
    let icon: LarderIconName
    var cost: String? = nil
    /// As wide as its label, like Scan on Home, for an action with no cost (the shared prompt
    /// pinned alone); the label still wraps when large text needs it.
    var compact = false
    let action: () -> Void

    @Environment(\.isEnabled) private var isEnabled

    var body: some View {
        Button(action: action) {
            HStack(spacing: LarderSpace.s3) {
                LarderIcon(icon)
                Text(title)
                    .larderFont(.label)
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: compact ? nil : .infinity, alignment: .leading)
                if let cost {
                    Text(cost)
                        .larderFont(.caption)
                        .padding(.vertical, 2)
                        .padding(.horizontal, LarderSpace.s2)
                        .overlay { Capsule().strokeBorder(lineWidth: 1) }
                        .fixedSize()
                }
            }
            .foregroundStyle(isEnabled ? LarderColor.onPrimary : LarderColor.onSurfaceVariant)
            .padding(.vertical, LarderSpace.s2)
            .padding(.horizontal, LarderSpace.s5)
            .frame(maxWidth: compact ? nil : .infinity, minHeight: 56)
            .background(
                isEnabled ? LarderColor.primary : LarderColor.surfaceContainerHigh,
                in: RoundedRectangle(cornerRadius: LarderRadius.lg)
            )
            .contentShape(RoundedRectangle(cornerRadius: LarderRadius.lg))
        }
        .buttonStyle(RecipeGoButtonStyle())
        .larderElevation(isEnabled ? .level2 : .level1)
        .accessibilityLabel(cost.map { "\(title), \($0)" } ?? title)
    }
}

private struct RecipeGoButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .overlay {
                RoundedRectangle(cornerRadius: LarderRadius.lg)
                    .fill(LarderColor.onPrimary.opacity(configuration.isPressed ? 0.14 : 0))
            }
    }
}

/// "How recipes work": one short sheet, shown by itself the first time the tab opens and from
/// Recipe settings after that. It names only the ways this device and build really offer, shows a
/// price only once the store has given one, and its one way out is just "Got it"; "See recipe
/// packs" is there only where buying is offered. Opening it calls nothing: no account, no App
/// Check token, nothing from our server. Android: `RecipeIntroSheet`.
struct RecipeIntroSheet: View {
    @ObservedObject var model: RecipeViewModel

    private var credits: RecipeCredits { model.credits }
    /// The phone's model is there, ready or still downloading.
    private var onDevice: Bool { model.routes.onDevice || model.routes.onDevicePreparing }

    var body: some View {
        LarderSheet(L10n.recipeIntroTitle) {
            Text(L10n.recipeIntroMessage)
                .larderFont(.body)
                .foregroundStyle(LarderColor.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
            LarderGroup {
                OnboardingRow(.share2, title: L10n.recipeIntroPromptTitle, supporting: L10n.recipeIntroPromptMessage)
                if onDevice {
                    OnboardingRow(.smartphone, title: L10n.recipeIntroDeviceTitle, supporting: L10n.recipeIntroDeviceMessage)
                }
                if credits.isPaidRouteAvailable {
                    OnboardingRow(
                        .sparkles,
                        title: L10n.recipeIntroPacksTitle,
                        supporting: L10n.recipeIntroPacksMessage(credits.costs, lowestPrice: credits.lowestPackPrice)
                    )
                }
            }
            Text(L10n.recipeIntroAgain)
                .larderFont(.bodySm)
                .foregroundStyle(LarderColor.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
            VStack(spacing: LarderSpace.s1) {
                LarderButton(L10n.recipeIntroStart, fullWidth: true) { model.dismissIntro() }
                if credits.canBuy {
                    LarderButton(L10n.recipeIntroPacksAction, variant: .text, fullWidth: true) { model.introToPacks() }
                }
            }
            .padding(.top, LarderSpace.s2)
        }
    }
}

private struct FormPreview: View {
    @State private var difficulty = RecipeDifficulty.easy
    @State private var servings = 2
    @State private var minutes: Int? = 30

    var body: some View {
        ScrollView {
            VStack(spacing: LarderSpace.s4) {
                RecipeFormCard {
                    RecipeDifficultyField(selection: $difficulty)
                    RecipeServingsField(servings: $servings)
                    RecipeTimeField(maxMinutes: $minutes)
                }
                RecipeGoButton(title: "Generate on this phone", icon: .smartphone, cost: "Free") {}
                RecipeGoButton(title: "Share prompt", icon: .share2) {}.disabled(true)
            }
            .padding(LarderSpace.s4)
        }
        .background(LarderColor.background)
    }
}

#Preview("Recipe form") { FormPreview() }
#Preview("Recipe form, dark") { FormPreview().preferredColorScheme(.dark) }
#Preview("Recipe form, Russian") { FormPreview().environment(\.locale, Locale(identifier: "ru")) }
