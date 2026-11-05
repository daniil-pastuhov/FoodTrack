import Foundation

public enum L10n {
    public static var appName: String { NSLocalizedString("app_name", comment: "") }
    public static var navHome: String { NSLocalizedString("nav_home", comment: "") }

    public static var metricExpired: String { NSLocalizedString("metric_expired", comment: "") }
    public static var metricExpiringSoon: String { NSLocalizedString("metric_expiring_soon", comment: "") }
    public static var metricFresh: String { NSLocalizedString("metric_fresh", comment: "") }

    public static var locationFilterAll: String { NSLocalizedString("location_filter_all", comment: "") }
    public static var categoryFilterAll: String { NSLocalizedString("category_filter_all", comment: "") }

    public static var statusFilterAll: String { NSLocalizedString("status_filter_all", comment: "") }
    public static var statusFilterExpiringSoon: String { NSLocalizedString("status_filter_expiring_soon", comment: "") }
    public static var statusFilterExpired: String { NSLocalizedString("status_filter_expired", comment: "") }
    public static var statusFilterFresh: String { NSLocalizedString("status_filter_fresh", comment: "") }

    public static var sortButton: String { NSLocalizedString("sort_button", comment: "") }
    public static var sortSheetTitle: String { NSLocalizedString("sort_sheet_title", comment: "") }
    public static var sortOptionExpiryDate: String { NSLocalizedString("sort_option_expiry_date", comment: "") }
    public static var sortOptionName: String { NSLocalizedString("sort_option_name", comment: "") }
    public static var sortOptionDateAdded: String { NSLocalizedString("sort_option_date_added", comment: "") }
    public static var sortOptionStorageLocation: String { NSLocalizedString("sort_option_storage_location", comment: "") }

    public static var emptyStateTitle: String { NSLocalizedString("empty_state_title", comment: "") }
    public static var emptyStateSubtitle: String { NSLocalizedString("empty_state_subtitle", comment: "") }

    public static var swipeDelete: String { NSLocalizedString("swipe_delete", comment: "") }
    public static var swipeAddToShoppingList: String { NSLocalizedString("swipe_add_to_shopping_list", comment: "") }
    public static func itemRemovedToast(_ name: String) -> String {
        String(format: NSLocalizedString("item_removed_toast_format", comment: ""), name)
    }
    public static func itemAddedToShoppingListToast(_ name: String) -> String {
        String(format: NSLocalizedString("item_added_to_shopping_list_toast_format", comment: ""), name)
    }
    public static var undoAction: String { NSLocalizedString("undo_action", comment: "") }

    // Pantry
    public static func ledeNextUp(_ a1: String, _ a2: String) -> String {
        String(format: NSLocalizedString("lede_next_up", comment: ""), a1, a2)
    }
    public static var ledeNothingThisWeek: String { NSLocalizedString("lede_nothing_this_week", comment: "") }
    public static var homeSectionUseFirst: String { NSLocalizedString("home_section_use_first", comment: "") }
    public static var homeSectionLaterThisWeek: String { NSLocalizedString("home_section_later_this_week", comment: "") }
    public static var homeSectionLater: String { NSLocalizedString("home_section_later", comment: "") }
    public static var homeClearFilters: String { NSLocalizedString("home_clear_filters", comment: "") }
    public static var homeSearchDescription: String { NSLocalizedString("home_search_description", comment: "") }
    public static var homeFiltersOnDescription: String { NSLocalizedString("home_filters_on_description", comment: "") }
    public static var homeEmptyTitle: String { NSLocalizedString("home_empty_title", comment: "") }
    public static var homeEmptyMessage: String { NSLocalizedString("home_empty_message", comment: "") }
    public static var homeEmptyScan: String { NSLocalizedString("home_empty_scan", comment: "") }
    public static var homeEmptyAddByHand: String { NSLocalizedString("home_empty_add_by_hand", comment: "") }
    public static func homeNoResultsTitle(_ a1: String) -> String {
        String(format: NSLocalizedString("home_no_results_title", comment: ""), a1)
    }
    public static var homeNoResultsMessage: String { NSLocalizedString("home_no_results_message", comment: "") }
    public static var homeClearSearch: String { NSLocalizedString("home_clear_search", comment: "") }
    public static var homeNoFilterResultsTitle: String { NSLocalizedString("home_no_filter_results_title", comment: "") }
    public static var homeNoFilterResultsMessage: String { NSLocalizedString("home_no_filter_results_message", comment: "") }
    public static var homeRemindersOffTitle: String { NSLocalizedString("home_reminders_off_title", comment: "") }
    public static var homeRemindersOffMessage: String { NSLocalizedString("home_reminders_off_message", comment: "") }
    public static var homeDismiss: String { NSLocalizedString("home_dismiss", comment: "") }
    public static var homeSwipeToList: String { NSLocalizedString("home_swipe_to_list", comment: "") }
    public static func homeUseOneItem(_ a1: String) -> String {
        String(format: NSLocalizedString("home_use_one_item", comment: ""), a1)
    }
    public static func homeUsedOneSnackbar(_ a1: String, _ a2: Int) -> String {
        String(format: NSLocalizedString("home_used_one_snackbar", comment: ""), a1, a2)
    }
    public static var homeLocationLabel: String { NSLocalizedString("home_location_label", comment: "") }
    public static var homeCategoryLabel: String { NSLocalizedString("home_category_label", comment: "") }
    public static var homeBestBeforeLabel: String { NSLocalizedString("home_best_before_label", comment: "") }
    public static var homeNotesLabel: String { NSLocalizedString("home_notes_label", comment: "") }
    public static var homeDone: String { NSLocalizedString("home_done", comment: "") }
    public static var detailClose: String { NSLocalizedString("detail_close", comment: "") }
    public static var detailEdit: String { NSLocalizedString("detail_edit", comment: "") }
    public static var detailToShoppingList: String { NSLocalizedString("detail_to_shopping_list", comment: "") }
    public static func detailNutriCaption(_ a1: String) -> String {
        String(format: NSLocalizedString("detail_nutri_caption", comment: ""), a1)
    }
    public static func detailNovaCaption(_ a1: Int, _ a2: String) -> String {
        String(format: NSLocalizedString("detail_nova_caption", comment: ""), a1, a2)
    }
    public static func detailNovaDescription(_ a1: Int, _ a2: String) -> String {
        String(format: NSLocalizedString("detail_nova_description", comment: ""), a1, a2)
    }
    public static var detailNova1: String { NSLocalizedString("detail_nova_1", comment: "") }
    public static var detailNova2: String { NSLocalizedString("detail_nova_2", comment: "") }
    public static var detailNova3: String { NSLocalizedString("detail_nova_3", comment: "") }
    public static var detailNova4: String { NSLocalizedString("detail_nova_4", comment: "") }
    public static var detailCredit: String { NSLocalizedString("detail_credit", comment: "") }
    public static var detailAllergenNote: String { NSLocalizedString("detail_allergen_note", comment: "") }
    public static var detailChooseItem: String { NSLocalizedString("detail_choose_item", comment: "") }
    public static func recipeIntro(_ a1: String) -> String {
        String(format: NSLocalizedString("recipe_intro", comment: ""), a1)
    }
    public static func recipeNamesTwo(_ a1: String, _ a2: String) -> String {
        String(format: NSLocalizedString("recipe_names_two", comment: ""), a1, a2)
    }
    public static func recipeNamesThree(_ a1: String, _ a2: String, _ a3: String) -> String {
        String(format: NSLocalizedString("recipe_names_three", comment: ""), a1, a2, a3)
    }
    /// A pantry count as a word up to ten, as the sentence on Home writes it.
    public static func ledeNumber(_ count: Int) -> String {
        (1...10).contains(count) ? NSLocalizedString("lede_number_\(count)", comment: "") : "\(count)"
    }
    /// "on Friday". Takes 1 for Monday, because the words carry their own grammar in Russian.
    public static func ledeOnWeekday(_ isoDay: Int) -> String {
        NSLocalizedString("lede_on_weekday_\(isoDay)", comment: "")
    }
    private static func plural(_ key: String, _ count: Int, _ inserts: [String: String] = [:]) -> String {
        var text = String.localizedStringWithFormat(NSLocalizedString(key, comment: ""), count)
        for (name, value) in inserts { text = text.replacingOccurrences(of: "{\(name)}", with: value) }
        return text
    }
    public static func ledeUseByTomorrow(_ count: Int) -> String {
        plural("lede_use_by_tomorrow", count, ["n": ledeNumber(count)])
    }
    public static func ledePastDate(_ count: Int) -> String {
        plural("lede_past_date", count, ["n": ledeNumber(count)])
    }
    public static func homeSearchResults(_ count: Int) -> String { plural("home_search_results", count) }
    public static func homeShowItems(_ count: Int) -> String { plural("home_show_items", count) }
    public static func homeActiveFilterCount(_ shown: Int, of total: Int) -> String {
        plural("home_active_filter_count", total, ["n": "\(shown)"])
    }
    public static func recipeNamesMore(_ first: String, _ second: String, others: Int) -> String {
        plural("recipe_names_more", others, ["a": first, "b": second])
    }
    public static func recipeIncludeOthersCount(_ count: Int) -> String {
        plural("recipe_include_others_count", count)
    }

    // Larder foundation
    public static var navShopping: String { NSLocalizedString("nav_shopping", comment: "") }
    public static var backButton: String { NSLocalizedString("back_button", comment: "") }

    /// How many days ahead a fresh item still counts down rather than showing its date.
    private static let relativeFreshDays = 7

    /// The words a status chip shows: "1 day over", "Today", "Tomorrow", "In 4 days", "19 Nov",
    /// and the year too when it isn't this one. Factual on purpose: a passed date is never
    /// "Expired!". Mirrors Android's `expiryStatusLabel`.
    public static func expiryStatusLabel(
        status: ExpiryStatus,
        daysRemaining: Int,
        expiryDate: Date,
        today: Date = Date(),
        locale: Locale = .current
    ) -> String {
        if daysRemaining < 0 {
            return String.localizedStringWithFormat(NSLocalizedString("status_days_over_format", comment: ""), -daysRemaining)
        }
        if daysRemaining == 0 { return NSLocalizedString("status_today", comment: "") }
        if daysRemaining == 1 { return NSLocalizedString("status_tomorrow", comment: "") }
        if status != .fresh || daysRemaining <= relativeFreshDays {
            return String.localizedStringWithFormat(NSLocalizedString("expires_in_days_format", comment: ""), daysRemaining)
        }
        let calendar = Calendar.current
        let sameYear = calendar.component(.year, from: expiryDate) == calendar.component(.year, from: today)
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.setLocalizedDateFormatFromTemplate(sameYear ? "dMMM" : "dMMMy")
        return formatter.string(from: expiryDate)
    }

    public static var clearFieldButton: String { NSLocalizedString("clear_field_button", comment: "") }
    public static var dismissButton: String { NSLocalizedString("dismiss_button", comment: "") }
    public static var quantityDecrease: String { NSLocalizedString("quantity_decrease", comment: "") }
    public static var quantityIncrease: String { NSLocalizedString("quantity_increase", comment: "") }
    public static func useOneLabel(_ name: String) -> String {
        String(format: NSLocalizedString("use_one_label_format", comment: ""), name)
    }
    public static func itemSavedTo(_ name: String, _ location: String) -> String {
        String(format: NSLocalizedString("item_saved_to_format", comment: ""), name, location)
    }
    public static func itemsDeleted(_ count: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("items_deleted_format", comment: ""), count)
    }

    // MARK: - Onboarding
    public static var onboardingGetStarted: String { NSLocalizedString("onboarding_get_started", comment: "") }
    public static var onboardingWelcomeTitle: String { NSLocalizedString("onboarding_welcome_title", comment: "") }
    public static var onboardingPrivateTitle: String { NSLocalizedString("onboarding_private_title", comment: "") }
    public static var onboardingPrivateBody: String { NSLocalizedString("onboarding_private_body", comment: "") }
    public static var onboardingNotificationsTitle: String { NSLocalizedString("onboarding_notifications_title", comment: "") }
    public static var onboardingNotificationsBody: String { NSLocalizedString("onboarding_notifications_body", comment: "") }
    public static func onboardingSampleSource(_ appName: String) -> String {
        String(format: NSLocalizedString("onboarding_sample_source", comment: ""), appName)
    }
    public static var onboardingSampleTitle: String { NSLocalizedString("onboarding_sample_title", comment: "") }
    public static var onboardingSampleBody: String { NSLocalizedString("onboarding_sample_body", comment: "") }
    public static func onboardingSampleDescription(_ title: String, _ body: String) -> String {
        String(format: NSLocalizedString("onboarding_sample_description", comment: ""), title, body)
    }
    public static var onboardingNotificationsAllow: String { NSLocalizedString("onboarding_notifications_allow", comment: "") }
    public static var onboardingNotNow: String { NSLocalizedString("onboarding_not_now", comment: "") }
    public static var onboardingCameraTitle: String { NSLocalizedString("onboarding_camera_title", comment: "") }
    public static func onboardingCameraBody(_ appName: String) -> String {
        String(format: NSLocalizedString("onboarding_camera_body", comment: ""), appName)
    }
    public static var onboardingCameraBarcodesTitle: String { NSLocalizedString("onboarding_camera_barcodes_title", comment: "") }
    public static var onboardingCameraBarcodesBody: String { NSLocalizedString("onboarding_camera_barcodes_body", comment: "") }
    public static var onboardingCameraDatesTitle: String { NSLocalizedString("onboarding_camera_dates_title", comment: "") }
    public static var onboardingCameraDatesBody: String { NSLocalizedString("onboarding_camera_dates_body", comment: "") }
    public static var onboardingCameraAllow: String { NSLocalizedString("onboarding_camera_allow", comment: "") }
    public static var onboardingReadyTitle: String { NSLocalizedString("onboarding_ready_title", comment: "") }
    public static var onboardingReadyBody: String { NSLocalizedString("onboarding_ready_body", comment: "") }
    public static var onboardingSkip: String { NSLocalizedString("onboarding_skip", comment: "") }
    public static var onboardingDeclinedTitle: String { NSLocalizedString("onboarding_declined_title", comment: "") }
    public static var onboardingDeclinedBody: String { NSLocalizedString("onboarding_declined_body", comment: "") }
    public static var onboardingDeclinedRemindersTitle: String { NSLocalizedString("onboarding_declined_reminders_title", comment: "") }
    public static var onboardingDeclinedRemindersBody: String { NSLocalizedString("onboarding_declined_reminders_body", comment: "") }
    public static var onboardingDeclinedCameraTitle: String { NSLocalizedString("onboarding_declined_camera_title", comment: "") }
    public static var onboardingDeclinedCameraBody: String { NSLocalizedString("onboarding_declined_camera_body", comment: "") }
    public static var onboardingAddByHand: String { NSLocalizedString("onboarding_add_by_hand", comment: "") }
    public static var remindersNotAskedMessage: String { NSLocalizedString("reminders_not_asked_message", comment: "") }

    public static var exitSelectionButton: String { NSLocalizedString("exit_selection_button", comment: "") }
    public static var selectItemsButton: String { NSLocalizedString("select_items_button", comment: "") }
    public static var moreOptionsButton: String { NSLocalizedString("more_options_button", comment: "") }
    public static var actionGetRecipeIdeas: String { NSLocalizedString("action_get_recipe_ideas", comment: "") }
    public static var recipePromptIntro: String { NSLocalizedString("recipe_prompt_intro", comment: "") }
    public static var recipePromptOtherItemsIntro: String { NSLocalizedString("recipe_prompt_other_items_intro", comment: "") }
    public static var shareRecipeDialogTitle: String { NSLocalizedString("share_recipe_dialog_title", comment: "") }
    public static var shareRecipeIncludeOtherItemsTitle: String { NSLocalizedString("share_recipe_include_other_items_title", comment: "") }
    public static var shareRecipeIncludeOtherItemsSubtitle: String { NSLocalizedString("share_recipe_include_other_items_subtitle", comment: "") }
    public static var shareRecipeShareButton: String { NSLocalizedString("share_recipe_share_button", comment: "") }
    public static var shareRecipeCancelButton: String { NSLocalizedString("share_recipe_cancel_button", comment: "") }

    public static func selectionCount(_ count: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("selection_count_format", comment: ""), count)
    }

    public static func recipePromptItem(name: String, expiry: String) -> String {
        String(format: NSLocalizedString("recipe_prompt_item_format", comment: ""), name, expiry)
    }

    public static func recipePromptOtherItemsOmitted(_ count: Int) -> String {
        String(format: NSLocalizedString("recipe_prompt_other_items_omitted", comment: ""), count)
    }

    public static var searchPlaceholder: String { NSLocalizedString("search_placeholder", comment: "") }
    public static var scanButton: String { NSLocalizedString("scan_button", comment: "") }
    public static var statusPickerLabel: String { NSLocalizedString("status_picker_label", comment: "") }
    public static var filtersSheetTitle: String { NSLocalizedString("filters_sheet_title", comment: "") }
    public static var filtersButton: String { NSLocalizedString("filters_button", comment: "") }
    public static var doneButton: String { NSLocalizedString("done_button", comment: "") }
    public static var productDetailNutriScore: String { NSLocalizedString("product_detail_nutriscore", comment: "") }
    public static var productDetailNova: String { NSLocalizedString("product_detail_nova", comment: "") }
    public static var productDetailAllergens: String { NSLocalizedString("product_detail_allergens", comment: "") }
    public static var productDetailIngredients: String { NSLocalizedString("product_detail_ingredients", comment: "") }
    public static var productDetailContentDescription: String { NSLocalizedString("product_detail_content_description", comment: "") }
    public static var settingsSectionAllergens: String { NSLocalizedString("settings_section_allergens", comment: "") }
    public static var settingsAllergensDesc: String { NSLocalizedString("settings_allergens_desc", comment: "") }
    public static var settingsAddAllergenPlaceholder: String { NSLocalizedString("settings_add_allergen_placeholder", comment: "") }
    public static var settingsAddAllergen: String { NSLocalizedString("settings_add_allergen", comment: "") }
    public static func allergenWarning(_ allergens: String) -> String {
        String(format: NSLocalizedString("allergen_warning", comment: ""), allergens)
    }

    public static var calendarButton: String { NSLocalizedString("calendar_button", comment: "") }
    public static var calendarTitle: String { NSLocalizedString("calendar_title", comment: "") }
    public static var calendarPreviousMonth: String { NSLocalizedString("calendar_previous_month", comment: "") }
    public static var calendarNextMonth: String { NSLocalizedString("calendar_next_month", comment: "") }
    public static var calendarNoItemsForDay: String { NSLocalizedString("calendar_no_items_for_day", comment: "") }
    public static var calendarSectionOverdue: String { NSLocalizedString("calendar_section_overdue", comment: "") }
    public static var calendarSectionToday: String { NSLocalizedString("calendar_section_today", comment: "") }
    public static var calendarSectionTomorrow: String { NSLocalizedString("calendar_section_tomorrow", comment: "") }
    public static var calendarSectionThisWeek: String { NSLocalizedString("calendar_section_this_week", comment: "") }
    public static var calendarSectionNextWeek: String { NSLocalizedString("calendar_section_next_week", comment: "") }
    public static var calendarSectionLater: String { NSLocalizedString("calendar_section_later", comment: "") }
    public static var calendarAgendaEmpty: String { NSLocalizedString("calendar_agenda_empty", comment: "") }
    public static var calendarShowAll: String { NSLocalizedString("calendar_show_all", comment: "") }
    public static var calendarSwipeUsed: String { NSLocalizedString("calendar_swipe_used", comment: "") }
    public static var calendarSwipeTossed: String { NSLocalizedString("calendar_swipe_tossed", comment: "") }
    public static var calendarShowMonth: String { NSLocalizedString("calendar_show_month", comment: "") }
    public static var calendarShowWeek: String { NSLocalizedString("calendar_show_week", comment: "") }
    public static var calendarPaneTitle: String { NSLocalizedString("calendar_pane_title", comment: "") }
    public static var calendarEmptyMessage: String { NSLocalizedString("calendar_empty_message", comment: "") }
    public static func calendarUsedSnackbar(_ name: String) -> String {
        String(format: NSLocalizedString("calendar_used_snackbar", comment: ""), name)
    }
    public static func calendarTossedSnackbar(_ name: String) -> String {
        String(format: NSLocalizedString("calendar_tossed_snackbar", comment: ""), name)
    }
    public static func calendarDayItemCount(_ count: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("calendar_day_item_count", comment: ""), count)
    }

    public static var sectionProductDetails: String { NSLocalizedString("section_product_details", comment: "") }
    public static var productNameLabel: String { NSLocalizedString("product_name_label", comment: "") }
    public static var brandLabel: String { NSLocalizedString("brand_label", comment: "") }
    public static var barcodeWord: String { NSLocalizedString("barcode_word", comment: "") }

    public static var sectionExpirationDate: String { NSLocalizedString("section_expiration_date", comment: "") }
    public static func expiryDateImplausibleWarning(maxYears: Int) -> String {
        let format = NSLocalizedString("expiry_date_implausible_warning_format", comment: "")
        return String.localizedStringWithFormat(format, maxYears)
    }
    public static var expiryPickerLabel: String { NSLocalizedString("expiry_picker_label", comment: "") }
    /// Caption under the expiry field when the date was pre-filled from the shelf life this
    /// barcode was given last time - see `ShelfLife`.
    public static func expirySuggestionRemembered(days: Int) -> String {
        String.localizedStringWithFormat(
            NSLocalizedString("expiry_suggestion_remembered_format", comment: ""),
            days
        )
    }
    /// Caption under the expiry field when the date came from the category's configured default.
    public static func expirySuggestionCategory(_ category: String, days: Int) -> String {
        String.localizedStringWithFormat(
            NSLocalizedString("expiry_suggestion_category_format", comment: ""),
            category,
            days
        )
    }
    public static var quickDate3Days: String { NSLocalizedString("quick_date_3_days", comment: "") }
    public static var quickDate1Week: String { NSLocalizedString("quick_date_1_week", comment: "") }
    public static var quickDate2Weeks: String { NSLocalizedString("quick_date_2_weeks", comment: "") }
    public static var quickDate1Month: String { NSLocalizedString("quick_date_1_month", comment: "") }

    public static var sectionStorage: String { NSLocalizedString("section_storage", comment: "") }
    public static var locationPickerLabel: String { NSLocalizedString("location_picker_label", comment: "") }

    public static var categoryLabel: String { NSLocalizedString("category_label", comment: "") }
    public static var quantityLabel: String { NSLocalizedString("quantity_label", comment: "") }
    public static func quantityFormat(_ count: Int) -> String {
        String(format: NSLocalizedString("quantity_format", comment: ""), count)
    }
    public static var useOneButton: String { NSLocalizedString("use_one_button", comment: "") }

    public static var sectionNotes: String { NSLocalizedString("section_notes", comment: "") }
    public static var notesFieldPlaceholder: String { NSLocalizedString("notes_field_placeholder", comment: "") }

    public static var editItemTitle: String { NSLocalizedString("edit_item_title", comment: "") }
    public static var addItemTitle: String { NSLocalizedString("add_item_title", comment: "") }
    public static var cancelButton: String { NSLocalizedString("cancel_button", comment: "") }
    public static var discardChangesTitle: String { NSLocalizedString("discard_changes_title", comment: "") }
    public static var discardChangesConfirm: String { NSLocalizedString("discard_changes_confirm", comment: "") }
    public static var discardChangesKeep: String { NSLocalizedString("discard_changes_keep", comment: "") }
    public static var saveButton: String { NSLocalizedString("save_button", comment: "") }

    public static var essentialItemLabel: String { NSLocalizedString("essential_item_label", comment: "") }
    public static var essentialItemDescription: String { NSLocalizedString("essential_item_description", comment: "") }
    public static var itemNotificationsLabel: String { NSLocalizedString("item_notifications_label", comment: "") }
    public static var itemNotificationsDescription: String { NSLocalizedString("item_notifications_description", comment: "") }

    public static var shoppingListButton: String { NSLocalizedString("shopping_list_button", comment: "") }
    public static var shoppingListTitle: String { NSLocalizedString("shopping_list_title", comment: "") }
    public static var shoppingListAddPlaceholder: String { NSLocalizedString("shopping_list_add_placeholder", comment: "") }
    public static var shoppingListAddButton: String { NSLocalizedString("shopping_list_add_button", comment: "") }
    public static var shoppingListEmptyTitle: String { NSLocalizedString("shopping_list_empty_title", comment: "") }
    public static var shoppingListEmptySubtitle: String { NSLocalizedString("shopping_list_empty_subtitle", comment: "") }
    public static var shoppingListCheckOff: String { NSLocalizedString("shopping_list_check_off", comment: "") }
    public static var shoppingListUncategorized: String { NSLocalizedString("shopping_list_uncategorized", comment: "") }
    public static var shoppingSectionDefault: String { NSLocalizedString("shopping_section_default", comment: "") }
    public static var shoppingSectionNew: String { NSLocalizedString("shopping_section_new", comment: "") }
    public static var shoppingSectionRename: String { NSLocalizedString("shopping_section_rename", comment: "") }
    public static var shoppingSectionDelete: String { NSLocalizedString("shopping_section_delete", comment: "") }
    public static func shoppingSectionDeleteTitle(_ name: String) -> String {
        String(format: NSLocalizedString("shopping_section_delete_title", comment: ""), name)
    }
    public static var shoppingSectionSave: String { NSLocalizedString("shopping_section_save", comment: "") }
    public static var shoppingSectionNamePlaceholder: String { NSLocalizedString("shopping_section_name_placeholder", comment: "") }
    public static var shoppingSectionMoveTo: String { NSLocalizedString("shopping_section_move_to", comment: "") }
    public static var shoppingSectionReorder: String { NSLocalizedString("shopping_section_reorder", comment: "") }
    public static func shoppingSectionOptions(_ name: String) -> String {
        String(format: NSLocalizedString("shopping_section_options", comment: ""), name)
    }
    public static var shoppingSectionCollapsed: String { NSLocalizedString("shopping_section_collapsed", comment: "") }
    public static var shoppingSectionExpanded: String { NSLocalizedString("shopping_section_expanded", comment: "") }
    public static func widgetShoppingListMore(_ count: Int) -> String {
        String(format: NSLocalizedString("widget_shopping_list_more", comment: ""), count)
    }
    /// What a section is called on screen: the default section's localized name, a built-in
    /// category key (a section converted from a category) translated, anything else as typed.
    public static func shoppingSectionName(_ section: ShoppingSection) -> String {
        section.isDefault ? shoppingSectionDefault : categoryName(section.name)
    }
    public static var photoSuggestionsTitle: String { NSLocalizedString("photo_suggestions_title", comment: "") }
    public static var photoSuggestionsAnalyzing: String { NSLocalizedString("photo_suggestions_analyzing", comment: "") }
    /// What to show for a storage location. The built-in ones (`DefaultStorageLocations`) are
    /// stored under English keys and translated here; a location the user named is shown as
    /// typed.
    public static func storageLocationName(_ location: String) -> String {
        switch location {
        case DefaultStorageLocations.fridge: return NSLocalizedString("storage_location_fridge", comment: "")
        case DefaultStorageLocations.freezer: return NSLocalizedString("storage_location_freezer", comment: "")
        case DefaultStorageLocations.pantry: return NSLocalizedString("storage_location_pantry", comment: "")
        default: return location
        }
    }

    /// How a category name is shown: one of the app's own category keys (`FoodLabels.category*`,
    /// e.g. "Dairy") translated, anything else (a name the user typed or renamed to, an Open Food
    /// Facts category) as-is. Applied to the resolved display name, so a renamed built-in shows
    /// its new name and an untouched one follows the device's language, without the translation
    /// ever being stored. Mirrors `categoryLabel` in the Android app.
    public static func categoryName(_ name: String) -> String {
        switch name {
        case FoodLabels.categoryFruit: return NSLocalizedString("category_builtin_fruit", comment: "")
        case FoodLabels.categoryVegetables: return NSLocalizedString("category_builtin_vegetables", comment: "")
        case FoodLabels.categoryBakery: return NSLocalizedString("category_builtin_bakery", comment: "")
        case FoodLabels.categoryDairy: return NSLocalizedString("category_builtin_dairy", comment: "")
        case FoodLabels.categoryProtein: return NSLocalizedString("category_builtin_meat_fish", comment: "")
        case FoodLabels.categoryDrinks: return NSLocalizedString("category_builtin_drinks", comment: "")
        case FoodLabels.categorySweets: return NSLocalizedString("category_builtin_sweets", comment: "")
        case FoodLabels.categoryPantry: return NSLocalizedString("category_builtin_pantry", comment: "")
        default: return name
        }
    }

    /// The localized name for an image-classifier suggestion, or nil when the key has no string
    /// yet - the caller should then skip the chip rather than show an empty one.
    ///
    /// `NSLocalizedString` needs a literal key for `genstrings`, so the keys are looked up
    /// against the main bundle directly here; a key with no entry returns itself, which is how
    /// a missing translation is detected rather than shown.
    public static func foodLabelName(_ key: String) -> String? {
        let resourceKey = "food_label_" + key
        let value = Bundle.main.localizedString(forKey: resourceKey, value: resourceKey, table: nil)
        return value == resourceKey ? nil : value
    }
    public static var shoppingListShareContentDescription: String { NSLocalizedString("shopping_list_share_content_description", comment: "") }
    public static var shoppingListShareDialogTitle: String { NSLocalizedString("shopping_list_share_dialog_title", comment: "") }
    public static var shoppingListShareDialogMessage: String { NSLocalizedString("shopping_list_share_dialog_message", comment: "") }
    public static var shoppingListShareAsText: String { NSLocalizedString("shopping_list_share_as_text", comment: "") }
    public static var shoppingListShareAsQr: String { NSLocalizedString("shopping_list_share_as_qr", comment: "") }
    public static var shoppingListShareQrHint: String { NSLocalizedString("shopping_list_share_qr_hint", comment: "") }
    public static var shoppingListShareQrTooLarge: String { NSLocalizedString("shopping_list_share_qr_too_large", comment: "") }
    public static var shoppingListImport: String { NSLocalizedString("shopping_list_import", comment: "") }
    public static var shoppingListImportMessage: String { NSLocalizedString("shopping_list_import_message", comment: "") }
    public static var shoppingListImportConfirm: String { NSLocalizedString("shopping_list_import_confirm", comment: "") }
    public static var shoppingListImportAllPresent: String { NSLocalizedString("shopping_list_import_all_present", comment: "") }
    public static var shoppingListImportNothing: String { NSLocalizedString("shopping_list_import_nothing", comment: "") }
    public static func shoppingListImportAdded(_ count: Int) -> String {
        String.localizedStringWithFormat(
            NSLocalizedString("shopping_list_import_added_format", comment: ""),
            count
        )
    }
    public static var shoppingListBrand: String { NSLocalizedString("shopping_list_brand", comment: "") }
    public static var shoppingListLedeEmpty: String { NSLocalizedString("shopping_list_lede_empty", comment: "") }
    public static func shoppingListLede(_ count: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("shopping_list_lede_format", comment: ""), count)
    }
    public static func shoppingListAlreadyListed(_ name: String) -> String {
        String(format: NSLocalizedString("shopping_list_already_listed", comment: ""), name)
    }
    public static func shoppingListMarkBought(_ name: String) -> String {
        String(format: NSLocalizedString("shopping_list_mark_bought", comment: ""), name)
    }
    public static var shoppingListImportPlaceholder: String { NSLocalizedString("shopping_list_import_placeholder", comment: "") }
    public static func shoppingListBarcode(_ barcode: String) -> String {
        String(format: NSLocalizedString("shopping_list_barcode_format", comment: ""), barcode)
    }
    public static var shoppingListImportLabel: String { NSLocalizedString("shopping_list_import_label", comment: "") }
    public static var shoppingListClose: String { NSLocalizedString("shopping_list_close", comment: "") }
    public static var shoppingListQrDescription: String { NSLocalizedString("shopping_list_qr_description", comment: "") }
    public static var shoppingListDragHandle: String { NSLocalizedString("shopping_list_drag_handle", comment: "") }
    public static var shoppingSectionsLabel: String { NSLocalizedString("shopping_sections_label", comment: "") }
    public static var shoppingSectionNameLabel: String { NSLocalizedString("shopping_section_name_label", comment: "") }
    public static var shoppingSectionKeep: String { NSLocalizedString("shopping_section_keep", comment: "") }
    public static var shoppingSectionDeleteConfirm: String { NSLocalizedString("shopping_section_delete_confirm", comment: "") }
    public static func shoppingSectionDeleteCount(_ count: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("shopping_section_delete_count_format", comment: ""), count)
    }
    public static var shoppingListScan: String { NSLocalizedString("shopping_list_scan", comment: "") }
    public static var shoppingListDidYouMeanTitle: String { NSLocalizedString("shopping_list_did_you_mean_title", comment: "") }
    public static var shoppingListDidYouMeanNoneOfThese: String { NSLocalizedString("shopping_list_did_you_mean_none_of_these", comment: "") }

    public static var manualEntryButton: String { NSLocalizedString("manual_entry_button", comment: "") }
    public static var cameraOffTitle: String { NSLocalizedString("camera_off_title", comment: "") }
    public static var cameraOffBody: String { NSLocalizedString("camera_off_body", comment: "") }
    public static var cameraOffOpenSettings: String { NSLocalizedString("camera_off_open_settings", comment: "") }
    public static var cameraOffAddByHand: String { NSLocalizedString("camera_off_add_by_hand", comment: "") }
    public static var scannerModePickerLabel: String { NSLocalizedString("scanner_mode_picker_label", comment: "") }
    public static var scanModeBarcode: String { NSLocalizedString("scan_mode_barcode", comment: "") }
    public static var scanModeDateOcr: String { NSLocalizedString("scan_mode_date_ocr", comment: "") }
    public static var scanModePhoto: String { NSLocalizedString("scan_mode_photo", comment: "") }
    public static var lookingUpProduct: String { NSLocalizedString("looking_up_product", comment: "") }
    public static var scanDateButton: String { NSLocalizedString("scan_date_button", comment: "") }
    public static var continueButton: String { NSLocalizedString("continue_button", comment: "") }

    public static var productNotFoundTitle: String { NSLocalizedString("product_not_found_title", comment: "") }
    public static var switchToPhotoButton: String { NSLocalizedString("switch_to_photo_button", comment: "") }

    public static var takePhotoButton: String { NSLocalizedString("take_photo_button", comment: "") }
    public static var photoCapturedTitle: String { NSLocalizedString("photo_captured_title", comment: "") }
    public static var photoCapturedMessage: String { NSLocalizedString("photo_captured_message", comment: "") }
    public static var retakePhotoButton: String { NSLocalizedString("retake_photo_button", comment: "") }
    public static var photoCaptureFailedMessage: String { NSLocalizedString("photo_capture_failed_message", comment: "") }

    public static var reticleHintBarcode: String { NSLocalizedString("reticle_hint_barcode", comment: "") }
    public static var reticleHintDateOcr: String { NSLocalizedString("reticle_hint_date_ocr", comment: "") }
    public static var reticleHintPhoto: String { NSLocalizedString("reticle_hint_photo", comment: "") }
    public static var scanModeBatch: String { NSLocalizedString("scan_mode_batch", comment: "") }
    public static var reticleHintBatch: String { NSLocalizedString("reticle_hint_batch", comment: "") }
    public static var scanModeReceipt: String { NSLocalizedString("scan_mode_receipt", comment: "") }
    public static var reticleHintReceipt: String { NSLocalizedString("reticle_hint_receipt", comment: "") }
    public static var scanReceiptButton: String { NSLocalizedString("scan_receipt_button", comment: "") }
    public static var receiptNothingFound: String { NSLocalizedString("receipt_nothing_found", comment: "") }
    public static func receiptLinesAdded(_ count: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("receipt_lines_added", comment: ""), count)
    }
    public static var batchQueueEmpty: String { NSLocalizedString("batch_queue_empty", comment: "") }
    public static func batchQueueRemove(_ name: String) -> String {
        String(format: NSLocalizedString("batch_queue_remove_content_description", comment: ""), name)
    }
    public static var batchQueueNeedsDate: String { NSLocalizedString("batch_queue_needs_date", comment: "") }
    public static var batchCommitButton: String { NSLocalizedString("batch_commit_button", comment: "") }
    public static var batchClearButton: String { NSLocalizedString("batch_clear_button", comment: "") }
    public static var duplicateDialogTitle: String { NSLocalizedString("duplicate_dialog_title", comment: "") }
    public static var duplicateDialogAddOne: String { NSLocalizedString("duplicate_dialog_add_one", comment: "") }
    public static var duplicateDialogSeparate: String { NSLocalizedString("duplicate_dialog_separate", comment: "") }
    public static func duplicateAdded(_ name: String, _ quantity: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("duplicate_added", comment: ""), name, quantity)
    }
    public static func inPantryCount(_ quantity: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("in_pantry_count", comment: ""), quantity)
    }
    public static func duplicateDialogMessage(_ nameWithQuantity: String) -> String {
        String(format: NSLocalizedString("duplicate_dialog_message", comment: ""), nameWithQuantity)
    }
    public static func batchQueueCount(_ count: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("batch_queue_count_format", comment: ""), count)
    }
    public static func batchAdded(_ count: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("batch_added_format", comment: ""), count)
    }
    public static var dateCapturedTitle: String { NSLocalizedString("date_captured_title", comment: "") }
    public static var scanAgainButton: String { NSLocalizedString("scan_again_button", comment: "") }
    public static var shoppingListScanUnsupported: String { NSLocalizedString("shopping_list_scan_unsupported", comment: "") }
    public static var batchDiscardTitle: String { NSLocalizedString("batch_discard_title", comment: "") }
    public static var batchDiscardConfirm: String { NSLocalizedString("batch_discard_confirm", comment: "") }
    public static var batchDiscardKeep: String { NSLocalizedString("batch_discard_keep", comment: "") }
    public static func batchDiscardMessage(_ count: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("batch_discard_message_format", comment: ""), count)
    }
    // Scanner (Larder)
    public static func barcodeFormat(_ barcode: String) -> String {
        String(format: NSLocalizedString("barcode_format", comment: ""), barcode)
    }
    public static var closeScanner: String { NSLocalizedString("close_scanner", comment: "") }
    public static var scanTorchOn: String { NSLocalizedString("scan_torch_on", comment: "") }
    public static var scanTorchOff: String { NSLocalizedString("scan_torch_off", comment: "") }
    public static var scanIdleResultsTitle: String { NSLocalizedString("scan_idle_results_title", comment: "") }
    public static var scanIdleResultsMessage: String { NSLocalizedString("scan_idle_results_message", comment: "") }
    public static var scanIdleDateTitle: String { NSLocalizedString("scan_idle_date_title", comment: "") }
    public static var scanIdleDateMessage: String { NSLocalizedString("scan_idle_date_message", comment: "") }
    public static var scanIdlePhotoTitle: String { NSLocalizedString("scan_idle_photo_title", comment: "") }
    public static var scanIdlePhotoMessage: String { NSLocalizedString("scan_idle_photo_message", comment: "") }
    public static var scanAllergenNote: String { NSLocalizedString("scan_allergen_note", comment: "") }
    public static var scanOfflineTitle: String { NSLocalizedString("scan_offline_title", comment: "") }
    public static var scanOfflineMessage: String { NSLocalizedString("scan_offline_message", comment: "") }
    public static var scanTryAgain: String { NSLocalizedString("scan_try_again", comment: "") }
    public static var scanContinueByHand: String { NSLocalizedString("scan_continue_by_hand", comment: "") }
    public static func scanBestBefore(_ date: String) -> String {
        String(format: NSLocalizedString("scan_best_before_format", comment: ""), date)
    }
    public static var scanDateFromBarcode: String { NSLocalizedString("scan_date_from_barcode", comment: "") }
    public static var scanDateFromPackaging: String { NSLocalizedString("scan_date_from_packaging", comment: "") }
    public static var scanUseThisDate: String { NSLocalizedString("scan_use_this_date", comment: "") }
    public static var scanReadingReceipt: String { NSLocalizedString("scan_reading_receipt", comment: "") }
    public static var receiptQueueEmpty: String { NSLocalizedString("receipt_queue_empty", comment: "") }
    public static var scanQueueCleared: String { NSLocalizedString("scan_queue_cleared", comment: "") }
    public static func batchQueueEstimated(_ date: String) -> String {
        String(format: NSLocalizedString("batch_queue_estimated_format", comment: ""), date)
    }
    public static var torchToggleButton: String { NSLocalizedString("torch_toggle_button", comment: "") }

    public static var expiresToday: String { NSLocalizedString("expires_today", comment: "") }
    public static var expiresTomorrow: String { NSLocalizedString("expires_tomorrow", comment: "") }

    public static var notifTitleToday: String { NSLocalizedString("notif_title_today", comment: "") }
    public static var notifTitleSoon: String { NSLocalizedString("notif_title_soon", comment: "") }
    public static var notifTitlePast: String { NSLocalizedString("notif_title_past", comment: "") }

    public static func expiryDateFound(_ date: String) -> String {
        String(format: NSLocalizedString("expiry_date_found_format", comment: ""), date)
    }

    public static func notifBodyToday(_ nameWithBrand: String) -> String {
        String(format: NSLocalizedString("notif_body_today_format", comment: ""), nameWithBrand)
    }

    public static func notifBodySoon(_ nameWithBrand: String, days: Int) -> String {
        let format = NSLocalizedString("notif_body_soon_format", comment: "")
        return String.localizedStringWithFormat(format, nameWithBrand, days)
    }

    public static func notifBodyPast(_ nameWithBrand: String, days: Int) -> String {
        let format = NSLocalizedString("notif_body_past_format", comment: "")
        return String.localizedStringWithFormat(format, nameWithBrand, days)
    }

    public static var notifActionRemove: String { NSLocalizedString("notif_action_remove", comment: "") }
    public static var notifActionUsed: String { NSLocalizedString("notif_action_used", comment: "") }
    public static var notifActionSnooze: String { NSLocalizedString("notif_action_snooze", comment: "") }
    public static func notifActionStillGood(_ days: Int) -> String {
        String.localizedStringWithFormat(
            NSLocalizedString("notif_action_still_good_format", comment: ""),
            days
        )
    }
    public static func notifDigestTitle(_ count: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("notif_digest_title_format", comment: ""), count)
    }
    public static func notifDigestItem(name: String, expiry: String) -> String {
        String.localizedStringWithFormat(
            NSLocalizedString("notif_digest_item_format", comment: ""),
            name,
            expiry
        )
    }
    public static func notifDigestMore(_ count: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("notif_digest_more_format", comment: ""), count)
    }
    public static var notifActionRecipeIdeas: String { NSLocalizedString("notif_action_recipe_ideas", comment: "") }

    public static var settingsSectionStores: String { NSLocalizedString("settings_section_stores", comment: "") }
    public static var settingsStoreRemindersEnabled: String { NSLocalizedString("settings_store_reminders_enabled", comment: "") }
    public static var settingsStoreRemindersDesc: String { NSLocalizedString("settings_store_reminders_desc", comment: "") }
    public static var settingsAddStorePlaceholder: String { NSLocalizedString("settings_add_store_placeholder", comment: "") }
    public static var settingsAddStore: String { NSLocalizedString("settings_add_store", comment: "") }
    public static var settingsStoreLocationFailed: String { NSLocalizedString("settings_store_location_failed", comment: "") }
    public static func storeReminderTitleNamed(_ storeLabel: String) -> String {
        String.localizedStringWithFormat(
            NSLocalizedString("store_reminder_title_named_format", comment: ""),
            storeLabel
        )
    }
    public static func storeReminderTitle(_ count: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("store_reminder_title_format", comment: ""), count)
    }
    public static func storeReminderMore(_ count: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("store_reminder_more_format", comment: ""), count)
    }

    public static var settingsTitle: String { NSLocalizedString("settings_title", comment: "") }
    public static var settingsSectionNotifications: String { NSLocalizedString("settings_section_notifications", comment: "") }
    public static var settingsNotificationsEnabled: String { NSLocalizedString("settings_notifications_enabled", comment: "") }
    public static var settingsLeadDaysLabel: String { NSLocalizedString("settings_lead_days_label", comment: "") }
    public static var settingsTimeLabel: String { NSLocalizedString("settings_time_label", comment: "") }
    public static var settingsReminderStyleLabel: String { NSLocalizedString("settings_reminder_style_label", comment: "") }
    public static var settingsReminderStyleEachItem: String { NSLocalizedString("settings_reminder_style_each_item", comment: "") }
    public static var settingsReminderStyleSummary: String { NSLocalizedString("settings_reminder_style_summary", comment: "") }
    public static var settingsReminderStyleBoth: String { NSLocalizedString("settings_reminder_style_both", comment: "") }
    /// "3 days before" / "за 3 дня" - the lead-time value, unit included.
    public static func settingsLeadDaysPhrase(_ days: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("settings_lead_days_phrase", comment: ""), days)
    }
    public static func settingsReminderPreviewEachItem(leadDays: Int, time: String) -> String {
        String(format: NSLocalizedString("settings_reminder_preview_each_item", comment: ""), settingsLeadDaysPhrase(leadDays), time)
    }
    public static func settingsReminderPreviewSummaryDaily(time: String) -> String {
        String(format: NSLocalizedString("settings_reminder_preview_summary_daily", comment: ""), time)
    }
    public static func settingsReminderPreviewSummaryWeekly(weekday: String, time: String) -> String {
        String(format: NSLocalizedString("settings_reminder_preview_summary_weekly", comment: ""), weekday, time)
    }
    public static func settingsReminderPreviewSummaryEvery(days: Int, time: String) -> String {
        let phrase = String.localizedStringWithFormat(NSLocalizedString("settings_digest_every_days_phrase", comment: ""), days)
        return String(format: NSLocalizedString("settings_reminder_preview_summary_every", comment: ""), phrase, time)
    }
    public static func settingsDigestEveryDaysChip(_ days: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("settings_digest_every_days_chip", comment: ""), days)
    }
    public static var settingsReminderPreviewNone: String { NSLocalizedString("settings_reminder_preview_none", comment: "") }
    public static var settingsNotificationsBlocked: String { NSLocalizedString("settings_notifications_blocked", comment: "") }
    public static var settingsNotificationsOpenSettings: String { NSLocalizedString("settings_notifications_open_settings", comment: "") }
    public static var settingsDigestFrequencyLabel: String { NSLocalizedString("settings_digest_frequency_label", comment: "") }
    public static var settingsDigestDaily: String { NSLocalizedString("settings_digest_daily", comment: "") }
    public static var settingsDigestWeekly: String { NSLocalizedString("settings_digest_weekly", comment: "") }
    public static var settingsDigestWeekdayLabel: String { NSLocalizedString("settings_digest_weekday_label", comment: "") }

    public static var settingsSectionExpiringSoon: String { NSLocalizedString("settings_section_expiring_soon", comment: "") }
    public static var settingsExpiringSoonDesc: String { NSLocalizedString("settings_expiring_soon_desc", comment: "") }

    public static var settingsSectionLocations: String { NSLocalizedString("settings_section_locations", comment: "") }
    public static var settingsLocationsDesc: String { NSLocalizedString("settings_locations_desc", comment: "") }
    public static var settingsRememberLastLocation: String { NSLocalizedString("settings_remember_last_location", comment: "") }
    public static var settingsRememberLastLocationDesc: String { NSLocalizedString("settings_remember_last_location_desc", comment: "") }
    public static var settingsAddLocationPlaceholder: String { NSLocalizedString("settings_add_location_placeholder", comment: "") }
    public static var settingsAddLocation: String { NSLocalizedString("settings_add_location", comment: "") }
    public static var settingsSetDefaultLocation: String { NSLocalizedString("settings_set_default_location", comment: "") }

    public static var settingsSectionCategories: String { NSLocalizedString("settings_section_categories", comment: "") }
    public static var settingsCategoriesDesc: String { NSLocalizedString("settings_categories_desc", comment: "") }
    public static var settingsManageCategoriesButton: String { NSLocalizedString("settings_manage_categories_button", comment: "") }

    public static var categorySettingsTitle: String { NSLocalizedString("category_settings_title", comment: "") }
    public static var categorySettingsDescription: String { NSLocalizedString("category_settings_description", comment: "") }
    public static var categorySettingsToggleAll: String { NSLocalizedString("category_settings_toggle_all", comment: "") }
    public static var categorySettingsEmpty: String { NSLocalizedString("category_settings_empty", comment: "") }
    public static var categorySettingsRenameTitle: String { NSLocalizedString("category_settings_rename_title", comment: "") }
    public static var categorySettingsRenameContentDescription: String { NSLocalizedString("category_settings_rename_content_description", comment: "") }
    public static var categorySettingsShelfLifeNone: String { NSLocalizedString("category_settings_shelf_life_none", comment: "") }
    public static var categorySettingsShelfLifeContentDescription: String { NSLocalizedString("category_settings_shelf_life_content_description", comment: "") }
    public static var categorySettingsShelfLifeDialogTitle: String { NSLocalizedString("category_settings_shelf_life_dialog_title", comment: "") }
    public static var categorySettingsShelfLifeDialogMessage: String { NSLocalizedString("category_settings_shelf_life_dialog_message", comment: "") }
    public static var categorySettingsShelfLifeFieldLabel: String { NSLocalizedString("category_settings_shelf_life_field_label", comment: "") }
    public static var categorySettingsShelfLifeClear: String { NSLocalizedString("category_settings_shelf_life_clear", comment: "") }
    public static func categorySettingsShelfLifeDaysFormat(_ days: Int) -> String {
        String.localizedStringWithFormat(
            NSLocalizedString("category_settings_shelf_life_days_format", comment: ""),
            days
        )
    }
    public static func categorySettingsOriginalNameFormat(_ name: String) -> String {
        String(format: NSLocalizedString("category_settings_original_name_format", comment: ""), name)
    }

    public static var settingsSectionAppearance: String { NSLocalizedString("settings_section_appearance", comment: "") }
    public static var settingsThemeSystem: String { NSLocalizedString("settings_theme_system", comment: "") }
    public static var settingsThemeLight: String { NSLocalizedString("settings_theme_light", comment: "") }
    public static var settingsThemeDark: String { NSLocalizedString("settings_theme_dark", comment: "") }

    public static var settingsSectionData: String { NSLocalizedString("settings_section_data", comment: "") }
    public static var settingsClearDataButton: String { NSLocalizedString("settings_clear_data_button", comment: "") }
    public static var settingsBackupDesc: String { NSLocalizedString("settings_backup_desc", comment: "") }
    public static var settingsExportBackupButton: String { NSLocalizedString("settings_export_backup_button", comment: "") }
    public static var settingsImportBackupButton: String { NSLocalizedString("settings_import_backup_button", comment: "") }
    public static var restoreModeMessage: String { NSLocalizedString("restore_mode_message", comment: "") }
    public static var restoreModeMergeTitle: String { NSLocalizedString("restore_mode_merge_title", comment: "") }
    public static var restoreModeReplaceTitle: String { NSLocalizedString("restore_mode_replace_title", comment: "") }
    public static var backupExportSuccess: String { NSLocalizedString("backup_export_success", comment: "") }
    public static var backupExportFailed: String { NSLocalizedString("backup_export_failed", comment: "") }
    public static var backupImportMalformed: String { NSLocalizedString("backup_import_malformed", comment: "") }
    public static var backupImportUnsupported: String { NSLocalizedString("backup_import_unsupported", comment: "") }
    public static var backupImportEmpty: String { NSLocalizedString("backup_import_empty", comment: "") }
    public static func backupImportSuccess(_ count: Int) -> String {
        String.localizedStringWithFormat(
            NSLocalizedString("backup_import_success_format", comment: ""),
            count
        )
    }
    public static var clearDataDialogTitle: String { NSLocalizedString("clear_data_dialog_title", comment: "") }
    public static var clearDataDialogMessage: String { NSLocalizedString("clear_data_dialog_message", comment: "") }
    public static var clearDataConfirm: String { NSLocalizedString("clear_data_confirm", comment: "") }

    public static var widgetExpiringTitle: String { NSLocalizedString("widget_expiring_title", comment: "") }
    public static var widgetExpiringDescription: String { NSLocalizedString("widget_expiring_description", comment: "") }
    public static var widgetExpiringEmpty: String { NSLocalizedString("widget_expiring_empty", comment: "") }
    public static var widgetShoppingListTitle: String { NSLocalizedString("widget_shopping_list_title", comment: "") }
    public static var widgetShoppingListDescription: String { NSLocalizedString("widget_shopping_list_description", comment: "") }
    public static var widgetShoppingListEmpty: String { NSLocalizedString("widget_shopping_list_empty", comment: "") }
    public static var widgetActionUsed: String { NSLocalizedString("widget_action_used", comment: "") }
    public static var widgetActionBought: String { NSLocalizedString("widget_action_bought", comment: "") }
    public static var widgetOverviewTitle: String { NSLocalizedString("widget_overview_title", comment: "") }
    public static var widgetOverviewDescription: String { NSLocalizedString("widget_overview_description", comment: "") }

    /// Mirrors PantryItem.daysRemainingFormatted, but resolved through the plural
    /// rules in Localizable.stringsdict instead of hardcoded English text.
    public static func daysRemaining(_ days: Int) -> String {
        if days < 0 {
            let format = NSLocalizedString("expired_days_ago_format", comment: "")
            return String.localizedStringWithFormat(format, -days)
        } else if days == 0 {
            return expiresToday
        } else if days == 1 {
            return expiresTomorrow
        } else {
            let format = NSLocalizedString("expires_in_days_format", comment: "")
            return String.localizedStringWithFormat(format, days)
        }
    }

    // MARK: Larder settings
    public static var settingsGroupReminders: String { NSLocalizedString("settings_group_reminders", comment: "") }
    public static var settingsGroupKitchen: String { NSLocalizedString("settings_group_kitchen", comment: "") }
    public static var settingsNotificationsBlockedTitle: String { NSLocalizedString("settings_notifications_blocked_title", comment: "") }
    public static var settingsOff: String { NSLocalizedString("settings_off", comment: "") }
    public static var settingsAllergensNone: String { NSLocalizedString("settings_allergens_none", comment: "") }
    public static var settingsAddAllergenLabel: String { NSLocalizedString("settings_add_allergen_label", comment: "") }
    public static var settingsDataSummary: String { NSLocalizedString("settings_data_summary", comment: "") }
    public static var settingsLocationDefault: String { NSLocalizedString("settings_location_default", comment: "") }
    public static func settingsLocationTaken(_ value1: String) -> String {
        String(format: NSLocalizedString("settings_location_taken", comment: ""), value1)
    }
    public static func settingsRenameLocationFormat(_ value1: String) -> String {
        String(format: NSLocalizedString("settings_rename_location_format", comment: ""), value1)
    }
    public static func settingsDeleteLocationFormat(_ value1: String) -> String {
        String(format: NSLocalizedString("settings_delete_location_format", comment: ""), value1)
    }
    public static func settingsSetDefaultLocationFormat(_ value1: String) -> String {
        String(format: NSLocalizedString("settings_set_default_location_format", comment: ""), value1)
    }
    public static func settingsRemoveFormat(_ value1: String) -> String {
        String(format: NSLocalizedString("settings_remove_format", comment: ""), value1)
    }
    public static var settingsTryAgain: String { NSLocalizedString("settings_try_again", comment: "") }
    public static var restoreModeCancel: String { NSLocalizedString("restore_mode_cancel", comment: "") }
    public static var clearDataKeep: String { NSLocalizedString("clear_data_keep", comment: "") }
    public static var categorySettingsEmptyTitle: String { NSLocalizedString("category_settings_empty_title", comment: "") }
    public static var categorySettingsScan: String { NSLocalizedString("category_settings_scan", comment: "") }
    public static var categorySettingsMoveUp: String { NSLocalizedString("category_settings_move_up", comment: "") }
    public static var categorySettingsMoveDown: String { NSLocalizedString("category_settings_move_down", comment: "") }
    public static var categorySettingsMoveDone: String { NSLocalizedString("category_settings_move_done", comment: "") }
    public static var categorySettingsRenameFieldLabel: String { NSLocalizedString("category_settings_rename_field_label", comment: "") }
    public static var categorySettingsRenameConfirm: String { NSLocalizedString("category_settings_rename_confirm", comment: "") }
    public static func categorySettingsRenameFormat(_ value1: String) -> String {
        String(format: NSLocalizedString("category_settings_rename_format", comment: ""), value1)
    }
    public static func categorySettingsDragHandleFormat(_ value1: String) -> String {
        String(format: NSLocalizedString("category_settings_drag_handle_format", comment: ""), value1)
    }
    public static func categorySettingsShowFormat(_ value1: String) -> String {
        String(format: NSLocalizedString("category_settings_show_format", comment: ""), value1)
    }
    public static func categorySettingsShelfLifeFormat(_ value1: String, _ value2: String) -> String {
        String(format: NSLocalizedString("category_settings_shelf_life_format", comment: ""), value1, value2)
    }
    public static func categorySettingsShelfLifeTitleFormat(_ value1: String) -> String {
        String(format: NSLocalizedString("category_settings_shelf_life_title_format", comment: ""), value1)
    }
    public static var categorySettingsShelfLifeRange: String { NSLocalizedString("category_settings_shelf_life_range", comment: "") }
    public static var categorySettingsShelfLifeError: String { NSLocalizedString("category_settings_shelf_life_error", comment: "") }
    public static var categorySettingsShelfLifeSave: String { NSLocalizedString("category_settings_shelf_life_save", comment: "") }
    public static func clearDataDialogCount(_ count: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("clear_data_dialog_count", comment: ""), count)
    }
}
