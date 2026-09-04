import Foundation

public enum L10n {
    public static var appName: String { NSLocalizedString("app_name", comment: "") }

    public static var metricExpired: String { NSLocalizedString("metric_expired", comment: "") }
    public static var metricExpiringSoon: String { NSLocalizedString("metric_expiring_soon", comment: "") }
    public static var metricFresh: String { NSLocalizedString("metric_fresh", comment: "") }

    public static var locationFilterAll: String { NSLocalizedString("location_filter_all", comment: "") }

    public static var statusFilterAll: String { NSLocalizedString("status_filter_all", comment: "") }
    public static var statusFilterExpiringSoon: String { NSLocalizedString("status_filter_expiring_soon", comment: "") }
    public static var statusFilterExpired: String { NSLocalizedString("status_filter_expired", comment: "") }
    public static var statusFilterFresh: String { NSLocalizedString("status_filter_fresh", comment: "") }

    public static var emptyStateTitle: String { NSLocalizedString("empty_state_title", comment: "") }
    public static var emptyStateSubtitle: String { NSLocalizedString("empty_state_subtitle", comment: "") }

    public static var swipeDelete: String { NSLocalizedString("swipe_delete", comment: "") }

    public static var exitSelectionButton: String { NSLocalizedString("exit_selection_button", comment: "") }
    public static var selectItemsButton: String { NSLocalizedString("select_items_button", comment: "") }
    public static var moreOptionsButton: String { NSLocalizedString("more_options_button", comment: "") }
    public static var actionGetRecipeIdeas: String { NSLocalizedString("action_get_recipe_ideas", comment: "") }
    public static var recipePromptIntro: String { NSLocalizedString("recipe_prompt_intro", comment: "") }

    public static func selectionCount(_ count: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("selection_count_format", comment: ""), count)
    }

    public static func recipePromptItem(name: String, expiry: String) -> String {
        String(format: NSLocalizedString("recipe_prompt_item_format", comment: ""), name, expiry)
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

    public static var sectionProductDetails: String { NSLocalizedString("section_product_details", comment: "") }
    public static var productNameLabel: String { NSLocalizedString("product_name_label", comment: "") }
    public static var brandLabel: String { NSLocalizedString("brand_label", comment: "") }
    public static var barcodeWord: String { NSLocalizedString("barcode_word", comment: "") }

    public static var sectionExpirationDate: String { NSLocalizedString("section_expiration_date", comment: "") }
    public static var expiryPickerLabel: String { NSLocalizedString("expiry_picker_label", comment: "") }
    public static var quickDate3Days: String { NSLocalizedString("quick_date_3_days", comment: "") }
    public static var quickDate1Week: String { NSLocalizedString("quick_date_1_week", comment: "") }
    public static var quickDate2Weeks: String { NSLocalizedString("quick_date_2_weeks", comment: "") }
    public static var quickDate1Month: String { NSLocalizedString("quick_date_1_month", comment: "") }

    public static var sectionStorage: String { NSLocalizedString("section_storage", comment: "") }
    public static var locationPickerLabel: String { NSLocalizedString("location_picker_label", comment: "") }

    public static var sectionNotes: String { NSLocalizedString("section_notes", comment: "") }
    public static var notesFieldPlaceholder: String { NSLocalizedString("notes_field_placeholder", comment: "") }

    public static var editItemTitle: String { NSLocalizedString("edit_item_title", comment: "") }
    public static var addItemTitle: String { NSLocalizedString("add_item_title", comment: "") }
    public static var cancelButton: String { NSLocalizedString("cancel_button", comment: "") }
    public static var saveButton: String { NSLocalizedString("save_button", comment: "") }

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

    public static var settingsTitle: String { NSLocalizedString("settings_title", comment: "") }
    public static var settingsSectionNotifications: String { NSLocalizedString("settings_section_notifications", comment: "") }
    public static var settingsNotificationsEnabled: String { NSLocalizedString("settings_notifications_enabled", comment: "") }
    public static var settingsLeadDaysLabel: String { NSLocalizedString("settings_lead_days_label", comment: "") }
    public static var settingsTimeLabel: String { NSLocalizedString("settings_time_label", comment: "") }

    public static var settingsSectionExpiringSoon: String { NSLocalizedString("settings_section_expiring_soon", comment: "") }
    public static var settingsExpiringSoonDesc: String { NSLocalizedString("settings_expiring_soon_desc", comment: "") }

    public static var settingsSectionLocations: String { NSLocalizedString("settings_section_locations", comment: "") }
    public static var settingsLocationsDesc: String { NSLocalizedString("settings_locations_desc", comment: "") }
    public static var settingsAddLocationPlaceholder: String { NSLocalizedString("settings_add_location_placeholder", comment: "") }
    public static var settingsAddLocation: String { NSLocalizedString("settings_add_location", comment: "") }
    public static var settingsSetDefaultLocation: String { NSLocalizedString("settings_set_default_location", comment: "") }

    public static var settingsSectionAppearance: String { NSLocalizedString("settings_section_appearance", comment: "") }
    public static var settingsThemeSystem: String { NSLocalizedString("settings_theme_system", comment: "") }
    public static var settingsThemeLight: String { NSLocalizedString("settings_theme_light", comment: "") }
    public static var settingsThemeDark: String { NSLocalizedString("settings_theme_dark", comment: "") }

    public static var settingsSectionData: String { NSLocalizedString("settings_section_data", comment: "") }
    public static var settingsClearDataButton: String { NSLocalizedString("settings_clear_data_button", comment: "") }
    public static var clearDataDialogTitle: String { NSLocalizedString("clear_data_dialog_title", comment: "") }
    public static var clearDataDialogMessage: String { NSLocalizedString("clear_data_dialog_message", comment: "") }
    public static var clearDataConfirm: String { NSLocalizedString("clear_data_confirm", comment: "") }

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
