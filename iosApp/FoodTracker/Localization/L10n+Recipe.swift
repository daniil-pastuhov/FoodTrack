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
    static func recipePromptDifficulty(_ difficulty: RecipeDifficulty) -> String {
        switch difficulty {
        case .easy: return text("recipe_prompt_difficulty_easy")
        case .medium: return text("recipe_prompt_difficulty_medium")
        case .ambitious: return text("recipe_prompt_difficulty_ambitious")
        }
    }
    static func recipePromptDetail(_ detail: RecipeDetail) -> String {
        switch detail {
        case .brief: return text("recipe_prompt_detail_brief")
        case .standard: return text("recipe_prompt_detail_standard")
        case .stepByStep: return text("recipe_prompt_detail_step_by_step")
        }
    }
    static func recipePromptServings(_ servings: Int) -> String {
        String(format: text("recipe_prompt_servings"), servings)
    }
    static func recipePromptMaxMinutes(_ minutes: Int) -> String {
        String(format: text("recipe_prompt_max_minutes"), minutes)
    }
    static func recipePromptMeal(_ meal: RecipeMealType) -> String {
        String(format: text("recipe_prompt_meal"), recipeMealName(meal))
    }
    static func recipePromptDiet(_ diet: RecipeDiet) -> String {
        String(format: text("recipe_prompt_diet"), recipeDietName(diet))
    }
    static func recipePromptAllergens(_ list: String) -> String {
        String(format: text("recipe_prompt_allergens"), list)
    }
    static func recipePromptLanguage(_ language: String) -> String {
        String(format: text("recipe_prompt_language"), language)
    }

    static var recipeNotFound: String { text("recipe_not_found") }
}

/// The paid route's strings. The unit is a "recipe" everywhere the person reads it.
extension L10n {
    private static func paid(_ key: String) -> String { NSLocalizedString(key, comment: "") }
    private static func plural(_ key: String, _ count: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString(key, comment: ""), count)
    }

    // Tab
    static var recipeModeRecipe: String { paid("recipe_mode_recipe") }
    static var recipeModeMenu: String { paid("recipe_mode_menu") }
    static var recipeCoursesLabel: String { paid("recipe_courses_label") }
    static func recipeCourses(_ count: Int) -> String { plural("recipe_courses_count", count) }
    static var recipeGenerateRecipe: String { paid("recipe_generate_recipe") }
    static var recipeGenerateMenu: String { paid("recipe_generate_menu") }
    /// "1 recipe", "5 recipes": what an action takes, and a pack's size.
    static func recipeCost(_ count: Int) -> String { plural("recipe_cost_count", count) }
    static func recipeBalanceLeft(_ count: Int) -> String { plural("recipe_balance_left", count) }
    static var recipeBuy: String { paid("recipe_buy") }
    /// `cost` is what a menu of the chosen number of courses takes.
    static func recipeMenuServerHint(_ cost: Int) -> String { plural("recipe_menu_server_hint", cost) }
    static func recipeMenuShortTitle(_ cost: Int) -> String { plural("recipe_menu_short_title", cost) }
    /// Under the title: what is left, and whether a single recipe still fits in it.
    static func recipeMenuShortMessage(_ balance: Int, recipeCost: Int) -> String {
        if balance <= 0 { return paid("recipe_menu_short_none") }
        if balance >= recipeCost { return String(format: paid("recipe_menu_short_message"), balance) }
        return recipeBalanceLeft(balance)
    }

    // Writing on the server
    static func recipeWritingServerTitle(_ kind: RecipeKind) -> String {
        paid(kind == .menu ? "recipe_writing_menu_title" : "recipe_writing_server_title")
    }
    static func recipeWritingServerMessage(_ kind: RecipeKind) -> String {
        paid(kind == .menu ? "recipe_writing_menu_message" : "recipe_writing_server_message")
    }
    static func recipeServerFailed(_ kind: RecipeKind) -> String {
        paid(kind == .menu ? "recipe_server_failed_menu" : "recipe_server_failed")
    }
    static func recipeServerRefused(_ kind: RecipeKind, taken: Int) -> String {
        plural(kind == .menu ? "recipe_server_refused_menu" : "recipe_server_refused", taken)
    }
    /// A refusal this version can't name: try later, and whether anything was taken.
    static func recipeServerUnrecognised(_ kind: RecipeKind, taken: Int) -> String {
        guard taken > 0 else { return paid(kind == .menu ? "recipe_server_unknown_menu" : "recipe_server_unknown") }
        return plural(kind == .menu ? "recipe_server_unknown_taken_menu" : "recipe_server_unknown_taken", taken)
    }
    static var recipeServerBusy: String { paid("recipe_server_busy") }
    static var recipeServerRateLimited: String { paid("recipe_server_rate_limited") }
    static var recipeServerDailyCap: String { paid("recipe_server_daily_cap") }
    static var recipeServerUserDailyLimit: String { paid("recipe_server_user_daily_limit") }
    static var recipeServerInfraFailureLimit: String { paid("recipe_server_infra_failure_limit") }
    static func recipeArrived(balance: Int) -> String { plural("recipe_arrived", balance) }

    // Lasting conditions
    static var recipeOfflineTitle: String { paid("recipe_offline_title") }
    static var recipeOfflineMessage: String { paid("recipe_offline_message") }
    static var recipePausedTitle: String { paid("recipe_paused_title") }
    static func recipePausedMessage(_ balance: Int?) -> String {
        guard let balance, balance > 0 else { return paid("recipe_paused_message_none") }
        return plural("recipe_paused_message", balance)
    }
    static var recipeUnconfirmedTitle: String { paid("recipe_unconfirmed_title") }
    static func recipeUnconfirmedMessage(_ credits: Int) -> String { plural("recipe_unconfirmed_message", credits) }
    static var recipeTryNow: String { paid("recipe_try_now") }
    static var recipePaymentPendingTitle: String { paid("recipe_payment_pending_title") }
    static var recipePaymentPendingMessage: String { paid("recipe_payment_pending_message") }

    // Buying
    /// What a menu takes with two and with three courses a day, from the server's costs.
    static func recipeBuyMessage(_ costs: RecipeCosts) -> String {
        String(format: paid("recipe_buy_message"), costs.menu2, costs.menu3)
    }
    static func recipePackEach(_ price: String) -> String { String(format: paid("recipe_pack_each"), price) }
    static var recipeContinue: String { paid("recipe_continue") }
    static var recipeClose: String { paid("recipe_close") }
    static var recipeOK: String { paid("recipe_ok") }
    static var recipeAdding: String { paid("recipe_adding") }
    static func recipeAdded(_ count: Int) -> String { plural("recipe_added", count) }
    static var recipeStoreErrorTitle: String { paid("recipe_store_error_title") }
    static var recipeStoreErrorMessage: String { paid("recipe_store_error_message") }
    static var recipeChoiceTitle: String { paid("recipe_choice_title") }
    static var recipeChoiceMessage: String { paid("recipe_choice_message") }
    static var recipeChoicePhoneTitle: String { paid("recipe_choice_phone_title") }
    static var recipeChoicePhoneMessage: String { paid("recipe_choice_phone_message") }
    static var recipeChoiceAccountTitle: String { paid("recipe_choice_account_title") }
    /// Without Google sign-in configured, the line names Apple only.
    static func recipeChoiceAccountMessage(googleAvailable: Bool) -> String {
        paid(googleAvailable ? "recipe_choice_account_message" : "recipe_choice_account_message_apple")
    }
    static var recipeRejectedTitle: String { paid("recipe_rejected_title") }
    static var recipeRejectedMessage: String { paid("recipe_rejected_message") }

    // Account
    static var recipeYourRecipes: String { paid("recipe_your_recipes") }
    static func recipeOnPhoneLabel(_ count: Int) -> String { plural("recipe_on_phone_label", count) }
    static func recipeInAccountLabel(_ count: Int) -> String { plural("recipe_in_account_label", count) }
    static var recipeOnPhoneMessage: String { paid("recipe_on_phone_message") }
    static var recipeHaveAccountHint: String { paid("recipe_have_account_hint") }
    static func recipeSignedIn(with provider: RecipeSignInProvider) -> String {
        paid(provider == .apple ? "recipe_signed_in_apple" : "recipe_signed_in_google")
    }
    static func recipeSignedInHint(missing: RecipeSignInProvider?) -> String {
        switch missing {
        case .google?: return paid("recipe_signed_in_hint_add_google")
        case .apple?: return paid("recipe_signed_in_hint_add_apple")
        case nil: return paid("recipe_signed_in_hint")
        }
    }
    static func recipeAddSignIn(_ provider: RecipeSignInProvider) -> String {
        paid(provider == .apple ? "recipe_add_apple" : "recipe_add_google")
    }
    static var recipeSignOut: String { paid("recipe_sign_out") }
    static var recipeDeleteAccount: String { paid("recipe_delete_account") }
    static var recipeSignOutTitle: String { paid("recipe_sign_out_title") }
    static func recipeSignOutMessage(_ balance: Int) -> String { plural("recipe_sign_out_message", balance) }
    static var recipeDeleteTitle: String { paid("recipe_delete_title") }
    static func recipeDeleteMessage(_ balance: Int) -> String { plural("recipe_delete_message", balance) }
    static var recipeMergeTitle: String { paid("recipe_merge_title") }
    static func recipeMergeMessage(account: Int, install: Int) -> String {
        String(format: paid("recipe_merge_message"), account, install, account + install)
    }
    static var recipeMergeConfirm: String { paid("recipe_merge_confirm") }
    static var recipeSignInFailed: String { paid("recipe_sign_in_failed") }
    static var recipeProviderInUse: String { paid("recipe_provider_in_use") }
    static var recipeAccountDeleted: String { paid("recipe_account_deleted") }
    static var recipeAccountActionFailed: String { paid("recipe_account_action_failed") }
    static var recipeAccountBusy: String { paid("recipe_account_busy") }
    static var recipePurchasesBlocked: String { paid("recipe_purchases_blocked") }
    static var recipeConsumptionNote: String { paid("recipe_consumption_note") }

    // Menu
    static var recipeMenuResultTitle: String { paid("recipe_menu_result_title") }
    static var recipeMenuHeading: String { paid("recipe_menu_heading") }
    static func recipeMenuSummary(courses: Int, servings: Int) -> String {
        String(format: paid(courses >= 3 ? "recipe_menu_summary_3" : "recipe_menu_summary_2"), servings)
    }
    static func recipeMenuToday(_ date: String) -> String { String(format: paid("recipe_menu_today"), date) }
    static func recipeMenuUses(_ items: String) -> String { String(format: paid("recipe_menu_uses"), items) }
    static func recipeCourseName(_ course: RecipeCourse) -> String {
        switch course {
        case .starter: return paid("recipe_course_starter")
        case .main: return paid("recipe_course_main")
        case .dessert: return paid("recipe_course_dessert")
        }
    }
    static var recipePartOfMenu: String { paid("recipe_part_of_menu") }
    static var recipeShareMenu: String { paid("recipe_share_menu") }
    static func recipeDishes(_ count: Int) -> String { plural("recipe_dishes_count", count) }
}
