import SwiftUI
import UIKit

/// Root of the app: hosts the four peer tabs (Home/Recipe/Shopping/Settings) behind the
/// `FloatingNav` pill, with `ScanFab` on Home and snackbars above both. Owns the single shared
/// `NativePantryViewModel` so all four tabs (and the scan/add sheets) operate on the same pantry
/// state, and the Recipe tab's `RecipeViewModel`, so a recipe being written outlives a trip to
/// another tab.
struct RootTabView: View {
    @StateObject private var viewModel = NativePantryViewModel()
    @StateObject private var recipeViewModel = RecipeViewModel()
    @ObservedObject private var router = AppRouter.shared
    @State private var selectedTab: RootTab = .home
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
    @State private var isKeyboardVisible = false
    @State private var scannerRequest: ScannerRequest?
    /// What the add/edit sheet should open with, or nil while it is closed. See `AddEditRequest`.
    @State private var addEditRequest: AddEditRequest?
    @State private var productDetailItem: PantryItem?
    /// Runs once the product detail sheet has gone, so a second sheet or a snackbar isn't lost
    /// behind it.
    @State private var afterProductDetail: (() -> Void)?
    /// The row picked for the right-hand pane on expanded Home.
    @State private var homePaneItemID: String?
    @State private var remindersCalloutDismissed = false
    @State private var showCategories: Bool = false
    @State private var showOnboarding: Bool = false
    /// The one snackbar on screen. Hosted here, rather than in each tab, so it sits above the
    /// pill and Scan; tabs reach it through `\.showSnackbar`.
    @State private var snackbar: LarderSnackbarContent?
    /// Lets the tabs hide the pill or Scan. See `shellChromeEffect`.
    @StateObject private var shellChrome = ShellChrome()

    /// Sends a fresh install to onboarding. The same switch as Android's
    /// `SHOW_ONBOARDING_ON_FIRST_RUN`.
    private static let showOnboardingOnFirstRun = true

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
            isExpanded: isExpanded,
            pinnedActionHeight: shellChrome.pinnedActionHeight
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
        .environment(\.shellClearance, metrics.contentClearance)
        .animation(.spring(response: 0.35, dampingFraction: 0.8), value: isScanFloating)
        .animation(.spring(response: 0.35, dampingFraction: 0.8), value: shellChrome.navHidden)
        .background(safeAreaReader)
        // Store transactions waiting for the server are sent at launch, not when Recipe opens.
        .task { recipeViewModel.start() }
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
                }
            )
        }
        .larderSheet(item: $productDetailItem, onDismiss: {
            afterProductDetail?()
            afterProductDetail = nil
        }) { item in
            productDetail(
                item,
                actions: productDetailActions(item) { action in
                    afterProductDetail = action
                    productDetailItem = nil
                },
                onClose: { productDetailItem = nil }
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
        // Over the shell rather than a cover of its own, so it is up from the first frame and the
        // scanner or the add sheet can open as it goes, with no wait for a dismissal.
        .accessibilityHidden(showOnboarding)
        .overlay {
            if showOnboarding {
                OnboardingView(onFinish: finishOnboarding)
                    .accessibilityElement(children: .contain)
                    .accessibilityAddTraits(.isModal)
                    .transition(.opacity)
            }
        }
        .onChange(of: router.route) { _, route in
            guard let route else { return }
            // First run finishes before anything else opens.
            if !showOnboarding { apply(route) }
            router.route = nil
        }
        .onChange(of: viewModel.homeConfirmation) { _, confirmation in
            guard let confirmation else { return }
            // A save confirms where the person already is, such as Shopping's scans. The
            // scanner's Add all and Add one more land on Home.
            if case .saved = confirmation {} else { selectedTab = .home }
            showSnackbar(confirmation.message)
            viewModel.homeConfirmation = nil
        }
        .onAppear {
            // A quick action that launched the app cold arrives before any view exists to
            // observe the router, so it waits here until there is one.
            let isFirstRun = Self.showOnboardingOnFirstRun && viewModel.needsOnboarding
                && !FoodTrackerApp.isTakingScreenshots
            if let pending = router.pendingLaunchRoute {
                router.pendingLaunchRoute = nil
                if !isFirstRun { apply(pending) }
            }
            // A route set before this view was observing, such as a notification action that
            // started the app, would otherwise wait for the next one.
            if let pending = router.route {
                router.route = nil
                if !isFirstRun { apply(pending) }
            }
            if isFirstRun { showOnboarding = true }
        }
    }

    @ViewBuilder
    private var tabContent: some View {
        Group {
            switch selectedTab {
            case .home:
                PantryDashboardView(
                    viewModel: viewModel,
                    pickedItemID: homePaneItemID,
                    remindersCalloutDismissed: $remindersCalloutDismissed,
                    onOpenItem: { apply(.productDetail(itemId: $0.id)) },
                    onUseOne: useOne,
                    onItemRemoved: offerItemUndo,
                    onItemsDeleted: offerDeletedItemsUndo,
                    onScan: { presentScanner() },
                    onAddByHand: { presentAddEdit(editing: nil) },
                    onGetRecipeIdeas: { apply(.recipeIdeas(itemIds: $0)) },
                    showsHeaderScan: barsInSideStrip
                ) { item in
                    productDetail(item, actions: productDetailActions(item) { $0() })
                }
                .transition(.opacity)
            case .recipe:
                RecipeView(pantry: viewModel, model: recipeViewModel, onScan: { presentScanner() })
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
        ZStack(alignment: .bottom) {
            floatingChrome

            // Outside the chrome, which ignores the keyboard: a snackbar has to stay above it,
            // or its Undo sits out of reach behind the keys.
            // Over the list on expanded Home, so it never covers the product pane's buttons.
            let overList = isExpanded && selectedTab == .home
            LarderSnackbarHost(content: $snackbar)
                .frame(maxWidth: overList ? HomeLayout.listPaneWidth : .infinity)
                .frame(maxWidth: .infinity, alignment: overList ? .leading : .center)
                .padding(.bottom, isKeyboardVisible ? LarderSpace.s2 : metrics.snackbarBottom)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { _ in
            isKeyboardVisible = true
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in
            isKeyboardVisible = false
        }
    }

    private var floatingChrome: some View {
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
            if isExpanded && selectedTab == .home {
                homePaneItemID = itemId
            } else {
                productDetailItem = viewModel.items.first { $0.id == itemId }
            }
        case .categories:
            showCategories = true
        case .onboarding:
            showOnboarding = true
        case .recipeIdeas(let itemIds):
            recipeViewModel.select(itemIds, from: viewModel.items)
            recipeViewModel.path = []
            selectedTab = .recipe
        }
    }

    private func productDetail(
        _ item: PantryItem,
        actions: ProductDetailActions,
        onClose: (() -> Void)? = nil
    ) -> PantryProductDetail {
        let nutrition = viewModel.nutrition(for: item)
        let settings = SettingsStore.shared.settings
        return PantryProductDetail(
            item: item,
            categoryName: resolveCategoryDisplayName(item.category, viewModel.categoryPreferences)
                .flatMap { $0.isEmpty ? nil : L10n.categoryName($0) },
            expiringSoonDays: settings.expiringSoonDays,
            nutrition: nutrition,
            warnings: nutrition.matchedAllergens(settings.allergens),
            actions: actions,
            onClose: onClose
        )
    }

    /// `perform` decides when an action runs: straight away in the pane, after the sheet has gone.
    private func productDetailActions(
        _ item: PantryItem,
        perform: @escaping (@escaping () -> Void) -> Void
    ) -> ProductDetailActions {
        ProductDetailActions(
            useOne: { perform { useOne(item) } },
            edit: { perform { presentAddEdit(editing: item) } },
            moveToShoppingList: {
                perform { viewModel.moveToShoppingList(item.id, onItemRemoved: offerItemUndo) }
            },
            delete: { perform { viewModel.deleteItem(item.id, onItemRemoved: offerItemUndo) } }
        )
    }

    /// "Use one", from a row, the product detail or the scanner: the last unit goes through the
    /// same removal (essential items land on the shopping list, with an undo), and any other unit
    /// gets its own undo, since a count changing is easy to miss.
    private func useOne(_ item: PantryItem) {
        if item.quantity <= 1 {
            viewModel.decrementQuantity(item.id, onItemRemoved: offerItemUndo)
        } else {
            viewModel.decrementQuantity(item.id)
            showSnackbar(L10n.homeUsedOneSnackbar(item.name, item.quantity - 1)) {
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

    private func finishOnboarding(_ exit: OnboardingExit) {
        viewModel.completeOnboarding()
        withAnimation { showOnboarding = false }
        switch exit {
        case .scanner: presentScanner(mode: .barcode)
        case .addByHand: presentAddEdit(editing: nil)
        case .home: break
        }
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
    /// recipe, shopping, settings) opens that tab, `-demoSnackbar <text>` shows a snackbar with
    /// an Undo, and `-demoRoute <name>` (onboarding, categories, productDetail with `-demoItem <name>`,
    /// recipeSettings, savedRecipes, recipeResult, which saves a sample recipe first, or the one in
    /// `-demoRecipe <file>`, likewise recipeMenu, and scan, see below) goes to that route, and
    /// `-demoAllergen <name>` adds to the allergen list.
    ///
    /// `-demoRoute scan -demoScanImage <png> -demoScanBarcode <code> -demoScanDate <yyyy-mm-dd>`
    /// opens the scanner on that picture instead of the camera, which the simulator lacks, and
    /// hands the scanner the barcode and then the date as if the camera had read them off it: the
    /// barcode is looked up as usual (the screenshots put it in the product cache) and the date
    /// lands on the product as Scan date's does. scripts/demo_scan_image.py draws the picture.
    func applyScreenshotLaunchArguments() {
        let arguments = UserDefaults.standard
        if let path = arguments.string(forKey: "demoBackup"),
           let raw = try? String(contentsOfFile: path, encoding: .utf8) {
            _ = viewModel.restoreBackup(raw, mode: .replace)
        }
        if let allergen = arguments.string(forKey: "demoAllergen") {
            var settings = SettingsStore.shared.settings
            settings.allergens.insert(allergen)
            SettingsStore.shared.save(settings)
        }
        switch arguments.string(forKey: "demoTab") {
        case "recipe": selectedTab = .recipe
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
        case "productDetail":
            let item = viewModel.items.first { $0.name == arguments.string(forKey: "demoItem") }
            apply(.productDetail(itemId: (item ?? viewModel.filteredItems.first)?.id ?? ""))
        case "recipeSettings":
            selectedTab = .recipe
            recipeViewModel.path = [.settings]
        case "savedRecipes":
            selectedTab = .recipe
            recipeViewModel.path = [.saved]
        case "recipeResult":
            selectedTab = .recipe
            recipeViewModel.path = [.result(id: recipeViewModel.saveDemoRecipe(demoFile(Recipe.self)))]
        case "recipeMenu":
            selectedTab = .recipe
            recipeViewModel.path = [.result(id: recipeViewModel.saveDemoMenu(demoFile(RecipeMenu.self)))]
        case "scan":
            if let path = arguments.string(forKey: "demoScanImage"), let image = UIImage(contentsOfFile: path),
               let barcode = arguments.string(forKey: "demoScanBarcode"),
               let day = arguments.string(forKey: "demoScanDate"),
               case let parts = day.split(separator: "-").compactMap({ Int($0) }), parts.count == 3,
               let date = Calendar.current.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2])) {
                ScannerSheetView.demoFeed = ScanDemoFeed(image: image, barcode: barcode, date: date)
                scannerRequest = ScannerRequest(mode: .barcode)
            }
        case "buyRecipes":
            selectedTab = .recipe
            recipeViewModel.showsBuySheet = true
        default: break
        }
        if arguments.object(forKey: "demoRecipeCredits") != nil {
            recipeViewModel.credits.showDemo(
                balance: arguments.integer(forKey: "demoRecipeCredits"),
                state: arguments.string(forKey: "demoRecipeState")
            )
            if arguments.string(forKey: "demoRecipeMode") == "menu" { recipeViewModel.mode = .menu }
            if arguments.string(forKey: "demoRecipeState") == "busy" { recipeViewModel.showDemoServerBusy() }
            // `-demoRecipeCourses 2|3`: the menu's course count, and so what it takes.
            if arguments.object(forKey: "demoRecipeCourses") != nil {
                recipeViewModel.setMenuCourses(arguments.integer(forKey: "demoRecipeCourses"))
            }
        }
    }

    /// The JSON file `-demoRecipe` names, decoded, or nil.
    private func demoFile<T: Decodable>(_ type: T.Type) -> T? {
        guard let path = UserDefaults.standard.string(forKey: "demoRecipe"),
              let data = FileManager.default.contents(atPath: path) else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }
}
#endif
