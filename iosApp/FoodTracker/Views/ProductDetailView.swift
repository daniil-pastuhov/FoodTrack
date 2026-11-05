import SwiftUI

/// Nutritional and dietary detail for a scanned product: Nutri-Score, NOVA group, allergens and
/// ingredients.
///
/// Open Food Facts has returned all of this along with the name and image since the app first
/// called it; the response was simply being discarded. For someone with an allergy the allergen
/// list is the reason to scan at all, so this is closer to a missing feature than an extra.
///
/// Everything here is frequently absent — OFF is crowd-sourced, and a product having a name says
/// nothing about whether anyone filled in its ingredients — so each section is omitted rather
/// than shown empty, and the sheet is only offered at all when something is known.
struct ProductDetailView: View {
    let productName: String
    let nutrition: ProductNutrition
    /// The user's own allergens that this product matched, surfaced at the top rather than left
    /// for them to spot in the list.
    let warnings: [String]

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    if !warnings.isEmpty {
                        Label(L10n.allergenWarning(warnings.joined(separator: ", ")), systemImage: "exclamationmark.triangle.fill")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(LarderColor.onErrorContainer)
                            .padding(12)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(LarderColor.errorContainer)
                            .cornerRadius(12)
                    }

                    HStack(spacing: 16) {
                        if let grade = nutrition.nutriScore {
                            ScoreBadge(text: grade.uppercased(), caption: L10n.productDetailNutriScore, colors: nutriScoreColors(grade))
                        }
                        if let nova = nutrition.novaGroup {
                            ScoreBadge(text: "\(nova)", caption: L10n.productDetailNova, colors: novaColors(nova))
                        }
                    }

                    if !nutrition.allergens.isEmpty {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(L10n.productDetailAllergens).font(.headline)
                            FlowLayout(spacing: 8) {
                                ForEach(nutrition.allergens, id: \.self) { allergen in
                                    Text(allergen)
                                        .font(.caption)
                                        .padding(.horizontal, 10)
                                        .padding(.vertical, 6)
                                        .background(LarderColor.surfaceContainer)
                                        .cornerRadius(8)
                                }
                            }
                        }
                    }

                    if let ingredients = nutrition.ingredients, !ingredients.isEmpty {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(L10n.productDetailIngredients).font(.headline)
                            Text(ingredients)
                                .font(.subheadline)
                                .foregroundStyle(LarderColor.onSurfaceVariant)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(20)
            }
            .navigationTitle(productName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L10n.doneButton) { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    /// Green-to-red the way the official scale is — the letter alone means nothing to someone
    /// who hasn't seen the scale before.
    private func nutriScoreColors(_ grade: String) -> (Color, Color) {
        switch grade.lowercased() {
        case "a": return (LarderColor.nutriA, LarderColor.onNutriA)
        case "b": return (LarderColor.nutriB, LarderColor.onNutriB)
        case "c": return (LarderColor.nutriC, LarderColor.onNutriC)
        case "d": return (LarderColor.nutriD, LarderColor.onNutriD)
        default: return (LarderColor.nutriE, LarderColor.onNutriE)
        }
    }

    /// NOVA 1...4, least to most processed.
    private func novaColors(_ group: Int) -> (Color, Color) {
        switch group {
        case 1: return (LarderColor.nutriA, LarderColor.onNutriA)
        case 2: return (LarderColor.nutriB, LarderColor.onNutriB)
        case 3: return (LarderColor.nutriD, LarderColor.onNutriD)
        default: return (LarderColor.nutriE, LarderColor.onNutriE)
        }
    }
}

private struct ScoreBadge: View {
    let text: String
    let caption: String
    let colors: (background: Color, foreground: Color)

    var body: some View {
        VStack(spacing: 4) {
            Text(text)
                .font(.title2.weight(.bold))
                .foregroundColor(colors.foreground)
                .frame(width: 44, height: 44)
                .background(colors.background)
                .clipShape(Circle())
            Text(caption)
                .font(.caption2)
                .foregroundStyle(LarderColor.onSurfaceVariant)
        }
    }
}
