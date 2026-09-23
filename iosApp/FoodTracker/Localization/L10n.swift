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
    public static var deleteSelectedDialogTitle: String { NSLocalizedString("delete_selected_dialog_title", comment: "") }
    public static func itemRemovedToast(_ name: String) -> String {
        String(format: NSLocalizedString("item_removed_toast_format", comment: ""), name)
    }
    public static func itemAddedToShoppingListToast(_ name: String) -> String {
        String(format: NSLocalizedString("item_added_to_shopping_list_toast_format", comment: ""), name)
    }
    public static var undoAction: String { NSLocalizedString("undo_action", comment: "") }

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

    public static var calendarButton: String { NSLocalizedString("calendar_button", comment: "") }
    public static var calendarTitle: String { NSLocalizedString("calendar_title", comment: "") }
    public static var calendarNoItemsForDay: String { NSLocalizedString("calendar_no_items_for_day", comment: "") }
    public static var calendarAllFresh: String { NSLocalizedString("calendar_summary_all_fresh", comment: "") }
    public static func calendarSummaryExpired(_ count: Int) -> String {
        String(format: NSLocalizedString("calendar_summary_expired", comment: ""), count)
    }
    public static func calendarSummaryExpiringSoon(_ count: Int) -> String {
        String(format: NSLocalizedString("calendar_summary_expiring_soon", comment: ""), count)
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
    public static var saveButton: String { NSLocalizedString("save_button", comment: "") }

    public static var essentialItemLabel: String { NSLocalizedString("essential_item_label", comment: "") }
    public static var essentialItemDescription: String { NSLocalizedString("essential_item_description", comment: "") }

    public static var shoppingListButton: String { NSLocalizedString("shopping_list_button", comment: "") }
    public static var shoppingListTitle: String { NSLocalizedString("shopping_list_title", comment: "") }
    public static var shoppingListAddPlaceholder: String { NSLocalizedString("shopping_list_add_placeholder", comment: "") }
    public static var shoppingListAddButton: String { NSLocalizedString("shopping_list_add_button", comment: "") }
    public static var shoppingListEmptyTitle: String { NSLocalizedString("shopping_list_empty_title", comment: "") }
    public static var shoppingListEmptySubtitle: String { NSLocalizedString("shopping_list_empty_subtitle", comment: "") }
    public static var shoppingListCheckOff: String { NSLocalizedString("shopping_list_check_off", comment: "") }
    public static var shoppingListUncategorized: String { NSLocalizedString("shopping_list_uncategorized", comment: "") }
    public static var photoSuggestionsTitle: String { NSLocalizedString("photo_suggestions_title", comment: "") }
    public static var photoSuggestionsAnalyzing: String { NSLocalizedString("photo_suggestions_analyzing", comment: "") }
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
    public static var shoppingListScan: String { NSLocalizedString("shopping_list_scan", comment: "") }
    public static var shoppingListDidYouMeanTitle: String { NSLocalizedString("shopping_list_did_you_mean_title", comment: "") }
    public static var shoppingListDidYouMeanNoneOfThese: String { NSLocalizedString("shopping_list_did_you_mean_none_of_these", comment: "") }

    public static var manualEntryButton: String { NSLocalizedString("manual_entry_button", comment: "") }
    public static var scannerModePickerLabel: String { NSLocalizedString("scanner_mode_picker_label", comment: "") }
    public static var scanModeBarcode: String { NSLocalizedString("scan_mode_barcode", comment: "") }
    public static var scanModeDateOcr: String { NSLocalizedString("scan_mode_date_ocr", comment: "") }
    public static var scanModePhoto: String { NSLocalizedString("scan_mode_photo", comment: "") }
    public static var lookingUpProduct: String { NSLocalizedString("looking_up_product", comment: "") }
    public static var scanDateButton: String { NSLocalizedString("scan_date_button", comment: "") }
    public static var continueButton: String { NSLocalizedString("continue_button", comment: "") }

    public static var productNotFoundTitle: String { NSLocalizedString("product_not_found_title", comment: "") }
    public static var productNotFoundMessage: String { NSLocalizedString("product_not_found_message", comment: "") }
    public static var switchToPhotoButton: String { NSLocalizedString("switch_to_photo_button", comment: "") }

    public static var takePhotoButton: String { NSLocalizedString("take_photo_button", comment: "") }
    public static var photoCapturedTitle: String { NSLocalizedString("photo_captured_title", comment: "") }
    public static var photoCapturedMessage: String { NSLocalizedString("photo_captured_message", comment: "") }
    public static var retakePhotoButton: String { NSLocalizedString("retake_photo_button", comment: "") }
    public static var photoCaptureFailedMessage: String { NSLocalizedString("photo_capture_failed_message", comment: "") }

    public static var reticleHintBarcode: String { NSLocalizedString("reticle_hint_barcode", comment: "") }
    public static var reticleHintDateOcr: String { NSLocalizedString("reticle_hint_date_ocr", comment: "") }
    public static var reticleHintPhoto: String { NSLocalizedString("reticle_hint_photo", comment: "") }
    public static var torchToggleButton: String { NSLocalizedString("torch_toggle_button", comment: "") }

    public static var expiresToday: String { NSLocalizedString("expires_today", comment: "") }
    public static var expiresTomorrow: String { NSLocalizedString("expires_tomorrow", comment: "") }

    public static var notifTitleToday: String { NSLocalizedString("notif_title_today", comment: "") }
    public static var notifTitleSoon: String { NSLocalizedString("notif_title_soon", comment: "") }

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

    public static var notifActionRemove: String { NSLocalizedString("notif_action_remove", comment: "") }
    public static var notifActionUsed: String { NSLocalizedString("notif_action_used", comment: "") }
    public static var notifActionSnooze: String { NSLocalizedString("notif_action_snooze", comment: "") }
    public static func notifActionStillGood(_ days: Int) -> String {
        String.localizedStringWithFormat(
            NSLocalizedString("notif_action_still_good_format", comment: ""),
            days
        )
    }
    public static var notifDigestTitle: String { NSLocalizedString("notif_digest_title", comment: "") }
    public static func notifDigestBody(_ count: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("notif_digest_body_format", comment: ""), count)
    }

    public static var settingsTitle: String { NSLocalizedString("settings_title", comment: "") }
    public static var settingsSectionNotifications: String { NSLocalizedString("settings_section_notifications", comment: "") }
    public static var settingsNotificationsEnabled: String { NSLocalizedString("settings_notifications_enabled", comment: "") }
    public static var settingsLeadDaysLabel: String { NSLocalizedString("settings_lead_days_label", comment: "") }
    public static var settingsTimeLabel: String { NSLocalizedString("settings_time_label", comment: "") }

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
}
