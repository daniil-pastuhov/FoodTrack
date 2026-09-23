import SwiftUI

/// Root of the app: hosts the four peer tabs (Home/Calendar/Shopping List/Settings) behind a
/// floating pill nav bar, plus the two floating round buttons (scan / add item) that hover
/// above it and stay reachable from every tab. Owns the single shared `NativePantryViewModel`
/// so all four tabs (and the scan/add sheets) operate on the same pantry state.
struct RootTabView: View {
    @StateObject private var viewModel = NativePantryViewModel()
    @ObservedObject private var router = AppRouter.shared
    @State private var selectedTab: RootTab = .home
    @State private var isDashboardScrollingDown: Bool = false

    @State private var showScanner: Bool = false
    @State private var showAddEdit: Bool = false
    @State private var editingItem: PantryItem? = nil
    @State private var prefillBarcode: String? = nil
    @State private var prefillName: String? = nil
    @State private var prefillBrand: String? = nil
    @State private var prefillImageUrl: String? = nil
    @State private var prefillDate: Date? = nil
    @State private var prefillCategory: String? = nil
    /// Expiry to start a new item from when the scan produced no date of its own - the
    /// shelf life this barcode was last given, or its category's default.
    @State private var prefillSuggestion: ShelfLifeSuggestion? = nil
    /// Outcome of a shared-list QR scanned from the dashboard's scanner, shown as a
    /// transient toast - this screen has no snackbar of its own.
    @State private var importResult: String? = nil
    /// The most recently removed pantry item or shopping list entry (via a "-" button, swipe
    /// action, or check-off), offered back through an undo toast since each of those is a
    /// single tap/swipe that's easy to trigger by mistake. Hosted here — rather than inside
    /// `PantryDashboardView`/`ShoppingListView` — so it renders above the floating pill nav bar
    /// and scan/add buttons below, instead of underneath them. Cleared automatically after a
    /// few seconds by the `.task(id:)` below.
    @State private var pendingUndo: PendingUndo? = nil

    var body: some View {
        // Full-size, edge-to-edge tab content with the pill nav bar and (Home-only) scan/add
        // buttons overlaid on top in a ZStack — not a `.safeAreaInset`, which under-reserved
        // space here because the buttons' `.offset` (a purely visual shift) doesn't change the
        // height SwiftUI accounts for. Each screen reserves its own bottom content padding
        // (see their `.safeAreaInset` on their List/Form) so the last row still scrolls clear.
        ZStack(alignment: .bottom) {
            Group {
                switch selectedTab {
                case .home:
                    PantryDashboardView(
                        viewModel: viewModel,
                        onEditItem: { presentAddEdit(editing: $0) },
                        onScrollingDownChanged: { isDashboardScrollingDown = $0 },
                        onItemRemoved: { removed, removedFromShoppingList in
                            withAnimation {
                                pendingUndo = PendingUndo(
                                    id: removed.id,
                                    itemName: removed.name,
                                    addedToShoppingList: removedFromShoppingList
                                ) {
                                    viewModel.restoreItem(removed, removedFromShoppingList: removedFromShoppingList)
                                }
                            }
                        }
                    )
                    .transition(.opacity)
                case .calendar:
                    CalendarView(viewModel: viewModel)
                        .transition(.opacity)
                case .shoppingList:
                    ShoppingListView(
                        viewModel: viewModel,
                        onItemRemoved: { removed in
                            withAnimation {
                                pendingUndo = PendingUndo(
                                    id: removed.id,
                                    itemName: removed.productName,
                                    addedToShoppingList: false
                                ) {
                                    viewModel.restoreShoppingListEntry(removed)
                                }
                            }
                        }
                    )
                    .transition(.opacity)
                case .settings:
                    SettingsView(viewModel: viewModel, onDataCleared: { selectedTab = .home })
                        .transition(.opacity)
                }
            }
            .animation(.easeInOut(duration: 0.2), value: selectedTab)
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            FloatingTabBar(selectedTab: $selectedTab, shoppingListCount: viewModel.shoppingList.count)
                .padding(.bottom, 8)

            // Scan/Add are only meaningful from the pantry list, so they only show on Home —
            // floating out (fade + slide toward the corner they hover over) on every other tab,
            // and also collapsing on the same downward scroll that collapses the dashboard's
            // own metrics row, reappearing on the way back up.
            if selectedTab == .home {
                HStack {
                    Spacer()
                    ScanAndAddButtons(
                        onScan: { presentScanner() },
                        onAdd: { presentAddEdit(editing: nil) }
                    )
                    .padding(.trailing, 16)
                    .padding(.bottom, 72)
                }
                .transition(.move(edge: .trailing).combined(with: .opacity))
            }

            // Last in the ZStack (topmost), so it renders above the pill nav bar and the
            // scan/add buttons above it rather than underneath them.
            if let pendingUndo {
                UndoToastView(itemName: pendingUndo.itemName, addedToShoppingList: pendingUndo.addedToShoppingList) {
                    pendingUndo.restore()
                    self.pendingUndo = nil
                }
                .padding(.bottom, 200)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }

            if let importResult {
                Text(importResult)
                    .font(.footnote.weight(.medium))
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(.thinMaterial, in: Capsule())
                    .padding(.bottom, 200)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.spring(response: 0.35, dampingFraction: 0.8), value: selectedTab == .home)
        .animation(.easeInOut(duration: 0.2), value: isDashboardScrollingDown)
        .animation(.easeInOut(duration: 0.25), value: pendingUndo?.id)
        .task(id: pendingUndo?.id) {
            guard pendingUndo != nil else { return }
            try? await Task.sleep(for: .seconds(4))
            if !Task.isCancelled {
                withAnimation { pendingUndo = nil }
            }
        }
        .animation(.easeInOut(duration: 0.25), value: importResult)
        .task(id: importResult) {
            guard importResult != nil else { return }
            try? await Task.sleep(for: .seconds(3))
            if !Task.isCancelled { importResult = nil }
        }
        .sheet(isPresented: $showScanner) {
            ScannerSheetView(
                viewModel: viewModel,
                onShoppingListScanned: { payload in
                    let result = viewModel.importShoppingList(payload)
                    importResult = if result.added > 0 {
                        L10n.shoppingListImportAdded(result.added)
                    } else if result.found > 0 {
                        L10n.shoppingListImportAllPresent
                    } else {
                        L10n.shoppingListImportNothing
                    }
                }
            ) { barcode, name, brand, imageUrl, date, category, suggestion in
                editingItem = nil
                prefillBarcode = barcode
                prefillName = name
                prefillBrand = brand
                prefillImageUrl = imageUrl
                prefillDate = date
                prefillCategory = category
                prefillSuggestion = suggestion
                showAddEdit = true
            }
        }
        .sheet(isPresented: $showAddEdit) {
            AddEditItemView(
                viewModel: viewModel,
                editingItem: editingItem,
                prefillBarcode: prefillBarcode,
                prefillName: prefillName,
                prefillBrand: prefillBrand,
                prefillImageUrl: prefillImageUrl,
                prefillDate: prefillDate,
                prefillCategory: prefillCategory,
                prefillSuggestion: prefillSuggestion
            )
        }
        .onChange(of: router.openShoppingList) { _, shouldOpen in
            guard shouldOpen else { return }
            selectedTab = .shoppingList
            router.openShoppingList = false
        }
    }

    private func presentScanner() {
        editingItem = nil
        showScanner = true
    }

    private func presentAddEdit(editing item: PantryItem?) {
        editingItem = item
        prefillBarcode = nil
        prefillName = nil
        prefillBrand = nil
        prefillImageUrl = nil
        prefillDate = nil
        prefillCategory = nil
        showAddEdit = true
    }
}

/// A pending undo offer, generic over what was removed (a `PantryItem` or a
/// `ShoppingListEntry`) so `RootTabView` can host one toast for both. `id` identifies the
/// specific removal (for `.task(id:)`'s auto-dismiss timer), `itemName` is what the toast
/// displays, `addedToShoppingList` picks which toast message to show (it left the pantry for
/// the shopping list, not just "removed"), and `restore` puts the removed thing back when the
/// user taps Undo.
private struct PendingUndo {
    let id: String
    let itemName: String
    let addedToShoppingList: Bool
    let restore: () -> Void
}

private struct UndoToastView: View {
    let itemName: String
    let addedToShoppingList: Bool
    let onUndo: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Text(addedToShoppingList ? L10n.itemAddedToShoppingListToast(itemName) : L10n.itemRemovedToast(itemName))
                .font(.subheadline)
                .foregroundColor(.white)
                .lineLimit(1)
            Spacer(minLength: 8)
            Button(action: onUndo) {
                Text(L10n.undoAction)
                    .font(.subheadline)
                    .fontWeight(.semibold)
                    .foregroundColor(.accentColor)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(RoundedRectangle(cornerRadius: 12).fill(Color(white: 0.15)))
        .padding(.horizontal, 16)
        .shadow(radius: 6)
    }
}
