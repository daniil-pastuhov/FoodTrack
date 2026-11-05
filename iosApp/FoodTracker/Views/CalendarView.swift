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

/// Where the calendar rests after paging `months` from `anchor`: today in today's month, so the
/// week strip reopens on it, and otherwise the 4th, because the week holding the 4th always has
/// its Thursday in that month and so keeps the month's title. Port of `CalendarLogic.pagedAnchor`.
private func pagedAnchor(from anchor: Date, by months: Int, today: Date) -> Date {
    let calendar = Calendar.current
    var components = calendar.dateComponents([.year, .month], from: anchor)
    components.day = 4
    guard let fourth = calendar.date(from: components),
          let target = calendar.date(byAdding: .month, value: months, to: fourth) else { return anchor }
    return calendar.isDate(target, equalTo: today, toGranularity: .month) ? today : target
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

private enum CalendarLayout {
    /// The month grid's pane on expanded windows.
    static let navigatorPaneWidth: CGFloat = 360
}

/// The Calendar tab. On phones a swipeable week strip, or from the title the month grid, over an
/// agenda of everything in the pantry bucketed by when it's due. On expanded windows the month
/// grid sits beside the agenda and there is no week strip. Tapping a day narrows the agenda to
/// that day; tapping it again, or Show all, widens it back.
///
/// A row opens the product detail, swipe right uses one and swipe left tosses the whole item, each
/// with Undo. Those two say "used" and "tossed" rather than the "removed" of `RootTabView`'s
/// handlers, so the last unit and the toss run here; a use that leaves the row in place goes
/// through `onUseOne`, which shares its wording with Home.
///
/// `onEditItem` and `onToss` are unused now, until `RootTabView` stops passing them.
struct CalendarView: View {
    @ObservedObject var viewModel: NativePantryViewModel
    let onEditItem: (PantryItem) -> Void
    let onUseOne: (PantryItem) -> Void
    let onToss: (PantryItem) -> Void

    @ObservedObject private var settingsStore = SettingsStore.shared
    @Environment(\.isExpanded) private var isExpanded
    @Environment(\.showSnackbar) private var showSnackbar

    @State private var showMonth = false
    /// Some date inside the week or month currently on screen; the title shows its month.
    @State private var anchor: Date = Date()
    /// Week strip page, as a whole number of weeks from the current one.
    @State private var weekOffset: Int? = 0
    /// Nil shows the whole agenda; a date narrows it to that day.
    @State private var pickedDay: Date? = nil

    @ScaledMetric(relativeTo: .subheadline) private var weekRowHeight: CGFloat = 56

    private var today: Date { Calendar.current.startOfDay(for: Date()) }
    private var pantryEmpty: Bool { viewModel.items.isEmpty }
    private var selectedDay: Date? { pantryEmpty ? nil : pickedDay }
    private var expiringSoonDays: Int { settingsStore.settings.expiringSoonDays }

    private var groupedItems: [Date: [PantryItem]] {
        Dictionary(grouping: viewModel.items) { Calendar.current.startOfDay(for: $0.expiryDate) }
    }

    var body: some View {
        let grouped = groupedItems
        Group {
            if isExpanded {
                HStack(spacing: 0) {
                    VStack(spacing: 0) {
                        header
                        ScrollView {
                            monthPane(grouped: grouped)
                        }
                    }
                    .frame(width: CalendarLayout.navigatorPaneWidth)
                    Rectangle()
                        .fill(LarderColor.outlineVariant)
                        .frame(width: 1)
                        .ignoresSafeArea()
                    VStack(alignment: .leading, spacing: 0) {
                        if selectedDay == nil && !pantryEmpty {
                            Text(L10n.calendarPaneTitle)
                                .larderFont(.headline)
                                .foregroundStyle(LarderColor.onBackground)
                                .accessibilityAddTraits(.isHeader)
                                .frame(minHeight: LarderSize.touch)
                                .padding(.horizontal, LarderSpace.s4)
                                .padding(.top, LarderSpace.s5)
                        }
                        agenda(grouped: grouped, showsMonth: false)
                    }
                }
            } else {
                VStack(spacing: 0) {
                    header
                    if !showMonth {
                        weekStrip(grouped: grouped)
                        LarderDivider()
                    }
                    agenda(grouped: grouped, showsMonth: showMonth)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(LarderColor.background.ignoresSafeArea())
        .shellChromeEffect(hideScan: pantryEmpty)
    }

    // MARK: - Header

    /// The month, which is also the week and month switch on phones. Expanded windows always show
    /// the month, so there it's a plain title. The chevrons go wherever the month grid is showing.
    private var header: some View {
        HStack(spacing: 0) {
            if isExpanded {
                monthTitle
                    .accessibilityAddTraits(.isHeader)
            } else {
                monthToggle
            }
            Spacer(minLength: LarderSpace.s2)
            if isExpanded || showMonth {
                LarderIconButton(.chevronLeft, label: L10n.calendarPreviousMonth) {
                    anchor = pagedAnchor(from: anchor, by: -1, today: today)
                }
                LarderIconButton(.chevronRight, label: L10n.calendarNextMonth) {
                    anchor = pagedAnchor(from: anchor, by: 1, today: today)
                }
            }
        }
        .frame(minHeight: LarderSize.touch)
        .padding(.leading, LarderSpace.s4)
        .padding(.trailing, LarderSpace.s2)
        .padding(.top, isExpanded ? LarderSpace.s5 : LarderSpace.s3)
    }

    private var monthTitle: some View {
        Text(monthTitleText)
            .larderFont(.headline)
            .lineLimit(1)
            .foregroundStyle(LarderColor.onBackground)
    }

    private var monthToggle: some View {
        Button {
            // Open the other view on whatever the user is looking at.
            if let selectedDay { anchor = selectedDay }
            withAnimation(.easeInOut(duration: 0.2)) { showMonth.toggle() }
        } label: {
            HStack(spacing: LarderSpace.s1) {
                monthTitle
                LarderIcon(.chevronDown)
                    .foregroundStyle(LarderColor.onSurfaceVariant)
                    .rotationEffect(.degrees(showMonth ? 180 : 0))
            }
            .frame(minHeight: LarderSize.touch)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(monthTitleText), \(showMonth ? L10n.calendarShowWeek : L10n.calendarShowMonth)")
    }

    private var monthTitleText: String {
        let formatter = DateFormatter()
        // Standalone month and bare year: a template adds Russian's "г.".
        formatter.dateFormat = "LLLL y"
        return formatter.string(from: anchor).capitalized(with: .current)
    }

    // MARK: - Week strip / month grid

    private func weekStrip(grouped: [Date: [PantryItem]]) -> some View {
        let currentWeekStart = weekStart(today)
        // Thursday decides which month a week belongs to (ISO 8601), so a week split across two
        // months is titled by the one holding most of it.
        let thursday = { (offset: Int) in
            Calendar.current.date(byAdding: .day, value: offset * 7 + 3, to: currentWeekStart) ?? anchor
        }
        return VStack(spacing: 0) {
            weekdayHeader
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 0) {
                    ForEach(weekOffsetRange, id: \.self) { offset in
                        let start = Calendar.current.date(byAdding: .day, value: offset * 7, to: currentWeekStart) ?? currentWeekStart
                        HStack(spacing: 0) {
                            ForEach(0..<7, id: \.self) { index in
                                let date = Calendar.current.date(byAdding: .day, value: index, to: start) ?? start
                                dayCell(date: date, isOutsideMonth: false, grouped: grouped)
                            }
                        }
                        .containerRelativeFrame(.horizontal)
                    }
                }
                .scrollTargetLayout()
            }
            // A horizontal ScrollView takes all the height it's offered; pin it to one row.
            .frame(height: weekRowHeight)
            .scrollTargetBehavior(.paging)
            .scrollPosition(id: $weekOffset)
            .onChange(of: weekOffset) { _, offset in
                guard let offset else { return }
                anchor = thursday(offset)
            }
        }
        .padding(.horizontal, LarderSpace.s2)
        .padding(.bottom, LarderSpace.s2)
        .onAppear {
            let offset = weeksFromToday(to: anchor)
            weekOffset = offset
            anchor = thursday(offset)
        }
    }

    /// Six rows always, so the height holds when paging months. The key is hidden on an empty
    /// pantry, when there is nothing to explain.
    private func monthPane(grouped: [Date: [PantryItem]]) -> some View {
        let days = monthGrid(for: anchor)
        return VStack(spacing: 0) {
            weekdayHeader
            ForEach(0..<(days.count / 7), id: \.self) { week in
                HStack(spacing: 0) {
                    ForEach(days[(week * 7)..<(week * 7 + 7)], id: \.date) { day in
                        dayCell(date: day.date, isOutsideMonth: !day.isInCurrentMonth, grouped: grouped)
                    }
                }
            }
            if !pantryEmpty {
                CalendarKey()
                    .padding(.top, isExpanded ? LarderSpace.s4 : LarderSpace.s1)
            }
        }
        .padding(.horizontal, LarderSpace.s2)
        .padding(.top, isExpanded ? LarderSpace.s2 : 0)
        .padding(.bottom, isExpanded ? LarderSpace.s4 : LarderSpace.s2)
    }

    private var weekdayHeader: some View {
        HStack(spacing: 0) {
            ForEach(Array(weekdaySymbols.enumerated()), id: \.offset) { _, symbol in
                Text(symbol)
                    .larderFont(.caption)
                    .foregroundStyle(LarderColor.onSurfaceVariant)
                    .frame(maxWidth: .infinity)
            }
        }
        .padding(.vertical, LarderSpace.s1)
        .accessibilityHidden(true)
    }

    private func dayCell(date: Date, isOutsideMonth: Bool, grouped: [Date: [PantryItem]]) -> some View {
        let items = grouped[date] ?? []
        let isSelected = selectedDay.map { Calendar.current.isDate($0, inSameDayAs: date) } ?? false
        return CalendarDayCell(
            date: date,
            isOutsideMonth: isOutsideMonth,
            isSelected: isSelected,
            count: items.count,
            status: mostUrgentStatus(items),
            isTall: isExpanded
        ) {
            guard !pantryEmpty else { return }
            withAnimation(.easeInOut(duration: 0.2)) {
                pickedDay = isSelected ? nil : date
            }
        }
    }

    private func mostUrgentStatus(_ items: [PantryItem]) -> ExpiryStatus? {
        guard !items.isEmpty else { return nil }
        let statuses = items.map { $0.expiryStatus(expiringSoonDays: expiringSoonDays) }
        if statuses.contains(.expired) { return .expired }
        if statuses.contains(.expiringToday) { return .expiringToday }
        if statuses.contains(.expiringSoon) { return .expiringSoon }
        return .fresh
    }

    // MARK: - Agenda

    /// `showsMonth` puts the month grid at the top of the scrolling content. It's taller than a
    /// short phone has room for beside a list, so it scrolls with the agenda rather than squeezing
    /// it behind the pill and Scan.
    @ViewBuilder
    private func agenda(grouped: [Date: [PantryItem]], showsMonth: Bool) -> some View {
        if pantryEmpty {
            emptyAgenda(grouped: grouped, showsMonth: showsMonth)
        } else {
            List {
                if showsMonth {
                    VStack(spacing: 0) {
                        monthPane(grouped: grouped)
                        LarderDivider()
                    }
                    .listRowInsets(EdgeInsets())
                    .listRowSeparator(.hidden)
                    .listRowBackground(Color.clear)
                }
                if let selectedDay {
                    dayAgenda(selectedDay, grouped: grouped)
                } else {
                    ForEach(buildAgenda(viewModel.items, today: today)) { section in
                        Section {
                            rows(section.items, showDate: section.bucket.spansSeveralDays)
                        } header: {
                            agendaHeading(section)
                        }
                    }
                }
            }
            .larderList()
            .animation(.default, value: viewModel.items)
        }
    }

    /// First use. The body offers Scan, so the floating one steps aside and the screen keeps one
    /// primary action.
    private func emptyAgenda(grouped: [Date: [PantryItem]], showsMonth: Bool) -> some View {
        GeometryReader { proxy in
            ScrollView {
                VStack(spacing: 0) {
                    if showsMonth {
                        monthPane(grouped: grouped)
                        LarderDivider()
                    }
                    EmptyState(.shelf, title: L10n.calendarAgendaEmpty, message: L10n.calendarEmptyMessage) {
                        LarderButton(L10n.homeEmptyScan) {
                            AppRouter.shared.navigate(to: .scanner(.barcode))
                        }
                    }
                    .frame(minHeight: showsMonth ? nil : proxy.size.height)
                }
            }
        }
    }

    @ViewBuilder
    private func dayAgenda(_ day: Date, grouped: [Date: [PantryItem]]) -> some View {
        let items = (grouped[Calendar.current.startOfDay(for: day)] ?? [])
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        plainRow {
            HStack(spacing: LarderSpace.s2) {
                Text(dayHeading(day))
                    .larderFont(.title)
                    .foregroundStyle(LarderColor.onBackground)
                    .accessibilityAddTraits(.isHeader)
                    .frame(maxWidth: .infinity, alignment: .leading)
                LarderButton(L10n.calendarShowAll, variant: .text) {
                    withAnimation(.easeInOut(duration: 0.2)) { pickedDay = nil }
                }
                .padding(.trailing, -LarderSpace.s3)
            }
            .frame(minHeight: LarderSize.touch)
            .padding(.top, LarderSpace.s2)
            .padding(.bottom, LarderSpace.s2)
        }
        if items.isEmpty {
            plainRow {
                Text(L10n.calendarNoItemsForDay)
                    .larderFont(.body)
                    .foregroundStyle(LarderColor.onSurfaceVariant)
                    .padding(.top, LarderSpace.s2)
            }
        } else {
            rows(items, showDate: false)
        }
    }

    /// Sticks to the top of the list, so it paints the page behind itself. The count is rows.
    private func agendaHeading(_ section: AgendaSection) -> some View {
        HStack(spacing: 6) {
            Text(section.bucket.title)
                .larderFont(.label)
            Text("\(section.items.count)")
                .larderFont(.label)
                .fontWeight(.regular)
                .monospacedDigit()
        }
        .foregroundStyle(LarderColor.onSurfaceVariant)
        .textCase(nil)
        .padding(.top, LarderSpace.s3)
        .padding(.bottom, LarderSpace.s2)
        .padding(.horizontal, LarderSpace.s4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(LarderColor.background)
        .listRowInsets(EdgeInsets())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }

    private func plainRow<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        content()
            .listRowInsets(EdgeInsets(top: 0, leading: LarderSpace.s4, bottom: 0, trailing: LarderSpace.s4))
            .listRowSeparator(.hidden)
            .listRowBackground(Color.clear)
    }

    private func rows(_ items: [PantryItem], showDate: Bool) -> some View {
        ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
            agendaRow(item, showDate: showDate, position: LarderGroupPosition(index: index, count: items.count))
        }
    }

    /// One item. Rows under a heading of several days lead with their date; a row under one day
    /// drops it, since the heading gives it. The chip stays relative at any distance for the same
    /// reason: the date is already there.
    ///
    /// Swipe right uses one, swipe left tosses the item. Both are VoiceOver actions too, and on
    /// the detail sheet, so nothing depends on the gesture.
    private func agendaRow(_ item: PantryItem, showDate: Bool, position: LarderGroupPosition) -> some View {
        let place = showDate
            ? [rowDateFormatter.string(from: item.expiryDate), L10n.storageLocationName(item.storageLocation)]
                .joined(separator: " · ")
            : ItemRowView.place(of: item, categoryPreferences: viewModel.categoryPreferences)
        return PantryRow(
            name: item.name,
            status: item.expiryStatus(expiringSoonDays: expiringSoonDays),
            // Any status but fresh counts the days, whatever the distance.
            statusText: L10n.expiryStatusLabel(
                status: .expiringSoon,
                daysRemaining: item.daysRemaining,
                expiryDate: item.expiryDate
            ),
            place: place,
            quantity: item.quantity,
            imageReference: item.imageUrl,
            placeholderIcon: .forCategory(item.category),
            onOpen: { AppRouter.shared.navigate(to: .productDetail(itemId: item.id)) }
        )
        .larderGroupRow(position)
        .larderSwipeActions(
            leading: [LarderSwipeAction(L10n.calendarSwipeUsed, icon: .check, tint: .success) { used(item) }],
            trailing: [LarderSwipeAction(L10n.calendarSwipeTossed, icon: .trash2, tint: .destructive) { tossed(item) }]
        )
        .accessibilityAction(named: L10n.calendarSwipeUsed) { used(item) }
        .accessibilityAction(named: L10n.calendarSwipeTossed) { tossed(item) }
    }

    // MARK: - Actions

    private func used(_ item: PantryItem) {
        guard item.quantity <= 1 else {
            onUseOne(item)
            return
        }
        viewModel.decrementQuantity(item.id) { removed, addedToShoppingList in
            let message = addedToShoppingList
                ? L10n.itemAddedToShoppingListToast(removed.name)
                : L10n.calendarUsedSnackbar(removed.name)
            showSnackbar(message) {
                viewModel.restoreItem(removed, removedFromShoppingList: addedToShoppingList)
            }
        }
    }

    private func tossed(_ item: PantryItem) {
        viewModel.deleteItem(item.id) { removed, _ in
            showSnackbar(L10n.calendarTossedSnackbar(removed.name)) {
                viewModel.restoreItem(removed)
            }
        }
    }

    // MARK: - Helpers

    private func weeksFromToday(to date: Date) -> Int {
        let days = Calendar.current.dateComponents([.day], from: weekStart(today), to: weekStart(date)).day ?? 0
        return Int((Double(days) / 7).rounded())
    }

    private var weekdaySymbols: [String] {
        // veryShortStandaloneWeekdaySymbols is Sunday-first; rotate to Monday-first to match the grid.
        let symbols = Calendar.current.veryShortStandaloneWeekdaySymbols
        return Array(symbols[1...]) + [symbols[0]]
    }

    /// "Friday 6 November", always with a capital. The year joins it when the day isn't this year.
    private func dayHeading(_ day: Date) -> String {
        let calendar = Calendar.current
        let thisYear = calendar.isDate(day, equalTo: today, toGranularity: .year)
        let formatter = DateFormatter()
        formatter.setLocalizedDateFormatFromTemplate(thisYear ? "EEEEdMMMM" : "yEEEEdMMMM")
        let text = formatter.string(from: day)
        return text.prefix(1).uppercased() + text.dropFirst()
    }

    private var rowDateFormatter: DateFormatter {
        let formatter = DateFormatter()
        formatter.setLocalizedDateFormatFromTemplate("EEE d MMM")
        return formatter
    }
}

/// One day, for the week strip and the month grid alike: its number over a mark. `count` is pantry
/// rows, not units, and is read aloud with the date. The selected day gets a ring, and today keeps
/// its fill with a second ring outside it.
private struct CalendarDayCell: View {
    let date: Date
    let isOutsideMonth: Bool
    let isSelected: Bool
    let count: Int
    let status: ExpiryStatus?
    /// The taller cell of the expanded month grid.
    let isTall: Bool
    let action: () -> Void

    /// Scales the number's circle and the cell along with the text inside them.
    @ScaledMetric(relativeTo: .subheadline) private var scale: CGFloat = 1

    private var isToday: Bool { Calendar.current.isDateInToday(date) }
    private var numberSide: CGFloat { (isTall ? 36 : 32) * scale }

    var body: some View {
        Button(action: action) {
            VStack(spacing: isTall ? LarderSpace.s1 : 2) {
                number
                DayMark(status: status, count: count)
                    .frame(minHeight: 16)
            }
            .padding(.top, isTall ? LarderSpace.s2 : LarderSpace.s1)
            .padding(.bottom, 2)
            .frame(maxWidth: .infinity, minHeight: (isTall ? 84 : 56) * scale, alignment: .top)
            .contentShape(RoundedRectangle(cornerRadius: LarderRadius.md))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText)
        .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : .isButton)
    }

    private var number: some View {
        Text("\(Calendar.current.component(.day, from: date))")
            .larderFont(.label)
            .fontWeight(isOutsideMonth && !isToday && !isSelected ? .regular : nil)
            .monospacedDigit()
            .foregroundStyle(numberColor)
            .frame(width: numberSide, height: numberSide)
            .background(numberFill, in: Circle())
            .overlay {
                if isSelected {
                    // Sits outside the fill, and a ring's width further out around today's.
                    Circle()
                        .stroke(LarderColor.primary, lineWidth: 2)
                        .padding(isToday ? -3 : -1)
                }
            }
    }

    private var accessibilityText: String {
        let dateText = DateFormatter.localizedString(from: date, dateStyle: .full, timeStyle: .none)
        return count > 0 ? "\(dateText), \(L10n.calendarDayItemCount(count))" : dateText
    }

    private var numberFill: Color {
        if isToday { return LarderColor.primary }
        if isSelected { return LarderColor.primaryContainer }
        return .clear
    }

    private var numberColor: Color {
        if isToday { return LarderColor.onPrimary }
        if isSelected { return LarderColor.onPrimaryContainer }
        if isOutsideMonth { return LarderColor.onSurfaceVariant }
        return LarderColor.onSurface
    }
}

/// A shape and a count, or only a count for a fresh day. The cap keeps a busy day inside its cell;
/// the section heading gives the true count.
private struct DayMark: View {
    let status: ExpiryStatus?
    let count: Int

    var body: some View {
        if let status, count > 0 {
            HStack(spacing: 3) {
                DayMarkShape(status: status)
                Text(count > 9 ? "9+" : "\(count)")
                    .larderFont(.caption)
                    .monospacedDigit()
                    .foregroundStyle(status == .fresh ? LarderColor.onSurfaceVariant : LarderColor.onSurface)
            }
        }
    }
}

/// The shape carries the status, so a mark reads without colour: square for a past date, circle
/// for today, diamond for soon. The tint only repeats it, and fresh has neither.
private struct DayMarkShape: View {
    let status: ExpiryStatus

    private func outlined<S: InsettableShape>(_ shape: S, fill: Color, line: Color) -> some View {
        shape.fill(fill).overlay(shape.strokeBorder(line, lineWidth: 1.5))
    }

    var body: some View {
        switch status {
        case .expired:
            outlined(RoundedRectangle(cornerRadius: 2), fill: LarderColor.expiredContainer, line: LarderColor.onExpiredContainer)
                .frame(width: 11, height: 11)
        case .expiringToday:
            outlined(Circle(), fill: LarderColor.todayContainer, line: LarderColor.onTodayContainer)
                .frame(width: 11, height: 11)
        case .expiringSoon:
            outlined(Rectangle(), fill: LarderColor.soonContainer, line: LarderColor.onSoonContainer)
                .frame(width: 9, height: 9)
                .rotationEffect(.degrees(45))
                .padding(1)
        case .fresh:
            EmptyView()
        }
    }
}

/// What the marks mean.
private struct CalendarKey: View {
    private func entry<Mark: View>(_ label: String, @ViewBuilder mark: () -> Mark) -> some View {
        HStack(spacing: LarderSpace.s1) {
            mark()
            Text(label)
                .larderFont(.caption)
                .foregroundStyle(LarderColor.onSurfaceVariant)
        }
    }

    var body: some View {
        FlowLayout(spacing: LarderSpace.s4) {
            entry(L10n.metricExpired) { DayMarkShape(status: .expired) }
            entry(L10n.calendarSectionToday) { DayMarkShape(status: .expiringToday) }
            entry(L10n.metricExpiringSoon) { DayMarkShape(status: .expiringSoon) }
            entry(L10n.metricFresh) { DayMark(status: .fresh, count: 1) }
        }
        .frame(maxWidth: .infinity)
    }
}

private struct DayCellGallery: View {
    var body: some View {
        let day = Date()
        let tomorrow = Calendar.current.date(byAdding: .day, value: 1, to: day) ?? day
        VStack(spacing: LarderSpace.s4) {
            HStack(spacing: 0) {
                CalendarDayCell(date: tomorrow, isOutsideMonth: false, isSelected: false, count: 0, status: nil, isTall: false) {}
                CalendarDayCell(date: tomorrow, isOutsideMonth: false, isSelected: false, count: 1, status: .expired, isTall: false) {}
                CalendarDayCell(date: day, isOutsideMonth: false, isSelected: false, count: 1, status: .expiringToday, isTall: false) {}
                CalendarDayCell(date: tomorrow, isOutsideMonth: false, isSelected: false, count: 12, status: .expiringSoon, isTall: false) {}
                CalendarDayCell(date: tomorrow, isOutsideMonth: false, isSelected: false, count: 2, status: .fresh, isTall: false) {}
                CalendarDayCell(date: tomorrow, isOutsideMonth: false, isSelected: true, count: 1, status: .expiringSoon, isTall: false) {}
                CalendarDayCell(date: day, isOutsideMonth: false, isSelected: true, count: 1, status: .expiringToday, isTall: false) {}
                CalendarDayCell(date: tomorrow, isOutsideMonth: true, isSelected: false, count: 0, status: nil, isTall: false) {}
            }
            CalendarKey()
        }
        .padding(LarderSpace.s4)
        .background(LarderColor.background)
    }
}

#Preview("Day cells") { DayCellGallery() }
#Preview("Day cells, dark") { DayCellGallery().preferredColorScheme(.dark) }
