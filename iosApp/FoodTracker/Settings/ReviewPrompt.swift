import Foundation

/// The store-rating prompt: when the app may ask the OS (`@Environment(\.requestReview)`) for it.
/// A hand port of `shared/.../review/ReviewPromptRule.kt` and `ReviewPrompter.kt`; a change to
/// the rule is made in both. Also compiled into the widget extension, which builds
/// `NativePantryViewModel`; only the app's views ever ask.

/// What the prompt remembers between launches. Under its own `UserDefaults` key, outside
/// `AppSettings`, so neither a backup nor `WireSettings` carries it and a restore can't reset it.
struct ReviewPromptRecord: Codable, Equatable {
    /// When the app first ran with this rule in it.
    var firstLaunch: Date?
    /// The most pantry items seen at once: a lower bound on how many were ever added.
    var mostItemsSeen: Int = 0
    /// "Use one" taps on an item that was not past its date.
    var usesBeforeDate: Int = 0
    var lastRequest: Date?
    /// The app version the prompt was last asked for in.
    var lastRequestVersion: String?
}

/// The positive moments the prompt may follow.
enum ReviewMoment {
    /// An item was used before its date, for the third time or later.
    case usedBeforeDate
    /// A recipe or menu that had just been written was shown, and the person left it.
    case recipeLeft
}

/// Only after a positive moment, never while the person is new, in onboarding or in a session
/// where something went wrong, and at most once per app version and 120 days. The OS throttles
/// on its own as well and may show nothing.
enum ReviewPromptRule {
    static let minDaysSinceFirstLaunch = 7
    static let minItemsAdded = 2
    static let minUsesBeforeDate = 3
    static let minDaysBetweenRequests = 120

    private static let day: TimeInterval = 24 * 60 * 60

    /// On its date still counts.
    static func isBeforeDate(daysRemaining: Int) -> Bool { daysRemaining >= 0 }

    /// A clock set back before the first launch or the last request reads as "too soon".
    static func mayAsk(
        _ record: ReviewPromptRecord,
        moment: ReviewMoment,
        now: Date,
        appVersion: String,
        sessionBlocked: Bool
    ) -> Bool {
        if sessionBlocked { return false }
        guard let firstLaunch = record.firstLaunch else { return false }
        if now.timeIntervalSince(firstLaunch) < Double(minDaysSinceFirstLaunch) * day { return false }
        if record.mostItemsSeen < minItemsAdded { return false }
        if moment == .usedBeforeDate && record.usesBeforeDate < minUsesBeforeDate { return false }
        if record.lastRequestVersion == appVersion { return false }
        if let lastRequest = record.lastRequest,
           now.timeIntervalSince(lastRequest) < Double(minDaysBetweenRequests) * day {
            return false
        }
        return true
    }
}

protocol ReviewPromptStore {
    func load() -> ReviewPromptRecord
    func save(_ record: ReviewPromptRecord)
}

struct UserDefaultsReviewPromptStore: ReviewPromptStore {
    static let storageKey = "com.foodtracker.review_prompt"

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func load() -> ReviewPromptRecord {
        guard let data = defaults.data(forKey: Self.storageKey),
              let record = try? JSONDecoder().decode(ReviewPromptRecord.self, from: data) else {
            return ReviewPromptRecord()
        }
        return record
    }

    func save(_ record: ReviewPromptRecord) {
        if let data = try? JSONEncoder().encode(record) {
            defaults.set(data, forKey: Self.storageKey)
        }
    }
}

/// Follows the session and the moments, and runs `ask` (the view's `requestReview`) when one
/// passes the rule. Screens and view models report to `shared`.
@MainActor
final class ReviewPrompter {
    static let shared = ReviewPrompter()

    private let store: ReviewPromptStore
    private let appVersion: String
    private let now: () -> Date
    private var record: ReviewPromptRecord

    /// Something went wrong in front of the person, onboarding ran, or this is a demo launch.
    private(set) var sessionBlocked: Bool
    /// A freshly written recipe is on screen; leaving it is the moment.
    private var recipeShown = false

    init(
        store: ReviewPromptStore = UserDefaultsReviewPromptStore(),
        appVersion: String = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "",
        now: @escaping () -> Date = Date.init,
        suppressed: Bool = ReviewPrompter.isDemoLaunch
    ) {
        self.store = store
        self.appVersion = appVersion
        self.now = now
        self.sessionBlocked = suppressed
        record = store.load()
        if record.firstLaunch == nil {
            record.firstLaunch = now()
            store.save(record)
        }
    }

    /// Debug launches with any `-demo…` argument (`scripts/ios_screenshots.sh`): no prompt may
    /// appear in a store screenshot.
    nonisolated static var isDemoLaunch: Bool {
        #if DEBUG
        ProcessInfo.processInfo.arguments.contains { $0.hasPrefix("-demo") }
        #else
        false
        #endif
    }

    func onboardingShown() { sessionBlocked = true }

    /// An error, a refused permission, a failed or refused generation, a purchase problem.
    func problemShown() { sessionBlocked = true }

    func pantrySize(_ count: Int) {
        guard count > record.mostItemsSeen else { return }
        record.mostItemsSeen = count
        store.save(record)
    }

    /// "Use one" on an item with `daysRemaining` left. Returns whether `ask` ran.
    @discardableResult
    func usedOne(daysRemaining: Int, ask: () -> Void) -> Bool {
        guard ReviewPromptRule.isBeforeDate(daysRemaining: daysRemaining) else { return false }
        record.usesBeforeDate += 1
        store.save(record)
        return askIfDue(.usedBeforeDate, ask: ask)
    }

    /// A recipe or menu was just written and opened.
    func recipeArrived() { recipeShown = true }

    /// The person left the recipe that was just written. Returns whether `ask` ran.
    @discardableResult
    func recipeLeft(ask: () -> Void) -> Bool {
        guard recipeShown else { return false }
        recipeShown = false
        return askIfDue(.recipeLeft, ask: ask)
    }

    private func askIfDue(_ moment: ReviewMoment, ask: () -> Void) -> Bool {
        let date = now()
        guard ReviewPromptRule.mayAsk(record, moment: moment, now: date, appVersion: appVersion, sessionBlocked: sessionBlocked) else {
            return false
        }
        ask()
        record.lastRequest = date
        record.lastRequestVersion = appVersion
        store.save(record)
        return true
    }
}
