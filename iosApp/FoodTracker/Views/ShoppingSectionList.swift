import SwiftUI

/// The shopping list as collapsible sections. The iOS counterpart of Android's
/// `ShoppingSectionedList` (and the same rules):
///  - an entry dropped within a section lands between its new neighbours; one dropped under a
///    folded section's header goes to that section's end, and the section opens;
///  - sections are reordered with every section folded, so a header can only land between
///    other headers;
///  - the default section is pinned first.
///
/// Headers and entries are rows of one `ForEach`, so `List`'s own `.onMove` (long press, then
/// drag) moves an entry across sections, and a header among the others while reordering.
/// SwiftUI's `.onDrag` and `.onDrop` don't work here: inside a `List` on iPhone a drag never
/// starts and rows get no drop. `.onMove` has no moment a drag starts at, though, so where
/// Android folds the sections as a header is picked up, iOS has a mode for it.
struct ShoppingSectionedList: View {
    @ObservedObject var viewModel: NativePantryViewModel
    var onItemRemoved: (ShoppingListEntry) -> Void
    var onEntryTap: (ShoppingListEntry) -> Void
    var onMoveEntry: (ShoppingListEntry, String) -> Void
    var onRenameSection: (ShoppingSection) -> Void
    var onDeleteSection: (ShoppingSection, Int) -> Void
    /// Opens the reorder mode, offered on a header's long press; nil while there's nothing to
    /// reorder.
    var onReorderSections: (() -> Void)?
    /// Every section folded, with only the headers to drag. Ended by Done.
    @Binding var isReorderingSections: Bool
    /// The row to bring into view, set by the tablet's section index and cleared once done.
    @Binding var scrollTarget: String?

    /// Rows showing their tick for a moment before they leave.
    @State private var tickedIds: Set<String> = []
    @Environment(\.scenePhase) private var scenePhase

    private var rows: [ShoppingRow] {
        guard isReorderingSections else { return viewModel.shoppingRows }
        return ShoppingSections.rows(
            entries: viewModel.shoppingList,
            sections: viewModel.shoppingSections,
            collapsedOverride: { _ in true }
        )
    }

    var body: some View {
        VStack(spacing: 0) {
            if isReorderingSections { reorderBar }
            list
        }
    }

    private var reorderBar: some View {
        HStack(spacing: LarderSpace.s2) {
            Text(L10n.shoppingSectionReorder)
                .larderFont(.titleSm)
                .foregroundStyle(LarderColor.onSurface)
                .accessibilityAddTraits(.isHeader)
            Spacer(minLength: 0)
            LarderButton(L10n.doneButton, variant: .text) {
                withAnimation(.easeInOut(duration: 0.3)) { isReorderingSections = false }
            }
        }
        .padding(.horizontal, LarderSpace.s4)
        .transition(.opacity)
    }

    private var list: some View {
        ScrollViewReader { proxy in
            List {
                ForEach(Self.keyedRows(rows), id: \.key) { keyed in
                    switch keyed.row {
                    case .header(let section, let count):
                        header(section, count: count, hasGap: Self.headerHasGap(rows, at: keyed.index))
                            .moveDisabled(section.isDefault || !isReorderingSections)
                    case .item(let entry, let sectionId):
                        itemRow(entry, sectionId: sectionId, position: Self.groupPosition(rows, at: keyed.index))
                    }
                }
                .onMove { source, destination in
                    apply(Self.move(rows, from: source, to: destination))
                }
            }
            .larderList()
            .animation(.easeInOut(duration: 0.25), value: rows.map(\.id))
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { viewModel.reloadShoppingList() }
            }
            .onChange(of: scrollTarget) { _, target in
                guard let target else { return }
                withAnimation { proxy.scrollTo(target, anchor: .top) }
                scrollTarget = nil
            }
        }
    }

    // MARK: - Layout

    struct KeyedRow {
        let key: String
        let index: Int
        let row: ShoppingRow
    }

    /// The rows with their `ForEach` identity. An entry's carries its place in its group, so a
    /// row that has changed place is a new row: after a drag, `List` keeps a moved row's old
    /// `listRowBackground`, and with it the rounded corners of where the row used to be.
    static func keyedRows(_ rows: [ShoppingRow]) -> [KeyedRow] {
        rows.indices.map { index in
            let row = rows[index]
            guard case .item = row else { return KeyedRow(key: row.id, index: index, row: row) }
            return KeyedRow(key: "\(row.id)#\(groupPosition(rows, at: index))", index: index, row: row)
        }
    }

    /// Where the row at `index` falls in the group of rows under its section header.
    static func groupPosition(_ rows: [ShoppingRow], at index: Int) -> LarderGroupPosition {
        func isItem(_ i: Int) -> Bool {
            guard rows.indices.contains(i), case .item = rows[i] else { return false }
            return true
        }
        switch (isItem(index - 1), isItem(index + 1)) {
        case (true, true): return .middle
        case (true, false): return .last
        case (false, true): return .first
        case (false, false): return .only
        }
    }

    /// A header only needs space above it after a group of rows: folded sections sit close.
    static func headerHasGap(_ rows: [ShoppingRow], at index: Int) -> Bool {
        guard rows.indices.contains(index - 1), case .item = rows[index - 1] else { return false }
        return true
    }

    // MARK: - Rows

    /// The toggle takes the free space, so the menu stays pinned right however long the name is.
    /// The main list has no menu. While reordering, the chevron and menu give way to a grip.
    private func header(_ section: ShoppingSection, count: Int, hasGap: Bool) -> some View {
        let collapsed = isReorderingSections || section.collapsed
        let name = L10n.shoppingSectionName(section)
        return HStack(spacing: 0) {
            Button {
                withAnimation(.easeInOut(duration: 0.25)) {
                    viewModel.setShoppingSectionCollapsed(section.id, !section.collapsed)
                }
            } label: {
                HStack(spacing: LarderSpace.s2) {
                    LarderIcon(.chevronDown, size: .custom(ShoppingRowMetrics.smallIcon))
                        .rotationEffect(.degrees(collapsed ? -90 : 0))
                        .animation(.easeInOut(duration: 0.2), value: collapsed)
                    Text(name)
                        .larderFont(.label)
                        .lineLimit(1)
                    Text("\(count)")
                        .larderFont(.caption)
                        .monospacedDigit()
                    Spacer(minLength: 0)
                }
                .foregroundStyle(LarderColor.onSurfaceVariant)
                .frame(minHeight: LarderSize.touch)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            // Not `.disabled`, which would dim the headers that are there to be dragged.
            .allowsHitTesting(!isReorderingSections)
            .accessibilityValue(collapsed ? L10n.shoppingSectionCollapsed : L10n.shoppingSectionExpanded)

            if isReorderingSections {
                if !section.isDefault {
                    LarderIcon(.gripVertical, size: .custom(ShoppingRowMetrics.smallIcon))
                        .foregroundStyle(LarderColor.onSurfaceVariant)
                        .frame(width: LarderSize.touch, height: LarderSize.touch)
                        .accessibilityHidden(true)
                }
            } else if !section.isDefault {
                Menu {
                    Button(L10n.shoppingSectionRename) { onRenameSection(section) }
                    Button(L10n.shoppingSectionDelete, role: .destructive) { onDeleteSection(section, count) }
                } label: {
                    LarderIcon(.ellipsisVertical)
                        .foregroundStyle(LarderColor.onSurfaceVariant)
                        .frame(width: LarderSize.touch, height: LarderSize.touch)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L10n.shoppingSectionOptions(name))
            }
        }
        .padding(.top, hasGap ? LarderSpace.s2 : 0)
        .listRowInsets(EdgeInsets(top: 0, leading: LarderSpace.s4, bottom: 0, trailing: LarderSpace.s4))
        .listRowSeparator(.hidden)
        .listRowBackground(Color.clear)
        .deleteDisabled(true)
        .contextMenu {
            if !isReorderingSections, let onReorderSections {
                Button(L10n.shoppingSectionReorder, action: onReorderSections)
            }
        }
    }

    private func itemRow(
        _ entry: ShoppingListEntry,
        sectionId: String,
        position: LarderGroupPosition
    ) -> some View {
        let isTicked = tickedIds.contains(entry.id)
        return ShoppingRowContent(
            entry: entry,
            isTicked: isTicked,
            onTick: { tick(entry) },
            onQuantityChange: { changeQuantity(of: entry, to: $0) },
            onTap: { onEntryTap(entry) },
            onMoveToSection: viewModel.shoppingSections.count > 1 ? { onMoveEntry(entry, sectionId) } : nil
        )
        .larderGroupRow(position)
        // A long press lifts the row to drag it; held still, it shows this menu instead.
        .contextMenu {
            if viewModel.shoppingSections.count > 1 {
                Button(L10n.shoppingSectionMoveTo) { onMoveEntry(entry, sectionId) }
            }
        }
        .larderSwipeActions(
            trailing: isTicked ? [] : [
                LarderSwipeAction(L10n.shoppingListCheckOff, icon: .check, tint: .success) { checkOff(entry) }
            ]
        )
        .deleteDisabled(true)
    }

    /// The row shows its tick for a moment, then leaves as a swipe would.
    private func tick(_ entry: ShoppingListEntry) {
        guard !tickedIds.contains(entry.id) else { return }
        tickedIds.insert(entry.id)
        Task { @MainActor in
            try? await Task.sleep(for: ShoppingRowMetrics.tickHold)
            checkOff(entry)
            tickedIds.remove(entry.id)
        }
    }

    private func checkOff(_ entry: ShoppingListEntry) {
        withAnimation { viewModel.deleteShoppingListEntry(entry.id, onItemRemoved: onItemRemoved) }
    }

    /// At quantity 1 the minus stays on and takes the row off, with Undo, like a tick.
    private func changeQuantity(of entry: ShoppingListEntry, to quantity: Int) {
        if quantity > entry.quantity {
            viewModel.incrementShoppingListQuantity(entry.id)
        } else if entry.quantity <= 1 {
            checkOff(entry)
        } else {
            viewModel.decrementShoppingListQuantity(entry.id)
        }
    }

    // MARK: - Moving

    /// What a drop does, worked out from the rows as they would be after it.
    enum Move: Equatable {
        /// Into `section`, between two of its entries (either may be nil at an end).
        case entry(String, section: String, beforeId: String?, afterId: String?)
        /// To the end of `section`, which then opens: dropped under a folded or empty header,
        /// there are no neighbours to place it between.
        case entryToEnd(String, section: String)
        /// The sections' new order, top to bottom.
        case sections([String])
    }

    /// `List.onMove`'s `source` and `destination` against `rows`, as a `Move`, or nil when the
    /// drop leaves everything where it was.
    static func move(_ rows: [ShoppingRow], from source: IndexSet, to destination: Int) -> Move? {
        guard source.count == 1, let from = source.first, rows.indices.contains(from) else { return nil }
        var moved = rows
        moved.move(fromOffsets: source, toOffset: destination)
        guard moved != rows, let to = moved.firstIndex(where: { $0.id == rows[from].id }) else { return nil }

        func sectionIds(_ rows: [ShoppingRow]) -> [String] {
            rows.compactMap { if case .header(let section, _) = $0 { section.id } else { nil } }
        }
        func entryId(at index: Int) -> String? {
            guard moved.indices.contains(index), case .item(let entry, _) = moved[index] else { return nil }
            return entry.id
        }

        switch rows[from] {
        case .header:
            let order = sectionIds(moved)
            return order == sectionIds(rows) ? nil : .sections(order)
        case .item(let entry, _):
            let header = moved[..<to].reversed().lazy.compactMap { row -> ShoppingSection? in
                if case .header(let section, _) = row { section } else { nil }
            }.first
            guard let header else {
                // Above the pinned default header, which is the first row: first in that section.
                guard let first = entryId(at: to + 2) else {
                    return .entryToEnd(entry.id, section: ShoppingSection.defaultId)
                }
                return .entry(entry.id, section: ShoppingSection.defaultId, beforeId: nil, afterId: first)
            }
            let before = entryId(at: to - 1)
            let after = entryId(at: to + 1)
            if header.collapsed || (before == nil && after == nil) {
                return .entryToEnd(entry.id, section: header.id)
            }
            return .entry(entry.id, section: header.id, beforeId: before, afterId: after)
        }
    }

    private func apply(_ move: Move?) {
        switch move {
        case .entry(let id, let section, let beforeId, let afterId):
            viewModel.moveShoppingListEntry(id, toSection: section, beforeId: beforeId, afterId: afterId)
        case .entryToEnd(let id, let section):
            if viewModel.shoppingSections.first(where: { $0.id == section })?.collapsed == true {
                viewModel.setShoppingSectionCollapsed(section, false)
            }
            viewModel.moveShoppingListEntry(id, toSection: section)
        case .sections(let order):
            viewModel.reorderShoppingSections(order)
        case nil:
            break
        }
    }
}

/// From shopping.css.
enum ShoppingRowMetrics {
    /// A row is at least this tall, since a name may take two lines.
    static let minHeight: CGFloat = 64
    /// Narrower than a full target so the name keeps its room on a phone. The handle still spans
    /// the row's height, and Move to section does the same job without a drag.
    static let gripWidth: CGFloat = 28
    /// The chevron and grip are a size down from the icon default.
    static let smallIcon: CGFloat = 18
    /// How long a ticked row shows its tick before it leaves.
    static let tickHold: Duration = .milliseconds(350)
}

/// Tick, name, quantity, handle. Tapping the name opens Add item; the list's context menu offers Move to
/// section, which is also the way to move a row without dragging it.
struct ShoppingRowContent: View {
    let entry: ShoppingListEntry
    var isTicked = false
    var onTick: () -> Void = {}
    var onQuantityChange: (Int) -> Void = { _ in }
    var onTap: () -> Void = {}
    var onMoveToSection: (() -> Void)? = nil

    private var name: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(entry.productName)
                .larderFont(.titleSm)
                .lineLimit(2)
                .strikethrough(isTicked)
                .foregroundStyle(isTicked ? LarderColor.onSurfaceVariant : LarderColor.onSurface)
            if let barcode = entry.barcode, !barcode.isEmpty {
                Text(L10n.shoppingListBarcode(barcode))
                    .larderFont(.caption)
                    .lineLimit(1)
                    .foregroundStyle(LarderColor.onSurfaceVariant)
            }
        }
        .padding(.horizontal, LarderSpace.s1)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }

    var body: some View {
        HStack(spacing: 0) {
            LarderTick(isOn: isTicked, label: L10n.shoppingListMarkBought(entry.productName), onToggle: onTick)
            name
                .onTapGesture(perform: onTap)
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(.isButton)
                .accessibilityAction(named: L10n.shoppingSectionMoveTo) { onMoveToSection?() }
            LarderStepper(
                value: Binding(get: { entry.quantity }, set: onQuantityChange),
                range: 0...maxItemQuantity,
                outlined: false
            )
            LarderIcon(.gripVertical, size: .custom(ShoppingRowMetrics.smallIcon))
                .foregroundStyle(LarderColor.onSurfaceVariant)
                .frame(width: ShoppingRowMetrics.gripWidth, height: LarderSize.touch)
                // A hint only: the list picks the whole row up on a long press.
                .accessibilityHidden(true)
        }
        .padding(.leading, LarderSpace.s1)
        .padding(.vertical, LarderSpace.s1)
        .frame(minHeight: ShoppingRowMetrics.minHeight)
    }
}

private func previewEntry(_ name: String, quantity: Int = 1, barcode: String? = nil) -> ShoppingListEntry {
    ShoppingListEntry(id: name, productName: name, barcode: barcode, quantity: quantity, addedDate: Date())
}

private struct ShoppingRowGallery: View {
    var body: some View {
        LarderGroup {
            ShoppingRowContent(entry: previewEntry("Semi-skimmed milk", quantity: 2))
            ShoppingRowContent(entry: previewEntry("Greek yoghurt", quantity: 2, barcode: "5200435000027"))
            ShoppingRowContent(entry: previewEntry("Sourdough bread"), isTicked: true)
            ShoppingRowContent(entry: previewEntry("Молоко ультрапастеризованное безлактозное 3,2%", quantity: 12))
        }
        .padding(LarderSpace.s4)
        .background(LarderColor.background)
    }
}

#Preview("Shopping rows") { ShoppingRowGallery() }
#Preview("Shopping rows, dark") { ShoppingRowGallery().preferredColorScheme(.dark) }
