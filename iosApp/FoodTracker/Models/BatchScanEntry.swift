import Foundation

/// One product waiting in the scanner's batch queue: scanned and looked up, but not yet saved.
///
/// Batch mode exists because unpacking a weekly shop is fifteen to thirty items, and the normal
/// flow costs a full round trip through Add/Edit for each one. Here each scan is looked up and
/// parked, and the whole queue is reviewed and committed at the end.
///
/// Mirrors `shared`'s `com.foodtracker.viewmodel.BatchScanEntry`.
public struct BatchScanEntry: Identifiable, Equatable {
    /// Row identity, independent of `barcode` — a receipt-scanned row has no barcode at all
    /// (see `ReceiptParser`), so the queue can't key on one.
    public var id: String
    public var barcode: String?
    public var name: String
    public var brand: String?
    public var imageUrl: String?
    public var category: String?
    /// Starts from whatever the scan could work out — a date read off the packaging, or the
    /// shelf life this barcode was last given — so a queue of familiar products is often
    /// correct without the user touching any of it.
    public var expiryDate: Date
    public var quantity: Int
    public var storageLocation: String
    /// Where `expiryDate` came from, so the review list can flag the rows that are guesses.
    public var expirySource: ShelfLifeSource
    /// True while the product lookup is still in flight, so a row can appear immediately on the
    /// scan rather than after the network answers.
    public var isLookingUp: Bool
    /// True once the person has set this row's date themselves, so a date the camera reads
    /// afterwards never replaces theirs (`BatchDateRead`). Nothing in the queue edits a date by
    /// hand yet; whatever comes to must set this.
    public var dateSetByHand: Bool
    /// The date and source a date read off the pack replaced, for its Undo. Nil when the date
    /// wasn't read in Batch mode's one pass.
    public var dateBeforeRead: BatchDateBefore?

    /// A row read off a till receipt: no barcode was scanned for it, so the camera never reads
    /// a date for it either.
    public var isReceiptRow: Bool { id.hasPrefix("receipt_") }

    /// True when nothing better than the fallback was known, so the user should look at it.
    public var needsAttention: Bool { expirySource == .fallback }

    /// The queue's Check date mark: a guess nothing better than the fallback backs up, and every
    /// receipt row, because a receipt prints no dates. Only a date read off a pack is exempt.
    public var showsCheckDate: Bool {
        !isLookingUp && (needsAttention || (isReceiptRow && expirySource != .detected))
    }

    public init(
        id: String,
        barcode: String? = nil,
        name: String,
        brand: String? = nil,
        imageUrl: String? = nil,
        category: String? = nil,
        expiryDate: Date,
        quantity: Int = 1,
        storageLocation: String,
        expirySource: ShelfLifeSource,
        isLookingUp: Bool = false,
        dateSetByHand: Bool = false,
        dateBeforeRead: BatchDateBefore? = nil
    ) {
        self.id = id
        self.barcode = barcode
        self.name = name
        self.brand = brand
        self.imageUrl = imageUrl
        self.category = category
        self.expiryDate = expiryDate
        self.quantity = quantity
        self.storageLocation = storageLocation
        self.expirySource = expirySource
        self.isLookingUp = isLookingUp
        self.dateSetByHand = dateSetByHand
        self.dateBeforeRead = dateBeforeRead
    }
}

/// A row's date as it was before a date read off the pack replaced it.
public struct BatchDateBefore: Equatable {
    public var date: Date
    public var source: ShelfLifeSource
    public var setByHand: Bool

    public init(date: Date, source: ShelfLifeSource, setByHand: Bool = false) {
        self.date = date
        self.source = source
        self.setByHand = setByHand
    }
}

/// The row a barcode scan in Batch mode just made or bumped (or whose date was tapped to read it
/// again), and until when the camera may read its printed date.
public struct BatchDateWindow: Equatable {
    public let entryId: String
    public let closesAt: Date
    /// Opened by tapping the row's date: the next read that differs from it replaces it, whatever
    /// the date already is.
    public let rescan: Bool

    public init(entryId: String, closesAt: Date, rescan: Bool = false) {
        self.entryId = entryId
        self.closesAt = closesAt
        self.rescan = rescan
    }
}

/// The latest date the camera read that has not landed on anything yet, in Barcode and Batch
/// mode, where dates are read beside barcodes all the time. A barcode scan within
/// `BatchDateRead.recentDateSeconds` takes it, so a date seen just before the barcode counts.
public struct RecentDateRead: Equatable {
    public let date: Date
    public let readAt: Date

    public init(date: Date, readAt: Date) {
        self.date = date
        self.readAt = readAt
    }
}

/// Batch mode's one pass: the camera reads printed dates beside barcodes, and after each barcode
/// scan reads them as often as Date mode for a short while, putting the date on that scan's row,
/// so nobody has to switch to Date mode per product. A date read just before the scan counts
/// too (`RecentDateRead`), and tapping a row's date reads it again (a rescan).
///
/// The same rule as Android's (`shared`'s batch date read); change both. Time is passed in, so
/// `iosApp/BatchDateReadCheck/check.sh` can run it without the app.
public enum BatchDateRead {
    /// How long after a barcode scan a date read still belongs to that product. Date OCR runs
    /// in Batch mode only while it is open, for the battery.
    public static let windowSeconds: TimeInterval = 10

    /// How long a date read with no product to go on still counts for the next barcode scan.
    public static let recentDateSeconds: TimeInterval = 2

    /// Outside an open window, how often the camera reads text at most in Barcode and Batch mode,
    /// for the battery. Inside one it reads every frame, as Date mode does.
    public static let idleTextReadInterval: TimeInterval = 0.3

    /// Keeps `date`, read with nothing to land on, as the recent date, unless it is the day that
    /// last landed on a product: that is usually the same pack still in view, read again once the
    /// de-duplication lets it through, and it must not land on the next barcode. Inside an open
    /// window such a read is handled as any other (`dateRead`), so two products with the same
    /// printed date still work by showing the date after the barcode.
    public static func keepRecent(_ date: Date, recent: inout RecentDateRead?, lastLanded: Date?, at now: Date) {
        if let lastLanded, Calendar.current.isDate(date, inSameDayAs: lastLanded) { return }
        recent = RecentDateRead(date: date, readAt: now)
    }

    /// The recent date, if it still counts at `now`, and forgets it: a date lands at most once.
    public static func takeRecent(_ recent: inout RecentDateRead?, at now: Date) -> Date? {
        guard let read = recent else { return nil }
        let age = now.timeIntervalSince(read.readAt)
        guard age >= 0, age < recentDateSeconds else { return nil }
        recent = nil
        return read.date
    }

    /// The window a scan of `entry` opens, replacing any earlier one. Receipt rows never get one.
    public static func window(afterScanOf entry: BatchScanEntry, at now: Date) -> BatchDateWindow? {
        guard !entry.isReceiptRow else { return nil }
        return BatchDateWindow(entryId: entry.id, closesAt: now.addingTimeInterval(windowSeconds))
    }

    /// The window tapping a row's date opens. Never for a receipt row, nor while the row is still
    /// being looked up.
    public static func window(forRescanOf entry: BatchScanEntry, at now: Date) -> BatchDateWindow? {
        guard mayRescan(entry) else { return nil }
        return BatchDateWindow(entryId: entry.id, closesAt: now.addingTimeInterval(windowSeconds), rescan: true)
    }

    public static func mayRescan(_ entry: BatchScanEntry) -> Bool {
        !entry.isReceiptRow && !entry.isLookingUp
    }

    /// A barcode scan of the row `entryId` in Batch mode. The scan always uses up the recent
    /// date: it lands on the row at once if the row may take it, and no window opens; otherwise
    /// the scan opens the window, replacing any earlier one (a rescan's included), but only for
    /// a row that may take a date, so the camera stays at its idle rate for one that can't.
    /// `gs1Date`, a date in the barcode itself, counts as landed (`keepRecent`). Returns the
    /// row's index when a date landed.
    public static func afterScan(
        of entryId: String,
        gs1Date: Date? = nil,
        queue: inout [BatchScanEntry],
        window: inout BatchDateWindow?,
        recent: inout RecentDateRead?,
        lastLanded: inout Date?,
        at now: Date
    ) -> Int? {
        if let gs1Date { lastLanded = gs1Date }
        let recentDate = takeRecent(&recent, at: now)
        window = nil
        guard let index = queue.firstIndex(where: { $0.id == entryId }), mayTake(queue[index]) else { return nil }
        let scanWindow = self.window(afterScanOf: queue[index], at: now)
        if let recentDate {
            lastLanded = recentDate
            return apply(recentDate, to: &queue, window: scanWindow, at: now)
        }
        window = scanWindow
        return nil
    }

    /// Commit, the scanner opening or closing, and a mode change: no window, nothing recent and
    /// nothing landed carries over.
    public static func endSession(window: inout BatchDateWindow?, recent: inout RecentDateRead?, lastLanded: inout Date?) {
        window = nil
        recent = nil
        lastLanded = nil
    }

    /// Barcode mode stops reading dates while its held scan already has one; the next scan
    /// starts them again. Only Date mode's explicit read replaces a date there.
    public static func pausesSingleScanDates(holdingScan: Bool, hasDate: Bool) -> Bool {
        holdingScan && hasDate
    }

    /// A date the camera read in Batch mode: it lands through an open window if it may, which
    /// closes the window. One that doesn't land while a window is open belongs to that window's
    /// product and is dropped; with none open it may be kept as the recent date for the next
    /// scan (`keepRecent`). Returns the row's index when it landed.
    public static func dateRead(
        _ date: Date,
        queue: inout [BatchScanEntry],
        window: inout BatchDateWindow?,
        recent: inout RecentDateRead?,
        lastLanded: inout Date?,
        at now: Date
    ) -> Int? {
        if let index = apply(date, to: &queue, window: window, at: now) {
            window = nil
            lastLanded = date
            return index
        }
        if !isOpen(window, at: now) {
            keepRecent(date, recent: &recent, lastLanded: lastLanded, at: now)
        }
        return nil
    }

    /// A row leaving the queue takes its window with it.
    public static func rowRemoved(_ id: String, window: inout BatchDateWindow?) {
        if window?.entryId == id { window = nil }
    }

    public static func isOpen(_ window: BatchDateWindow?, at now: Date) -> Bool {
        guard let window else { return false }
        return now < window.closesAt
    }

    /// A row takes a read date only when nothing better is on it: not a date already read off
    /// the pack (GS1 or an earlier read), and not one the person set.
    public static func mayTake(_ entry: BatchScanEntry) -> Bool {
        !entry.isReceiptRow && entry.expirySource != .detected && !entry.dateSetByHand
    }

    /// Puts `date` on the window's row and returns that row's index, or nil (ignored) when no
    /// window is open, its row has gone, or the row may not take it. The caller closes the
    /// window on success: one date per scan. A rescan takes any date but the one already there,
    /// and leaves its window open on that one, so the camera can be turned from a production
    /// date to the best-before date.
    public static func apply(
        _ date: Date,
        to queue: inout [BatchScanEntry],
        window: BatchDateWindow?,
        at now: Date
    ) -> Int? {
        guard isOpen(window, at: now), let window,
              let index = queue.firstIndex(where: { $0.id == window.entryId }) else { return nil }
        let row = queue[index]
        if window.rescan {
            guard mayRescan(row), !Calendar.current.isDate(date, inSameDayAs: row.expiryDate) else { return nil }
        } else {
            guard mayTake(row) else { return nil }
        }
        queue[index].dateBeforeRead = BatchDateBefore(date: row.expiryDate, source: row.expirySource, setByHand: row.dateSetByHand)
        queue[index].expiryDate = date
        queue[index].expirySource = .detected
        queue[index].dateSetByHand = false
        return index
    }

    /// Undo for `apply`: the row gets back the date and source the read replaced. Returns false
    /// when there is nothing to undo (the row has gone, or was already undone).
    @discardableResult
    public static func undo(entryId: String, in queue: inout [BatchScanEntry]) -> Bool {
        guard let index = queue.firstIndex(where: { $0.id == entryId }),
              let before = queue[index].dateBeforeRead else { return false }
        queue[index].expiryDate = before.date
        queue[index].expirySource = before.source
        queue[index].dateSetByHand = before.setByHand
        queue[index].dateBeforeRead = nil
        return true
    }

    /// The product lookup's shelf-life suggestion, landing on a row. A date read while the lookup
    /// was in flight wins; the suggestion becomes what its Undo goes back to, since the
    /// placeholder it replaced was only ever a stand-in for it.
    public static func mergeLookup(_ suggestion: ShelfLifeSuggestion, into entry: inout BatchScanEntry) {
        if entry.dateBeforeRead != nil {
            entry.dateBeforeRead = BatchDateBefore(date: suggestion.date, source: suggestion.source, setByHand: entry.dateBeforeRead?.setByHand ?? false)
        } else {
            entry.expiryDate = suggestion.date
            entry.expirySource = suggestion.source
        }
    }
}
