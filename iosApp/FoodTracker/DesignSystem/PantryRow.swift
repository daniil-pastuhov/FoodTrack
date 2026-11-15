import SwiftUI

/// One item: name, then status, then where it lives, with the quantity at the end. Rows stay one
/// line each and truncate; the full name is on the detail sheet.
///
///     PantryRow(item: item, expiringSoonDays: days, place: "Fridge · Dairy",
///               onOpen: { open(item) }, onUseOne: { use(item) })
///
///     // Multi-select: pass `selection`. The minus goes, and the tick toggles.
///     PantryRow(item: item, expiringSoonDays: days, place: place,
///               selection: .init(isSelected: isSelected, toggle: { toggle(item) }))
///
/// Goes in a `LarderGroup`, or in a `List` with `.larderGroupRow(_:)` when it needs native swipe
/// actions. Everything a swipe does must also be on the detail sheet.
struct PantryRow: View {
    struct Selection {
        let isSelected: Bool
        let toggle: () -> Void
    }

    private let name: String
    private let status: ExpiryStatus
    private let statusText: String
    private let place: String
    private let quantity: Int
    private let imageReference: String?
    private let placeholderIcon: LarderIconName
    private let selection: Selection?
    private let isCurrent: Bool
    private let spokenStatus: String?
    private let onOpen: (() -> Void)?
    private let onUseOne: (() -> Void)?

    @Environment(\.larderForcedState) private var forcedState
    @State private var isHovering = false

    /// - Parameters:
    ///   - place: Location and category, already joined: "Fridge · Dairy".
    ///   - placeholderIcon: Shown when there is no photo. See `LarderIconName.forCategory(_:)`.
    ///   - isCurrent: This row's product is open in the detail pane beside the list.
    ///   - spokenStatus: What VoiceOver says for the status when it differs from `statusText`:
    ///     "one day past its date" for "1 day over".
    init(
        name: String,
        status: ExpiryStatus,
        statusText: String,
        place: String,
        quantity: Int,
        imageReference: String? = nil,
        placeholderIcon: LarderIconName = .package,
        selection: Selection? = nil,
        isCurrent: Bool = false,
        spokenStatus: String? = nil,
        onOpen: (() -> Void)? = nil,
        onUseOne: (() -> Void)? = nil
    ) {
        self.name = name
        self.status = status
        self.statusText = statusText
        self.place = place
        self.quantity = quantity
        self.imageReference = imageReference
        self.placeholderIcon = placeholderIcon
        self.selection = selection
        self.isCurrent = isCurrent
        self.spokenStatus = spokenStatus
        self.onOpen = onOpen
        self.onUseOne = onUseOne
    }

    init(
        item: PantryItem,
        expiringSoonDays: Int,
        place: String,
        selection: Selection? = nil,
        isCurrent: Bool = false,
        spokenStatus: String? = nil,
        onOpen: (() -> Void)? = nil,
        onUseOne: (() -> Void)? = nil
    ) {
        self.init(
            name: item.name,
            status: item.expiryStatus(expiringSoonDays: expiringSoonDays),
            statusText: item.statusLabel(expiringSoonDays: expiringSoonDays),
            place: place,
            quantity: item.quantity,
            imageReference: item.imageUrl,
            placeholderIcon: .forCategory(item.category),
            selection: selection,
            isCurrent: isCurrent,
            spokenStatus: spokenStatus,
            onOpen: onOpen,
            onUseOne: onUseOne
        )
    }

    private var isSelected: Bool { selection?.isSelected ?? false }
    private var secondaryText: Color { isSelected ? LarderColor.onPrimaryContainer : LarderColor.onSurfaceVariant }

    private var rowBackground: Color {
        if isSelected { return LarderColor.primaryContainer }
        if isCurrent { return LarderColor.surfaceContainer }
        if isHovering || forcedState == .hover { return LarderColor.onSurface.opacity(0.06) }
        return .clear
    }

    var body: some View {
        HStack(spacing: LarderSpace.s2) {
            if let selection {
                LarderTick(isOn: selection.isSelected, label: name, onToggle: selection.toggle)
                    .padding(.horizontal, -LarderSpace.s2)
            }

            if selection == nil, let onOpen {
                Button(action: onOpen) { summary }
                    .buttonStyle(.plain)
            } else {
                summary
            }

            if let onUseOne, selection == nil {
                LarderIconButton(.circleMinus, label: L10n.useOneLabel(name), action: onUseOne)
                    .padding(.leading, -LarderSpace.s1)
                    .padding(.trailing, -LarderSpace.s2)
            }

            Text(L10n.quantityFormat(quantity))
                .larderFont(.label)
                .monospacedDigit()
                .foregroundStyle(secondaryText)
                .fixedSize()
                .contentTransition(.numericText())
        }
        .padding(.vertical, LarderSpace.s2)
        .padding(.horizontal, LarderSpace.s3)
        .frame(minHeight: 68)
        .background(rowBackground)
        .contentShape(Rectangle())
        .onHover { isHovering = $0 }
        .animation(.easeInOut(duration: 0.2), value: quantity)
    }

    private var statusChip: some View {
        StatusChip(status: status, text: statusText, isOnSelectedRow: isSelected)
    }

    private var placeText: some View {
        Text(place)
            .larderFont(.caption)
            .lineLimit(2)
            .foregroundStyle(secondaryText)
    }

    private var summary: some View {
        HStack(spacing: LarderSpace.s3) {
            LarderThumb(
                imageReference: imageReference,
                placeholderIcon: placeholderIcon,
                background: isSelected || isCurrent ? LarderColor.surface : LarderColor.surfaceContainer
            )

            VStack(alignment: .leading, spacing: 2) {
                Text(name)
                    .larderFont(.titleSm)
                    .lineLimit(1)
                    .foregroundStyle(isSelected ? LarderColor.onPrimaryContainer : LarderColor.onSurface)
                // The place sits beside the status while it fits whole, and wraps under it
                // otherwise, rather than cutting "Kjøleskap · Meieri" down to "K…".
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: LarderSpace.s2) {
                        statusChip
                        placeText.fixedSize()
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        statusChip
                        placeText.fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(
            [name, spokenStatus ?? statusText, place, L10n.quantityFormat(quantity)]
                .filter { !$0.isEmpty }
                .joined(separator: ", ")
        )
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

/// 44pt product photo, or a category icon when there isn't one.
struct LarderThumb: View {
    let imageReference: String?
    var placeholderIcon: LarderIconName = .package
    var background: Color = LarderColor.surfaceContainer
    var side: CGFloat = 44

    var body: some View {
        ProductImageView(reference: imageReference) {
            ZStack {
                background
                LarderIcon(placeholderIcon, size: .large)
                    .foregroundStyle(LarderColor.onSurfaceVariant)
            }
        }
        .frame(width: side, height: side)
        .clipShape(RoundedRectangle(cornerRadius: LarderRadius.md))
        .accessibilityHidden(true)
    }
}

private struct RowGallery: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: LarderSpace.s4) {
                LarderGroup {
                    PantryRow(name: "Baby spinach", status: .expiringToday, statusText: "Today", place: "Fridge · Vegetables", quantity: 1, placeholderIcon: .leaf, onOpen: {}, onUseOne: {})
                    PantryRow(name: "Semi-skimmed milk", status: .expiringSoon, statusText: "In 2 days", place: "Fridge · Dairy", quantity: 1, placeholderIcon: .milk)
                        .environment(\.larderForcedState, .hover)
                    PantryRow(name: "Salmon fillet", status: .expiringSoon, statusText: "In 3 days", place: "Fridge · Meat & fish", quantity: 2, placeholderIcon: .fish, selection: .init(isSelected: true, toggle: {}))
                    PantryRow(name: "Sourdough bread", status: .expired, statusText: "1 day over", place: "Pantry · Bakery", quantity: 1, placeholderIcon: .croissant, selection: .init(isSelected: false, toggle: {}))
                    PantryRow(name: "Greek yoghurt", status: .expiringSoon, statusText: "Tomorrow", place: "Fridge · Dairy", quantity: 2, placeholderIcon: .milk, isCurrent: true, onUseOne: {})
                    PantryRow(name: "Organic stone-ground wholemeal spelt sourdough loaf with seeds", status: .fresh, statusText: "14 Mar 2027", place: "Pantry · Bakery", quantity: 12, onUseOne: {})
                }
            }
            .padding(LarderSpace.s4)
        }
        .background(LarderColor.background)
    }
}

#Preview("Rows") { RowGallery() }
#Preview("Rows, dark") { RowGallery().preferredColorScheme(.dark) }
#Preview("Rows, large text") { RowGallery().dynamicTypeSize(.accessibility1) }
