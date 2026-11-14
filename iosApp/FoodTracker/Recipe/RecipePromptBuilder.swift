import Foundation

/// The words each free route sends. Pure functions of a `RecipeRequest`, so the configuration
/// and the allergen list shape both routes the same way. Ported by hand, as the rest of iOS is,
/// from Android's `RecipePromptBuilder.kt`.
enum RecipePromptBuilder {
    /// The prompt shared to another AI app, in the app's language: the ticked items soonest
    /// first, then the preferences. It names a recipe language only when the person chose one,
    /// since the prompt itself is already in the app's.
    static func sharePrompt(for request: RecipeRequest) -> String {
        let items = request.items
            .map { "- " + L10n.recipePromptItem(name: $0.name, expiry: $0.expiryText) }
            .joined(separator: "\n")
        let preferences = sharePreferences(request)
            .map { "- " + $0 }
            .joined(separator: "\n")
        // The cook chose to use these despite their date: said so, so the other AI doesn't drop them.
        let past = request.items.filter { $0.daysRemaining < 0 }.map(\.name)
        let pastLine = past.isEmpty ? "" : "\n\n" + L10n.recipePromptPastDate(past.joined(separator: ", "))
        return L10n.recipePromptIntro + "\n\n" + items + pastLine + otherItemsSection(request.otherItems)
            + "\n\n" + L10n.recipePromptPreferencesIntro + "\n" + preferences
    }

    /// Cap on the "other items" section, in characters, as `MAX_OTHER_ITEMS_CHARS` on Android:
    /// share sheets degrade on very large strings.
    static let maxOtherItemsChars = 20_000

    /// "Send all products": the rest of the pantry after the main list, soonest first, each with
    /// how long it has left, as items the recipe may use as well; cut at `maxOtherItemsChars`
    /// with a note of how many were left out. Empty without any. Android: `buildRecipePrompt`'s
    /// `otherItems`.
    static func otherItemsSection(_ others: [RecipeIngredientInput]) -> String {
        guard !others.isEmpty else { return "" }
        var section = ""
        var included = 0
        for item in others {
            let line = "- " + L10n.recipePromptItem(name: item.name, expiry: item.expiryText)
            let lengthIfAdded = section.count + (section.isEmpty ? 0 : 1) + line.count
            if lengthIfAdded > maxOtherItemsChars { break }
            if !section.isEmpty { section += "\n" }
            section += line
            included += 1
        }
        guard included > 0 else { return "" }
        var text = "\n\n" + L10n.recipePromptOtherItemsIntro + "\n" + section
        if others.count > included { text += "\n" + L10n.recipePromptOtherItemsOmitted(others.count - included) }
        return text
    }

    /// One line per preference, in the order the form shows them. "Any" and "None" are left out:
    /// they ask for nothing.
    static func sharePreferences(_ request: RecipeRequest) -> [String] {
        let config = request.config
        var lines = [
            L10n.recipePromptDifficulty(config.difficulty),
            L10n.recipePromptDetail(config.detail),
            L10n.recipePromptServings(config.servings),
        ]
        if let minutes = config.maxMinutes {
            lines.append(L10n.recipePromptMaxMinutes(minutes))
        }
        if config.mealType != .any {
            lines.append(L10n.recipePromptMeal(config.mealType))
        }
        if config.diet != .none {
            lines.append(L10n.recipePromptDiet(config.diet))
        }
        if !request.allergens.isEmpty {
            lines.append(L10n.recipePromptAllergens(request.allergens.joined(separator: ", ")))
        }
        if let language = config.language {
            lines.append(L10n.recipePromptLanguage(language.endonym))
        }
        return lines
    }

    // MARK: On-device model

    /// Instructions for the phone's own model. Written in English, which the model follows most
    /// reliably, and told which language to answer in.
    static func onDeviceInstructions(for request: RecipeRequest) -> String {
        """
        You help someone cook with food they already have before it goes off. Write exactly one \
        recipe. Build it around the pantry items listed, using the ones that expire soonest first; \
        it does not have to use all of them, and it may add common staples. Follow every preference \
        exactly. Never include an ingredient that is or contains a listed allergen. Mark an \
        ingredient as from the pantry only when it is one of the listed pantry items, and then \
        copy that item's name exactly as listed into pantryItem, untranslated. Write every other \
        text field in \(request.config.resolvedLanguage.englishName).\(request.otherItems.isEmpty ? "" : " " + onDeviceOtherItemsRule)\
        \(request.items.contains { $0.daysRemaining < 0 } ? " " + onDevicePastDateRule : "")
        """
    }

    /// The owner's rule: an item past its date is the cook's choice and is used, never dropped
    /// for its age; the app warns about it itself. As Android's on-device prompt.
    static let onDevicePastDateRule = "Items past their date are ones the cook has checked and chosen to use: do not leave them out for their date."

    /// With "Send all products": what the second list is for.
    static let onDeviceOtherItemsRule = """
        Other items the person also has at home may be listed after the pantry items; use one \
        only where it helps the dish, none is needed, and they count as pantry items too.
        """

    /// Items the phone's model sees at most, ticked first, as Android's `MAX_RECIPE_INGREDIENTS`:
    /// its context is small.
    static let maxOnDeviceItems = 30

    static func onDevicePrompt(for request: RecipeRequest) -> String {
        let config = request.config
        var lines = ["Pantry items, soonest first:"]
        let main = request.items.prefix(maxOnDeviceItems)
        lines += main.map { "- \($0.name) (\(englishExpiry($0.daysRemaining)))" }
        let extra = request.otherItems.prefix(max(0, maxOnDeviceItems - main.count))
        if !extra.isEmpty {
            lines.append("")
            lines.append("Also at home, use only where it helps, none is needed:")
            lines += extra.map { "- \($0.name) (\(englishExpiry($0.daysRemaining)))" }
        }
        lines.append("")
        lines.append("Preferences:")
        lines.append("- Difficulty: \(englishDifficulty(config.difficulty))")
        lines.append("- Method: \(englishDetail(config.detail))")
        lines.append("- Servings: \(config.servings)")
        if let minutes = config.maxMinutes {
            lines.append("- Total time: at most \(minutes) minutes")
        }
        if config.mealType != .any {
            lines.append("- Meal: \(englishMeal(config.mealType))")
        }
        if config.diet != .none {
            lines.append("- Diet: \(englishDiet(config.diet))")
        }
        if !request.allergens.isEmpty {
            lines.append("- Must not contain: \(request.allergens.joined(separator: ", "))")
        }
        return lines.joined(separator: "\n")
    }

    static func englishExpiry(_ days: Int) -> String {
        switch days {
        case ..<(-1): return "past its date by \(-days) days"
        case -1: return "past its date by 1 day"
        case 0: return "expires today"
        case 1: return "expires tomorrow"
        default: return "expires in \(days) days"
        }
    }

    private static func englishDifficulty(_ difficulty: RecipeDifficulty) -> String {
        switch difficulty {
        case .easy: return "easy, for a beginner"
        case .medium: return "medium"
        case .ambitious: return "ambitious, for a confident cook"
        }
    }

    private static func englishDetail(_ detail: RecipeDetail) -> String {
        switch detail {
        case .brief: return "brief, at most 5 short steps"
        case .standard: return "standard detail"
        case .stepByStep: return "step by step, explaining each step fully"
        }
    }

    private static func englishMeal(_ meal: RecipeMealType) -> String {
        switch meal {
        case .any: return "any"
        case .breakfast: return "breakfast"
        case .lunch: return "lunch"
        case .dinner: return "dinner"
        case .snack: return "a snack"
        case .dessert: return "a dessert"
        }
    }

    private static func englishDiet(_ diet: RecipeDiet) -> String {
        switch diet {
        case .none: return "none"
        case .vegetarian: return "vegetarian"
        case .vegan: return "vegan"
        case .pescatarian: return "pescatarian"
        case .glutenFree: return "gluten-free"
        case .dairyFree: return "dairy-free"
        }
    }

    // MARK: Sharing a written recipe

    /// A recipe as plain text, for the result screen's Share.
    static func plainText(_ recipe: Recipe) -> String {
        var parts = [recipe.title]
        if let warning = L10n.recipePastDateWarning(recipe.pastDate) { parts.append("⚠ " + warning) }
        if !recipe.summary.isEmpty { parts.append(recipe.summary) }
        let ingredients = recipe.ingredients.map { ingredient in
            "- " + (ingredient.amount.isEmpty ? ingredient.name : "\(ingredient.amount) \(ingredient.name)")
        }
        parts.append(L10n.recipeIngredientsTitle + "\n" + ingredients.joined(separator: "\n"))
        let steps = recipe.steps.enumerated().map { "\($0.offset + 1). \($0.element)" }
        parts.append(L10n.recipeMethodTitle + "\n" + steps.joined(separator: "\n"))
        if let tip = recipe.tip, !tip.isEmpty {
            parts.append(L10n.recipeTipTitle + ": " + tip)
        }
        return parts.joined(separator: "\n\n")
    }

    /// A menu as plain text: each day's heading, then each course and its full recipe.
    static func plainText(_ menu: RecipeMenu) -> String {
        var parts = [L10n.recipeMenuHeading]
        if let warning = L10n.recipePastDateWarning(menu.pastDate) { parts.append("⚠ " + warning) }
        for day in menu.days {
            let heading = RecipeMenuView.dayTitle(day.date)
            let courses = day.courses.map { course in
                L10n.recipeCourseName(course.course).uppercased() + "\n" + plainText(course.recipe)
            }
            parts.append(([heading.uppercased()] + courses).joined(separator: "\n\n"))
        }
        return parts.joined(separator: "\n\n\n")
    }
}
