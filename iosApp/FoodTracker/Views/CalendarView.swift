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
    @Environment(\.dismiss) private var dismiss

    @State private var displayedMonth: Date = Date()
    @State private var showDayDetail: Bool = false
    @State private var selectedDay: Date = Date()

    private var groupedItems: [Date: [PantryItem]] {
        Dictionary(grouping: viewModel.items) { Calendar.current.startOfDay(for: $0.expiryDate) }
    }

    var body: some View {
        NavigationStack {
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
                        CalendarDayCell(day: day, itemCount: dayItems.count)
                            .contentShape(Rectangle())
                            .onTapGesture {
                                guard !dayItems.isEmpty else { return }
                                selectedDay = day.date
                                showDayDetail = true
                            }
                    }
                }
                .padding(.horizontal, 8)

                Spacer()
            }
            .navigationTitle(L10n.calendarTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(L10n.doneButton) { dismiss() }
                        .fontWeight(.semibold)
                }
            }
            .sheet(isPresented: $showDayDetail) {
                DayDetailView(
                    date: selectedDay,
                    items: groupedItems[Calendar.current.startOfDay(for: selectedDay)] ?? [],
                    expiringSoonDays: settingsStore.settings.expiringSoonDays
                )
                .presentationDetents([.medium])
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
}

private struct CalendarDayCell: View {
    let day: CalendarDay
    let itemCount: Int

    private var isToday: Bool { Calendar.current.isDateInToday(day.date) }
    private var hasItems: Bool { itemCount > 0 }

    var body: some View {
        VStack(spacing: 3) {
            Text("\(Calendar.current.component(.day, from: day.date))")
                .font(.system(size: 15))
                .fontWeight(isToday ? .bold : .regular)
                .foregroundColor(textColor)
                .frame(width: 30, height: 30)
                .background(isToday ? Color.accentColor : Color.clear)
                .clipShape(Circle())

            Circle()
                .fill(hasItems ? Color.accentColor : Color.clear)
                .frame(width: 5, height: 5)
        }
        .frame(maxWidth: .infinity, minHeight: 44)
    }

    private var textColor: Color {
        if isToday { return .white }
        if !day.isInCurrentMonth { return .secondary.opacity(0.4) }
        return .primary
    }
}

private struct DayDetailView: View {
    let date: Date
    let items: [PantryItem]
    let expiringSoonDays: Int
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List(items) { item in
                ItemRowView(item: item, expiringSoonDays: expiringSoonDays)
            }
            .navigationTitle(dateFormatter.string(from: date))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(L10n.doneButton) { dismiss() }
                        .fontWeight(.semibold)
                }
            }
        }
    }

    private var dateFormatter: DateFormatter {
        let formatter = DateFormatter()
        formatter.setLocalizedDateFormatFromTemplate("MMMM d, yyyy")
        return formatter
    }
}
