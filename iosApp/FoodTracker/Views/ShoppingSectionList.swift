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
    var onRenameSection: (ShoppingSection) -> Void
    var onDeleteSection: (ShoppingSection) -> Void

    private struct DragState: Equatable {
        let entryId: String
        let sourceSectionId: String
    }

    @State private var drag: DragState?
    /// The list as it is while a drag is in progress: built with the other sections folded when
    /// the drag starts, then rearranged as the entry moves over rows. Never persisted.
    @State private var dragRows: [ShoppingRow]?
    @Environment(\.scenePhase) private var scenePhase

    private var rows: [ShoppingRow] { dragRows ?? viewModel.shoppingRows }

    var body: some View {
        List {
            ForEach(rows) { row in
                switch row {
                case .header(let section, let count):
                    header(section, count: count)
                        .onDrop(of: [.text], delegate: RowDropDelegate(
                            onEnter: { moveDragged(over: row.id) },
                            onDrop: commitDrag
                        ))
                case .item(let entry, let sectionId):
                    itemRow(entry, sectionId: sectionId)
                        .onDrag { startDrag(entry, sectionId: sectionId) }
                        .onDrop(of: [.text], delegate: RowDropDelegate(
                            onEnter: { moveDragged(over: row.id) },
                            onDrop: commitDrag
                        ))
                }
            }
            .listRowBackground(Color.clear)
        }
        .listStyle(.plain)
        .larderListBackground()
        // A drop in the gaps between rows still lands where the entry currently sits.
        .onDrop(of: [.text], delegate: RowDropDelegate(onEnter: {}, onDrop: commitDrag))
        .animation(.easeInOut(duration: 0.25), value: rows.map(\.id))
        // Insurance on top of DragSessionItemProvider: never come back from the background with
        // sections stuck folded.
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                viewModel.reloadShoppingList()
            } else {
                cancelDrag()
            }
        }
    }

    // MARK: - Rows

    private func header(_ section: ShoppingSection, count: Int) -> some View {
        let collapsed: Bool = {
            guard let drag else { return section.collapsed }
            return section.id != drag.sourceSectionId
        }()
        let name = L10n.shoppingSectionName(section)
        return HStack(spacing: 8) {
            Button {
                withAnimation(.easeInOut(duration: 0.25)) {
                    viewModel.setShoppingSectionCollapsed(section.id, !section.collapsed)
                }
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "chevron.down")
                        .font(.footnote.weight(.semibold))
                        .rotationEffect(.degrees(collapsed ? -90 : 0))
                        .animation(.easeInOut(duration: 0.2), value: collapsed)
                    Text(name)
                        .font(.subheadline.weight(.bold))
                        .lineLimit(1)
                    Text("\(count)")
                        .font(.footnote)
                        .foregroundStyle(LarderColor.onSurfaceVariant)
                    Spacer(minLength: 0)
                }
                .foregroundStyle(LarderColor.onSurfaceVariant)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(drag != nil)
            .accessibilityValue(collapsed ? L10n.shoppingSectionCollapsed : L10n.shoppingSectionExpanded)

            if !section.isDefault {
                Menu {
                    Button(L10n.shoppingSectionRename) { onRenameSection(section) }
                    Button(L10n.shoppingSectionDelete, role: .destructive) { onDeleteSection(section) }
                } label: {
                    Image(systemName: "ellipsis")
                        .foregroundStyle(LarderColor.onSurfaceVariant)
                        .frame(width: 32, height: 28)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L10n.shoppingSectionOptions(name))
            }
        }
        .padding(.top, 8)
        .listRowSeparator(.hidden)
        .deleteDisabled(true)
    }

    private func itemRow(_ entry: ShoppingListEntry, sectionId: String) -> some View {
        HStack(spacing: 14) {
            // Decorative affordance: the row itself is picked up with a long press.
            Image(systemName: "line.3.horizontal")
                .font(.title3)
                .foregroundColor(LarderColor.onSurfaceVariant)
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 4) {
                Text(entry.productName)
                    .font(.title3)
                if let barcode = entry.barcode {
                    Text(barcode)
                        .font(.subheadline)
                        .foregroundColor(LarderColor.onSurfaceVariant)
                }
            }
            Spacer()
            Button {
                if entry.quantity <= 1 {
                    viewModel.deleteShoppingListEntry(entry.id, onItemRemoved: onItemRemoved)
                } else {
                    viewModel.decrementShoppingListQuantity(entry.id)
                }
            } label: {
                Image(systemName: "minus.circle")
                    .font(.title2)
            }
            .buttonStyle(.plain)
            Text("\(entry.quantity)")
                .font(.title3)
                .frame(minWidth: 24)
                .contentTransition(.numericText())
                .animation(.easeInOut(duration: 0.2), value: entry.quantity)
            Button {
                viewModel.incrementShoppingListQuantity(entry.id)
            } label: {
                Image(systemName: "plus.circle")
                    .font(.title2)
            }
            .buttonStyle(.plain)
        }
        .padding(.vertical, 10)
        .contentShape(Rectangle())
        .onTapGesture { onEntryTap(entry) }
        .swipeActions(edge: .trailing) {
            Button(role: .destructive) {
                viewModel.deleteShoppingListEntry(entry.id, onItemRemoved: onItemRemoved)
            } label: {
                Label(L10n.shoppingListCheckOff, systemImage: "checkmark.circle.fill")
            }
        }
        .contextMenu {
            // The non-drag way to move an entry, for anyone who can't or won't drag.
            let targets = viewModel.shoppingSections.filter { $0.id != sectionId }
            if !targets.isEmpty {
                Menu(L10n.shoppingSectionMoveTo) {
                    ForEach(targets) { target in
                        Button(L10n.shoppingSectionName(target)) {
                            viewModel.moveShoppingListEntry(entry.id, toSection: target.id)
                        }
                    }
                }
            }
        }
        .deleteDisabled(true)
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

/// Reordering the user's sections: a plain edit-mode list, since the default section is pinned
/// and the rest are few.
struct ShoppingSectionReorderSheet: View {
    @ObservedObject var viewModel: NativePantryViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var order: [ShoppingSection] = []

    var body: some View {
        NavigationStack {
            List {
                ForEach(order) { section in
                    Text(L10n.shoppingSectionName(section))
                }
                .onMove { from, to in
                    order.move(fromOffsets: from, toOffset: to)
                    viewModel.reorderShoppingSections(order.map(\.id))
                }
            }
            .environment(\.editMode, .constant(.active))
            .larderListBackground()
            .navigationTitle(L10n.shoppingSectionReorder)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L10n.doneButton) { dismiss() }
                }
            }
            .onAppear { order = viewModel.shoppingSections.filter { !$0.isDefault } }
        }
        .presentationDetents([.medium, .large])
    }
}
