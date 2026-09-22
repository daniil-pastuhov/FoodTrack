import Foundation

/// Where the expiry date pre-filled on a new item came from. Drives the caption shown under the
/// date field, and distinguishes "the app guessed this" from "the packaging said so".
/// Mirrors Android's `com.foodtracker.model.ShelfLifeSource`.
public enum ShelfLifeSource {
    /// Read off the packaging - a GS1 AI(17)/(15) segment or the date OCR. Not a guess.
    case detected
    /// The shelf life this exact barcode was given the last time it was added.
    case remembered
    /// The default configured for the product's category in Settings.
    case categoryDefault
    /// Nothing was known - `ShelfLife.fallbackDays` from today.
    case fallback
}

/// `days` is the shelf life the suggestion was built from, for the caption ("14 days"). It is
/// nil for `.detected`, which is a date read off the packaging rather than a span, and so says
/// nothing about how long the product keeps.
public struct ShelfLifeSuggestion: Equatable {
    public let date: Date
    public let source: ShelfLifeSource
    public let days: Int?

    public init(date: Date, source: ShelfLifeSource, days: Int? = nil) {
        self.date = date
        self.source = source
        self.days = days
    }

    /// True when this is a guess worth captioning. A detected date needs no explanation, and
    /// the fallback is the same +7 days the form has always started from.
    public var isInformative: Bool {
        source == .remembered || source == .categoryDefault
    }
}

/// Resolves which expiry date a freshly scanned/added item should start out with, and learns
/// shelf lives from items the user saves.
///
/// A hand-written port of `shared`'s `com.foodtracker.model.ShelfLife` - nothing under
/// `iosApp/` consumes that module, so the two must be kept in step by hand. Any change to the
/// precedence rule or the bounds here has to be mirrored there, and vice versa.
public enum ShelfLife {

    /// Upper bound on a shelf life the app will learn or suggest. Anything longer is almost
    /// certainly a mis-read year or a lot number rather than a real date, so it is ignored
    /// rather than remembered - one bad OCR read must not poison every future scan of that
    /// product. Matches the window `ExpiryDatePlausibility` already applies to dates.
    public static let maxLearnableDays = ExpiryDatePlausibility.maxYearsFromToday * 365

    /// Used when nothing at all is known about a product. This is the default the add/edit
    /// form has always started from.
    public static let fallbackDays = 7

    /// The shelf life to remember for a product the user just added, or nil when the span is
    /// not worth learning: a negative one (the item was added already expired, which says
    /// nothing about how long a fresh one lasts) or an implausibly long one.
    public static func learnableShelfLife(addedDate: Date, expiryDate: Date) -> Int? {
        let calendar = Calendar.current
        let from = calendar.startOfDay(for: addedDate)
        let to = calendar.startOfDay(for: expiryDate)
        guard let days = calendar.dateComponents([.day], from: from, to: to).day else { return nil }
        return (0...maxLearnableDays).contains(days) ? days : nil
    }

    /// The expiry date to pre-fill, in precedence order:
    ///
    ///  1. `detectedDate` - read off the packaging by the GS1 parser or the date OCR. Always
    ///     wins: it is the actual printed date, not a guess.
    ///  2. `rememberedDays` - what this exact barcode was given last time.
    ///  3. `categoryDefaultDays` - the default configured for its category in Settings.
    ///  4. `fallbackDays` from today.
    ///
    /// Implausible remembered/default values are skipped rather than used, so a bad value that
    /// somehow reached storage degrades to the next source instead of suggesting nonsense.
    public static func suggest(
        today: Date = Date(),
        detectedDate: Date? = nil,
        rememberedDays: Int? = nil,
        categoryDefaultDays: Int? = nil
    ) -> ShelfLifeSuggestion {
        if let detectedDate {
            return ShelfLifeSuggestion(date: detectedDate, source: .detected)
        }
        if let rememberedDays, isUsable(rememberedDays) {
            return ShelfLifeSuggestion(
                date: addingDays(rememberedDays, to: today),
                source: .remembered,
                days: rememberedDays
            )
        }
        if let categoryDefaultDays, isUsable(categoryDefaultDays) {
            return ShelfLifeSuggestion(
                date: addingDays(categoryDefaultDays, to: today),
                source: .categoryDefault,
                days: categoryDefaultDays
            )
        }
        return ShelfLifeSuggestion(
            date: addingDays(fallbackDays, to: today),
            source: .fallback,
            days: fallbackDays
        )
    }

    private static func isUsable(_ days: Int) -> Bool {
        (0...maxLearnableDays).contains(days)
    }

    private static func addingDays(_ days: Int, to date: Date) -> Date {
        Calendar.current.date(byAdding: .day, value: days, to: date) ?? date
    }
}
