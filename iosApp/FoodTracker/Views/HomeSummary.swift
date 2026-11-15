import Foundation

/// The sentence at the top of Home.
enum HomeLede: Equatable {
    /// An empty pantry has no sentence: the empty state speaks.
    case none
    /// The count includes past-date items, because they want using as much as today's.
    case useByTomorrow(Int)
    case pastDate(Int)
    case nextUp(name: String, date: Date)
    case nothingThisWeek

    var text: String? {
        switch self {
        case .none: return nil
        case .useByTomorrow(let count): return L10n.ledeUseByTomorrow(count)
        case .pastDate(let count): return L10n.ledePastDate(count)
        case .nextUp(let name, let date):
            // Calendar's weekday starts at Sunday = 1.
            let weekday = Calendar.current.component(.weekday, from: date)
            return L10n.ledeNextUp(name, L10n.ledeOnWeekday((weekday + 5) % 7 + 1))
        case .nothingThisWeek: return L10n.ledeNothingThisWeek
        }
    }
}

/// What heads a group of rows on Home. `place` holds the stored key, not a label.
enum HomeSectionTitle: Hashable {
    case useFirst, laterThisWeek, later
    case place(String)

    var text: String {
        switch self {
        case .useFirst: return L10n.homeSectionUseFirst
        case .laterThisWeek: return L10n.homeSectionLaterThisWeek
        case .later: return L10n.homeSectionLater
        case .place(let location): return L10n.storageLocationName(location)
        }
    }
}

/// A run of rows on Home. A nil title is one flat list with no heading.
struct HomeSection: Identifiable, Equatable {
    let title: HomeSectionTitle?
    let items: [PantryItem]

    var id: String { title.map { "\($0)" } ?? "flat" }
}

/// Swift port of `shared`'s `HomeSummary`. iOS has no test target, so its tests live on the
/// Kotlin side: keep the two in step.
enum HomeSummary {
    /// Today and tomorrow are "use first".
    private static let useFirstLastDay = 1
    /// The sentence and "Later this week" look this far ahead.
    private static let weekLastDay = 7
    private static let countCap = 999

    /// Always takes the whole pantry, so the sentence doesn't jump when a filter changes. Past-date
    /// items only count towards "to use by tomorrow" when something is actually due by then.
    static func lede(_ items: [PantryItem]) -> HomeLede {
        guard !items.isEmpty else { return .none }
        let past = items.filter { $0.daysRemaining < 0 }.count
        let dueSoon = items.filter { (0...useFirstLastDay).contains($0.daysRemaining) }.count
        if dueSoon > 0 { return .useByTomorrow(past + dueSoon) }
        if past > 0 { return .pastDate(past) }
        let next = items
            .filter { $0.daysRemaining <= weekLastDay }
            .min { ($0.daysRemaining, $0.name.lowercased()) < ($1.daysRemaining, $1.name.lowercased()) }
        guard let next else { return .nothingThisWeek }
        return .nextUp(name: next.name, date: next.expiryDate)
    }

    /// Groups already sorted items for display. The date sections belong to the expiry sort, and
    /// the location sort gets one section per place. The other sorts are one flat list.
    static func sections(_ items: [PantryItem], sort: SortOption) -> [HomeSection] {
        guard !items.isEmpty else { return [] }
        switch sort {
        case .expiryDate:
            return [
                HomeSection(title: .useFirst, items: items.filter { $0.daysRemaining <= useFirstLastDay }),
                HomeSection(
                    title: .laterThisWeek,
                    items: items.filter { ((useFirstLastDay + 1)...weekLastDay).contains($0.daysRemaining) }
                ),
                HomeSection(title: .later, items: items.filter { $0.daysRemaining > weekLastDay }),
            ].filter { !$0.items.isEmpty }
        case .storageLocation:
            var order: [String] = []
            var byPlace: [String: [PantryItem]] = [:]
            for item in items {
                if byPlace[item.storageLocation] == nil { order.append(item.storageLocation) }
                byPlace[item.storageLocation, default: []].append(item)
            }
            return order.map { HomeSection(title: .place($0), items: byPlace[$0] ?? []) }
        case .name, .dateAdded:
            return [HomeSection(title: nil, items: items)]
        }
    }

    /// Whether a count has to read "999+" on its chip.
    static func isCapped(_ count: Int) -> Bool { count > countCap }
    static let cappedLabel = "\(countCap)+"

    /// Which product the right-hand pane shows: the one picked, while it is still listed, else the
    /// first row. Nothing is picked for you while searching, and selecting leaves the pane blank.
    static func paneItem(pickedID: String?, sections: [HomeSection], searching: Bool, selecting: Bool) -> PantryItem? {
        if selecting { return nil }
        let rows = sections.flatMap(\.items)
        if let pickedID, let picked = rows.first(where: { $0.id == pickedID }) { return picked }
        return searching ? nil : rows.first
    }
}
