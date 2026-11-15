// The cases iosApp/ReviewPromptCheck/check.sh runs against ReviewPrompt.swift; the same ones
// as shared/src/commonTest/.../review/ReviewPromptRuleTest.kt.
import Foundation

final class MemStore: ReviewPromptStore {
    var record = ReviewPromptRecord()
    func load() -> ReviewPromptRecord { record }
    func save(_ record: ReviewPromptRecord) { self.record = record }
}

var failures = 0
func check(_ cond: Bool, _ msg: String, line: Int = #line) {
    if !cond { failures += 1; print("FAIL line \(line): \(msg)") }
}

let day: TimeInterval = 86_400
let start = Date(timeIntervalSince1970: 1_800_000_000)

MainActor.assumeIsolated {
    // Rule
    let eligible = ReviewPromptRecord(firstLaunch: start, mostItemsSeen: 2, usesBeforeDate: 3)
    let later = start.addingTimeInterval(7 * day)
    check(ReviewPromptRule.mayAsk(eligible, moment: .usedBeforeDate, now: later, appVersion: "2.1.0", sessionBlocked: false), "eligible")
    check(!ReviewPromptRule.mayAsk(eligible, moment: .usedBeforeDate, now: later.addingTimeInterval(-1), appVersion: "2.1.0", sessionBlocked: false), "7 days")
    check(!ReviewPromptRule.mayAsk(eligible, moment: .usedBeforeDate, now: later, appVersion: "2.1.0", sessionBlocked: true), "blocked")
    var two = eligible; two.usesBeforeDate = 2
    check(!ReviewPromptRule.mayAsk(two, moment: .usedBeforeDate, now: later, appVersion: "2.1.0", sessionBlocked: false), "3 uses")
    check(ReviewPromptRule.mayAsk(two, moment: .recipeLeft, now: later, appVersion: "2.1.0", sessionBlocked: false), "recipe needs no uses")
    var one = eligible; one.mostItemsSeen = 1
    check(!ReviewPromptRule.mayAsk(one, moment: .recipeLeft, now: later, appVersion: "2.1.0", sessionBlocked: false), "2 items")
    var asked = eligible; asked.lastRequest = later; asked.lastRequestVersion = "2.1.0"
    check(!ReviewPromptRule.mayAsk(asked, moment: .usedBeforeDate, now: later.addingTimeInterval(400 * day), appVersion: "2.1.0", sessionBlocked: false), "once per version")
    check(!ReviewPromptRule.mayAsk(asked, moment: .usedBeforeDate, now: later.addingTimeInterval(120 * day - 1), appVersion: "2.2.0", sessionBlocked: false), "120 days")
    check(ReviewPromptRule.mayAsk(asked, moment: .usedBeforeDate, now: later.addingTimeInterval(120 * day), appVersion: "2.2.0", sessionBlocked: false), "after 120 days")
    check(!ReviewPromptRule.mayAsk(eligible, moment: .usedBeforeDate, now: start.addingTimeInterval(-400 * day), appVersion: "2.1.0", sessionBlocked: false), "clock back")
    check(ReviewPromptRule.isBeforeDate(daysRemaining: 0) && !ReviewPromptRule.isBeforeDate(daysRemaining: -1), "on the date")

    // Prompter
    var now = start
    let store = MemStore()
    var asks = 0
    let p = ReviewPrompter(store: store, appVersion: "2.1.0", now: { now }, suppressed: false)
    check(store.record.firstLaunch == start, "first launch recorded")
    p.pantrySize(2); p.pantrySize(0)
    check(store.record.mostItemsSeen == 2, "peak")
    now = start.addingTimeInterval(7 * day)
    check(!p.usedOne(daysRemaining: 1) { asks += 1 }, "1st")
    check(!p.usedOne(daysRemaining: -1) { asks += 1 }, "past date")
    check(!p.usedOne(daysRemaining: 0) { asks += 1 }, "2nd")
    check(p.usedOne(daysRemaining: 3) { asks += 1 }, "3rd asks")
    check(asks == 1 && store.record.lastRequestVersion == "2.1.0", "recorded")
    check(!p.usedOne(daysRemaining: 3) { asks += 1 }, "not twice")

    let q = ReviewPrompter(store: MemStore(), appVersion: "2.1.0", now: { now }, suppressed: false)
    check(!q.recipeLeft { asks += 1 }, "not armed")
    let r = ReviewPrompter(store: { let s = MemStore(); s.record = ReviewPromptRecord(firstLaunch: start, mostItemsSeen: 2); return s }(), appVersion: "2.1.0", now: { now }, suppressed: false)
    r.recipeArrived()
    check(r.recipeLeft { asks += 1 }, "recipe left asks")
    let s = ReviewPrompter(store: { let s = MemStore(); s.record = ReviewPromptRecord(firstLaunch: start, mostItemsSeen: 2); return s }(), appVersion: "2.1.0", now: { now }, suppressed: true)
    s.recipeArrived()
    check(!s.recipeLeft { asks += 1 }, "demo suppressed")
    let t = ReviewPrompter(store: { let s = MemStore(); s.record = ReviewPromptRecord(firstLaunch: start, mostItemsSeen: 2); return s }(), appVersion: "2.1.0", now: { now }, suppressed: false)
    t.problemShown(); t.recipeArrived()
    check(!t.recipeLeft { asks += 1 }, "problem blocks")

    // Codable round trip through UserDefaults
    let defaults = UserDefaults(suiteName: "reviewcheck-\(UUID().uuidString)")!
    let ud = UserDefaultsReviewPromptStore(defaults: defaults)
    check(ud.load() == ReviewPromptRecord(), "fresh")
    ud.save(asked)
    check(ud.load() == asked, "round trip")
    defaults.set(Data("{x".utf8), forKey: UserDefaultsReviewPromptStore.storageKey)
    check(ud.load() == ReviewPromptRecord(), "broken")
}
print(failures == 0 ? "ALL PASSED" : "\(failures) FAILED")
exit(failures == 0 ? 0 : 1)
