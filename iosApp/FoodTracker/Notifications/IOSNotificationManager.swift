import Foundation
import UserNotifications
import SwiftData
import WidgetKit

@MainActor
public class IOSNotificationManager: NSObject, UNUserNotificationCenterDelegate {
    public static let shared = IOSNotificationManager()

    // nonisolated: read from the nonisolated UNUserNotificationCenterDelegate callback below,
    // which can't access MainActor-isolated static state (an error under Swift 6 strict
    // concurrency, only a warning today).
    private nonisolated static let digestIdentifier = "com.foodtracker.digest"
    private nonisolated static let expiryCategoryIdentifier = "EXPIRY_CATEGORY"
    private nonisolated static let digestCategoryIdentifier = "DIGEST_CATEGORY"
    private nonisolated static let recipeActionIdentifier = "RECIPE_ACTION"
    private nonisolated static let removeActionIdentifier = "REMOVE_ACTION"
    private nonisolated static let usedActionIdentifier = "USED_ACTION"
    private nonisolated static let stillGoodActionIdentifier = "STILL_GOOD_ACTION"
    private nonisolated static let snoozeActionIdentifier = "SNOOZE_ACTION"

    /// How far "Still good" pushes the expiry date out. Small on purpose: this is a nudge from
    /// someone looking at the food, not a reassessment of its shelf life. Matches Android's
    /// `ExpiryNotificationReceiver.STILL_GOOD_DAYS`.
    public nonisolated static let stillGoodDays = 3

    private override init() {
        super.init()
    }

    public func requestAuthorization() {
        UNUserNotificationCenter.current().delegate = self

        // These cover what a user actually does at the moment the reminder fires. Until now
        // the only choices were "Remove" or open the app, which fits none of the common
        // outcomes: the food was eaten, it's still fine, or it's not a today problem.
        let usedAction = UNNotificationAction(
            identifier: Self.usedActionIdentifier,
            title: L10n.notifActionUsed,
            options: []
        )
        let stillGoodAction = UNNotificationAction(
            identifier: Self.stillGoodActionIdentifier,
            title: L10n.notifActionStillGood(Self.stillGoodDays),
            options: []
        )
        let snoozeAction = UNNotificationAction(
            identifier: Self.snoozeActionIdentifier,
            title: L10n.notifActionSnooze,
            options: []
        )
        let removeAction = UNNotificationAction(
            identifier: Self.removeActionIdentifier,
            title: L10n.notifActionRemove,
            options: [.destructive]
        )
        let expiryCategory = UNNotificationCategory(
            identifier: Self.expiryCategoryIdentifier,
            actions: [usedAction, stillGoodAction, snoozeAction, removeAction],
            intentIdentifiers: [],
            options: []
        )
        // The digest names the items rather than only counting them, so it can offer to turn
        // exactly those into a recipe prompt without the user opening the app first.
        let recipeAction = UNNotificationAction(
            identifier: Self.recipeActionIdentifier,
            title: L10n.notifActionRecipeIdeas,
            options: [.foreground]
        )
        let digestCategory = UNNotificationCategory(
            identifier: Self.digestCategoryIdentifier,
            actions: [recipeAction],
            intentIdentifiers: [],
            options: []
        )
        UNUserNotificationCenter.current().setNotificationCategories([expiryCategory, digestCategory])

        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) { granted, error in
            if let error = error {
                print("Notification permission error: \(error.localizedDescription)")
            }
        }
    }

    public func scheduleNotification(for item: PantryItem) {
        let notificationSettings = SettingsStore.shared.settings.notifications
        guard notificationSettings.enabled else { return }

        // If the item already expired before today, do not schedule
        guard item.daysRemaining >= 0 else { return }

        let content = UNMutableNotificationContent()
        let brandSuffix = (item.brand?.isEmpty == false) ? " (\(item.brand!))" : ""

        // If the item expires today, the lead-day reminder window has already passed
        // (e.g. it was added the same day it expires) - fall back to an immediate "today"
        // notification instead of silently missing the warning.
        let nameWithBrand = "\(item.name)\(brandSuffix)"
        if item.daysRemaining == 0 {
            content.title = L10n.notifTitleToday
            content.body = L10n.notifBodyToday(nameWithBrand)
        } else {
            content.title = L10n.notifTitleSoon
            content.body = L10n.notifBodySoon(nameWithBrand, days: item.daysRemaining)
        }
        content.sound = .default
        content.categoryIdentifier = Self.expiryCategoryIdentifier
        content.userInfo = ["itemId": item.id]

        let calendar = Calendar.current
        guard let reminderDay = calendar.date(byAdding: .day, value: -notificationSettings.leadDays, to: item.expiryDate) else { return }

        var triggerComponents = calendar.dateComponents([.year, .month, .day], from: reminderDay)
        triggerComponents.hour = notificationSettings.hour
        triggerComponents.minute = notificationSettings.minute
        triggerComponents.second = 0

        // UNCalendarNotificationTrigger never fires for a date/time already in the past, so if
        // the 9 AM reminder slot has already gone by, fire almost immediately instead.
        let trigger: UNNotificationTrigger
        if let triggerDate = calendar.date(from: triggerComponents), triggerDate > Date() {
            trigger = UNCalendarNotificationTrigger(dateMatching: triggerComponents, repeats: false)
        } else {
            trigger = UNTimeIntervalNotificationTrigger(timeInterval: 5, repeats: false)
        }

        let request = UNNotificationRequest(identifier: item.id, content: content, trigger: trigger)

        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                print("Failed to schedule notification for \(item.name): \(error.localizedDescription)")
            }
        }
    }

    public func cancelNotification(for itemId: String) {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [itemId])
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [itemId])
    }

    public func cancelAll() {
        UNUserNotificationCenter.current().removeAllPendingNotificationRequests()
        UNUserNotificationCenter.current().removeAllDeliveredNotifications()
    }

    /// Cancels every item's pending notification and re-schedules it against the current
    /// settings. Used after a notification-settings change (enabled/lead time/time of day)
    /// so already-added items pick up the new configuration.
    public func rescheduleAll(items: [PantryItem]) {
        items.forEach { cancelNotification(for: $0.id) }
        items.forEach { scheduleNotification(for: $0) }
    }

    /// How far ahead the digest looks, and how many items it names before collapsing the rest
    /// into "+N more". Three keeps the collapsed notification readable; the rest are still
    /// included in the recipe prompt the action builds. Mirrors Android's
    /// `DigestNotificationReceiver`.
    private nonisolated static let digestWindowDays = 7
    private nonisolated static let namedItemCount = 3

    /// (Re)schedules the once-daily digest for the next occurrence of the configured reminder
    /// time, naming the most urgent of the given items as of right now.
    ///
    /// Unlike Android's `DigestNotificationReceiver` (a `BroadcastReceiver` that recomputes the
    /// count fresh at the moment the alarm fires), a `UNNotificationRequest`'s content is fixed
    /// at scheduling time — iOS has no equivalent hook to recompute content exactly at fire
    /// time for a local notification. So this is re-scheduled here, from every call site that
    /// changes pantry data (`NativePantryViewModel.saveItem`/`deleteItem`/`decrementQuantity`)
    /// plus once at app launch, rather than relying on `repeats: true` with static content.
    /// The trade-off: if the app is never opened for more than a day, the last-scheduled
    /// digest still fires (with day-old contents) but won't automatically roll forward to the
    /// day after that until the app runs again — acceptable for a local-only app with no
    /// background refresh mechanism, since opening the app is how it gets corrected anyway.
    /// Naming items rather than counting them makes a stale digest more visibly stale, which
    /// is a fair trade for a notification the user can otherwise do nothing with; the recipe
    /// action re-resolves the ids against live data, so it never acts on a stale name.
    public func scheduleDigestNotification(items: [PantryItem]) {
        let notificationSettings = SettingsStore.shared.settings.notifications
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [Self.digestIdentifier])
        guard notificationSettings.enabled else { return }

        let expiringThisWeek = items
            .filter { $0.daysRemaining >= 0 && $0.daysRemaining <= Self.digestWindowDays }
            .sorted { $0.daysRemaining < $1.daysRemaining }
        guard !expiringThisWeek.isEmpty else { return }

        // Naming the items rather than only counting them is the point of the digest: "3 items
        // expiring this week" tells the user nothing they can act on without opening the app.
        let named = expiringThisWeek.prefix(Self.namedItemCount)
        var body = named
            .map { L10n.notifDigestItem(name: $0.name, expiry: $0.daysRemainingFormatted) }
            .joined(separator: "\n")
        let remaining = expiringThisWeek.count - named.count
        if remaining > 0 {
            body += "\n" + L10n.notifDigestMore(remaining)
        }

        let content = UNMutableNotificationContent()
        content.title = L10n.notifDigestTitle(expiringThisWeek.count)
        content.body = body
        content.sound = .default
        content.categoryIdentifier = Self.digestCategoryIdentifier
        content.userInfo = ["digestItemIds": expiringThisWeek.map { $0.id }]

        var timeComponents = DateComponents()
        timeComponents.hour = notificationSettings.hour
        timeComponents.minute = notificationSettings.minute
        timeComponents.second = 0

        let calendar = Calendar.current
        guard let nextFireDate = calendar.nextDate(after: Date(), matching: timeComponents, matchingPolicy: .nextTime, direction: .forward) else { return }
        let fireComponents = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: nextFireDate)

        let trigger = UNCalendarNotificationTrigger(dateMatching: fireComponents, repeats: false)
        let request = UNNotificationRequest(identifier: Self.digestIdentifier, content: content, trigger: trigger)
        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                print("Failed to schedule digest notification: \(error.localizedDescription)")
            }
        }
    }

    // MARK: - UNUserNotificationCenterDelegate

    public nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let userInfo = response.notification.request.content.userInfo
        let action = response.actionIdentifier

        // The digest carries a list of ids rather than a single `itemId`, so it is handled
        // before the per-item guard below — that guard used to return first, which would leave
        // the digest's action dead.
        if action == Self.recipeActionIdentifier,
           let digestItemIds = userInfo["digestItemIds"] as? [String] {
            Task { @MainActor in
                IOSNotificationManager.shareRecipePrompt(for: digestItemIds)
                completionHandler()
            }
            return
        }

        guard let itemId = userInfo["itemId"] as? String else {
            completionHandler()
            return
        }
        // All four act straight on SwiftData (see `PantryDirectActions`) rather than through
        // NativePantryViewModel: an action can fire while no view (and so no view model
        // instance) exists yet, e.g. right after a cold launch from tapping it.
        Task { @MainActor in
            switch action {
            case Self.removeActionIdentifier:
                PantryDirectActions.deleteItem(itemId)
            case Self.usedActionIdentifier:
                PantryDirectActions.useOne(itemId)
            case Self.stillGoodActionIdentifier:
                PantryDirectActions.extendExpiry(itemId, byDays: Self.stillGoodDays)
            case Self.snoozeActionIdentifier:
                IOSNotificationManager.shared.snoozeUntilTomorrow(itemId: itemId)
            default:
                break
            }
            completionHandler()
        }
    }

    /// Builds a recipe prompt for the digest's items and hands it to the share sheet, via
    /// `AppRouter` — a notification response has no view to present from itself.
    ///
    /// Reads the items back out of SwiftData rather than trusting names carried in the
    /// notification: its content was fixed when it was scheduled (see
    /// `scheduleDigestNotification`), so anything eaten since simply drops out of the prompt.
    /// Nothing is presented when none of the ids still resolve.
    ///
    /// Compiled out of the widget extension, which shares this file for its direct-SwiftData
    /// helpers but has no `AppRouter` (that is main-app-only, by design) and never handles a
    /// notification response in the first place.
    @MainActor
    private static func shareRecipePrompt(for itemIds: [String]) {
        #if !WIDGET_EXTENSION
        let context = ModelContext(NativePantryViewModel.sharedModelContainer)
        let wanted = Set(itemIds)
        let descriptor = FetchDescriptor<PantryItemRecord>()
        let items = ((try? context.fetch(descriptor)) ?? [])
            .filter { wanted.contains($0.id) }
            .map { $0.asPantryItem }
        guard !items.isEmpty else { return }
        AppRouter.shared.pendingRecipeShare = NativePantryViewModel.recipePrompt(for: items)
        #endif
    }



    /// Re-fires this item's reminder tomorrow at the configured reminder time.
    ///
    /// Deliberately not "in 24 hours": the user picked a time of day to be reminded, and a
    /// rolling offset would walk that reminder later every time they snoozed it. Re-uses the
    /// item's id as the request identifier, so the snooze replaces that item's pending
    /// notification rather than leaving two to fire.
    @MainActor
    public func snoozeUntilTomorrow(itemId: String) {
        let notificationSettings = SettingsStore.shared.settings.notifications
        let context = ModelContext(NativePantryViewModel.sharedModelContainer)
        let descriptor = FetchDescriptor<PantryItemRecord>(predicate: #Predicate { $0.id == itemId })
        guard let record = try? context.fetch(descriptor).first else { return }

        var components = Calendar.current.dateComponents(
            [.year, .month, .day],
            from: Calendar.current.date(byAdding: .day, value: 1, to: Date()) ?? Date()
        )
        components.hour = notificationSettings.hour
        components.minute = notificationSettings.minute

        let content = UNMutableNotificationContent()
        content.title = record.expiryDate <= Date() ? L10n.notifTitleToday : L10n.notifTitleSoon
        content.body = L10n.notifBodyToday(record.name)
        content.sound = .default
        content.categoryIdentifier = Self.expiryCategoryIdentifier
        content.userInfo = ["itemId": itemId]

        let trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: false)
        let request = UNNotificationRequest(identifier: itemId, content: content, trigger: trigger)
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [itemId])
        UNUserNotificationCenter.current().add(request)
    }

}
