import Foundation

// Checks BatchDateRead, Batch mode's one pass: the window a scan opens, which rows take a date
// read in it, applying, Undo, and the product lookup landing after the date. Run with check.sh.

/// What ShelfLife.swift needs; the app's own is in LocalModels.swift.
enum ExpiryDatePlausibility {
    static let maxYearsFromToday = 5
}

var failures = 0
func expect<T: Equatable>(_ actual: T, _ expected: T, _ what: String) {
    if actual != expected {
        failures += 1
        print("FAIL \(what): \(actual) != \(expected)")
    }
}

let t0 = Date(timeIntervalSince1970: 1_800_000_000)
let placeholder = t0
let printed = t0.addingTimeInterval(20 * 86_400)
func row(_ id: String, source: ShelfLifeSource = .fallback, lookingUp: Bool = false, byHand: Bool = false) -> BatchScanEntry {
    BatchScanEntry(id: id, barcode: id.hasPrefix("receipt_") ? nil : id, name: id, expiryDate: placeholder,
                   storageLocation: "Fridge", expirySource: source, isLookingUp: lookingUp, dateSetByHand: byHand)
}

// The window
expect(BatchDateRead.windowSeconds, 10, "ten seconds")
let window = BatchDateRead.window(afterScanOf: row("111"), at: t0)
expect(window?.entryId, "111", "the scanned row is the target")
expect(BatchDateRead.isOpen(window, at: t0), true, "open at the scan")
expect(BatchDateRead.isOpen(window, at: t0.addingTimeInterval(9.9)), true, "open just before ten seconds")
expect(BatchDateRead.isOpen(window, at: t0.addingTimeInterval(10)), false, "closed at ten seconds")
expect(BatchDateRead.isOpen(nil, at: t0), false, "no scan, no window")
expect(BatchDateRead.window(afterScanOf: row("receipt_1"), at: t0), nil, "a receipt row is never a target")

// Which rows take it
expect(BatchDateRead.mayTake(row("1")), true, "a fallback guess")
expect(BatchDateRead.mayTake(row("1", source: .remembered)), true, "a remembered guess")
expect(BatchDateRead.mayTake(row("1", source: .categoryDefault)), true, "a category default")
expect(BatchDateRead.mayTake(row("1", source: .detected)), false, "already read off the pack")
expect(BatchDateRead.mayTake(row("1", byHand: true)), false, "set by hand")
expect(BatchDateRead.mayTake(row("receipt_1")), false, "a receipt row")

// Applying
var queue = [row("111", source: .remembered), row("222")]
let w222 = BatchDateRead.window(afterScanOf: queue[1], at: t0)
expect(BatchDateRead.apply(printed, to: &queue, window: w222, at: t0.addingTimeInterval(11)), nil, "too late is ignored")
expect(queue[1].expirySource, .fallback, "nothing changed when ignored")
expect(BatchDateRead.apply(printed, to: &queue, window: nil, at: t0), nil, "no window is ignored")
expect(BatchDateRead.apply(printed, to: &queue, window: w222, at: t0.addingTimeInterval(3)), 1, "the target row takes it")
expect(queue[1].expiryDate, printed, "the date read")
expect(queue[1].expirySource, .detected, "counts as read off the pack")
expect(queue[1].showsCheckDate, false, "no Check date mark")
expect(queue[0].expirySource, .remembered, "other rows untouched")
expect(BatchDateRead.apply(t0, to: &queue, window: w222, at: t0.addingTimeInterval(4)), nil, "one date per row")
var gone: [BatchScanEntry] = [row("111")]
expect(BatchDateRead.apply(printed, to: &gone, window: w222, at: t0), nil, "a removed row is ignored")

// Undo
expect(BatchDateRead.undo(entryId: "222", in: &queue), true, "undone")
expect(queue[1].expiryDate, placeholder, "the previous date")
expect(queue[1].expirySource, .fallback, "the previous source")
expect(queue[1].dateBeforeRead, nil, "nothing left to undo")
expect(BatchDateRead.undo(entryId: "222", in: &queue), false, "undo twice is nothing")
expect(BatchDateRead.undo(entryId: "999", in: &queue), false, "undo for a row that has gone")
var remembered = [row("111", source: .remembered)]
_ = BatchDateRead.apply(printed, to: &remembered, window: BatchDateRead.window(afterScanOf: remembered[0], at: t0), at: t0)
BatchDateRead.undo(entryId: "111", in: &remembered)
expect(remembered[0].expirySource, .remembered, "undo restores a remembered source exactly")

// The lookup landing after the date
let suggestion = ShelfLifeSuggestion(date: t0.addingTimeInterval(5 * 86_400), source: .categoryDefault, days: 5)
var inFlight = [row("333", lookingUp: true)]
_ = BatchDateRead.apply(printed, to: &inFlight, window: BatchDateRead.window(afterScanOf: inFlight[0], at: t0), at: t0)
BatchDateRead.mergeLookup(suggestion, into: &inFlight[0])
expect(inFlight[0].expiryDate, printed, "the date read wins over the lookup")
expect(inFlight[0].expirySource, .detected, "still read off the pack")
BatchDateRead.undo(entryId: "333", in: &inFlight)
expect(inFlight[0].expiryDate, suggestion.date, "undo after the lookup gives the lookup's suggestion")
expect(inFlight[0].expirySource, .categoryDefault, "and its source")
var plain = [row("444", lookingUp: true)]
BatchDateRead.mergeLookup(suggestion, into: &plain[0])
expect(plain[0].expirySource, .categoryDefault, "without a read, the lookup fills the row as before")

var landed: Date? = nil

// The recent date
expect(BatchDateRead.recentDateSeconds, 2, "two seconds")
expect(BatchDateRead.idleTextReadInterval, 0.3, "300 ms between idle text reads")
var recent: RecentDateRead? = RecentDateRead(date: printed, readAt: t0)
expect(BatchDateRead.takeRecent(&recent, at: t0.addingTimeInterval(2)), nil, "too old at two seconds")
expect(recent != nil, true, "an old one is not taken")
expect(BatchDateRead.takeRecent(&recent, at: t0.addingTimeInterval(1.9)), printed, "recent just before two seconds")
expect(recent, nil, "taken once, forgotten")
expect(BatchDateRead.takeRecent(&recent, at: t0.addingTimeInterval(1)), nil, "never twice")

// A batch scan with a recent date
var scanQueue = [row("555")]
var scanWindow: BatchDateWindow? = nil
recent = RecentDateRead(date: printed, readAt: t0)
expect(BatchDateRead.afterScan(of: "555", queue: &scanQueue, window: &scanWindow, recent: &recent, lastLanded: &landed, at: t0.addingTimeInterval(1)), 0, "a recent date lands at the scan")
expect(scanQueue[0].expiryDate, printed, "on the row")
expect(scanQueue[0].expirySource, .detected, "as read off the pack")
expect(scanWindow, nil, "and no window opens")
expect(recent, nil, "the recent date is used up")
BatchDateRead.undo(entryId: "555", in: &scanQueue)
expect(scanQueue[0].expirySource, .fallback, "undo of a recent date")
expect(scanWindow, nil, "undo opens no window")
recent = RecentDateRead(date: printed, readAt: t0)
expect(BatchDateRead.afterScan(of: "555", queue: &scanQueue, window: &scanWindow, recent: &recent, lastLanded: &landed, at: t0.addingTimeInterval(3)), nil, "an old recent date doesn't land")
expect(scanWindow?.entryId, "555", "the window opens instead")
var gs1Queue = [row("666", source: .detected)]
var gs1Window: BatchDateWindow? = nil
recent = RecentDateRead(date: printed, readAt: t0)
expect(BatchDateRead.afterScan(of: "666", queue: &gs1Queue, window: &gs1Window, recent: &recent, lastLanded: &landed, at: t0), nil, "a row that may not take it")
expect(gs1Queue[0].expiryDate, placeholder, "keeps its GS1 date")
expect(recent, nil, "the scan still uses up the recent date (F2)")
expect(gs1Window, nil, "and opens no window for a row that can't take a date (F4)")

// A date read in Batch mode
var readQueue = [row("777")]
var readWindow = BatchDateRead.window(afterScanOf: readQueue[0], at: t0)
var readRecent: RecentDateRead? = nil
expect(BatchDateRead.dateRead(printed, queue: &readQueue, window: &readWindow, recent: &readRecent, lastLanded: &landed, at: t0.addingTimeInterval(1)), 0, "lands through the window")
expect(readWindow, nil, "which closes")
expect(readRecent, nil, "a landed date is never kept as recent")
expect(BatchDateRead.dateRead(t0, queue: &readQueue, window: &readWindow, recent: &readRecent, lastLanded: &landed, at: t0.addingTimeInterval(2)), nil, "no window: not landed")
expect(readRecent, RecentDateRead(date: t0, readAt: t0.addingTimeInterval(2)), "kept as recent instead")

// Removing the target closes the window
var removeWindow = BatchDateRead.window(afterScanOf: row("888"), at: t0)
BatchDateRead.rowRemoved("999", window: &removeWindow)
expect(removeWindow?.entryId, "888", "another row's removal leaves it")
BatchDateRead.rowRemoved("888", window: &removeWindow)
expect(removeWindow, nil, "the target's removal closes it")

// Rescan
let production = t0.addingTimeInterval(-30 * 86_400)
let bestBefore = t0.addingTimeInterval(40 * 86_400)
expect(BatchDateRead.window(forRescanOf: row("receipt_2"), at: t0), nil, "no rescan of a receipt row")
expect(BatchDateRead.window(forRescanOf: row("1", lookingUp: true), at: t0), nil, "no rescan while looking up")
var rescanQueue = [BatchScanEntry(id: "900", barcode: "900", name: "900", expiryDate: production,
                                  storageLocation: "Fridge", expirySource: .detected, dateSetByHand: true)]
var rescanWindow = BatchDateRead.window(forRescanOf: rescanQueue[0], at: t0)
expect(rescanWindow?.rescan, true, "a rescan window")
var rescanRecent: RecentDateRead? = nil
expect(BatchDateRead.dateRead(production.addingTimeInterval(3600), queue: &rescanQueue, window: &rescanWindow, recent: &rescanRecent, lastLanded: &landed, at: t0.addingTimeInterval(1)), nil, "the same date is ignored")
expect(rescanWindow?.entryId, "900", "and the window stays open")
expect(BatchDateRead.dateRead(bestBefore, queue: &rescanQueue, window: &rescanWindow, recent: &rescanRecent, lastLanded: &landed, at: t0.addingTimeInterval(2)), 0, "another date replaces a detected, hand-set one")
expect(rescanQueue[0].expiryDate, bestBefore, "the new date")
expect(rescanQueue[0].expirySource, .detected, "read off the pack")
expect(rescanQueue[0].dateSetByHand, false, "no longer the person's own")
expect(rescanWindow, nil, "the rescan window closes")
BatchDateRead.undo(entryId: "900", in: &rescanQueue)
expect(rescanQueue[0].expiryDate, production, "undo restores the date before the rescan")
expect(rescanQueue[0].expirySource, .detected, "and its source")
expect(rescanQueue[0].dateSetByHand, true, "and that it was set by hand")
var lateRescan = BatchDateRead.window(forRescanOf: rescanQueue[0], at: t0)
expect(BatchDateRead.dateRead(bestBefore, queue: &rescanQueue, window: &lateRescan, recent: &rescanRecent, lastLanded: &landed, at: t0.addingTimeInterval(10)), nil, "a rescan window runs out too")
var rescanThenScan = BatchDateRead.window(forRescanOf: rescanQueue[0], at: t0)
var scanRecent: RecentDateRead? = RecentDateRead(date: bestBefore, readAt: t0)
_ = BatchDateRead.afterScan(of: "900", queue: &rescanQueue, window: &rescanThenScan, recent: &scanRecent, lastLanded: &landed, at: t0.addingTimeInterval(1))
expect(rescanThenScan, nil, "a scan ends a rescan; its detected row opens no window (F4)")
expect(rescanQueue[0].expiryDate, production, "the recent date never goes on a detected row, rescan or not")

// A read of the day that last landed is not kept as recent (the same pack still in view)
var sameQueue = [row("a1"), row("a2")]
var sameWindow = BatchDateRead.window(afterScanOf: sameQueue[0], at: t0)
var sameRecent: RecentDateRead? = nil
var sameLanded: Date? = nil
_ = BatchDateRead.dateRead(printed, queue: &sameQueue, window: &sameWindow, recent: &sameRecent, lastLanded: &sameLanded, at: t0)
expect(sameLanded, printed, "a landed date is remembered")
_ = BatchDateRead.dateRead(printed.addingTimeInterval(60), queue: &sameQueue, window: &sameWindow, recent: &sameRecent, lastLanded: &sameLanded, at: t0.addingTimeInterval(3))
expect(sameRecent, nil, "the same day read again is not kept as recent")
expect(BatchDateRead.afterScan(of: "a2", queue: &sameQueue, window: &sameWindow, recent: &sameRecent, lastLanded: &sameLanded, at: t0.addingTimeInterval(4)), nil, "so it doesn't land on the next scan")
expect(sameWindow?.entryId, "a2", "which opens its window instead")
expect(BatchDateRead.dateRead(printed, queue: &sameQueue, window: &sameWindow, recent: &sameRecent, lastLanded: &sameLanded, at: t0.addingTimeInterval(5)), 1, "inside a window the same day still lands")
BatchDateRead.undo(entryId: "a2", in: &sameQueue)
expect(sameLanded, printed, "undo leaves the last landed date")
_ = BatchDateRead.dateRead(bestBefore, queue: &sameQueue, window: &sameWindow, recent: &sameRecent, lastLanded: &sameLanded, at: t0.addingTimeInterval(6))
expect(sameRecent?.date, bestBefore, "another day is kept as recent")
var keepRecent: RecentDateRead? = nil
BatchDateRead.keepRecent(printed, recent: &keepRecent, lastLanded: nil, at: t0)
expect(keepRecent?.date, printed, "with nothing landed yet, kept")
var recentLanded: Date? = nil
var rq = [row("b1")]
var rw: BatchDateWindow? = nil
var rr: RecentDateRead? = RecentDateRead(date: printed, readAt: t0)
_ = BatchDateRead.afterScan(of: "b1", queue: &rq, window: &rw, recent: &rr, lastLanded: &recentLanded, at: t0)
expect(recentLanded, printed, "a recent date landing counts as landed")

// F1: a read that doesn't land while a window is open is never kept as recent
var f1Queue = [row("c1", source: .detected)]
var f1Window: BatchDateWindow? = BatchDateWindow(entryId: "c1", closesAt: t0.addingTimeInterval(10))
var f1Recent: RecentDateRead? = nil
var f1Landed: Date? = nil
expect(BatchDateRead.dateRead(bestBefore, queue: &f1Queue, window: &f1Window, recent: &f1Recent, lastLanded: &f1Landed, at: t0.addingTimeInterval(1)), nil, "a detected row takes nothing")
expect(f1Recent, nil, "and the read isn't kept while the window is open")
var f1Rescan = [BatchScanEntry(id: "c2", barcode: "c2", name: "c2", expiryDate: production, storageLocation: "Fridge", expirySource: .detected)]
var f1RescanWindow = BatchDateRead.window(forRescanOf: f1Rescan[0], at: t0)
expect(BatchDateRead.dateRead(production, queue: &f1Rescan, window: &f1RescanWindow, recent: &f1Recent, lastLanded: &f1Landed, at: t0.addingTimeInterval(1)), nil, "the same day in a rescan")
expect(f1Recent, nil, "isn't kept either")
expect(f1RescanWindow?.rescan, true, "and the rescan stays open")
_ = BatchDateRead.dateRead(bestBefore, queue: &f1Queue, window: &f1Window, recent: &f1Recent, lastLanded: &f1Landed, at: t0.addingTimeInterval(11))
expect(f1Recent?.date, bestBefore, "once the window has run out it is kept")

// F2 / F3 / F4: a GS1 scan
var gQueue = [row("d1", source: .detected), row("d2")]
var gWindow: BatchDateWindow? = nil
var gRecent: RecentDateRead? = RecentDateRead(date: bestBefore, readAt: t0)
var gLanded: Date? = nil
expect(BatchDateRead.afterScan(of: "d1", gs1Date: printed, queue: &gQueue, window: &gWindow, recent: &gRecent, lastLanded: &gLanded, at: t0), nil, "a GS1 row takes no read date")
expect(gLanded, printed, "its GS1 date counts as landed (F3)")
expect(gRecent, nil, "the recent date is used up anyway (F2)")
expect(gWindow, nil, "no window for it (F4)")
BatchDateRead.keepRecent(printed, recent: &gRecent, lastLanded: gLanded, at: t0.addingTimeInterval(3))
expect(gRecent, nil, "the same pack's printed date is not kept")
expect(BatchDateRead.afterScan(of: "d2", queue: &gQueue, window: &gWindow, recent: &gRecent, lastLanded: &gLanded, at: t0.addingTimeInterval(4)), nil, "so it doesn't land on the next product")
expect(gWindow?.entryId, "d2", "which opens its window")
var handQueue = [row("d3", byHand: true)]
var handWindow: BatchDateWindow? = BatchDateRead.window(afterScanOf: row("d2"), at: t0)
_ = BatchDateRead.afterScan(of: "d3", queue: &handQueue, window: &handWindow, recent: &gRecent, lastLanded: &gLanded, at: t0)
expect(handWindow, nil, "a hand-set row opens no window and closes the last one (F4)")

// F5: Barcode mode pauses dates while its held scan has one
expect(BatchDateRead.pausesSingleScanDates(holdingScan: true, hasDate: true), true, "held with a date: paused")
expect(BatchDateRead.pausesSingleScanDates(holdingScan: true, hasDate: false), false, "held without one: reading")
expect(BatchDateRead.pausesSingleScanDates(holdingScan: false, hasDate: false), false, "nothing held: reading")

// F6: the session ends
var eWindow: BatchDateWindow? = BatchDateRead.window(afterScanOf: row("e1"), at: t0)
var eRecent: RecentDateRead? = RecentDateRead(date: printed, readAt: t0)
var eLanded: Date? = printed
BatchDateRead.endSession(window: &eWindow, recent: &eRecent, lastLanded: &eLanded)
expect(eWindow, nil, "no window after the session")
expect(eRecent, nil, "no recent date")
expect(eLanded, nil, "no landed date")

if failures > 0 {
    print("\(failures) failure(s)")
    exit(1)
}
print("BatchDateRead: all checks passed")
