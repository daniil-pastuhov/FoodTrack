import SwiftUI

private struct CalendarDay {
    let date: Date
    let isInCurrentMonth: Bool
}

/// Monday-start weekday index, 0 = Monday … 6 = Sunday, independent of the device's
/// locale/firstWeekday.
private func isoWeekdayIndex(_ date: Date) -> Int {
    (Calendar.current.component(.weekday, from: date) + 5) % 7
}

/// The Monday that starts `date`'s week.
private func weekStart(_ date: Date) -> Date {
    let calendar = Calendar.current
    let day = calendar.startOfDay(for: date)
    return calendar.date(byAdding: .day, value: -isoWeekdayIndex(day), to: day) ?? day
}

/// Fixed 6-week (42-day), Monday-start grid of days covering `month`'s month, padded with
/// leading/trailing days from adjacent months. A fixed cell count keeps the grid height
/// constant when paging between months.
private func monthGrid(for month: Date) -> [CalendarDay] {
    let calendar = Calendar.current
    let components = calendar.dateComponents([.year, .month], from: month)
    guard let firstOfMonth = calendar.date(from: components) else { return [] }
    let gridStart = weekStart(firstOfMonth)

    return (0..<42).compactMap { offset in
        guard let date = calendar.date(byAdding: .day, value: offset, to: gridStart) else { return nil }
        let dateComponents = calendar.dateComponents([.year, .month], from: date)
        let isInCurrentMonth = dateComponents.year == components.year && dateComponents.month == components.month
        return CalendarDay(date: date, isInCurrentMonth: isInCurrentMonth)
    }
}

/// The agenda's buckets, in display order. Hand-ported from `shared`'s `AgendaBucket` /
/// `PantryDerivation.buildAgenda` (iOS doesn't consume the shared module): "this week" and "next
/// week" are Monday-start calendar weeks, and today/tomorrow are checked first, so on a Sunday
/// tomorrow is still `.tomorrow` rather than `.nextWeek`.
private enum AgendaBucket: CaseIterable {
    case overdue, today, tomorrow, thisWeek, nextWeek, later

    var title: String {
        switch self {
        case .overdue: return L10n.calendarSectionOverdue
        case .today: return L10n.calendarSectionToday
        case .tomorrow: return L10n.calendarSectionTomorrow
        case .thisWeek: return L10n.calendarSectionThisWeek
        case .nextWeek: return L10n.calendarSectionNextWeek
        case .later: return L10n.calendarSectionLater
        }
    }

    var color: Color {
        switch self {
        case .overdue: return LarderColor.error
        case .today, .tomorrow: return LarderColor.onSoonContainer
        default: return LarderColor.onSurfaceVariant
        }
    }

    /// Rows under these headings span several days, so each row names its own date.
    var spansSeveralDays: Bool {
        switch self {
        case .overdue, .thisWeek, .nextWeek, .later: return true
        case .today, .tomorrow: return false
        }
    }
}

private struct AgendaSection: Identifiable {
    let bucket: AgendaBucket
    let items: [PantryItem]
    var id: AgendaBucket { bucket }
}

private func buildAgenda(_ items: [PantryItem], today: Date) -> [AgendaSection] {
    // Days from today to this week's Sunday: 0 on a Sunday, 6 on a Monday.
    let daysLeftInWeek = 6 - isoWeekdayIndex(today)
    let byBucket = Dictionary(grouping: items) { item -> AgendaBucket in
        let days = item.daysRemaining
        if days < 0 { return .overdue }
        if days == 0 { return .today }
        if days == 1 { return .tomorrow }
        if days <= daysLeftInWeek { return .thisWeek }
        if days <= daysLeftInWeek + 7 { return .nextWeek }
        return .later
    }
    return AgendaBucket.allCases.compactMap { bucket in
        guard let items = byBucket[bucket] else { return nil }
        let sorted = items.sorted {
            if $0.expiryDate != $1.expiryDate { return $0.expiryDate < $1.expiryDate }
            return $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
        }
        return AgendaSection(bucket: bucket, items: sorted)
    }
}

/// The week strip pages over a fixed window of weeks either side of the current one — about ten
/// years, which no pantry item plausibly outlives.
private let weekOffsetRange = -520...520

/// The Calendar tab: a swipeable week strip (or, from the title, the full month grid) over an
/// agenda of everything in the pantry bucketed by when it expires — Overdue first, then Today,
/// Tomorrow, the rest of this week, next week and later. Tapping a day narrows the agenda to that
/// day; tapping it again (or "Show all") widens it back.
///
/// Agenda rows take the same actions as elsewhere: tap to edit, swipe right to use one, swipe
/// left to throw it away. The actions come in from `RootTabView`, so they share its undo toast.
struct CalendarView: View {
    @ObservedObject var viewModel: NativePantryViewModel
    let onEditItem: (PantryItem) -> Void
    let onUseOne: (PantryItem) -> Void
    let onToss: (PantryItem) -> Void

    @ObservedObject private var settingsStore = SettingsStore.shared
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    @State private var showMonth = false
    /// Some date inside the week or month currently on screen; the title shows its month.
    @State private var anchor: Date = Date()
    /// Week strip page, as a whole number of weeks from the current one.
    @State private var weekOffset: Int? = 0
    /// Nil shows the whole agenda; a date narrows it to that day.
    @State private var selectedDay: Date? = nil

    private var today: Date { Calendar.current.startOfDay(for: Date()) }

    private var groupedItems: [Date: [PantryItem]] {
        Dictionary(grouping: viewModel.items) { Calendar.current.startOfDay(for: $0.expiryDate) }
    }

    var body: some View {
        let grouped = groupedItems
        NavigationStack {
            Group {
                // Regular width (iPad, landscape on larger phones) puts the agenda beside the
                // week strip / month grid; compact width stacks it below instead.
                if horizontalSizeClass == .regular {
                    HStack(spacing: 0) {
                        navigator(grouped: grouped)
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                        Divider()
                        agenda(grouped: grouped)
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                    }
                } else {
                    VStack(spacing: 0) {
                        navigator(grouped: grouped)
                        Divider()
                        agenda(grouped: grouped)
                    }
                }
            }
            .background(LarderColor.background.ignoresSafeArea())
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) { monthToggleTitle }
            }
        }
    }

    // MARK: - Title

    private var monthToggleTitle: some View {
        Button {
            // Open the other view on whatever the user is looking at.
            if let selectedDay { anchor = selectedDay }
            if showMonth {
                weekOffset = weeksFromToday(to: anchor)
            }
            withAnimation(.easeInOut(duration: 0.2)) { showMonth.toggle() }
        } label: {
            HStack(spacing: 4) {
                Text(monthYearFormatter.string(from: anchor))
                    .font(.headline)
                Image(systemName: showMonth ? "chevron.up" : "chevron.down")
                    .font(.caption.weight(.semibold))
            }
            .foregroundColor(LarderColor.onSurface)
        }
        .accessibilityLabel(showMonth ? L10n.calendarShowWeek : L10n.calendarShowMonth)
    }

    // MARK: - Week strip / month grid

    @ViewBuilder
    private func navigator(grouped: [Date: [PantryItem]]) -> some View {
        VStack(spacing: 0) {
            if showMonth {
                monthSection(grouped: grouped)
                    .transition(.opacity)
            } else {
                weekStrip(grouped: grouped)
                    .transition(.opacity)
            }
        }
        .padding(.horizontal, 8)
        .padding(.bottom, 4)
    }

    private func weekStrip(grouped: [Date: [PantryItem]]) -> some View {
        let currentWeekStart = weekStart(today)
        return VStack(spacing: 4) {
            weekdayHeader
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 0) {
                    ForEach(weekOffsetRange, id: \.self) { offset in
                        let start = Calendar.current.date(byAdding: .day, value: offset * 7, to: currentWeekStart) ?? currentWeekStart
                        HStack(spacing: 0) {
                            ForEach(0..<7, id: \.self) { index in
                                let date = Calendar.current.date(byAdding: .day, value: index, to: start) ?? start
                                dayCell(date: date, dimmed: false, grouped: grouped)
                            }
                        }
                        .containerRelativeFrame(.horizontal)
                    }
                }
                .scrollTargetLayout()
            }
            // A horizontal ScrollView takes all the height it's offered; pin it to one row.
            .frame(height: CalendarDayCell.height)
            .scrollTargetBehavior(.paging)
            .scrollPosition(id: $weekOffset)
            .onChange(of: weekOffset) { _, offset in
                // Thursday decides which month a week belongs to (ISO 8601), so a week split
                // across two months is titled by the one holding most of it.
                guard let offset else { return }
                anchor = Calendar.current.date(byAdding: .day, value: offset * 7 + 3, to: currentWeekStart) ?? anchor
            }
        }
    }

    private func monthSection(grouped: [Date: [PantryItem]]) -> some View {
        VStack(spacing: 4) {
            HStack {
                Button {
                    anchor = shiftMonth(of: anchor, by: -1)
                } label: {
                    Image(systemName: "chevron.left")
                        .frame(width: 44, height: 44)
                }
                .accessibilityLabel(L10n.calendarPreviousMonth)
                Spacer()
                Button {
                    anchor = shiftMonth(of: anchor, by: 1)
                } label: {
                    Image(systemName: "chevron.right")
                        .frame(width: 44, height: 44)
                }
                .accessibilityLabel(L10n.calendarNextMonth)
            }
            weekdayHeader
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 0), count: 7), spacing: 2) {
                ForEach(monthGrid(for: anchor), id: \.date) { day in
                    dayCell(date: day.date, dimmed: !day.isInCurrentMonth, grouped: grouped)
                }
            }
        }
    }

    private var weekdayHeader: some View {
        HStack(spacing: 0) {
            ForEach(Array(weekdaySymbols.enumerated()), id: \.offset) { _, symbol in
                Text(symbol)
                    .font(.caption2)
                    .fontWeight(.semibold)
                    .foregroundColor(LarderColor.onSurfaceVariant)
                    .frame(maxWidth: .infinity)
            }
        }
    }

    private func dayCell(date: Date, dimmed: Bool, grouped: [Date: [PantryItem]]) -> some View {
        let items = grouped[date] ?? []
        let isSelected = selectedDay.map { Calendar.current.isDate($0, inSameDayAs: date) } ?? false
        return CalendarDayCell(
            date: date,
            dimmed: dimmed,
            isSelected: isSelected,
            count: items.count,
            status: mostUrgentStatus(items)
        )
        .contentShape(Rectangle())
        .onTapGesture {
            withAnimation(.easeInOut(duration: 0.2)) {
                selectedDay = isSelected ? nil : date
            }
        }
    }

    private func mostUrgentStatus(_ items: [PantryItem]) -> ExpiryStatus? {
        guard !items.isEmpty else { return nil }
        let statuses = items.map { $0.expiryStatus(expiringSoonDays: settingsStore.settings.expiringSoonDays) }
        if statuses.contains(.expired) { return .expired }
        if statuses.contains(.expiringToday) { return .expiringToday }
        if statuses.contains(.expiringSoon) { return .expiringSoon }
        return .fresh
    }

    // MARK: - Agenda

    @ViewBuilder
    private func agenda(grouped: [Date: [PantryItem]]) -> some View {
        List {
            if let selectedDay {
                Section {
                    let dayItems = (grouped[Calendar.current.startOfDay(for: selectedDay)] ?? [])
                        .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
                    if dayItems.isEmpty {
                        emptyMessage(L10n.calendarNoItemsForDay)
                    } else {
                        ForEach(dayItems) { agendaRow($0, showDate: false) }
                    }
                } header: {
                    HStack {
                        Text(dayDetailFormatter.string(from: selectedDay))
                            .font(.subheadline.weight(.semibold))
                            .foregroundColor(LarderColor.onSurface)
                            .textCase(nil)
                        Spacer()
                        Button(L10n.calendarShowAll) {
                            withAnimation(.easeInOut(duration: 0.2)) { self.selectedDay = nil }
                        }
                        .font(.subheadline)
                        .textCase(nil)
                    }
                    .agendaLeadingInset()
                }
            } else {
                let sections = buildAgenda(viewModel.items, today: today)
                if sections.isEmpty {
                    emptyMessage(L10n.calendarAgendaEmpty)
                }
                ForEach(sections) { section in
                    Section {
                        ForEach(section.items) { agendaRow($0, showDate: section.bucket.spansSeveralDays) }
                    } header: {
                        HStack(spacing: 6) {
                            Text(section.bucket.title)
                                .fontWeight(.bold)
                                .foregroundColor(section.bucket.color)
                            Text("\(section.items.count)")
                                .foregroundColor(LarderColor.onSurfaceVariant)
                        }
                        .font(.caption)
                        .accessibilityElement(children: .combine)
                        .agendaLeadingInset()
                    }
                }
            }
        }
        .listStyle(.plain)
        .larderListBackground()
        .animation(.default, value: viewModel.items)
    }

    private func emptyMessage(_ text: String) -> some View {
        Text(text)
            .font(.subheadline)
            .foregroundColor(LarderColor.onSurfaceVariant)
            .listRowSeparator(.hidden)
            .agendaLeadingInset()
    }

    private func agendaRow(_ item: PantryItem, showDate: Bool) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            if showDate {
                Text(rowDateFormatter.string(from: item.expiryDate))
                    .font(.caption)
                    .fontWeight(.semibold)
                    .foregroundColor(LarderColor.onSurfaceVariant)
            }
            ItemRowView(
                item: item,
                expiringSoonDays: settingsStore.settings.expiringSoonDays,
                categoryPreferences: viewModel.categoryPreferences
            )
        }
        .contentShape(Rectangle())
        .listRowBackground(Color.clear)
        .onTapGesture { onEditItem(item) }
        .swipeActions(edge: .leading, allowsFullSwipe: true) {
            Button {
                onUseOne(item)
            } label: {
                Label(L10n.calendarSwipeUsed, systemImage: "checkmark")
            }
            .tint(LarderColor.primary)
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
            Button(role: .destructive) {
                onToss(item)
            } label: {
                Label(L10n.calendarSwipeTossed, systemImage: "trash.fill")
            }
        }
        .accessibilityAction(named: L10n.calendarSwipeUsed) { onUseOne(item) }
        .accessibilityAction(named: L10n.calendarSwipeTossed) { onToss(item) }
        .agendaLeadingInset()
    }

    // MARK: - Helpers

    private func weeksFromToday(to date: Date) -> Int {
        let days = Calendar.current.dateComponents([.day], from: weekStart(today), to: weekStart(date)).day ?? 0
        return Int((Double(days) / 7).rounded())
    }

    private func shiftMonth(of date: Date, by months: Int) -> Date {
        let calendar = Calendar.current
        let firstOfMonth = calendar.date(from: calendar.dateComponents([.year, .month], from: date)) ?? date
        return calendar.date(byAdding: .month, value: months, to: firstOfMonth) ?? date
    }

    private var weekdaySymbols: [String] {
        // veryShortStandaloneWeekdaySymbols is Sunday-first; rotate to Monday-first to match the grid.
        let symbols = Calendar.current.veryShortStandaloneWeekdaySymbols
        return Array(symbols[1...]) + [symbols[0]]
    }

    private var monthYearFormatter: DateFormatter {
        let formatter = DateFormatter()
        formatter.setLocalizedDateFormatFromTemplate("MMMM yyyy")
        return formatter
    }

    private var dayDetailFormatter: DateFormatter {
        let formatter = DateFormatter()
        formatter.setLocalizedDateFormatFromTemplate("EEEE MMMM d")
        return formatter
    }

    private var rowDateFormatter: DateFormatter {
        let formatter = DateFormatter()
        formatter.setLocalizedDateFormatFromTemplate("EEE d MMM")
        return formatter
    }
}

/// A day number over a count of the items expiring that day, coloured by the most urgent of
/// them. A count rather than a dot, so a day with six things to deal with doesn't look the same
/// as a day with one.
private struct CalendarDayCell: View {
    let date: Date
    let dimmed: Bool
    let isSelected: Bool
    let count: Int
    let status: ExpiryStatus?

    static let height: CGFloat = 50

    private var isToday: Bool { Calendar.current.isDateInToday(date) }

    private var badgeColor: Color? {
        switch status {
        case .none: return nil
        case .expired: return LarderColor.expiredContainer
        case .expiringToday: return LarderColor.todayContainer
        case .expiringSoon: return LarderColor.soonContainer
        case .fresh: return LarderColor.surfaceContainer
        }
    }

    private var badgeTextColor: Color {
        switch status {
        case .none, .fresh: return LarderColor.onSurfaceVariant
        case .expired: return LarderColor.onExpiredContainer
        case .expiringToday: return LarderColor.onTodayContainer
        case .expiringSoon: return LarderColor.onSoonContainer
        }
    }

    var body: some View {
        VStack(spacing: 2) {
            Text("\(Calendar.current.component(.day, from: date))")
                .font(.system(size: 15))
                .fontWeight(isToday || isSelected ? .bold : .regular)
                .foregroundColor(textColor)
                .frame(width: 30, height: 30)
                .background(background)
                .clipShape(Circle())

            if count > 0, let badgeColor {
                Text(count > 9 ? "9+" : "\(count)")
                    .font(.system(size: 9, weight: .bold))
                    .foregroundColor(badgeTextColor)
                    .padding(.horizontal, 4)
                    .frame(minWidth: 14, minHeight: 14)
                    .background(Capsule().fill(dimmed ? badgeColor.opacity(0.4) : badgeColor))
            } else {
                Color.clear.frame(width: 14, height: 14)
            }
        }
        .frame(maxWidth: .infinity, minHeight: Self.height)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText)
        .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : .isButton)
    }

    private var accessibilityText: String {
        let dateText = DateFormatter.localizedString(from: date, dateStyle: .long, timeStyle: .none)
        return count > 0 ? "\(dateText), \(L10n.calendarDayItemCount(count))" : dateText
    }

    private var background: Color {
        if isToday { return LarderColor.primary }
        if isSelected { return LarderColor.primaryContainer }
        return .clear
    }

    private var textColor: Color {
        if isToday { return LarderColor.onPrimary }
        if dimmed { return LarderColor.onSurfaceVariant.opacity(0.4) }
        return LarderColor.onSurface
    }
}

/// Pins the agenda's leading inset to 20pt when it sits beside the week strip (regular width).
/// A plain List takes that inset from its layout margins, and on the iPhone Duo's inner display
/// those come out as zero for a list that starts mid-screen, so rows and headers touched the
/// divider. Setting the inset (rather than padding, which would add to the 20pt iPad already
/// gets) gives both the same margin. Compact width keeps the system default (nil).
private struct AgendaLeadingInset: ViewModifier {
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.listRowInsets(.leading, horizontalSizeClass == .regular ? 20 : nil)
        } else {
            content
        }
    }
}

private extension View {
    func agendaLeadingInset() -> some View {
        modifier(AgendaLeadingInset())
    }
}
