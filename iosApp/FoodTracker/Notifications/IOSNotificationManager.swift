import Foundation
import UserNotifications
import SwiftData

@MainActor
public class IOSNotificationManager: NSObject, UNUserNotificationCenterDelegate {
    public static let shared = IOSNotificationManager()

    // nonisolated: read from the nonisolated UNUserNotificationCenterDelegate callback below,
    // which can't access MainActor-isolated static state (an error under Swift 6 strict
    // concurrency, only a warning today).
    private nonisolated static let digestIdentifier = "com.foodtracker.digest"
    private nonisolated static let expiryCategoryIdentifier = "EXPIRY_CATEGORY"
    private nonisolated static let removeActionIdentifier = "REMOVE_ACTION"

    private override init() {
        super.init()
    }

    public func requestAuthorization() {
        UNUserNotificationCenter.current().delegate = self

        let removeAction = UNNotificationAction(
            identifier: Self.removeActionIdentifier,
            title: L10n.notifActionRemove,
            options: [.destructive]
        )
        let expiryCategory = UNNotificationCategory(
            identifier: Self.expiryCategoryIdentifier,
            actions: [removeAction],
            intentIdentifiers: [],
            options: []
        )
        UNUserNotificationCenter.current().setNotificationCategories([expiryCategory])

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

    /// (Re)schedules the once-daily "N items expiring this week" digest for the next
    /// occurrence of the configured reminder time, computed from the given items right now.
    ///
    /// Unlike Android's `DigestNotificationReceiver` (a `BroadcastReceiver` that recomputes the
    /// count fresh at the moment the alarm fires), a `UNNotificationRequest`'s content is fixed
    /// at scheduling time — iOS has no equivalent hook to recompute content exactly at fire
    /// time for a local notification. So this is re-scheduled here, from every call site that
    /// changes pantry data (`NativePantryViewModel.saveItem`/`deleteItem`/`decrementQuantity`)
    /// plus once at app launch, rather than relying on `repeats: true` with static content.
    /// The trade-off: if the app is never opened for more than a day, the last-scheduled
    /// digest still fires (with a day-old count) but won't automatically roll forward to the
    /// day after that until the app runs again — acceptable for a local-only app with no
    /// background refresh mechanism, since opening the app is how counts get corrected anyway.
    public func scheduleDigestNotification(items: [PantryItem]) {
        let notificationSettings = SettingsStore.shared.settings.notifications
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [Self.digestIdentifier])
        guard notificationSettings.enabled else { return }

        let expiringThisWeek = items.filter { $0.daysRemaining >= 0 && $0.daysRemaining <= 7 }.count
        guard expiringThisWeek > 0 else { return }

        let content = UNMutableNotificationContent()
        content.title = L10n.notifDigestTitle
        content.body = L10n.notifDigestBody(expiringThisWeek)
        content.sound = .default

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
        if response.actionIdentifier == Self.removeActionIdentifier,
           let itemId = response.notification.request.content.userInfo["itemId"] as? String {
            Task { @MainActor in
                IOSNotificationManager.deleteItemDirectly(itemId)
                completionHandler()
            }
        } else {
            completionHandler()
        }
    }

    /// Deletes a pantry item straight from SwiftData, bypassing `NativePantryViewModel` — the
    /// "Remove" notification action can fire while no dashboard view (and so no view model
    /// instance) exists yet, e.g. right after a cold launch from tapping the action. Mirrors
    /// `NativePantryViewModel.deleteItem`'s essential-item shopping-list-readd behavior.
    @MainActor
    private static func deleteItemDirectly(_ itemId: String) {
        let context = ModelContext(NativePantryViewModel.sharedModelContainer)
        let descriptor = FetchDescriptor<PantryItemRecord>(predicate: #Predicate { $0.id == itemId })
        guard let record = try? context.fetch(descriptor).first else { return }

        let wasEssential = record.essential
        let name = record.name
        let barcode = record.barcode
        let category = record.category

        context.delete(record)
        try? context.save()
        shared.cancelNotification(for: itemId)

        guard wasEssential else { return }

        let existing: ShoppingListEntryRecord?
        if let barcode {
            let shoppingDescriptor = FetchDescriptor<ShoppingListEntryRecord>(predicate: #Predicate { $0.barcode == barcode })
            existing = try? context.fetch(shoppingDescriptor).first
        } else {
            let normalizedName = name.trimmingCharacters(in: .whitespaces).lowercased()
            let shoppingDescriptor = FetchDescriptor<ShoppingListEntryRecord>()
            existing = ((try? context.fetch(shoppingDescriptor)) ?? []).first {
                $0.productName.trimmingCharacters(in: .whitespaces).lowercased() == normalizedName
            }
        }
        guard existing == nil else { return }

        context.insert(ShoppingListEntryRecord(from: ShoppingListEntry(productName: name, barcode: barcode, category: category)))
        try? context.save()
    }
}
