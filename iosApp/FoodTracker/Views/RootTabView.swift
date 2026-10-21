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
    /// Whether the system has moved the status bar and navigation bar into a strip down the
    /// side of the screen, as iPhone Duo does on its folded display. Scan/Add then join the
    /// dashboard's toolbar in that strip instead of floating over the list.
    private var barsInSideStrip: Bool { hasSideSafeAreaInset && horizontalSizeClass == .compact }
    /// See `sideBarStripReader`.
    @State private var hasSideSafeAreaInset: Bool = false
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    @State private var showScanner: Bool = false
    /// What the add/edit sheet should open with, or nil while it is closed. See `AddEditRequest`.
    @State private var addEditRequest: AddEditRequest?
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
                        toolbarScanAndAdd: barsInSideStrip
                            ? ScanAndAddActions(onScan: presentScanner, onAdd: { presentAddEdit(editing: nil) })
                            : nil,
                        onScrollingDownChanged: { isDashboardScrollingDown = $0 },
                        onItemRemoved: offerItemUndo
                    )
                    .readableContentWidth(background: Color(.systemGroupedBackground))
                    .transition(.opacity)
                case .calendar:
                    CalendarView(
                        viewModel: viewModel,
                        onEditItem: { presentAddEdit(editing: $0) },
                        onUseOne: { useOne($0) },
                        onToss: { viewModel.deleteItem($0.id, onItemRemoved: offerItemUndo) }
                    )
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
                    .readableContentWidth(background: Color(.systemBackground))
                    .transition(.opacity)
                case .settings:
                    SettingsView(viewModel: viewModel, onDataCleared: { selectedTab = .home })
                        .readableContentWidth(background: Color(.systemGroupedBackground))
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
            // own metrics row, reappearing on the way back up. With the bars in a side strip
            // they sit in the dashboard's toolbar instead (see `barsInSideStrip`).
            if selectedTab == .home && !barsInSideStrip {
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
                UndoToastView(itemName: pendingUndo.itemName, addedToShoppingList: pendingUndo.addedToShoppingList, message: pendingUndo.message) {
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
        .background(sideBarStripReader)
        .animation(.easeInOut(duration: 0.2), value: isDashboardScrollingDown)
        #if DEBUG
        .onAppear(perform: applyScreenshotLaunchArguments)
        #endif
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
                onUseOne: { item in useOne(item) },
                onShoppingListScanned: { payload in
                    let result = viewModel.importShoppingList(payload)
                    importResult = if result.added > 0 {
                        L10n.shoppingListImportAdded(result.added)
                    } else if result.found > 0 {
                        L10n.shoppingListImportAllPresent
                    } else {
                        L10n.shoppingListImportNothing
                    }
                },
                onBatchCommitted: { added in
                    importResult = L10n.batchAdded(added)
                }
            ) { barcode, name, brand, imageUrl, date, category, suggestion in
                addEditRequest = AddEditRequest(
                    barcode: barcode, name: name, brand: brand, imageUrl: imageUrl,
                    date: date, category: category, suggestion: suggestion
                )
            }
        }
        .sheet(item: $addEditRequest) { request in
            AddEditItemView(
                viewModel: viewModel,
                editingItem: request.editing,
                prefillBarcode: request.barcode,
                prefillName: request.name,
                prefillBrand: request.brand,
                prefillImageUrl: request.imageUrl,
                prefillDate: request.date,
                prefillCategory: request.category,
                prefillSuggestion: request.suggestion,
                onDuplicateMerged: { updated in
                    importResult = L10n.duplicateAdded(updated.name, updated.quantity)
                }
            )
        }
        .sheet(isPresented: Binding(
            get: { router.pendingRecipeShare != nil },
            set: { if !$0 { router.pendingRecipeShare = nil } }
        )) {
            // Non-nil whenever the sheet is up, per the binding above; the fallback keeps this
            // total rather than force-unwrapping during the dismiss animation, when SwiftUI can
            // still re-evaluate the body after the prompt has been cleared.
            ActivityView(activityItems: [router.pendingRecipeShare ?? ""])
        }
        .onChange(of: router.destination) { _, destination in
            guard let destination else { return }
            apply(destination)
            router.destination = nil
        }
        .onAppear {
            // A quick action that launched the app cold arrives before any view exists to
            // observe the router, so it waits here until there is one.
            if let pending = router.pendingLaunchDestination {
                router.pendingLaunchDestination = nil
                apply(pending)
            }
        }
    }

    private func apply(_ destination: AppDestination) {
        switch destination {
        case .shoppingList:
            selectedTab = .shoppingList
        case .scanner:
            selectedTab = .home
            presentScanner()
        case .addItem:
            selectedTab = .home
            addEditRequest = AddEditRequest()
        }
    }

    /// The scanner's "Use one": the last unit goes through the same removal as the row's "-"
    /// button (essential items land on the shopping list, with an undo), while any other unit
    /// gets its own undo, since closing the scanner gives no other sign that the count changed.
    private func useOne(_ item: PantryItem) {
        if item.quantity <= 1 {
            viewModel.decrementQuantity(item.id, onItemRemoved: offerItemUndo)
        } else {
            viewModel.decrementQuantity(item.id)
            withAnimation {
                pendingUndo = PendingUndo(
                    id: "\(item.id)-\(item.quantity - 1)",
                    itemName: item.name,
                    addedToShoppingList: false,
                    message: L10n.duplicateAdded(item.name, item.quantity - 1)
                ) {
                    viewModel.incrementQuantity(item.id)
                }
            }
        }
    }

    /// Offers a removed pantry item back through the undo toast, reversing the shopping-list add
    /// too when the removal made one (per the flag every removal hands its `onItemRemoved`).
    private func offerItemUndo(_ removed: PantryItem, _ removedFromShoppingList: Bool) {
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

    /// SwiftUI has no signal for where the system puts the bars, so `barsInSideStrip` goes by
    /// the safe area: a side strip is the only thing that insets the app on the left or right
    /// (the app is portrait-only on other iPhones, and iPads have no side insets). Only in
    /// compact width does the toolbar move into the strip with the status bar; iPhone Duo's open
    /// display held sideways has a strip too, but with just the status bar in it.
    private var sideBarStripReader: some View {
        GeometryReader { geometry in
            let insets = geometry.safeAreaInsets
            Color.clear
                .onAppear { hasSideSafeAreaInset = insets.leading > 0 || insets.trailing > 0 }
                .onChange(of: insets) { _, insets in
                    hasSideSafeAreaInset = insets.leading > 0 || insets.trailing > 0
                }
        }
    }

    private func presentScanner() {
        showScanner = true
    }

    private func presentAddEdit(editing item: PantryItem?) {
        addEditRequest = AddEditRequest(editing: item)
    }
}

/// Everything the add/edit sheet opens with, presented with `.sheet(item:)`.
///
/// These used to be separate `@State` values next to an `isPresented` flag. SwiftUI could then
/// build the sheet from the previous values: a scanned product opened an empty form, with its
/// name, brand and picture missing. Handing the sheet a single value means it can only ever see
/// the values it was opened with.
private struct AddEditRequest: Identifiable {
    let id = UUID()
    var editing: PantryItem? = nil
    var barcode: String? = nil
    var name: String? = nil
    var brand: String? = nil
    var imageUrl: String? = nil
    /// Expiry printed on the packaging, when the scan read one.
    var date: Date? = nil
    var category: String? = nil
    /// Expiry to start a new item from when the scan produced no date of its own: the shelf life
    /// this barcode was last given, or its category's default.
    var suggestion: ShelfLifeSuggestion? = nil
}

/// A pending undo offer, generic over what was removed (a `PantryItem` or a
/// `ShoppingListEntry`) so `RootTabView` can host one toast for both. `id` identifies the
/// specific removal (for `.task(id:)`'s auto-dismiss timer), `itemName` is what the toast
/// displays, `addedToShoppingList` picks which toast message to show (it left the pantry for
/// the shopping list, not just "removed"), `message` replaces that wording for an undo that
/// isn't a removal (one unit used, the rest still there), and `restore` puts the removed thing
/// back when the user taps Undo.
private struct PendingUndo {
    let id: String
    let itemName: String
    let addedToShoppingList: Bool
    var message: String? = nil
    let restore: () -> Void
}

private struct UndoToastView: View {
    let itemName: String
    let addedToShoppingList: Bool
    let message: String?
    let onUndo: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Text(message ?? (addedToShoppingList ? L10n.itemAddedToShoppingListToast(itemName) : L10n.itemRemovedToast(itemName)))
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


/// Caps a tab's content at a comfortable reading width on iPad and centres it, filling the sides
/// with the screen's own background. Full-width rows on a 13" screen put a product's name and
/// its quantity controls a whole screen apart. Does nothing on iPhone (compact width).
/// The Calendar tab doesn't use it: it has its own two-column layout for regular width.
private struct ReadableContentWidth: ViewModifier {
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    let background: Color

    // One modifier chain whatever the size class, with only its values changing: an if/else
    // here would give the tab a different identity on each side, and resizing a Split View or
    // Stage Manager window across the compact/regular line would rebuild it, dropping its state
    // and any sheet it has open.
    func body(content: Content) -> some View {
        let isRegular = horizontalSizeClass == .regular
        content
            .frame(maxWidth: isRegular ? 720 : .infinity)
            .frame(maxWidth: .infinity)
            .background {
                if isRegular { background.ignoresSafeArea() }
            }
    }
}

private extension View {
    func readableContentWidth(background: Color) -> some View {
        modifier(ReadableContentWidth(background: background))
    }
}

#if DEBUG
extension RootTabView {
    /// Screenshot support for scripts/ios_screenshots.sh, compiled out of release builds:
    /// `-demoBackup <path>` replaces the pantry with a backup file, and `-demoTab <name>`
    /// (home, calendar, shopping, settings) opens that tab.
    func applyScreenshotLaunchArguments() {
        let arguments = UserDefaults.standard
        if let path = arguments.string(forKey: "demoBackup"),
           let raw = try? String(contentsOfFile: path, encoding: .utf8) {
            _ = viewModel.restoreBackup(raw, mode: .replace)
        }
        switch arguments.string(forKey: "demoTab") {
        case "calendar": selectedTab = .calendar
        case "shopping": selectedTab = .shoppingList
        case "settings": selectedTab = .settings
        default: break
        }
    }
}
#endif
