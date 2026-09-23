import CoreLocation
import Foundation
import SwiftData
import UserNotifications

/// Monitors the user's saved shops and posts their shopping list when they settle at one.
///
/// Mirrors Android's `StoreGeofenceManager` + `StoreGeofenceReceiver`, which are two classes
/// there only because a geofence transition arrives as a broadcast; here CoreLocation delivers
/// it to this same delegate.
///
/// Registrations are re-asserted from `sync(settings:)` rather than observed: region monitoring
/// is an OS-level registration that outlives the process, so the only correct model is to
/// restate it at the points where the intent can have changed — app start and a settings edit.
/// Unlike Android, iOS *does* restore monitored regions across a reboot, so there is no boot
/// receiver equivalent.
@MainActor
public final class StoreGeofenceManager: NSObject, ObservableObject, CLLocationManagerDelegate {

    public static let shared = StoreGeofenceManager()

    /// How many shops can be monitored. iOS caps an app at 20 regions in total; this is well
    /// under that, and is really a guard against a settings file that has grown silly.
    private static let maxMonitoredRegions = 10

    /// Shortest gap between two store reminders, whatever the regions do in between. Region
    /// events retrigger readily — GPS drift, or simply a shop passed every day — and a reminder
    /// that fires repeatedly is an uninstall rather than a feature. Matches Android's
    /// `MIN_INTERVAL_MILLIS`.
    private static let minInterval: TimeInterval = 6 * 60 * 60
    private static let lastNotifiedKey = "com.foodtracker.store_reminder_last_notified"

    private static let categoryIdentifier = "STORE_REMINDER_CATEGORY"
    private static let notificationIdentifier = "com.foodtracker.store_reminder"

    private let locationManager = CLLocationManager()

    /// Held while a one-shot location fix is in flight, so Settings' spinner is always answered.
    private var pendingLocationResult: ((CLLocationCoordinate2D?) -> Void)?

    private override init() {
        super.init()
        locationManager.delegate = self
    }

    // MARK: - Registration

    /// Re-registers every region implied by `settings`, or clears them all when the feature is
    /// off or has no stores.
    public func sync(settings: AppSettings) {
        stopMonitoringAll()
        guard settings.storeRemindersEnabled, !settings.stores.isEmpty else { return }
        guard CLLocationManager.isMonitoringAvailable(for: CLCircularRegion.self) else { return }

        // `authorizedAlways` is what makes a region fire with the app closed. While-in-use still
        // monitors, so this deliberately does not require always — the escalation is asked for
        // separately, after a shop has actually been saved.
        let status = locationManager.authorizationStatus
        guard status == .authorizedAlways || status == .authorizedWhenInUse else { return }

        for store in settings.stores.prefix(Self.maxMonitoredRegions) {
            let region = CLCircularRegion(
                center: CLLocationCoordinate2D(latitude: store.latitude, longitude: store.longitude),
                radius: store.radiusMeters,
                identifier: store.id
            )
            // Entry only. Exit would fire on the way out of the shop, which is exactly when the
            // reminder is useless.
            region.notifyOnEntry = true
            region.notifyOnExit = false
            locationManager.startMonitoring(for: region)
        }
    }

    private func stopMonitoringAll() {
        for region in locationManager.monitoredRegions {
            locationManager.stopMonitoring(for: region)
        }
    }

    // MARK: - Permissions and one-shot fixes

    /// Asks for **while-in-use** only. Background ("always") is what makes a region fire with
    /// the app closed, but requesting it up front is the most common cause of both a denial and
    /// a store-review rejection; `requestAlwaysAuthorizationIfNeeded` escalates later.
    public func requestWhenInUseAuthorization() {
        locationManager.requestWhenInUseAuthorization()
    }

    /// Escalates to "always", but only once a shop has been saved — at which point the dialog's
    /// purpose is something the user has just demonstrated wanting.
    public func requestAlwaysAuthorizationIfNeeded() {
        guard locationManager.authorizationStatus == .authorizedWhenInUse else { return }
        locationManager.requestAlwaysAuthorization()
    }

    /// Resolves the device's current position so Settings can save it as a store, asking for
    /// permission first if it hasn't been granted.
    ///
    /// `completion` is always called exactly once — with nil when permission was refused or no
    /// fix arrived — so the Settings screen never leaves its spinner running.
    public func resolveCurrentLocation(completion: @escaping (CLLocationCoordinate2D?) -> Void) {
        switch locationManager.authorizationStatus {
        case .notDetermined:
            pendingLocationResult = completion
            requestWhenInUseAuthorization()
        case .authorizedAlways, .authorizedWhenInUse:
            pendingLocationResult = completion
            // requestLocation, not `location`: the cached fix can be hours old and somewhere
            // else entirely, and saving *that* as a shop would put the region in the wrong place
            // with no sign anything went wrong.
            locationManager.requestLocation()
        default:
            completion(nil)
        }
    }

    // MARK: - CLLocationManagerDelegate

    public nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        Task { @MainActor in
            guard let pending = pendingLocationResult else { return }
            switch locationManager.authorizationStatus {
            case .authorizedAlways, .authorizedWhenInUse:
                locationManager.requestLocation()
            case .notDetermined:
                break // Still waiting on the user; the dialog is up.
            default:
                pendingLocationResult = nil
                pending(nil)
            }
        }
    }

    public nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        let coordinate = locations.last?.coordinate
        Task { @MainActor in
            let pending = pendingLocationResult
            pendingLocationResult = nil
            pending?(coordinate)
        }
    }

    public nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        Task { @MainActor in
            let pending = pendingLocationResult
            pendingLocationResult = nil
            pending?(nil)
        }
    }

    public nonisolated func locationManager(_ manager: CLLocationManager, didEnterRegion region: CLRegion) {
        let identifier = region.identifier
        Task { @MainActor in
            postReminder(forStoreId: identifier)
        }
    }

    // MARK: - The reminder

    private func postReminder(forStoreId storeId: String) {
        let settings = SettingsStore.shared.settings
        guard settings.storeRemindersEnabled else { return }

        // Rate limit before doing any work.
        let last = UserDefaults.standard.double(forKey: Self.lastNotifiedKey)
        guard Date().timeIntervalSince1970 - last >= Self.minInterval else { return }

        let context = ModelContext(NativePantryViewModel.sharedModelContainer)
        let descriptor = FetchDescriptor<ShoppingListEntryRecord>(
            sortBy: [SortDescriptor(\.addedDate, order: .reverse)]
        )
        let entries = ((try? context.fetch(descriptor)) ?? []).map { $0.asEntry }
        // Nothing to buy, nothing to say. Checked here rather than at registration time because
        // the list changes constantly while the regions do not.
        guard !entries.isEmpty else { return }

        UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: Self.lastNotifiedKey)

        let named = entries.prefix(Self.namedItemCount)
        var body = named.map { $0.productName }.joined(separator: "\n")
        let remaining = entries.count - named.count
        if remaining > 0 {
            body += "\n" + L10n.storeReminderMore(remaining)
        }

        let content = UNMutableNotificationContent()
        let storeLabel = settings.stores.first { $0.id == storeId }?.label
        content.title = storeLabel.map { L10n.storeReminderTitleNamed($0) } ?? L10n.storeReminderTitle(entries.count)
        content.body = body
        content.sound = .default
        content.categoryIdentifier = Self.categoryIdentifier
        content.userInfo = ["openShoppingList": true]

        let request = UNNotificationRequest(
            identifier: Self.notificationIdentifier,
            content: content,
            trigger: UNTimeIntervalNotificationTrigger(timeInterval: 1, repeats: false)
        )
        UNUserNotificationCenter.current().add(request)
    }

    private static let namedItemCount = 5
}
