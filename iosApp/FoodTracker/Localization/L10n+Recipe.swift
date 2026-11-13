import Foundation

/// The Recipe tab's strings, in the "Recipe tab" block of each Localizable.strings. A file of its
/// own because only the app target has the tab; the widget shares `L10n.swift`.
extension L10n {
    private static func text(_ key: String) -> String { NSLocalizedString(key, comment: "") }

    static var recipeTab: String { text("recipe_tab") }
    static var recipeLede: String { text("recipe_lede") }
    static var recipeSavedButton: String { text("recipe_saved_button") }
    static var recipeSettingsButton: String { text("recipe_settings_button") }
    static var recipeCookWith: String { text("recipe_cook_with") }
    static var recipeAddOtherItems: String { text("recipe_add_other_items") }
    static func recipeMoreInPantry(_ count: Int) -> String {
        String.localizedStringWithFormat(text("recipe_more_in_pantry"), count)
    }

    // Form
    static var recipeLabelDifficulty: String { text("recipe_label_difficulty") }
    static var recipeLabelDetail: String { text("recipe_label_detail") }
    static var recipeLabelServings: String { text("recipe_label_servings") }
    static var recipeLabelTime: String { text("recipe_label_time") }
    static var recipeLabelMeal: String { text("recipe_label_meal") }
    static var recipeLabelDiet: String { text("recipe_label_diet") }
    static var recipeMoreOptions: String { text("recipe_more_options") }
    static var recipeTimeAny: String { text("recipe_time_any") }

    static func recipeDifficultyName(_ difficulty: RecipeDifficulty) -> String {
        switch difficulty {
        case .easy: return text("recipe_difficulty_easy")
        case .medium: return text("recipe_difficulty_medium")
        case .ambitious: return text("recipe_difficulty_ambitious")
        }
    }

    static func recipeDetailName(_ detail: RecipeDetail) -> String {
        switch detail {
        case .brief: return text("recipe_detail_brief")
        case .standard: return text("recipe_detail_standard")
        case .stepByStep: return text("recipe_detail_step_by_step")
        }
    }

    static func recipeMealName(_ meal: RecipeMealType) -> String {
        switch meal {
        case .any: return text("recipe_meal_any")
        case .breakfast: return text("recipe_meal_breakfast")
        case .lunch: return text("recipe_meal_lunch")
        case .dinner: return text("recipe_meal_dinner")
        case .snack: return text("recipe_meal_snack")
        case .dessert: return text("recipe_meal_dessert")
        }
    }

    static func recipeDietName(_ diet: RecipeDiet) -> String {
        switch diet {
        case .none: return text("recipe_diet_none")
        case .vegetarian: return text("recipe_diet_vegetarian")
        case .vegan: return text("recipe_diet_vegan")
        case .pescatarian: return text("recipe_diet_pescatarian")
        case .glutenFree: return text("recipe_diet_gluten_free")
        case .dairyFree: return text("recipe_diet_dairy_free")
        }
    }

    /// A time chip: "15 min", "1 hour", "Any".
    static func recipeTimeOption(_ minutes: Int?) -> String {
        guard let minutes else { return recipeTimeAny }
        return minutes == 60 ? text("recipe_time_one_hour") : recipeMinutes(minutes)
    }

    /// A cooking time: "15 min", "1 h 10 min".
    static func recipeMinutes(_ minutes: Int) -> String {
        let hours = minutes / 60
        let rest = minutes % 60
        if hours == 0 { return String(format: text("recipe_time_minutes"), minutes) }
        if rest == 0 { return String(format: text("recipe_time_hours"), hours) }
        return String(format: text("recipe_time_hours_minutes"), hours, rest)
    }

    static func recipeServings(_ count: Int) -> String {
        String.localizedStringWithFormat(text("recipe_servings_count"), count)
    }

    /// Under More options: "English · Standard detail · Any meal · No diet".
    static func recipeOptionsSummary(_ config: RecipeConfig) -> String {
        let detail: String
        switch config.detail {
        case .brief: detail = text("recipe_detail_summary_brief")
        case .standard: detail = text("recipe_detail_summary_standard")
        case .stepByStep: detail = text("recipe_detail_summary_step_by_step")
        }
        return [
            config.resolvedLanguage.endonym,
            detail,
            config.mealType == .any ? text("recipe_meal_summary_any") : recipeMealName(config.mealType),
            config.diet == .none ? text("recipe_diet_summary_none") : recipeDietName(config.diet),
        ].joined(separator: " · ")
    }

    // Actions
    static var recipeGenerateOnPhone: String { text("recipe_generate_on_phone") }
    static var recipeCostFree: String { text("recipe_cost_free") }
    static var recipeSharePrompt: String { text("recipe_share_prompt") }
    static var recipeCopyPrompt: String { text("recipe_copy_prompt") }
    static var recipePromptOnlyHint: String { text("recipe_prompt_only_hint") }
    static var recipeNoneSelectedHint: String { text("recipe_none_selected_hint") }
    static var recipePromptCopied: String { text("recipe_prompt_copied") }

    // States
    static var recipeWritingOnPhoneTitle: String { text("recipe_writing_on_phone_title") }
    static var recipeWritingOnPhoneMessage: String { text("recipe_writing_on_phone_message") }
    static var recipeStop: String { text("recipe_stop") }
    static var recipePreparingTitle: String { text("recipe_preparing_title") }
    static var recipePreparingMessage: String { text("recipe_preparing_message") }
    static var recipeFailedOnDevice: String { text("recipe_failed_on_device") }
    static var recipeRefusedOnDevice: String { text("recipe_refused_on_device") }
    static var recipeEmptyTitle: String { text("recipe_empty_title") }
    static var recipeEmptyMessage: String { text("recipe_empty_message") }

    // Result
    static var recipeResultTitle: String { text("recipe_result_title") }
    static var recipeIngredientsTitle: String { text("recipe_ingredients_title") }
    static var recipeMethodTitle: String { text("recipe_method_title") }
    static var recipeTipTitle: String { text("recipe_tip_title") }
    static var recipeInYourPantry: String { text("recipe_in_your_pantry") }
    static var recipeShareRecipe: String { text("recipe_share_recipe") }
    static func recipeSource(_ source: RecipeSource) -> String {
        switch source {
        case .device: return text("recipe_source_device")
        case .server: return text("recipe_source_server")
        }
    }

    // Saved recipes
    static var recipeSavedEmptyTitle: String { text("recipe_saved_empty_title") }
    static var recipeSavedEmptyMessage: String { text("recipe_saved_empty_message") }
    static var recipeSavedDeleted: String { text("recipe_saved_deleted") }
    static var recipeSavedToday: String { text("recipe_saved_today") }
    static var recipeSavedYesterday: String { text("recipe_saved_yesterday") }
    static func recipeSavedOrigin(_ source: RecipeSource) -> String {
        switch source {
        case .device: return text("recipe_saved_from_phone")
        case .server: return text("recipe_saved_from_pack")
        }
    }

    // Recipe settings
    static var recipeSettingsCooking: String { text("recipe_settings_cooking") }
    static var recipeSettingsCookingDesc: String { text("recipe_settings_cooking_desc") }
    static var recipeSettingsMealDiet: String { text("recipe_settings_meal_diet") }
    static var recipeSettingsLanguage: String { text("recipe_settings_language") }
    static var recipeWriteIn: String { text("recipe_write_in") }
    static var recipeSettingsAccount: String { text("recipe_settings_account") }
    static var recipeSettingsNoAccount: String { text("recipe_settings_no_account") }
    static func recipeAllergensNote(_ allergens: [String]) -> String {
        String(format: text("recipe_allergens_note"), allergens.isEmpty ? text("recipe_allergens_none") : allergens.joined(separator: ", "))
    }
    static func recipeLanguageName(_ language: RecipeLanguage?) -> String {
        guard let language else {
            return String(format: text("recipe_language_same_as_app"), RecipeLanguage.app.endonym)
        }
        return language.endonym
    }

    // Shared prompt
    static var recipePromptPreferencesIntro: String { text("recipe_prompt_preferences_intro") }
    static func recipePromptPreference(_ label: String, _ value: String) -> String {
        String(format: text("recipe_prompt_preference_format"), label, value)
    }
    static func recipePromptTimeLimit(_ minutes: Int) -> String {
        String(format: text("recipe_prompt_time_limit"), minutes)
    }
    static func recipePromptAllergens(_ list: String) -> String {
        String(format: text("recipe_prompt_allergens"), list)
    }
    static func recipePromptLanguage(_ language: String) -> String {
        String(format: text("recipe_prompt_language"), language)
    }
}
