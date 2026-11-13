import AppIntents
import SwiftUI

/// What the three widgets share. Widgets use the system font, and Dynamic Type stops at the size
/// the rows were laid out for: nothing here can scroll, so bigger text only clips.
extension View {
    func widgetContent() -> some View {
        frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .foregroundStyle(LarderColor.onSurface)
            .dynamicTypeSize(...DynamicTypeSize.large)
    }
}

struct WidgetTitle: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(text)
            .larderFont(.titleSm)
            .lineLimit(1)
            .padding(.bottom, 2)
    }
}

/// The quiet line a list shows when it has nothing in it.
struct WidgetEmptyLine: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(text)
            .larderFont(.bodySm)
            .foregroundStyle(LarderColor.onSurfaceVariant)
    }
}

/// "+3 more": what a list that can't scroll says about the rows it left out.
struct WidgetMoreLine: View {
    let hidden: Int

    var body: some View {
        Text(L10n.widgetShoppingListMore(hidden))
            .larderFont(.caption)
            .foregroundStyle(LarderColor.onSurfaceVariant)
    }
}

struct WidgetItemName: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(text)
            .larderFont(.bodySm)
            .fontWeight(.medium)
            .lineLimit(1)
    }
}

/// The "×2" beside a shopping entry.
struct WidgetQuantity: View {
    let quantity: Int

    var body: some View {
        Text(L10n.quantityFormat(quantity))
            .larderFont(.label)
            .monospacedDigit()
            .foregroundStyle(LarderColor.onSurfaceVariant)
    }
}

/// The app's `StatusChip`, except that long words truncate inside the widget instead of holding
/// their full width: the Russian past-date label is wider than a small widget.
struct WidgetStatusChip: View {
    let status: ExpiryStatus
    let text: String

    init(item: PantryItem, expiringSoonDays: Int) {
        status = item.expiryStatus(expiringSoonDays: expiringSoonDays)
        text = item.statusLabel(expiringSoonDays: expiringSoonDays)
    }

    private var colors: (background: Color, foreground: Color) {
        switch status {
        case .expired: return (LarderColor.expiredContainer, LarderColor.onExpiredContainer)
        case .expiringToday: return (LarderColor.todayContainer, LarderColor.onTodayContainer)
        case .expiringSoon: return (LarderColor.soonContainer, LarderColor.onSoonContainer)
        case .fresh: return (.clear, LarderColor.onSurfaceVariant)
        }
    }

    private var icon: LarderIconName {
        switch status {
        case .expired: return .history
        case .expiringToday, .expiringSoon: return .clock
        case .fresh: return .check
        }
    }

    var body: some View {
        HStack(spacing: 3) {
            LarderIcon(icon, size: .small)
            Text(text)
                .larderFont(.caption)
                .lineLimit(1)
        }
        .foregroundStyle(colors.foreground)
        .padding(.vertical, 1)
        .padding(.leading, status == .fresh ? 0 : LarderSpace.s1)
        .padding(.trailing, LarderSpace.s2)
        .background(colors.background, in: RoundedRectangle(cornerRadius: LarderRadius.sm))
        .accessibilityElement(children: .combine)
    }
}

/// Name over status chip, the row of the expiring lists.
struct WidgetExpiringText: View {
    let item: PantryItem
    let expiringSoonDays: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            WidgetItemName(item.name)
            WidgetStatusChip(item: item, expiringSoonDays: expiringSoonDays)
        }
    }
}

/// The ring with a tick: an action, so it always shows its check. The button fills a 44pt square,
/// and rows are spaced to match, so neighbouring ticks never share a tap. Sideways it takes up only
/// the ring's width: the square runs into the widget's margin on one side, which lines the ring up
/// with the text above, and over the row's text on the other, which a small widget needs for names.
struct WidgetTick<Intent: AppIntent>: View {
    static var size: CGFloat { 44 }

    let intent: Intent
    let label: String

    var body: some View {
        Button(intent: intent) {
            Image(systemName: "checkmark.circle")
                .font(.system(size: 22))
                .foregroundStyle(LarderColor.onSurfaceVariant)
                .frame(width: Self.size, height: Self.size)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .padding(.horizontal, -11)
    }
}
