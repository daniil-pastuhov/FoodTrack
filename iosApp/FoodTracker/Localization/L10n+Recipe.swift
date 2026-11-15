import Foundation
import UIKit

/// The Recipe tab's strings, in the "Recipe tab" block of each Localizable.strings. A file of its
/// own because only the app target has the tab; the widget shares `L10n.swift`.
extension L10n {
    private static func text(_ key: String) -> String { withDevice(NSLocalizedString(key, comment: "")) }

    /// Names this device where a recipe string says where something happens or stays ("Generate
    /// on this iPad"): {device} plainly, {the_device} in its definite form, which only Norwegian
    /// inflects ("denne iPaden"). One sentence per string rather than one per device.
    static func withDevice(_ text: String) -> String {
        guard text.contains("device}") else { return text }
        let pad = UIDevice.current.userInterfaceIdiom == .pad
        return text
            .replacingOccurrences(of: "{the_device}", with: NSLocalizedString(pad ? "recipe_the_device_ipad" : "recipe_the_device_iphone", comment: ""))
            .replacingOccurrences(of: "{device}", with: NSLocalizedString(pad ? "recipe_device_ipad" : "recipe_device_iphone", comment: ""))
    }

    /// Text from the person or the model (an item name, an amount) set inside one of our lines,
    /// wrapped in a first-strong isolate (U+2068 ... U+2069): a Hebrew or Arabic name keeps its
    /// own direction without reordering the sentence or the amount around it. SwiftUI's `Text`
    /// has no BidiFormatter, so this is the standard Unicode isolate, the same one Android's
    /// recipe screens use (`isolated` in RecipeLabels.kt).
    static func isolated(_ embedded: String) -> String { "\u{2068}\(embedded)\u{2069}" }
    /// Names joined with ", ", each isolated.
    static func isolatedList(_ names: [String]) -> String { names.map(isolated).joined(separator: ", ") }

    static var recipeTab: String { text("recipe_tab") }
    static var recipeLede: String { text("recipe_lede") }
    static var recipeSavedButton: String { text("recipe_saved_button") }
    /// The header's Saved recipes button with `count` not seen yet: "Saved recipes, 2 new".
    static func recipeSavedButtonUnread(_ count: Int) -> String {
        count > 0 ? String.localizedStringWithFormat(text("recipe_saved_button_unread"), count) : recipeSavedButton
    }
    /// A Saved recipes row not opened yet, for VoiceOver beside its dot.
    static var recipeSavedNew: String { text("recipe_saved_new") }
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

    /// The time range: "Any", "up to 30 min", "at least 40 min", "30 min", "20–45 min". Android:
    /// `recipeTimeRangeLabel`.
    static func recipeTimeRange(min low: Int?, max high: Int?) -> String {
        switch (low, high) {
        case (nil, nil): return recipeTimeAny
        case (nil, let high?): return String(format: text("recipe_time_up_to"), high)
        case (let low?, nil): return String(format: text("recipe_time_at_least"), low)
        case (let low?, let high?):
            return low == high ? String(format: text("recipe_time_minutes"), low) : String(format: text("recipe_time_range"), low, high)
        }
    }
    static var recipeTimeShortest: String { text("recipe_time_shortest") }
    static var recipeTimeLongest: String { text("recipe_time_longest") }
    static var recipeTimeGuideHint: String { text("recipe_time_guide_hint") }
    /// A handle's value for VoiceOver: "20 minutes", or "No minimum" / "No limit" at its end.
    static func recipeTimeSpoken(_ minutes: Int?, lowEnd: Bool) -> String {
        guard let minutes else { return text(lowEnd ? "recipe_time_no_minimum" : "recipe_time_no_limit") }
        return String.localizedStringWithFormat(NSLocalizedString("recipe_time_minutes_spoken", comment: ""), minutes)
    }
    /// Under a menu's form: the meal applies to its main courses only.
    static func recipeMenuMealNote(_ meals: [RecipeMealType]) -> String {
        String(format: text(meals.count == 1 ? "recipe_menu_meal_note_one" : "recipe_menu_meal_note_many"), meals.map(recipeMealName).joined(separator: ", "))
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
            config.mealTypes.isEmpty
                ? text("recipe_meal_summary_any")
                : RecipeChoices.orderedMeals(config.mealTypes).map(recipeMealName).joined(separator: ", "),
            config.diets.isEmpty
                ? text("recipe_diet_summary_none")
                : RecipeChoices.orderedDiets(config.diets).map(recipeDietName).joined(separator: ", "),
        ].joined(separator: " · ")
    }

    // Actions
    static var recipeGenerateOnPhone: String { text("recipe_generate_on_phone") }
    static var recipeCostFree: String { text("recipe_cost_free") }
    static var recipeSharePrompt: String { text("recipe_share_prompt") }
    static var recipePromptOnlyHint: String { text("recipe_prompt_only_hint") }
    static var recipeNoneSelectedHint: String { text("recipe_none_selected_hint") }

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
    static var recipeOnDeviceLanguageNote: String { text("recipe_on_device_language_note") }
    static var recipeSettingsAccount: String { text("recipe_settings_account") }
    static var recipeSettingsNoAccount: String { text("recipe_settings_no_account") }
    static func recipeAllergensNote(_ allergens: [String]) -> String {
        String(format: text("recipe_allergens_note"), allergens.isEmpty ? text("recipe_allergens_none") : isolatedList(allergens))
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
    static func recipePromptMinMinutes(_ minutes: Int) -> String {
        String(format: text("recipe_prompt_min_minutes"), minutes)
    }
    /// "Meal: Dinner", or for several "Suitable for any of these meals: Breakfast, Lunch".
    static func recipePromptMeals(_ meals: [RecipeMealType]) -> String {
        let names = meals.map(recipeMealName).joined(separator: ", ")
        return String(format: text(meals.count == 1 ? "recipe_prompt_meal" : "recipe_prompt_meals"), names)
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
    private static func paid(_ key: String) -> String { withDevice(NSLocalizedString(key, comment: "")) }
    private static func plural(_ key: String, _ count: Int, _ inserts: [String: String] = [:]) -> String {
        var text = withDevice(String.localizedStringWithFormat(NSLocalizedString(key, comment: ""), count))
        for (name, value) in inserts { text = text.replacingOccurrences(of: "{\(name)}", with: value) }
        return text
    }

    // Tab
    static var recipeModeRecipe: String { paid("recipe_mode_recipe") }
    static var recipeModeMenu: String { paid("recipe_mode_menu") }
    static var recipeCoursesLabel: String { paid("recipe_courses_label") }
    static func recipeCourses(_ count: Int) -> String { plural("recipe_courses_count", count) }
    static var recipeDaysLabel: String { paid("recipe_days_label") }
    /// "1 day", "4 days": the Days choice.
    static func recipeDays(_ count: Int) -> String { plural("recipe_days_count", count) }
    static var recipeGenerateRecipe: String { paid("recipe_generate_recipe") }
    static var recipeGenerateMenu: String { paid("recipe_generate_menu") }
    /// "1 recipe", "5 recipes": what an action takes, and a pack's size.
    static func recipeCost(_ count: Int) -> String { plural("recipe_cost_count", count) }
    static func recipeBalanceLeft(_ count: Int) -> String { plural("recipe_balance_left", count) }
    static var recipeBuy: String { paid("recipe_buy") }
    /// `cost` is what a menu of the chosen number of courses takes.
    static func recipeMenuServerHint(_ cost: Int) -> String {
        String(format: paid("recipe_menu_server_hint"), recipeCost(cost))
    }
    static func recipeMenuShortTitle(_ cost: Int) -> String {
        String(format: paid("recipe_menu_short_title"), recipeCost(cost))
    }
    /// Under the title: what is left, and whether a single recipe still fits in it.
    static func recipeMenuShortMessage(_ balance: Int, recipeCost: Int) -> String {
        if balance <= 0 { return paid("recipe_menu_short_none") }
        if balance >= recipeCost { return plural("recipe_menu_short_message", balance) }
        return recipeBalanceLeft(balance)
    }

    // Writing on the server
    /// `days`: the menu's day count ("Planning 2 days").
    static func recipeWritingServerTitle(_ kind: RecipeKind, days: Int = MenuSet.defaultDays) -> String {
        kind == .menu ? plural("recipe_writing_menu_title", days) : paid("recipe_writing_server_title")
    }
    static func recipeWritingServerMessage(_ kind: RecipeKind) -> String {
        paid(kind == .menu ? "recipe_writing_menu_message" : "recipe_writing_server_message")
    }
    static func recipeServerFailed(_ kind: RecipeKind) -> String {
        paid(kind == .menu ? "recipe_server_failed_menu" : "recipe_server_failed")
    }
    /// NOT_ENOUGH_ITEMS: tick a few more (for a menu: or ask for one recipe); nothing taken.
    static func recipeNotEnoughItems(_ kind: RecipeKind, days: Int = MenuSet.defaultDays) -> String {
        kind == .menu ? plural("recipe_not_enough_items_menu", days) : paid("recipe_not_enough_items")
    }
    static var recipeDietsNeedUpdate: String { paid("recipe_diets_need_update") }
    static var recipeLanguageNeedsUpdate: String { paid("recipe_language_needs_update") }
    static var recipeNotFromPantryHint: String { paid("recipe_not_from_pantry_hint") }
    static var recipeShoppingAdd: String { paid("recipe_shopping_add") }
    static func recipeShoppingTitle(_ count: Int) -> String { String(format: paid("recipe_shopping_title"), count) }
    static var recipeShoppingMessage: String { paid("recipe_shopping_message") }
    static var recipeShoppingAlready: String { paid("recipe_shopping_already") }
    static func recipeShoppingConfirm(_ count: Int) -> String { plural("recipe_shopping_confirm", count) }
    static func recipeShoppingAdded(_ count: Int) -> String {
        count > 0 ? plural("recipe_shopping_added", count) : paid("recipe_shopping_nothing_added")
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
    /// A request from before the form changed arrived after all, and was saved.
    static func recipeEarlierArrived(_ kind: RecipeKind) -> String {
        paid(kind == .menu ? "recipe_earlier_arrived_menu" : "recipe_earlier_arrived")
    }
    static func recipeItemLeftOut(_ name: String) -> String { String(format: paid("recipe_item_left_out"), isolated(name)) }
    static func recipeItemNotSent(_ name: String) -> String { String(format: paid("recipe_item_not_sent"), isolated(name)) }
    static func recipeAllergenRefused(_ name: String) -> String { String(format: paid("recipe_allergen_refused"), isolated(name)) }
    /// More allergens set than one request takes (`limit`, the server's 20).
    static func recipeAllergensTooMany(_ limit: Int) -> String { plural("recipe_allergens_too_many", limit) }
    /// Every server instance busy: sent again in a moment.
    static var recipeServerBusyRetrying: String { paid("recipe_server_busy_retrying") }
    static var recipeServerBusyRetryingTitle: String { paid("recipe_server_busy_retrying_title") }
    /// A request from before the form changed failed after all, and that failure was counted.
    static func recipeEarlierRefused(taken: Int) -> String { plural("recipe_earlier_refused", taken) }

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
    /// What a menu set takes, from the server's costs: the range over its menu-set table when it
    /// sent one, otherwise what a 3-day menu takes with two and with three courses a day. As
    /// Android's `recipe_packs_body_sets` / `recipe_packs_body`.
    /// The table counts only while the server says it takes menu sets (`menuSets`), as Android
    /// leaves it out of the costs it shows until then.
    static func recipeBuyMessage(_ costs: RecipeCosts, menuSets: Bool) -> String {
        if menuSets, let range = costs.menuSetRange {
            return plural("recipe_packs_body_sets", range.upperBound, ["min": "\(range.lowerBound)"])
        }
        return String(format: paid("recipe_buy_message"), recipeCost(costs.menu2), recipeCost(costs.menu3))
    }
    static func recipePackEach(_ price: String) -> String { String(format: paid("recipe_pack_each"), price) }
    /// The pack row's spoken saving; the badge itself (`PackSavings.badgeText`) isn't localized.
    static func recipePackSavingA11y(_ percent: Int) -> String { String(format: paid("recipe_pack_saving_a11y"), percent) }
    static var recipeContinue: String { paid("recipe_continue") }
    static var recipeClose: String { paid("recipe_close") }
    static var recipeOK: String { paid("recipe_ok") }
    static var recipeAdding: String { paid("recipe_adding") }
    static func recipeAdded(_ count: Int) -> String { plural("recipe_added", count) }
    static var recipeStoreErrorTitle: String { paid("recipe_store_error_title") }
    static var recipeStoreErrorMessage: String { paid("recipe_store_error_message") }
    static var recipeNotAvailableTitle: String { paid("recipe_not_available_title") }
    /// `debugCause`: Debug builds add why (the App Check debug token isn't registered).
    static func recipeNotAvailableMessage(debugCause: Bool) -> String {
        let message = paid("recipe_not_available_message")
        return debugCause ? message + "\n\n" + paid("recipe_not_available_debug") : message
    }

    // Send all products
    static var recipeIngredientsSettings: String { paid("recipe_ingredients_settings") }
    static var recipeSendAll: String { paid("recipe_send_all") }
    static var recipeSendAllMessage: String { paid("recipe_send_all_message") }
    static func recipeOptionalCount(_ count: Int) -> String { plural("recipe_optional_count", count) }
    static var recipePromptOtherItemsIntro: String { paid("recipe_prompt_other_items_intro") }
    static func recipePromptOtherItemsOmitted(_ count: Int) -> String {
        String(format: paid("recipe_prompt_other_items_omitted"), count)
    }

    // Past their date
    /// "Hamburgare (145 days over)": each item with the status chip's own wording, its first
    /// letter lowered where the wording starts a sentence ("Просрочено…", "Utløpt…").
    static func recipePastDateList(_ items: [PastDateItem]) -> String {
        let english = Locale.current.language.languageCode?.identifier == "en"
        return items.map { item in
            let over = String.localizedStringWithFormat(NSLocalizedString("status_days_over_format", comment: ""), item.daysOver)
            let lowered = english ? over : over.prefix(1).lowercased() + over.dropFirst()
            return String(format: paid("recipe_past_date_item"), isolated(item.name), lowered)
        }.joined(separator: ", ")
    }

    /// The red line above a written recipe's ingredients and in its shared text; nil with none.
    static func recipePastDateWarning(_ items: [PastDateItem]) -> String? {
        guard !items.isEmpty else { return nil }
        return String(format: paid(items.count == 1 ? "recipe_past_date_result_one" : "recipe_past_date_result_many"), recipePastDateList(items))
    }

    /// The form's red callout over "Cook with": how many ticked items are past their date and
    /// the oldest; nil when none is.
    static func recipePastDateFormWarning(_ items: [PastDateItem]) -> String? {
        guard let oldest = items.map(\.daysOver).max() else { return nil }
        let days = plural("recipe_past_days", oldest)
        if items.count == 1 { return String(format: paid("recipe_past_date_form_single"), days) }
        return withDevice(String.localizedStringWithFormat(NSLocalizedString("recipe_past_date_form", comment: ""), items.count, days))
    }

    /// The shared prompt's line under its item list when `count` (> 0) of them are past their
    /// date: no names, since each item's line already says it.
    static func recipePromptPastDate(count: Int) -> String { paid(count == 1 ? "recipe_prompt_past_date_one" : "recipe_prompt_past_date_many") }

    // How recipes work
    static var recipeIntroTitle: String { paid("recipe_intro_title") }
    static var recipeIntroMessage: String { paid("recipe_intro_message") }
    static var recipeIntroPromptTitle: String { paid("recipe_intro_prompt_title") }
    static var recipeIntroPromptMessage: String { paid("recipe_intro_prompt_message") }
    static var recipeIntroDeviceTitle: String { paid("recipe_intro_device_title") }
    static var recipeIntroDeviceMessage: String { paid("recipe_intro_device_message") }
    static var recipeIntroPacksTitle: String { paid("recipe_intro_packs_title") }
    /// What a recipe and a menu take, from the server's costs (the fallback before any), then the
    /// cheapest pack's store price when the store has answered, then that the free ways stay.
    /// With a menu-set table, the range of what a menu set takes (Android's
    /// `recipe_intro_packs_body_sets`); otherwise the 3-day menu's two prices.
    static func recipeIntroPacksMessage(_ costs: RecipeCosts, menuSets: Bool, lowestPrice: String?) -> String {
        var text: String
        if menuSets, let range = costs.menuSetRange {
            text = String(format: paid("recipe_intro_packs_body_sets"), recipeCost(costs.recipe), range.lowerBound, range.upperBound)
        } else {
            text = String(format: paid("recipe_intro_packs_message"), recipeCost(costs.recipe), recipeCost(costs.menu2), recipeCost(costs.menu3))
        }
        if let lowestPrice { text += " " + String(format: paid("recipe_intro_packs_price"), lowestPrice) }
        return text + " " + paid("recipe_intro_free_stays")
    }
    static var recipeIntroAgain: String { paid("recipe_intro_again") }
    static var recipeIntroStart: String { paid("recipe_intro_start") }
    static var recipeIntroPacksAction: String { paid("recipe_intro_packs_action") }
    static var recipeIntroSettingsSupporting: String { paid("recipe_intro_settings_supporting") }
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
    /// `STORE_MISMATCH`: this build can't add App Store purchases.
    static var recipeRejectedBuildMessage: String { paid("recipe_rejected_build_message") }

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
    // Deleting what our server holds for recipes kept on this phone (an anonymous account)
    static var recipeDeleteDeviceData: String { paid("recipe_delete_device_data") }
    static var recipeDeleteDeviceTitle: String { paid("recipe_delete_device_title") }
    /// Below zero after a store refund of recipes already used: none of them is lost.
    static func recipeDeleteDeviceMessage(_ balance: Int) -> String {
        balance > 0 ? plural("recipe_delete_device_message", balance) : paid("recipe_delete_device_message_none")
    }
    static var recipeDeleteDeviceConfirm: String { paid("recipe_delete_device_confirm") }
    static var recipeDeviceDataDeleted: String { paid("recipe_device_data_deleted") }
    static var recipeDeleteWaitsForPurchase: String { paid("recipe_delete_waits_for_purchase") }
    static var recipeMergeTitle: String { paid("recipe_merge_title") }
    static func recipeMergeMessage(account: Int, install: Int) -> String {
        String(format: paid("recipe_merge_message"), account, install, account + install)
    }
    static var recipeMergeConfirm: String { paid("recipe_merge_confirm") }
    static var recipeSignInFailed: String { paid("recipe_sign_in_failed") }
    static var recipeProviderInUse: String { paid("recipe_provider_in_use") }
    static var recipeAccountDeleted: String { paid("recipe_account_deleted") }
    static var recipeAppleLinkLeftTitle: String { paid("recipe_apple_link_left_title") }
    static var recipeAppleLinkLeftMessage: String { paid("recipe_apple_link_left_message") }

    // Allergen consent (`AllergenConsent.version`: raise it when this wording changes; Android
    // has the same text)
    static var recipeAllergenConsentTitle: String { paid("recipe_allergen_consent_title") }
    static func recipeAllergenConsentMessage(_ allergens: [String]) -> String {
        String(format: paid("recipe_allergen_consent_message"), isolatedList(allergens))
    }
    static var recipeAllergenConsentAgree: String { paid("recipe_allergen_consent_agree") }
    static var recipeAllergenConsentAgreeOnly: String { paid("recipe_allergen_consent_agree_only") }
    static var recipeAllergenConsentDeclined: String { paid("recipe_allergen_consent_declined") }
    static var recipeAllergenConsentSetting: String { paid("recipe_allergen_consent_setting") }
    static func recipeAllergenConsentSettingOn(_ date: Date) -> String {
        String(format: paid("recipe_allergen_consent_setting_on"), date.formatted(date: .abbreviated, time: .omitted))
    }
    static var recipeAllergenConsentSettingOff: String { paid("recipe_allergen_consent_setting_off") }
    static var recipeAccountActionFailed: String { paid("recipe_account_action_failed") }
    static var recipeAccountBusy: String { paid("recipe_account_busy") }
    /// What a join of this install into an account moved: recipes, or a debt after a store
    /// refund (`moved` < 0). Android's `recipe_merged_moved` / `recipe_merged_debt`.
    static func recipeMerged(moved: Int) -> String {
        moved >= 0 ? plural("recipe_merged_moved", moved) : plural("recipe_merged_debt", -moved)
    }
    static var recipePurchasesBlocked: String { paid("recipe_purchases_blocked") }
    static var recipeOtherAccountTitle: String { paid("recipe_other_account_title") }
    static func recipeOtherAccountMessage(_ credits: Int) -> String { plural("recipe_other_account_message", credits) }
    static var recipeConsumptionNote: String { paid("recipe_consumption_note") }

    // Menu
    static var recipeMenuResultTitle: String { paid("recipe_menu_result_title") }
    /// "Menu for 2 days", from the menu's own day count (a menu saved before menu sets has 3).
    static func recipeMenuHeading(days: Int) -> String { plural("recipe_menu_heading", days) }
    static func recipeMenuSummary(courses: Int, servings: Int) -> String {
        plural("recipe_menu_summary", courses, ["servings": "\(servings)"])
    }
    static func recipeMenuToday(_ date: String) -> String { String(format: paid("recipe_menu_today"), date) }
    static func recipeMenuUses(_ items: [String]) -> String { String(format: paid("recipe_menu_uses"), isolatedList(items)) }
    static func recipeCourseName(_ course: RecipeCourse) -> String {
        switch course {
        case .starter: return paid("recipe_course_starter")
        case .main: return paid("recipe_course_main")
        case .dessert: return paid("recipe_course_dessert")
        }
    }
    static func recipePartOfMenu(days: Int) -> String { plural("recipe_part_of_menu", days) }
    static var recipeShareMenu: String { paid("recipe_share_menu") }
    static func recipeDishes(_ count: Int) -> String { plural("recipe_dishes_count", count) }

    // Taste profile: only the chrome. Questions, options and their texts come from the server
    // with the quiz (`TasteQuiz`). Android: the taste_* strings, copied as they are.
    static var tasteTitle: String { text("taste_title") }
    static func tasteChooseUpTo(_ cap: Int) -> String { String(format: text("taste_choose_up_to"), cap) }
    static var tasteChooseOne: String { text("taste_choose_one") }
    static func tasteProgress(_ step: Int, of total: Int) -> String { String(format: text("taste_progress"), step, total) }
    static var tasteIntro: String { text("taste_intro") }
    static var tasteSkip: String { text("taste_skip") }
    static var tasteBack: String { text("taste_back") }
    static var tasteNext: String { text("taste_next") }
    static var tasteSave: String { text("taste_save") }
    static var tasteSettingsEmpty: String { text("taste_settings_empty") }
    static func tasteSummaryMore(_ count: Int) -> String { String(format: text("taste_summary_more"), count) }
    static var tasteClear: String { text("taste_clear") }
    static var tasteUsesProfile: String { text("taste_uses_profile") }
    static func tasteChosen(_ count: Int) -> String { String(format: text("taste_chosen"), count) }
    static func tasteChosenOf(_ count: Int, _ cap: Int) -> String { String(format: text("taste_chosen_of"), count, cap) }
    static func tasteCapReached(_ cap: Int) -> String { String(format: text("taste_cap_reached"), cap) }
    static var tasteOnSkipNote: String { text("taste_on_skip_note") }
    static var tasteOnLoveNote: String { text("taste_on_love_note") }
    static func tasteChipOnSkip(_ label: String) -> String { String(format: text("taste_chip_on_skip"), label) }
    static func tasteChipOnLove(_ label: String) -> String { String(format: text("taste_chip_on_love"), label) }
    static func tasteChipAtCap(_ label: String) -> String { String(format: text("taste_chip_at_cap"), label) }
    static func tasteMovedOffSkip(_ label: String) -> String { String(format: text("taste_moved_off_skip"), label) }
    static func tasteMovedOffLove(_ label: String) -> String { String(format: text("taste_moved_off_love"), label) }
    static var tasteSavedSnackbar: String { text("taste_saved_snackbar") }
    static var tasteClearedSnackbar: String { text("taste_cleared_snackbar") }
    static var tasteDiscardMessage: String { text("taste_discard_message") }
    static var tastePromoLede: String { text("taste_promo_lede") }
    static var tastePromoQuestionsTitle: String { text("taste_promo_questions_title") }
    static var tastePromoQuestionsBody: String { text("taste_promo_questions_body") }
    static var tastePromoTasteTitle: String { text("taste_promo_taste_title") }
    static var tastePromoTasteBody: String { text("taste_promo_taste_body") }
    static var tastePromoDeviceTitle: String { text("taste_promo_device_title") }
    static var tastePromoDeviceBody: String { text("taste_promo_device_body") }
    static var tasteLoading: String { text("taste_loading") }
    static var tasteErrorTitle: String { text("taste_error_title") }
    static var tasteErrorBody: String { text("taste_error_body") }
    static var tasteRetry: String { text("taste_retry") }
    static var tasteClose: String { text("taste_close") }
    static var tasteNewTag: String { text("taste_new_tag") }
    /// Added to the settings button's label with the dot: "new taste profile questions".
    static var tasteSettingsBadge: String { text("taste_settings_badge") }
    static func tasteAnswers(_ count: Int) -> String { plural("taste_answers", count) }
    static func tasteNewQuestions(_ count: Int) -> String { plural("taste_new_questions", count) }

    /// The row's supporting line: "Italian, Nordic · Medium · 5 more"; "N answers" without a
    /// definition in this language; what it is for without answers (`TasteProfile.summary`). And,
    /// apart so it is never cut, "1 new question" when the definition has steps the person hasn't
    /// been through.
    static func tasteSummary(_ profile: TasteProfile, seen: Set<String>, quiz: TasteQuiz?) -> (line: String, newQuestions: String?) {
        guard !profile.isEmpty else { return (tasteSettingsEmpty, nil) }
        guard let quiz else { return (tasteAnswers(profile.count), nil) }
        guard let summary = profile.summary(quiz) else { return (tasteSettingsEmpty, nil) }
        var parts: [String] = []
        if !summary.named.isEmpty { parts.append(isolatedList(summary.named)) }
        if let single = summary.single { parts.append(isolated(single)) }
        if summary.more > 0 { parts.append(tasteSummaryMore(summary.more)) }
        let new = TasteProfile.newSteps(seen: seen, quiz: quiz).count
        return (parts.joined(separator: " \u{00B7} "), new > 0 ? tasteNewQuestions(new) : nil)
    }
}
