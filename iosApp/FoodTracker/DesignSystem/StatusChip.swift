import SwiftUI

/// The app's most important component. Colour appears only when food needs attention, so fresh
/// has no tint, and every status has an icon and words, so it never depends on colour.
///
///     StatusChip(status: .expiringSoon, text: "Tomorrow")
///     StatusChip(item: item, expiringSoonDays: 3)
///
/// Past-date wording stays factual: "1 day over", never "Expired!". The `item` form gets its
/// words from `PantryItem.statusLabel`.
struct StatusChip: View {
    private let status: ExpiryStatus
    private let text: String
    /// A fresh chip has no background of its own, so it takes the selected row's text colour.
    private let isOnSelectedRow: Bool

    init(status: ExpiryStatus, text: String, isOnSelectedRow: Bool = false) {
        self.status = status
        self.text = text
        self.isOnSelectedRow = isOnSelectedRow
    }

    init(item: PantryItem, expiringSoonDays: Int, isOnSelectedRow: Bool = false) {
        self.init(
            status: item.expiryStatus(expiringSoonDays: expiringSoonDays),
            text: item.statusLabel(expiringSoonDays: expiringSoonDays),
            isOnSelectedRow: isOnSelectedRow
        )
    }

    private var icon: LarderIconName {
        switch status {
        case .expired: return .history
        case .expiringToday, .expiringSoon: return .clock
        case .fresh: return .check
        }
    }

    private var colors: (background: Color, foreground: Color) {
        switch status {
        case .expired: return (LarderColor.expiredContainer, LarderColor.onExpiredContainer)
        case .expiringToday: return (LarderColor.todayContainer, LarderColor.onTodayContainer)
        case .expiringSoon: return (LarderColor.soonContainer, LarderColor.onSoonContainer)
        case .fresh:
            return (.clear, isOnSelectedRow ? LarderColor.onPrimaryContainer : LarderColor.onSurfaceVariant)
        }
    }

    var body: some View {
        HStack(spacing: 3) {
            LarderIcon(icon, size: .small)
            Text(text)
                .larderFont(.caption)
                .lineLimit(1)
                .fixedSize()
        }
        .foregroundStyle(colors.foreground)
        .padding(.vertical, 1)
        .padding(.leading, status == .fresh ? 0 : LarderSpace.s1)
        .padding(.trailing, LarderSpace.s2)
        .background(colors.background, in: RoundedRectangle(cornerRadius: LarderRadius.sm))
        .accessibilityElement(children: .combine)
    }
}

private struct StatusGallery: View {
    var body: some View {
        VStack(alignment: .leading, spacing: LarderSpace.s3) {
            StatusChip(status: .expired, text: "1 day over")
            StatusChip(status: .expiringToday, text: "Today")
            StatusChip(status: .expiringSoon, text: "Tomorrow")
            StatusChip(status: .fresh, text: "In 4 days")
            StatusChip(status: .fresh, text: "19 Nov")
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(LarderColor.background)
    }
}

#Preview("Status") { StatusGallery() }
#Preview("Status, dark") { StatusGallery().preferredColorScheme(.dark) }
