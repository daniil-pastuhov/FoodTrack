import Foundation
import FoundationModels

/// The on-device route through Apple's Foundation Models: free, and nothing leaves the phone.
/// The app still runs from iOS 17, so every use sits behind `#available(iOS 26.0, *)` and the
/// model's own availability check; the framework is weakly linked as a result.
struct FoundationModelsRecipeModel: OnDeviceRecipeModel {
    func availability(for language: RecipeLanguage) -> OnDeviceRecipeAvailability {
        guard #available(iOS 26.0, *) else { return .unsupported }
        let model = SystemLanguageModel.default
        switch model.availability {
        case .available:
            return model.supportsLocale(Locale(identifier: language.rawValue)) ? .available : .unsupportedLanguage
        case .unavailable(let reason):
            // Downloading, or waiting for the phone to be idle and charging: it will come.
            return reason == .modelNotReady ? .preparing : .unsupported
        }
    }

    func write(_ request: RecipeRequest) async throws -> Recipe {
        guard #available(iOS 26.0, *) else { throw OnDeviceRecipeError.unavailable }
        let session = LanguageModelSession(instructions: RecipePromptBuilder.onDeviceInstructions(for: request))
        let generated: GeneratedRecipe
        do {
            generated = try await session.respond(
                to: RecipePromptBuilder.onDevicePrompt(for: request),
                generating: GeneratedRecipe.self
            ).content
        } catch {
            throw Self.mapped(error)
        }
        try Task.checkCancellation()
        return generated.recipe(for: request)
    }

    @available(iOS 26.0, *)
    private static func mapped(_ error: Error) -> Error {
        if error is CancellationError { return error }
        // LanguageModelError is in the iOS 27 SDK only. CodeQL builds with the runner's default,
        // older Xcode (codeql.yml), whose SDK lacks the type, so compile this out there; the
        // shipped app is built with Xcode 27.1 (Swift 6.4).
        #if compiler(>=6.4)
        if #available(iOS 27.0, *), let error = error as? LanguageModelError {
            switch error {
            case .unsupportedLanguageOrLocale: return OnDeviceRecipeError.unsupportedLanguage
            case .guardrailViolation, .refusal: return OnDeviceRecipeError.refused
            default: return OnDeviceRecipeError.failed
            }
        }
        #endif
        if let error = error as? LanguageModelSession.GenerationError {
            switch error {
            case .unsupportedLanguageOrLocale: return OnDeviceRecipeError.unsupportedLanguage
            case .guardrailViolation, .refusal: return OnDeviceRecipeError.refused
            case .assetsUnavailable: return OnDeviceRecipeError.unavailable
            default: return OnDeviceRecipeError.failed
            }
        }
        return OnDeviceRecipeError.failed
    }
}

@available(iOS 26.0, *)
@Generable
struct GeneratedRecipe {
    @Guide(description: "The dish's name, a few words")
    var title: String
    @Guide(description: "One sentence on what the dish is and which pantry items it uses")
    var summary: String
    @Guide(description: "Total time from start to table, in minutes", .range(1...480))
    var minutes: Int
    @Guide(description: "Every ingredient, pantry items first", .maximumCount(20))
    var ingredients: [GeneratedIngredient]
    @Guide(description: "The method in order, one step per entry", .maximumCount(15))
    var steps: [String]
    @Guide(description: "One practical tip for this dish, or an empty string")
    var tip: String
}

@available(iOS 26.0, *)
@Generable
struct GeneratedIngredient {
    @Guide(description: "How much, with its unit, such as 100 g or 2 slices; empty for salt, pepper and the like")
    var amount: String
    @Guide(description: "The ingredient")
    var name: String
    @Guide(description: "True only when this is one of the listed pantry items")
    var fromPantry: Bool
    @Guide(description: "When this is one of the listed pantry items, that item's name copied exactly as listed, even if the recipe is in another language; otherwise an empty string")
    var pantryItem: String
}

@available(iOS 26.0, *)
extension GeneratedRecipe {
    func recipe(for request: RecipeRequest) -> Recipe {
        let pantryNames = request.items.map(\.name)
        let tip = tip.trimmingCharacters(in: .whitespacesAndNewlines)
        return Recipe(
            title: title.trimmingCharacters(in: .whitespacesAndNewlines),
            summary: summary.trimmingCharacters(in: .whitespacesAndNewlines),
            minutes: minutes,
            servings: request.config.servings,
            difficulty: request.config.difficulty,
            ingredients: ingredients.map { ingredient in
                RecipeIngredient(
                    amount: ingredient.amount.trimmingCharacters(in: .whitespacesAndNewlines),
                    name: ingredient.name.trimmingCharacters(in: .whitespacesAndNewlines),
                    fromPantry: RecipeMatching.isFromPantry(
                        ingredient.name,
                        pantryItem: ingredient.pantryItem,
                        claimed: ingredient.fromPantry,
                        pantryNames: pantryNames
                    )
                )
            },
            steps: steps.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty },
            tip: tip.isEmpty ? nil : tip,
            source: .device
        )
    }
}

enum RecipeMatching {
    /// "In your pantry" must never mark something the person doesn't have. The ingredient's
    /// own name is in the recipe's language, which need not be the pantry's (a German recipe
    /// from English item names), so the model also copies the pantry item it used, verbatim;
    /// that copy is matched against the ticked items, and the name itself only as a fallback:
    /// close to a ticked item when the model claims it, nearly identical when it doesn't.
    static func isFromPantry(_ name: String, pantryItem: String, claimed: Bool, pantryNames: [String]) -> Bool {
        let copied = pantryItem.trimmingCharacters(in: .whitespacesAndNewlines)
        if !copied.isEmpty, pantryNames.contains(where: { NameSimilarity.score(copied, $0) >= 0.9 }) {
            return true
        }
        let best = pantryNames.map { NameSimilarity.score(name, $0) }.max() ?? 0
        return best >= 0.9 || (claimed && best >= 0.5)
    }
}
