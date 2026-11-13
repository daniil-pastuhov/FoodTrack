import SwiftUI

/// One recipe, read on a counter at arm's length: large type, numbered steps. It is saved as
/// soon as it arrives, so there is no Save button; Share sends it as text.
struct RecipeResultView: View {
    /// Nil when the entry was deleted while this screen was on the stack.
    let entry: SavedRecipe?

    @State private var sharing = false

    var body: some View {
        Group {
            if let recipe = entry?.recipe {
                RecipeDocument(recipe: recipe)
                    .toolbar {
                        ToolbarItem(placement: .primaryAction) {
                            LarderIconButton(.share2, label: L10n.recipeShareRecipe, color: LarderColor.primary) {
                                sharing = true
                            }
                        }
                    }
                    .sheet(isPresented: $sharing) {
                        ActivityView(activityItems: [RecipePromptBuilder.plainText(recipe)])
                    }
            } else {
                EmptyState(.clear, title: L10n.recipeSavedEmptyTitle, message: L10n.recipeSavedEmptyMessage)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(LarderColor.background.ignoresSafeArea())
        .navigationTitle(L10n.recipeResultTitle)
        .navigationBarTitleDisplayMode(.inline)
        .shellChromeEffect(hideNav: true)
    }
}

/// The recipe itself. Titles wrap, never cut; amounts keep a fixed column so names line up.
struct RecipeDocument: View {
    let recipe: Recipe

    @ScaledMetric(relativeTo: .body) private var amountWidth: CGFloat = 72
    @ScaledMetric(relativeTo: .body) private var stepNumberSide: CGFloat = 28

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text(recipe.title)
                    .larderFont(.headline)
                    .foregroundStyle(LarderColor.onBackground)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityAddTraits(.isHeader)
                    .padding(.bottom, LarderSpace.s2)
                if !recipe.summary.isEmpty {
                    Text(recipe.summary)
                        .larderFont(.body)
                        .foregroundStyle(LarderColor.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.bottom, LarderSpace.s3)
                }
                facts

                SectionHeader(L10n.recipeIngredientsTitle)
                    .padding(.top, LarderSpace.s2)
                LarderGroup {
                    ForEach(Array(recipe.ingredients.enumerated()), id: \.offset) { _, ingredient in
                        ingredientRow(ingredient)
                    }
                }

                SectionHeader(L10n.recipeMethodTitle)
                    .padding(.top, LarderSpace.s2)
                VStack(alignment: .leading, spacing: LarderSpace.s4) {
                    ForEach(Array(recipe.steps.enumerated()), id: \.offset) { index, step in
                        stepRow(number: index + 1, text: step)
                    }
                }

                if let tip = recipe.tip, !tip.isEmpty {
                    LarderCallout(.info, icon: .lightbulb, title: L10n.recipeTipTitle, message: tip)
                        .padding(.top, LarderSpace.s4)
                }

                HStack(alignment: .top, spacing: LarderSpace.s2) {
                    LarderIcon(recipe.source == .device ? .smartphone : .cloud, size: .custom(16))
                    Text(L10n.recipeSource(recipe.source))
                        .larderFont(.caption)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .foregroundStyle(LarderColor.onSurfaceVariant)
                .padding(.top, LarderSpace.s6)
            }
            .padding(.horizontal, LarderSpace.s4)
            .padding(.top, LarderSpace.s2)
            .padding(.bottom, LarderSpace.s8)
            .frame(maxWidth: 640, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
    }

    private var facts: some View {
        FlowLayout(spacing: LarderSpace.s2, rowSpacing: LarderSpace.s2) {
            if recipe.minutes > 0 { fact(.timer, L10n.recipeMinutes(recipe.minutes)) }
            if recipe.servings > 0 { fact(.users, L10n.recipeServings(recipe.servings)) }
            fact(.gauge, L10n.recipeDifficultyName(recipe.difficulty))
        }
    }

    private func fact(_ icon: LarderIconName, _ text: String) -> some View {
        HStack(spacing: LarderSpace.s1) {
            LarderIcon(icon, size: .custom(16))
            Text(text).larderFont(.label)
        }
        .foregroundStyle(LarderColor.onSurfaceVariant)
        .padding(.vertical, LarderSpace.s1)
        .padding(.horizontal, LarderSpace.s2)
        .background(LarderColor.surfaceContainer, in: RoundedRectangle(cornerRadius: LarderRadius.sm))
        .accessibilityElement(children: .combine)
    }

    private func ingredientRow(_ ingredient: RecipeIngredient) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: LarderSpace.s3) {
            Text(ingredient.amount)
                .larderFont(.label)
                .monospacedDigit()
                .foregroundStyle(LarderColor.onSurfaceVariant)
                .frame(width: amountWidth, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
            Text(ingredient.name)
                .larderFont(.body)
                .foregroundStyle(LarderColor.onSurface)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
            if ingredient.fromPantry {
                HStack(spacing: 2) {
                    LarderIcon(.check, size: .custom(14))
                    Text(L10n.recipeInYourPantry).larderFont(.caption)
                }
                .foregroundStyle(LarderColor.onSuccessContainer)
                .padding(.vertical, 1)
                .padding(.leading, LarderSpace.s1)
                .padding(.trailing, LarderSpace.s2)
                .background(LarderColor.successContainer, in: RoundedRectangle(cornerRadius: LarderRadius.sm))
                .fixedSize()
            }
        }
        .padding(.vertical, LarderSpace.s3)
        .padding(.horizontal, LarderSpace.s4)
        .accessibilityElement(children: .combine)
    }

    private func stepRow(number: Int, text: String) -> some View {
        HStack(alignment: .top, spacing: LarderSpace.s3) {
            Text("\(number)")
                .larderFont(.label)
                .monospacedDigit()
                .foregroundStyle(LarderColor.onPrimaryContainer)
                .frame(width: stepNumberSide, height: stepNumberSide)
                .background(LarderColor.primaryContainer, in: Circle())
            Text(text)
                .larderFont(.body)
                .foregroundStyle(LarderColor.onSurface)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 2)
        }
        .accessibilityElement(children: .combine)
    }
}

#Preview("Recipe, from the phone") {
    NavigationStack {
        RecipeResultView(entry: SavedRecipe(content: .recipe(Recipe(
            title: "Slow-roasted salmon with spinach, yoghurt and sourdough crumb",
            summary: "A longer cook for a weekend, using the salmon before Saturday.",
            minutes: 70,
            servings: 4,
            difficulty: .ambitious,
            ingredients: [
                RecipeIngredient(amount: "2", name: "Salmon fillet", fromPantry: true),
                RecipeIngredient(amount: "4 slices", name: "Sourdough bread, blitzed to coarse crumbs", fromPantry: true),
                RecipeIngredient(amount: "", name: "Flaky salt", fromPantry: false),
            ],
            steps: ["Heat the oven to 120°C.", "Toast the crumbs with the oil until deep gold."],
            tip: "Low oven temperatures vary.",
            source: .device
        ))))
    }
}
