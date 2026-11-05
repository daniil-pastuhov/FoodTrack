import SwiftUI
import UniformTypeIdentifiers

/// An `NSItemProvider` that reports when the drag carrying it is over. SwiftUI's drag and drop
/// tells a drop target about a drop, but nobody about a drag that was let go somewhere it
/// couldn't land - and the folded-away sections have to come back either way. The system holds
/// the provider for exactly the length of the session, so its deinit is that missing signal.
final class DragSessionItemProvider: NSItemProvider {
    var onSessionEnd: (() -> Void)?

    deinit {
        let onSessionEnd = onSessionEnd
        DispatchQueue.main.async { onSessionEnd?() }
    }
}

/// The shopping list as collapsible sections, with entries dragged between them. The iOS
/// counterpart of Android's `ShoppingSectionedList` (and the same rules):
///  - picking an entry up folds every other section away, animated, so the headers are close
///    together and easy to drop onto; they come back when the drag ends;
///  - an entry dropped within its own section lands between its new neighbours, one dropped on
///    another (folded) section goes to that section's end, and that section opens;
///  - the default section is pinned first.
///
/// Drag and drop rather than `List`'s `.onMove`, because `.onMove` has no "drag started" moment
/// to fold the sections at, and can't cross a `Section` boundary.
struct ShoppingSectionedList: View {
    @ObservedObject var viewModel: NativePantryViewModel
    var onItemRemoved: (ShoppingListEntry) -> Void
    var onEntryTap: (ShoppingListEntry) -> Void
    var onMoveEntry: (ShoppingListEntry, String) -> Void
    var onRenameSection: (ShoppingSection) -> Void
    var onDeleteSection: (ShoppingSection, Int) -> Void
    /// The row to bring into view, set by the tablet's section index and cleared once done.
    @Binding var scrollTarget: String?

    private struct DragState: Equatable {
        let entryId: String
        let sourceSectionId: String
    }

    @State private var drag: DragState?
    /// The list as it is while a drag is in progress: built with the other sections folded when
    /// the drag starts, then rearranged as the entry moves over rows. Never persisted.
    @State private var dragRows: [ShoppingRow]?
    /// Rows showing their tick for a moment before they leave.
    @State private var tickedIds: Set<String> = []
    @Environment(\.scenePhase) private var scenePhase

    private var rows: [ShoppingRow] { dragRows ?? viewModel.shoppingRows }

    var body: some View {
        ScrollViewReader { proxy in
            List {
                ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
                    switch row {
                    case .header(let section, let count):
                        header(section, count: count, hasGap: Self.headerHasGap(rows, at: index))
                            .onDrop(of: [.text], delegate: RowDropDelegate(
                                onEnter: { moveDragged(over: row.id) },
                                onDrop: commitDrag
                            ))
                    case .item(let entry, let sectionId):
                        itemRow(entry, sectionId: sectionId, position: Self.groupPosition(rows, at: index))
                            .onDrop(of: [.text], delegate: RowDropDelegate(
                                onEnter: { moveDragged(over: row.id) },
                                onDrop: commitDrag
                            ))
                    }
                }
            }
            .larderList()
            // A drop in the gaps between rows still lands where the entry currently sits.
            .onDrop(of: [.text], delegate: RowDropDelegate(onEnter: {}, onDrop: commitDrag))
            .animation(.easeInOut(duration: 0.25), value: rows.map(\.id))
            // Insurance on top of DragSessionItemProvider: never come back from the background
            // with sections stuck folded.
            .onChange(of: scenePhase) { _, phase in
                if phase == .active {
                    viewModel.reloadShoppingList()
                } else {
                    cancelDrag()
                }
            }
            .onChange(of: scrollTarget) { _, target in
                guard let target else { return }
                withAnimation { proxy.scrollTo(target, anchor: .top) }
                scrollTarget = nil
            }
        }
    }

    // MARK: - Layout

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
    /// The main list has no menu. iOS reorders sections in a sheet, so headers have no handle.
    private func header(_ section: ShoppingSection, count: Int, hasGap: Bool) -> some View {
        let collapsed: Bool = {
            guard let drag else { return section.collapsed }
            return section.id != drag.sourceSectionId
        }()
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
            .disabled(drag != nil)
            .accessibilityValue(collapsed ? L10n.shoppingSectionCollapsed : L10n.shoppingSectionExpanded)

            if !section.isDefault {
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
            onLongPress: viewModel.shoppingSections.count > 1 ? { onMoveEntry(entry, sectionId) } : nil,
            gripDrag: { startDrag(entry, sectionId: sectionId) }
        )
        .larderGroupRow(position)
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

    // MARK: - Dragging

    private func startDrag(_ entry: ShoppingListEntry, sectionId: String) -> NSItemProvider {
        drag = DragState(entryId: entry.id, sourceSectionId: sectionId)
        withAnimation(.easeInOut(duration: 0.3)) {
            dragRows = ShoppingSections.rows(
                entries: viewModel.shoppingList,
                sections: viewModel.shoppingSections,
                collapsedOverride: { $0.id != sectionId }
            )
        }
        let provider = DragSessionItemProvider(object: entry.id as NSString)
        let entryId = entry.id
        provider.onSessionEnd = {
            // Only a drag that ended without a drop is still pending here.
            if drag?.entryId == entryId { cancelDrag() }
        }
        return provider
    }

    /// Moves the dragged entry to where the row `targetId` is. Over a header it goes just under
    /// it - i.e. into that section.
    private func moveDragged(over targetId: String) {
        guard let drag, var current = dragRows,
              let from = current.firstIndex(where: { $0.id == drag.entryId }),
              current[from].id != targetId else { return }
        let moving = current.remove(at: from)
        guard var to = current.firstIndex(where: { $0.id == targetId }) else { return }
        if case .header = current[to] {
            to += 1
        } else if from <= to {
            to += 1
        }
        current.insert(moving, at: to)
        withAnimation(.easeInOut(duration: 0.2)) { dragRows = current }
    }

    private func commitDrag() -> Bool {
        guard let drag, let current = dragRows,
              let index = current.firstIndex(where: { $0.id == drag.entryId }) else {
            cancelDrag()
            return false
        }
        var targetSectionId = ShoppingSection.defaultId
        for row in current[..<index].reversed() {
            if case .header(let section, _) = row {
                targetSectionId = section.id
                break
            }
        }

        if targetSectionId == drag.sourceSectionId {
            // The source section stayed open, so the neighbours on screen are the real ones.
            func entryId(at i: Int) -> String? {
                guard current.indices.contains(i), case .item(let entry, _) = current[i] else { return nil }
                return entry.id
            }
            viewModel.moveShoppingListEntry(
                drag.entryId, toSection: targetSectionId,
                beforeId: entryId(at: index - 1), afterId: entryId(at: index + 1)
            )
        } else {
            // Dropped into a folded section: no position within it to read off the screen, so
            // it goes to the end - and the section opens, so the entry is seen to arrive.
            if viewModel.shoppingSections.first(where: { $0.id == targetSectionId })?.collapsed == true {
                viewModel.setShoppingSectionCollapsed(targetSectionId, false)
            }
            viewModel.moveShoppingListEntry(drag.entryId, toSection: targetSectionId)
        }
        withAnimation(.easeInOut(duration: 0.3)) {
            self.drag = nil
            dragRows = nil
        }
        return true
    }

    private func cancelDrag() {
        guard drag != nil || dragRows != nil else { return }
        withAnimation(.easeInOut(duration: 0.3)) {
            drag = nil
            dragRows = nil
        }
    }
}

private struct RowDropDelegate: DropDelegate {
    let onEnter: () -> Void
    let onDrop: () -> Bool

    func dropEntered(info: DropInfo) { onEnter() }
    func dropUpdated(info: DropInfo) -> DropProposal? { DropProposal(operation: .move) }
    func performDrop(info: DropInfo) -> Bool { onDrop() }
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

/// Tick, name, quantity, handle. Tapping the name opens Add item; a long press offers Move to
/// section, which is also the way to move a row without dragging it.
struct ShoppingRowContent: View {
    let entry: ShoppingListEntry
    var isTicked = false
    var onTick: () -> Void = {}
    var onQuantityChange: (Int) -> Void = { _ in }
    var onTap: () -> Void = {}
    var onLongPress: (() -> Void)? = nil
    var gripDrag: () -> NSItemProvider = { NSItemProvider() }

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
                .onLongPressGesture { onLongPress?() }
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(.isButton)
                .accessibilityAction(named: L10n.shoppingSectionMoveTo) { onLongPress?() }
            LarderStepper(
                value: Binding(get: { entry.quantity }, set: onQuantityChange),
                range: 0...maxItemQuantity,
                outlined: false
            )
            LarderIcon(.gripVertical, size: .custom(ShoppingRowMetrics.smallIcon))
                .foregroundStyle(LarderColor.onSurfaceVariant)
                .frame(width: ShoppingRowMetrics.gripWidth, height: LarderSize.touch)
                .contentShape(Rectangle())
                .onDrag(gripDrag) {
                    Text(entry.productName)
                        .larderFont(.titleSm)
                        .foregroundStyle(LarderColor.onSurface)
                        .padding(LarderSpace.s3)
                        .background(LarderColor.surfaceContainerHigh, in: RoundedRectangle(cornerRadius: LarderRadius.md))
                }
                .accessibilityLabel(L10n.shoppingListDragHandle)
        }
        .padding(.leading, LarderSpace.s1)
        .padding(.vertical, LarderSpace.s1)
        .frame(minHeight: ShoppingRowMetrics.minHeight)
    }
}

/// Reordering the user's sections. The main list isn't shown because it stays pinned first.
struct ShoppingSectionReorderSheet: View {
    @ObservedObject var viewModel: NativePantryViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var order: [ShoppingSection] = []

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(L10n.shoppingSectionReorder)
                .larderFont(.title)
                .foregroundStyle(LarderColor.onSurface)
                .accessibilityAddTraits(.isHeader)
                .padding(.top, LarderSpace.s6)
                .padding(.horizontal, LarderSpace.s4)
            List {
                ForEach(order) { section in
                    Text(L10n.shoppingSectionName(section))
                        .larderFont(.body)
                        .foregroundStyle(LarderColor.onSurface)
                        .listRowBackground(Color.clear)
                }
                .onMove { from, to in
                    order.move(fromOffsets: from, toOffset: to)
                    viewModel.reorderShoppingSections(order.map(\.id))
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .environment(\.editMode, .constant(.active))
            HStack {
                Spacer()
                LarderButton(L10n.doneButton) { dismiss() }
            }
            .padding(LarderSpace.s4)
        }
        .onAppear { order = viewModel.shoppingSections.filter { !$0.isDefault } }
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
