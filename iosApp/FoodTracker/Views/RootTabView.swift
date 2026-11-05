import SwiftUI

/// Root of the app: hosts the four peer tabs (Home/Calendar/Shopping/Settings) behind the
/// `FloatingNav` pill, with `ScanFab` on Home and Calendar and snackbars above both. Owns the
/// single shared `NativePantryViewModel` so all four tabs (and the scan/add sheets) operate on
/// the same pantry state.
struct RootTabView: View {
    @StateObject private var viewModel = NativePantryViewModel()
    @ObservedObject private var router = AppRouter.shared
    @State private var selectedTab: RootTab = .home
    @State private var isDashboardScrollingDown: Bool = false
    /// Whether the system has moved the status bar and navigation bar into a strip down the
    /// side of the screen, as iPhone Duo does on its folded display. Scan then joins the
    /// dashboard's toolbar in that strip instead of floating over the list.
    private var barsInSideStrip: Bool { hasSideSafeAreaInset && horizontalSizeClass == .compact }
    /// See `safeAreaReader`.
    @State private var hasSideSafeAreaInset: Bool = false
    @State private var bottomSafeArea: CGFloat = 0
    @State private var pillHeight: CGFloat = ShellMetrics.defaultPillHeight
    @State private var scanHeight: CGFloat = ScanFab.minHeight
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.isExpanded) private var isExpanded

    /// What the scanner should open in, or nil while it is closed.
    @State private var scannerRequest: ScannerRequest?
    /// What the add/edit sheet should open with, or nil while it is closed. See `AddEditRequest`.
    @State private var addEditRequest: AddEditRequest?
    @State private var productDetailItem: PantryItem?
    @State private var showCategories: Bool = false
    @State private var showOnboarding: Bool = false
    /// The one snackbar on screen. Hosted here, rather than in each tab, so it sits above the
    /// pill and Scan; tabs reach it through `\.showSnackbar`.
    @State private var snackbar: LarderSnackbarContent?
    /// Lets the tabs hide the pill or Scan. See `shellChromeEffect`.
    @StateObject private var shellChrome = ShellChrome()

    /// Off until the real first-run flow lands, so nobody meets the placeholder. The same switch
    /// as Android's `SHOW_ONBOARDING_ON_FIRST_RUN`.
    private static let showOnboardingOnFirstRun = false

    private var isScanFloating: Bool {
        selectedTab.showsScan && !shellChrome.scanHidden && !(selectedTab == .home && barsInSideStrip)
    }

    private var metrics: ShellMetrics {
        ShellMetrics(
            pillHeight: pillHeight,
            bottomSafeArea: bottomSafeArea,
            scanHeight: scanHeight,
            isNavVisible: !shellChrome.navHidden,
            isScanVisible: isScanFloating,
            isExpanded: isExpanded
        )
    }

    private var showSnackbar: ShowSnackbarAction {
        ShowSnackbarAction { content in
            withAnimation { snackbar = content }
        }
    }

    var body: some View {
        // The tabs run edge to edge with the floating parts over them. Their clearance is one
        // safe area inset around all of them, which a screen's List or ScrollView takes up as
        // content inset, so everything scrolls under the pill and still clears it at the end.
        ZStack(alignment: .bottom) {
            tabContent
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .safeAreaInset(edge: .bottom, spacing: 0) {
                    Color.clear
                        .frame(height: metrics.contentClearance)
                        .allowsHitTesting(false)
                }

            floatingLayer
        }
        .background(LarderColor.background.ignoresSafeArea())
        .environment(\.showSnackbar, showSnackbar)
        .environment(\.shellChrome, shellChrome)
        .animation(.spring(response: 0.35, dampingFraction: 0.8), value: isScanFloating)
        .animation(.spring(response: 0.35, dampingFraction: 0.8), value: shellChrome.navHidden)
        .background(safeAreaReader)
        .animation(.easeInOut(duration: 0.2), value: isDashboardScrollingDown)
        #if DEBUG
        .onAppear(perform: applyScreenshotLaunchArguments)
        #endif
        .fullScreenCover(item: $scannerRequest) { request in
            ScannerSheetView(
                viewModel: viewModel,
                initialMode: request.mode,
                onUseOne: { item in useOne(item) },
                onShoppingListScanned: { payload in
                    let result = viewModel.importShoppingList(payload)
                    showSnackbar(
                        result.added > 0 ? L10n.shoppingListImportAdded(result.added)
                            : result.found > 0 ? L10n.shoppingListImportAllPresent
                            : L10n.shoppingListImportNothing
                    )
                },
                onBatchCommitted: { added in
                    viewModel.homeConfirmation = .batchAdded(count: added)
                }
            ) { barcode, name, brand, imageUrl, date, category, suggestion in
                addEditRequest = AddEditRequest(prefill: AddItemPrefill(
                    barcode: barcode, name: name, brand: brand, imageUrl: imageUrl,
                    date: date, category: category, suggestion: suggestion
                ))
            }
        }
        .sheet(item: $addEditRequest) { request in
            AddEditItemView(
                viewModel: viewModel,
                editingItem: request.editing,
                prefillBarcode: request.prefill.barcode,
                prefillName: request.prefill.name,
                prefillBrand: request.prefill.brand,
                prefillImageUrl: request.prefill.imageUrl,
                prefillDate: request.prefill.date,
                prefillCategory: request.prefill.category,
                prefillSuggestion: request.prefill.suggestion,
                onDuplicateMerged: { updated in
                    viewModel.homeConfirmation = .oneMoreAdded(name: updated.name, quantity: updated.quantity)
                },
                onNewItemSaved: { item in
                    viewModel.homeConfirmation = .saved(name: item.name, location: item.storageLocation)
                }
            )
        }
        .sheet(item: $productDetailItem) { item in
            let nutrition = viewModel.nutrition(for: item)
            ProductDetailView(
                productName: item.name,
                nutrition: nutrition,
                warnings: nutrition.matchedAllergens(SettingsStore.shared.settings.allergens)
            )
        }
        .sheet(isPresented: $showCategories) {
            NavigationStack {
                CategorySettingsView(viewModel: viewModel)
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button(L10n.doneButton) { showCategories = false }
                        }
                    }
            }
        }
        .fullScreenCover(isPresented: $showOnboarding) {
            OnboardingPlaceholderView {
                viewModel.completeOnboarding()
                showOnboarding = false
            }
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
        .onChange(of: router.route) { _, route in
            guard let route else { return }
            apply(route)
            router.route = nil
        }
        .onChange(of: viewModel.homeConfirmation) { _, confirmation in
            guard let confirmation else { return }
            selectedTab = .home
            showSnackbar(confirmation.message)
            viewModel.homeConfirmation = nil
        }
        .onAppear {
            // A quick action that launched the app cold arrives before any view exists to
            // observe the router, so it waits here until there is one.
            if let pending = router.pendingLaunchRoute {
                router.pendingLaunchRoute = nil
                apply(pending)
            }
            if Self.showOnboardingOnFirstRun, viewModel.needsOnboarding {
                showOnboarding = true
            }
        }
    }

    @ViewBuilder
    private var tabContent: some View {
        Group {
            switch selectedTab {
            case .home:
                PantryDashboardView(
                    viewModel: viewModel,
                    onEditItem: { presentAddEdit(editing: $0) },
                    toolbarScan: barsInSideStrip ? { presentScanner() } : nil,
                    onScrollingDownChanged: { isDashboardScrollingDown = $0 },
                    onItemRemoved: offerItemUndo,
                    onItemsDeleted: offerDeletedItemsUndo
                )
                .transition(.opacity)
            case .calendar:
                CalendarView(
                    viewModel: viewModel,
                    onEditItem: { presentAddEdit(editing: $0) },
                    onUseOne: { useOne($0) },
                    onToss: { viewModel.deleteItem($0.id, onItemRemoved: offerItemUndo) }
                )
                .transition(.opacity)
            case .shopping:
                ShoppingListView(
                    viewModel: viewModel,
                    onItemRemoved: { removed in
                        showSnackbar(L10n.itemRemovedToast(removed.productName)) {
                            viewModel.restoreShoppingListEntry(removed)
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
    }

    /// Pill, Scan and snackbar. Left out of the keyboard's safe area, so the keyboard covers
    /// them rather than pushing them up the screen.
    private var floatingLayer: some View {
        let metrics = metrics
        return ZStack(alignment: .bottom) {
            if isScanFloating {
                ScanFab { presentScanner() }
                    .onGeometryChange(for: CGFloat.self, of: \.size.height) { scanHeight = $0 }
                    .frame(maxWidth: .infinity, alignment: .trailing)
                    .padding(.trailing, metrics.scanTrailing)
                    .padding(.bottom, metrics.scanBottom)
                    .transition(.move(edge: .trailing).combined(with: .opacity))
            }

            if !shellChrome.navHidden {
                FloatingNav(selection: $selectedTab, shoppingCount: viewModel.shoppingList.count)
                    .onGeometryChange(for: CGFloat.self, of: \.size.height) { pillHeight = $0 }
                    .padding(.horizontal, LarderSpace.s3)
                    .padding(.bottom, metrics.pillBottom)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }

            // Last, so it renders above the pill and Scan rather than underneath them.
            LarderSnackbarHost(content: $snackbar)
                .padding(.bottom, metrics.snackbarBottom)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
        .ignoresSafeArea(.keyboard, edges: .bottom)
    }

    private func apply(_ route: AppRoute) {
        switch route {
        case .tab(let tab):
            selectedTab = tab
        case .scanner(let mode):
            selectedTab = .home
            presentScanner(mode: mode)
        case .addItem(.new):
            selectedTab = .home
            addEditRequest = AddEditRequest()
        case .addItem(.prefilled(let prefill)):
            selectedTab = .home
            addEditRequest = AddEditRequest(prefill: prefill)
        case .addItem(.edit(let itemId)):
            if let item = viewModel.items.first(where: { $0.id == itemId }) {
                presentAddEdit(editing: item)
            }
        case .productDetail(let itemId):
            productDetailItem = viewModel.items.first { $0.id == itemId }
        case .categories:
            showCategories = true
        case .onboarding:
            showOnboarding = true
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
            showSnackbar(L10n.duplicateAdded(item.name, item.quantity - 1)) {
                viewModel.incrementQuantity(item.id)
            }
        }
    }

    /// Offers a removed pantry item back through the snackbar, reversing the shopping-list add
    /// too when the removal made one (per the flag every removal hands its `onItemRemoved`).
    private func offerItemUndo(_ removed: PantryItem, _ removedFromShoppingList: Bool) {
        let message = removedFromShoppingList
            ? L10n.itemAddedToShoppingListToast(removed.name)
            : L10n.itemRemovedToast(removed.name)
        showSnackbar(message) {
            viewModel.restoreItem(removed, removedFromShoppingList: removedFromShoppingList)
        }
    }

    private func offerDeletedItemsUndo(_ deleted: DeletedItems) {
        showSnackbar(L10n.itemsDeleted(deleted.items.count)) {
            viewModel.restoreItems(deleted)
        }
    }

    /// SwiftUI has no signal for where the system puts the bars, so `barsInSideStrip` goes by
    /// the safe area: a side strip is the only thing that insets the app on the left or right
    /// (the app is portrait-only on other iPhones, and iPads have no side insets). Only in
    /// compact width does the toolbar move into the strip with the status bar; iPhone Duo's open
    /// display held sideways has a strip too, but with just the status bar in it.
    ///
    /// Reads the bottom inset for `ShellMetrics` too, keyboard left out.
    private var safeAreaReader: some View {
        GeometryReader { geometry in
            let insets = geometry.safeAreaInsets
            Color.clear
                .onAppear { readSafeArea(insets) }
                .onChange(of: insets) { _, insets in readSafeArea(insets) }
        }
        .ignoresSafeArea(.keyboard, edges: .bottom)
    }

    private func readSafeArea(_ insets: EdgeInsets) {
        hasSideSafeAreaInset = insets.leading > 0 || insets.trailing > 0
        bottomSafeArea = insets.bottom
    }

    private func presentScanner(mode: IOSCameraMode? = nil) {
        scannerRequest = ScannerRequest(mode: mode)
    }

    private func presentAddEdit(editing item: PantryItem?) {
        addEditRequest = AddEditRequest(editing: item)
    }
}

private extension HomeConfirmation {
    var message: String {
        switch self {
        case .saved(let name, let location):
            return L10n.itemSavedTo(name, L10n.storageLocationName(location))
        case .batchAdded(let count):
            return L10n.batchAdded(count)
        case .oneMoreAdded(let name, let quantity):
            return L10n.duplicateAdded(name, quantity)
        }
    }
}

private struct ScannerRequest: Identifiable {
    let id = UUID()
    var mode: IOSCameraMode?
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
    var prefill = AddItemPrefill()
}

#if DEBUG
extension RootTabView {
    /// Screenshot support for scripts/ios_screenshots.sh, compiled out of release builds:
    /// `-demoBackup <path>` replaces the pantry with a backup file, `-demoTab <name>` (home,
    /// calendar, shopping, settings) opens that tab, `-demoSnackbar <text>` shows a snackbar with
    /// an Undo, and `-demoRoute <name>` (onboarding, categories) goes to that route.
    func applyScreenshotLaunchArguments() {
        let arguments = UserDefaults.standard
        if let path = arguments.string(forKey: "demoBackup"),
           let raw = try? String(contentsOfFile: path, encoding: .utf8) {
            _ = viewModel.restoreBackup(raw, mode: .replace)
        }
        switch arguments.string(forKey: "demoTab") {
        case "calendar": selectedTab = .calendar
        case "shopping": selectedTab = .shopping
        case "settings": selectedTab = .settings
        default: break
        }
        if let text = arguments.string(forKey: "demoSnackbar") {
            snackbar = LarderSnackbarContent(message: text, actionTitle: L10n.undoAction, action: {})
        }
        switch arguments.string(forKey: "demoRoute") {
        case "onboarding": apply(.onboarding)
        case "categories": apply(.categories)
        default: break
        }
    }
}
#endif
