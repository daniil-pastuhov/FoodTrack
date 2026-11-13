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
        guard Self.wantsIndividualReminder(notificationSettings, notificationsEnabled: item.notificationsEnabled) else {
            // Also clears a request scheduled before the reminder was switched off - for the
            // item, or for per-item reminders as a whole - which would otherwise still fire.
            cancelNotification(for: item.id)
            return
        }

        // If the item already expired before today, do not schedule
        guard item.daysRemaining >= 0 else { return }

        let calendar = Calendar.current
        guard let reminderDay = calendar.date(byAdding: .day, value: -notificationSettings.leadDays, to: item.expiryDate) else { return }

        var triggerComponents = calendar.dateComponents([.year, .month, .day], from: reminderDay)
        triggerComponents.hour = notificationSettings.hour
        triggerComponents.minute = notificationSettings.minute
        triggerComponents.second = 0

        // UNCalendarNotificationTrigger never fires for a date/time already in the past, so if
        // the 9 AM reminder slot has already gone by - the item was added the same day it
        // expires, or with less than leadDays to go - fire almost immediately instead of
        // silently missing the warning.
        let trigger: UNNotificationTrigger
        let daysRemainingAtTrigger: Int
        if let triggerDate = calendar.date(from: triggerComponents), triggerDate > Date() {
            trigger = UNCalendarNotificationTrigger(dateMatching: triggerComponents, repeats: false)
            daysRemainingAtTrigger = Self.days(from: triggerDate, to: item.expiryDate) ?? item.daysRemaining
        } else {
            trigger = UNTimeIntervalNotificationTrigger(timeInterval: 5, repeats: false)
            daysRemainingAtTrigger = item.daysRemaining
        }

        // Written from the day the reminder actually fires, not from today: this content is
        // fixed when the request is scheduled but read whenever it lands. An item five days out
        // is reminded about the day before it expires, so it has to say "expires in 1 day".
        let content = Self.expiryContent(
            for: item.name,
            brand: item.brand,
            daysRemaining: daysRemainingAtTrigger,
            itemId: item.id
        )

        let request = UNNotificationRequest(identifier: item.id, content: content, trigger: trigger)

        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                print("Failed to schedule notification for \(item.name): \(error.localizedDescription)")
            }
        }
    }

    /// Whether an item should get its own reminder: notifications on as a whole, per-item
    /// reminders on, and not silenced for this product. Mirrors Android's
    /// `NotificationHelper.wantsIndividualReminder`.
    private nonisolated static func wantsIndividualReminder(
        _ settings: NotificationSettings,
        notificationsEnabled: Bool
    ) -> Bool {
        settings.enabled && settings.individualEnabled && notificationsEnabled
    }

    /// Builds the per-item reminder's content for an item that will have [daysRemaining] days
    /// left *at the moment the notification fires*. Shared by the initial schedule and by
    /// [snoozeUntilTomorrow] so a snoozed reminder reads as correctly as the original.
    private nonisolated static func expiryContent(
        for name: String,
        brand: String?,
        daysRemaining: Int,
        itemId: String
    ) -> UNMutableNotificationContent {
        let content = UNMutableNotificationContent()
        let brandSuffix = (brand?.isEmpty == false) ? " (\(brand!))" : ""
        let nameWithBrand = "\(name)\(brandSuffix)"
        if daysRemaining < 0 {
            content.title = L10n.notifTitlePast
            content.body = L10n.notifBodyPast(nameWithBrand, days: -daysRemaining)
        } else if daysRemaining == 0 {
            content.title = L10n.notifTitleToday
            content.body = L10n.notifBodyToday(nameWithBrand)
        } else {
            content.title = L10n.notifTitleSoon
            content.body = L10n.notifBodySoon(nameWithBrand, days: daysRemaining)
        }
        content.sound = .default
        content.categoryIdentifier = Self.expiryCategoryIdentifier
        content.userInfo = ["itemId": itemId]
        return content
    }

    /// Whole days between two instants, counted from the start of each day so a reminder at
    /// 09:00 and an expiry date at midnight don't round down to one day fewer.
    private nonisolated static func days(from: Date, to: Date) -> Int? {
        let calendar = Calendar.current
        return calendar.dateComponents(
            [.day],
            from: calendar.startOfDay(for: from),
            to: calendar.startOfDay(for: to)
        ).day
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

    /// (Re)schedules the digest for the next occurrence of the configured reminder time - daily,
    /// every N days, or weekly on the chosen weekday - naming the most urgent of the given items as of right now.
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
    /// The first day from today that `isDigestDay` accepts, at the reminder time; today counts
    /// only while its slot is still ahead. Mirrors Android's `NotificationHelper.nextDigestTime`.
    nonisolated static func nextDigestDate(after now: Date, settings: NotificationSettings, calendar: Calendar) -> Date? {
        let today = calendar.startOfDay(for: now)
        // Every schedule repeats within 7 days, so 8 candidates (today included) always hit.
        for offset in 0...7 {
            guard let day = calendar.date(byAdding: .day, value: offset, to: today),
                  let fire = calendar.date(bySettingHour: settings.hour, minute: settings.minute, second: 0, of: day),
                  fire > now else { continue }
            let ymd = calendar.dateComponents([.year, .month, .day, .weekday], from: day)
            guard let year = ymd.year, let month = ymd.month, let dayOfMonth = ymd.day, let weekday = ymd.weekday else { continue }
            // Calendar's 1=Sun...7=Sat onto ISO 1=Mon...7=Sun.
            let isoWeekday = (weekday + 5) % 7 + 1
            if settings.isDigestDay(epochDay: epochDay(year: year, month: month, day: dayOfMonth), isoWeekday: isoWeekday) {
                return fire
            }
        }
        return nil
    }

    /// Days since 1970-01-01 for a local calendar date - the same count as Java's
    /// `LocalDate.toEpochDay`, so iOS and Android agree on which days an every-N-days summary fires.
    private nonisolated static func epochDay(year: Int, month: Int, day: Int) -> Int {
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC")!
        let date = utc.date(from: DateComponents(year: year, month: month, day: day)) ?? Date(timeIntervalSince1970: 0)
        return Int((date.timeIntervalSince1970 / 86_400).rounded(.down))
    }

    public func scheduleDigestNotification(items: [PantryItem]) {
        let notificationSettings = SettingsStore.shared.settings.notifications
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [Self.digestIdentifier])
        guard notificationSettings.enabled, notificationSettings.digestEnabled else { return }

        let calendar = Calendar.current
        guard let nextFireDate = Self.nextDigestDate(after: Date(), settings: notificationSettings, calendar: calendar) else { return }
        let fireComponents = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: nextFireDate)

        // Selected and worded as of the day it fires, not today: a weekly summary can land up
        // to six days after it is scheduled, and "expires in 5 days" written today would be
        // wrong by then - or name food that has already gone off, and miss what has come due.
        let expiringThisWeek = items
            .filter { $0.notificationsEnabled }
            .compactMap { item -> (item: PantryItem, days: Int)? in
                guard let days = Self.days(from: nextFireDate, to: item.expiryDate),
                      (0...Self.digestWindowDays).contains(days) else { return nil }
                return (item, days)
            }
            .sorted { $0.days < $1.days }
        guard !expiringThisWeek.isEmpty else { return }

        // Naming the items rather than only counting them is the point of the digest: "3 items
        // expiring this week" tells the user nothing they can act on without opening the app.
        let named = expiringThisWeek.prefix(Self.namedItemCount)
        var body = named
            .map { L10n.notifDigestItem(name: $0.item.name, expiry: L10n.daysRemaining($0.days)) }
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
        content.userInfo = ["digestItemIds": expiringThisWeek.map { $0.item.id }]

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

        // The store reminder carries no item at all — tapping it just opens the shopping list.
        if userInfo["openShoppingList"] as? Bool == true {
            Task { @MainActor in
                #if !WIDGET_EXTENSION
                AppRouter.shared.destination = .shoppingList
                #endif
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
        guard let record = try? context.fetch(descriptor).first,
              Self.wantsIndividualReminder(notificationSettings, notificationsEnabled: record.notificationsEnabled)
        else { return }

        let tomorrow = Calendar.current.date(byAdding: .day, value: 1, to: Date()) ?? Date()
        var components = Calendar.current.dateComponents([.year, .month, .day], from: tomorrow)
        components.hour = notificationSettings.hour
        components.minute = notificationSettings.minute

        // Described as of tomorrow, when this fires - and via the same builder as the original
        // reminder, so the snoozed copy keeps the brand and counts down instead of always
        // claiming the item expires today.
        let content = Self.expiryContent(
            for: record.name,
            brand: record.brand,
            daysRemaining: Self.days(from: tomorrow, to: record.expiryDate) ?? 0,
            itemId: itemId
        )

        let trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: false)
        let request = UNNotificationRequest(identifier: itemId, content: content, trigger: trigger)
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [itemId])
        UNUserNotificationCenter.current().add(request)
    }

}
