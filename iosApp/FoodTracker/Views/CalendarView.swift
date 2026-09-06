import SwiftUI

private struct CalendarDay {
    let date: Date
    let isInCurrentMonth: Bool
}

/// Fixed 6-week (42-day), Monday-start grid of days covering `month`'s month, padded with
/// leading/trailing days from adjacent months. A fixed cell count keeps the grid height
/// constant when paging between months.
private func monthGrid(for month: Date) -> [CalendarDay] {
    let calendar = Calendar.current
    let components = calendar.dateComponents([.year, .month], from: month)
    guard let firstOfMonth = calendar.date(from: components) else { return [] }

    let weekday = calendar.component(.weekday, from: firstOfMonth)
    let isoWeekday = (weekday + 5) % 7 // 0 = Monday, independent of device locale/firstWeekday
    guard let gridStart = calendar.date(byAdding: .day, value: -isoWeekday, to: firstOfMonth) else { return [] }

    return (0..<42).compactMap { offset in
        guard let date = calendar.date(byAdding: .day, value: offset, to: gridStart) else { return nil }
        let dateComponents = calendar.dateComponents([.year, .month], from: date)
        let isInCurrentMonth = dateComponents.year == components.year && dateComponents.month == components.month
        return CalendarDay(date: date, isInCurrentMonth: isInCurrentMonth)
    }
}

struct CalendarView: View {
    @ObservedObject var viewModel: NativePantryViewModel
    @ObservedObject private var settingsStore = SettingsStore.shared
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    @State private var displayedMonth: Date = Date()
    @State private var selectedDay: Date = Calendar.current.startOfDay(for: Date())

    private var groupedItems: [Date: [PantryItem]] {
        Dictionary(grouping: viewModel.items) { Calendar.current.startOfDay(for: $0.expiryDate) }
    }

    private var selectedDayItems: [PantryItem] {
        groupedItems[Calendar.current.startOfDay(for: selectedDay)] ?? []
    }

    var body: some View {
        NavigationStack {
            Group {
                // Regular width (iPad, landscape on larger phones) puts the day list beside the
                // grid; compact width (most iPhones in portrait) stacks it below instead.
                if horizontalSizeClass == .regular {
                    HStack(spacing: 0) {
                        calendarSection
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                        Divider()
                        dayDetailSection
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                    }
                } else {
                    VStack(spacing: 0) {
                        calendarSection
                        Divider()
                        dayDetailSection
                    }
                }
            }
            .navigationTitle(L10n.calendarTitle)
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    private var calendarSection: some View {
        VStack(spacing: 0) {
            monthHeader

            LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 7), spacing: 4) {
                ForEach(Array(weekdaySymbols.enumerated()), id: \.offset) { _, symbol in
                    Text(symbol)
                        .font(.caption2)
                        .fontWeight(.semibold)
                        .foregroundColor(.secondary)
                        .frame(maxWidth: .infinity)
                }
                ForEach(monthGrid(for: displayedMonth), id: \.date) { day in
                    let dayItems = groupedItems[Calendar.current.startOfDay(for: day.date)] ?? []
                    CalendarDayCell(
                        day: day,
                        itemCount: dayItems.count,
                        isSelected: Calendar.current.isDate(day.date, inSameDayAs: selectedDay)
                    )
                    .contentShape(Rectangle())
                    .onTapGesture {
                        selectedDay = day.date
                    }
                }
            }
            .padding(.horizontal, 8)
        }
    }

    private var dayDetailSection: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(dayDetailFormatter.string(from: selectedDay))
                .font(.headline)
                .padding(.horizontal, 16)
                .padding(.top, 12)
                .padding(.bottom, 8)

            if selectedDayItems.isEmpty {
                Text(L10n.calendarNoItemsForDay)
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .padding(.horizontal, 16)
                Spacer()
            } else {
                List(selectedDayItems) { item in
                    ItemRowView(item: item, expiringSoonDays: settingsStore.settings.expiringSoonDays)
                }
                .listStyle(.plain)
                // Clears the floating pill nav bar that overlays this screen (see RootTabView).
                .safeAreaInset(edge: .bottom) { Color.clear.frame(height: 100) }
            }
        }
    }

    private var monthHeader: some View {
        HStack {
            Button {
                displayedMonth = Calendar.current.date(byAdding: .month, value: -1, to: displayedMonth) ?? displayedMonth
            } label: {
                Image(systemName: "chevron.left")
            }
            Spacer()
            Text(monthYearFormatter.string(from: displayedMonth))
                .font(.headline)
            Spacer()
            Button {
                displayedMonth = Calendar.current.date(byAdding: .month, value: 1, to: displayedMonth) ?? displayedMonth
            } label: {
                Image(systemName: "chevron.right")
            }
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 8)
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
        formatter.setLocalizedDateFormatFromTemplate("MMMM d, yyyy")
        return formatter
    }
}

private struct CalendarDayCell: View {
    let day: CalendarDay
    let itemCount: Int
    let isSelected: Bool

    private var isToday: Bool { Calendar.current.isDateInToday(day.date) }
    private var hasItems: Bool { itemCount > 0 }

    var body: some View {
        VStack(spacing: 3) {
            Text("\(Calendar.current.component(.day, from: day.date))")
                .font(.system(size: 15))
                .fontWeight(isToday || isSelected ? .bold : .regular)
                .foregroundColor(textColor)
                .frame(width: 30, height: 30)
                .background(background)
                .clipShape(Circle())

            Circle()
                .fill(hasItems ? Color.accentColor : Color.clear)
                .frame(width: 5, height: 5)
        }
        .frame(maxWidth: .infinity, minHeight: 44)
    }

    private var background: Color {
        if isToday { return .accentColor }
        if isSelected { return Color.accentColor.opacity(0.15) }
        return .clear
    }

    private var textColor: Color {
        if isToday { return .white }
        if !day.isInCurrentMonth { return .secondary.opacity(0.4) }
        return .primary
    }
}
