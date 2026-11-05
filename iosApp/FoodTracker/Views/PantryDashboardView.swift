import SwiftUI
import UIKit
import UserNotifications

/// Home. Answers one question in plain words, what should I use first, with the soonest dates at
/// the top. On expanded windows the product detail sits in a pane beside the list.
struct PantryDashboardView<DetailPane: View>: View {
    /// The list's width when the product detail sits beside it. The root lines snackbars up with it.
    static var listPaneWidth: CGFloat { HomeLayout.listPaneWidth }

    @ObservedObject var viewModel: NativePantryViewModel
    @ObservedObject private var settingsStore = SettingsStore.shared
    /// The row picked for the right-hand pane on expanded windows.
    let pickedItemID: String?
    /// Held by the root, so a dismissal outlasts a trip to another tab.
    @Binding var remindersCalloutDismissed: Bool
    let onOpenItem: (PantryItem) -> Void
    let onUseOne: (PantryItem) -> Void
    /// Fires with the removed item and whether that added a shopping-list entry, so the root can
    /// offer it back through a snackbar that reverses both.
    let onItemRemoved: (PantryItem, Bool) -> Void
    /// Fires after a multi-select delete, with what the root needs for its one Undo.
    let onItemsDeleted: (DeletedItems) -> Void
    let onScan: () -> Void
    let onAddByHand: () -> Void
    /// Scan beside search, when the root can't float it over the list (see
    /// `RootTabView.barsInSideStrip`).
    let showsHeaderScan: Bool
    @ViewBuilder let detailPane: (PantryItem) -> DetailPane

    @Environment(\.isExpanded) private var isExpanded
    @Environment(\.scenePhase) private var scenePhase

    @State private var filterSheet: FilterSheetPage?
    @State private var showRecipeSheet = false
    @State private var shareAfterRecipeSheet = false
    @State private var includeOtherItems = false
    @State private var showActivitySheet = false
    @State private var shareText = ""
    @State private var isSelecting = false
    @State private var selectedItemIDs: Set<String> = []
    @State private var isSearching = false
    @State private var notificationsDenied = false

    private var pantryEmpty: Bool { viewModel.items.isEmpty }
    private var query: String { viewModel.searchQuery.trimmingCharacters(in: .whitespaces) }
    private var showingResults: Bool { isSearching && !query.isEmpty }
    private var filtersActive: Bool { viewModel.selectedLocation != nil || viewModel.selectedCategory != nil }
    private var twoPane: Bool { isExpanded && !pantryEmpty }
    private var expiringSoonDays: Int { settingsStore.settings.expiringSoonDays }

    private func sections(for rows: [PantryItem]) -> [HomeSection] {
        if showingResults {
            return rows.isEmpty ? [] : [HomeSection(title: nil, items: rows)]
        }
        return HomeSummary.sections(rows, sort: settingsStore.settings.sortOption)
    }

    var body: some View {
        let rows = viewModel.filteredItems
        let sections = sections(for: rows)
        let paneItem = HomeSummary.paneItem(
            pickedID: pickedItemID,
            sections: sections,
            searching: isSearching,
            selecting: isSelecting
        )
        return HStack(spacing: 0) {
            listPane(rows: rows, sections: sections, paneItemID: paneItem?.id)
                .frame(maxWidth: twoPane ? HomeLayout.listPaneWidth : .infinity)
            if twoPane {
                Rectangle()
                    .fill(LarderColor.outlineVariant)
                    .frame(width: 1)
                    .ignoresSafeArea()
                Group {
                    if let paneItem {
                        detailPane(paneItem).id(paneItem.id)
                    } else {
                        NothingChosen()
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(LarderColor.surfaceContainer.ignoresSafeArea())
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(LarderColor.background.ignoresSafeArea())
        .shellChromeEffect(hideNav: isSelecting, hideScan: isSelecting || pantryEmpty)
        .larderSheet(item: $filterSheet) { page in
            FilterSheetView(viewModel: viewModel, settingsStore: settingsStore, page: page) {
                filterSheet = nil
            }
        }
        // `onDismiss` waits for this sheet to finish going before the share sheet presents:
        // presenting two sheets in the same action is unreliable.
        .larderSheet(isPresented: $showRecipeSheet, onDismiss: {
            if shareAfterRecipeSheet {
                shareAfterRecipeSheet = false
                performShare()
            }
        }) {
            RecipeIdeasSheet(
                names: viewModel.items.filter { selectedItemIDs.contains($0.id) }.map(\.name),
                otherCount: viewModel.items.count - selectedItemIDs.count,
                includeOtherItems: $includeOtherItems,
                onCancel: { showRecipeSheet = false },
                onShare: {
                    shareAfterRecipeSheet = true
                    showRecipeSheet = false
                }
            )
        }
        .sheet(isPresented: $showActivitySheet) {
            ActivityView(activityItems: [shareText])
        }
        // Re-checked on return to the foreground: the fix happens in the system Settings app.
        .task(id: scenePhase) {
            guard scenePhase == .active else { return }
            let status = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
            notificationsDenied = status == .denied
        }
        .onChange(of: pantryEmpty) { _, isEmpty in
            // Clear data can empty the pantry under a search or filter, which would then hide
            // the first thing added.
            guard isEmpty else { return }
            isSearching = false
            endSelection()
            viewModel.searchQuery = ""
            clearFilters()
            viewModel.selectedFilter = .all
        }
        .onAppear {
            // The query survives a trip to another tab, so the search has to come back with it.
            if !viewModel.searchQuery.isEmpty { isSearching = true }
            #if DEBUG
            applyDemoState(rows: rows)
            #endif
        }
    }

    @ViewBuilder
    private func listPane(rows: [PantryItem], sections: [HomeSection], paneItemID: String?) -> some View {
        VStack(spacing: 0) {
            if pantryEmpty {
                brandBar
                homeEmptyState
            } else {
                if isSelecting {
                    selectionBar
                } else if isSearching {
                    searchBar
                }
                if sections.isEmpty {
                    if !isSelecting && !isSearching { header }
                    noResults
                } else {
                    list(rows: rows, sections: sections, paneItemID: paneItemID)
                }
                if isSelecting {
                    selectionActions
                }
            }
        }
    }

    private func list(rows: [PantryItem], sections: [HomeSection], paneItemID: String?) -> some View {
        List {
            // In the list rather than above it, so it scrolls away with the rows.
            if !isSelecting && !isSearching {
                header
                    .listRowInsets(EdgeInsets())
                    .listRowSeparator(.hidden)
                    .listRowBackground(Color.clear)
            }
            if notificationsDenied, settingsStore.settings.notifications.enabled,
               !remindersCalloutDismissed, !isSelecting, !isSearching {
                plainRow {
                    LarderCallout(
                        .warning,
                        icon: .bellOff,
                        title: L10n.homeRemindersOffTitle,
                        message: L10n.homeRemindersOffMessage,
                        actionTitle: L10n.settingsNotificationsOpenSettings,
                        action: openNotificationSettings,
                        onDismiss: { remindersCalloutDismissed = true }
                    )
                    .padding(.bottom, LarderSpace.s3)
                }
            }
            if filtersActive, !isSelecting, !isSearching {
                plainRow { activeFilterLine(shown: rows.count) }
            }
            if showingResults {
                plainRow { SectionHeader(L10n.homeSearchResults(rows.count)) }
            }
            ForEach(sections) { section in
                if let title = section.title {
                    plainRow { SectionHeader(title.text) }
                }
                ForEach(Array(section.items.enumerated()), id: \.element.id) { index, item in
                    row(
                        item,
                        position: LarderGroupPosition(index: index, count: section.items.count),
                        isCurrent: twoPane && item.id == paneItemID
                    )
                }
            }
        }
        .larderList()
        // Keeps rows out from under the status bar once the header has scrolled away.
        .clipped()
        .scrollDismissesKeyboard(.immediately)
        .animation(.default, value: rows)
    }

    private func plainRow<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        content()
            .listRowInsets(EdgeInsets(top: 0, leading: LarderSpace.s4, bottom: 0, trailing: LarderSpace.s4))
            .listRowSeparator(.hidden)
            .listRowBackground(Color.clear)
    }

    private func row(_ item: PantryItem, position: LarderGroupPosition, isCurrent: Bool) -> some View {
        let isSelected = selectedItemIDs.contains(item.id)
        return ItemRowView(
            item: item,
            expiringSoonDays: expiringSoonDays,
            categoryPreferences: viewModel.categoryPreferences,
            onDecrement: { onUseOne(item) },
            selection: isSelecting ? .init(isSelected: isSelected, toggle: { toggleSelection(item.id) }) : nil,
            isCurrent: isCurrent,
            onOpen: { onOpenItem(item) }
        )
        // While selecting the row has no button of its own, so a tap anywhere toggles it.
        .gesture(TapGesture().onEnded { toggleSelection(item.id) }, including: isSelecting ? .all : .subviews)
        .simultaneousGesture(
            LongPressGesture(minimumDuration: 0.4).onEnded { _ in
                guard !isSelecting else { return }
                withAnimation(.easeInOut(duration: 0.2)) {
                    isSelecting = true
                    selectedItemIDs.insert(item.id)
                }
            }
        )
        .larderGroupRow(position, isSelected: isSelected)
        .larderSwipeActions(
            leading: isSelecting ? [] : [
                LarderSwipeAction(L10n.homeSwipeToList, icon: .shoppingBasket) {
                    viewModel.moveToShoppingList(item.id, onItemRemoved: onItemRemoved)
                },
            ],
            trailing: isSelecting ? [] : [
                LarderSwipeAction(L10n.swipeDelete, icon: .trash2, tint: .destructive) {
                    viewModel.deleteItem(item.id, onItemRemoved: onItemRemoved)
                },
            ]
        )
    }

    // MARK: Headers

    private var header: some View {
        LedeHeader(HomeSummary.lede(viewModel.items).text ?? "", brand: L10n.appName) {
            LarderIconButton(.search, label: L10n.homeSearchDescription) {
                withAnimation(.easeInOut(duration: 0.2)) { isSearching = true }
            }
            filterButton
            if showsHeaderScan {
                LarderIconButton(.scanBarcode, label: L10n.scanButton, action: onScan)
            }
        } below: {
            statusChips
        }
    }

    /// The header of an empty pantry: search and filters hide until there is something to find.
    private var brandBar: some View {
        Text(L10n.appName)
            .larderFont(.title)
            .foregroundStyle(LarderColor.onBackground)
            .frame(maxWidth: .infinity, minHeight: LarderSize.touch, alignment: .leading)
            .padding(.vertical, LarderSpace.s3)
            .padding(.horizontal, LarderSpace.s4)
    }

    private var filterButton: some View {
        LarderIconButton(
            .slidersHorizontal,
            label: filtersActive ? L10n.homeFiltersOnDescription : L10n.filtersButton
        ) {
            filterSheet = .filters
        }
        .overlay(alignment: .topTrailing) {
            if filtersActive {
                Circle()
                    .fill(LarderColor.primary)
                    .padding(2)
                    .background(LarderColor.background, in: Circle())
                    .frame(width: LarderSpace.s3, height: LarderSpace.s3)
                    .padding(LarderSpace.s2)
                    .allowsHitTesting(false)
            }
        }
    }

    /// Chips with nothing to show hide, except All and whichever is on, so it can be turned off.
    private var statusChips: some View {
        let selected = viewModel.selectedFilter
        return ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: LarderSpace.s2) {
                statusChip(L10n.statusFilterAll, viewModel.items.count, isOn: selected == .all) {
                    viewModel.selectedFilter = .all
                }
                if viewModel.expiredCount > 0 || selected == .expired {
                    statusChip(L10n.metricExpired, viewModel.expiredCount, isOn: selected == .expired) {
                        viewModel.toggleMetricFilter(.expired)
                    }
                }
                if viewModel.expiringSoonCount > 0 || selected == .expiringSoon {
                    statusChip(L10n.metricExpiringSoon, viewModel.expiringSoonCount, isOn: selected == .expiringSoon) {
                        viewModel.toggleMetricFilter(.expiringSoon)
                    }
                }
                if viewModel.freshCount > 0 || selected == .fresh {
                    statusChip(L10n.metricFresh, viewModel.freshCount, isOn: selected == .fresh) {
                        viewModel.toggleMetricFilter(.fresh)
                    }
                }
            }
            .padding(.vertical, LarderSpace.s2)
            .padding(.horizontal, LarderSpace.s4)
        }
        .padding(.horizontal, -LarderSpace.s4)
    }

    /// `LarderChip` prints its count as given, so a capped one goes in with the title. A screen
    /// reader still gets the real number.
    @ViewBuilder
    private func statusChip(_ title: String, _ count: Int, isOn: Bool, action: @escaping () -> Void) -> some View {
        if HomeSummary.isCapped(count) {
            LarderChip("\(title) \(HomeSummary.cappedLabel)", isSelected: isOn, action: action)
                .accessibilityLabel("\(title), \(count)")
        } else {
            LarderChip(title, count: count, isSelected: isOn, action: action)
        }
    }

    private var selectionBar: some View {
        HStack(spacing: LarderSpace.s1) {
            LarderIconButton(.x, label: L10n.exitSelectionButton) { endSelection() }
            Text(L10n.selectionCount(selectedItemIDs.count))
                .larderFont(.title)
                .lineLimit(1)
                .foregroundStyle(LarderColor.onBackground)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, LarderSpace.s1)
        .frame(minHeight: 64)
    }

    private var selectionActions: some View {
        VStack(spacing: 0) {
            LarderDivider()
            HStack(spacing: LarderSpace.s2) {
                LarderButton(L10n.actionGetRecipeIdeas, icon: .sparkles, variant: .secondary, fullWidth: true) {
                    showRecipeSheet = true
                }
                LarderButton(L10n.swipeDelete, icon: .trash2, variant: .dangerText) {
                    deleteSelectedItems()
                }
            }
            .padding(.vertical, LarderSpace.s3)
            .padding(.horizontal, LarderSpace.s4)
        }
        .background(LarderColor.surface.ignoresSafeArea(edges: .bottom))
    }

    private var searchBar: some View {
        HStack(spacing: 0) {
            LarderIconButton(.arrowLeft, label: L10n.backButton) {
                withAnimation(.easeInOut(duration: 0.2)) {
                    isSearching = false
                    viewModel.searchQuery = ""
                }
            }
            LarderTextField(nil, text: $viewModel.searchQuery, placeholder: L10n.searchPlaceholder, showsClear: true)
                .accessibilityLabel(L10n.searchPlaceholder)
        }
        .padding(.leading, LarderSpace.s1)
        .padding(.trailing, LarderSpace.s4)
        .padding(.vertical, LarderSpace.s3)
    }

    /// Says what the filter sheet is hiding: "Fridge · 8 of 13 items".
    private func activeFilterLine(shown: Int) -> some View {
        let parts = [
            viewModel.selectedLocation.map { L10n.storageLocationName($0) },
            viewModel.selectedCategory.map { viewModel.categoryLabel(for: $0) },
            L10n.homeActiveFilterCount(shown, of: viewModel.items.count),
        ]
        return SectionHeader(parts.compactMap { $0 }.joined(separator: " · ")) {
            LarderButton(L10n.homeClearFilters, variant: .text) { clearFilters() }
        }
    }

    // MARK: Whole-area states

    private var homeEmptyState: some View {
        EmptyState(.shelf, title: L10n.homeEmptyTitle, message: L10n.homeEmptyMessage) {
            LarderButton(L10n.homeEmptyScan, icon: .scanBarcode, action: onScan)
            LarderButton(L10n.homeEmptyAddByHand, variant: .text, action: onAddByHand)
        }
    }

    /// A dead end to step out of, not a first-use moment, so no illustration.
    @ViewBuilder
    private var noResults: some View {
        if query.isEmpty {
            EmptyState(nil, title: L10n.homeNoFilterResultsTitle, message: L10n.homeNoFilterResultsMessage) {
                LarderButton(L10n.homeClearFilters, variant: .secondary) {
                    clearFilters()
                    viewModel.selectedFilter = .all
                }
            }
        } else {
            EmptyState(nil, title: L10n.homeNoResultsTitle(query), message: L10n.homeNoResultsMessage) {
                LarderButton(L10n.homeClearSearch, variant: .secondary) { viewModel.searchQuery = "" }
            }
        }
    }

    // MARK: Actions

    private func clearFilters() {
        viewModel.selectedLocation = nil
        viewModel.selectedCategory = nil
    }

    private func endSelection() {
        withAnimation(.easeInOut(duration: 0.2)) {
            isSelecting = false
            selectedItemIDs = []
        }
    }

    private func deleteSelectedItems() {
        onItemsDeleted(viewModel.deleteItems(selectedItemIDs))
        endSelection()
    }

    private func openNotificationSettings() {
        if let url = URL(string: UIApplication.openNotificationSettingsURLString) {
            UIApplication.shared.open(url)
        }
    }

    private func performShare() {
        let selectedItems = viewModel.items.filter { selectedItemIDs.contains($0.id) }
        guard !selectedItems.isEmpty else { return }
        let otherItems = includeOtherItems
            ? viewModel.items.filter { !selectedItemIDs.contains($0.id) }
            : []
        shareText = NativePantryViewModel.recipePrompt(for: selectedItems, otherItems: otherItems)
        showActivitySheet = true
        endSelection()
        includeOtherItems = false
    }

    private func toggleSelection(_ id: String) {
        withAnimation(.easeInOut(duration: 0.2)) {
            if selectedItemIDs.contains(id) {
                selectedItemIDs.remove(id)
                // An empty selection has nothing to share or delete, so there's no reason to stay.
                if selectedItemIDs.isEmpty { isSelecting = false }
            } else {
                selectedItemIDs.insert(id)
            }
        }
    }

    #if DEBUG
    /// Screenshot support, as `RootTabView.applyScreenshotLaunchArguments`: `-demoHome <state>`
    /// opens a Home state that otherwise takes a gesture, since a simulator can't be tapped from
    /// a script.
    private func applyDemoState(rows: [PantryItem]) {
        switch UserDefaults.standard.string(forKey: "demoHome") {
        case "search": isSearching = true; viewModel.searchQuery = "ch"
        case "noResults": isSearching = true; viewModel.searchQuery = "quinoa"
        case "filter": filterSheet = .filters
        case "sort": filterSheet = .sort
        case "filtered": viewModel.selectedLocation = "Fridge"
        case "soon": viewModel.selectedFilter = .expiringSoon
        case "select", "recipe":
            isSelecting = true
            selectedItemIDs = Set(rows.prefix(2).map(\.id))
            showRecipeSheet = UserDefaults.standard.string(forKey: "demoHome") == "recipe"
        default: break
        }
    }
    #endif
}

/// Outside the generic view, because a generic type can't hold a stored static.
enum HomeLayout {
    static let listPaneWidth: CGFloat = 380
}

private struct NothingChosen: View {
    var body: some View {
        VStack(spacing: LarderSpace.s2) {
            LarderIcon(.package)
            Text(L10n.detailChooseItem)
                .larderFont(.body)
                .multilineTextAlignment(.center)
        }
        .foregroundStyle(LarderColor.onSurfaceVariant)
        .padding(LarderSpace.s6)
    }
}

private enum FilterSheetPage: String, Identifiable {
    case filters, sort

    var id: String { rawValue }
}

/// Filters and sort share one sheet, with sort a step on from the filters. Choices apply as they
/// are tapped, so the button can say how many items they leave.
private struct FilterSheetView: View {
    @ObservedObject var viewModel: NativePantryViewModel
    @ObservedObject var settingsStore: SettingsStore
    @State private var page: FilterSheetPage
    let onDone: () -> Void

    init(
        viewModel: NativePantryViewModel,
        settingsStore: SettingsStore,
        page: FilterSheetPage,
        onDone: @escaping () -> Void
    ) {
        self.viewModel = viewModel
        self.settingsStore = settingsStore
        _page = State(initialValue: page)
        self.onDone = onDone
    }

    private var chipLayout: FlowLayout {
        FlowLayout(spacing: LarderSpace.s2, rowSpacing: LarderChip.wrapRowSpacing)
    }

    var body: some View {
        switch page {
        case .filters: filters
        case .sort: sort
        }
    }

    private var filters: some View {
        LarderSheet(L10n.filtersSheetTitle) {
            Text(L10n.homeLocationLabel).sheetLabel()
            chipLayout {
                LarderChip(L10n.locationFilterAll, isSelected: viewModel.selectedLocation == nil) {
                    viewModel.selectedLocation = nil
                }
                ForEach(settingsStore.settings.storageLocations, id: \.self) { location in
                    LarderChip(L10n.storageLocationName(location), isSelected: viewModel.selectedLocation == location) {
                        viewModel.selectedLocation = location
                    }
                }
            }
            if !viewModel.availableCategories.isEmpty {
                Text(L10n.homeCategoryLabel).sheetLabel().padding(.top, LarderSpace.s3)
                chipLayout {
                    LarderChip(L10n.categoryFilterAll, isSelected: viewModel.selectedCategory == nil) {
                        viewModel.selectedCategory = nil
                    }
                    ForEach(viewModel.availableCategories, id: \.self) { category in
                        LarderChip(viewModel.categoryLabel(for: category), isSelected: viewModel.selectedCategory == category) {
                            viewModel.selectedCategory = category
                        }
                    }
                }
            }
            LarderDivider()
                .padding(.horizontal, -LarderSpace.s4)
                .padding(.top, LarderSpace.s3)
            LarderListItem(L10n.sortSheetTitle, onTap: { page = .sort }) {
                HStack(spacing: LarderSpace.s2) {
                    Text(settingsStore.settings.sortOption.localizedTitle).larderFont(.bodySm)
                    LarderIcon(.chevronRight)
                }
            }
            .padding(.horizontal, -LarderSpace.s4)
            HStack(spacing: LarderSpace.s2) {
                Spacer(minLength: 0)
                LarderButton(L10n.homeClearFilters, variant: .text) {
                    viewModel.selectedLocation = nil
                    viewModel.selectedCategory = nil
                }
                LarderButton(L10n.homeShowItems(viewModel.filteredItems.count), action: onDone)
            }
        }
    }

    private var sort: some View {
        LarderSheet(L10n.sortSheetTitle) {
            chipLayout {
                ForEach(SortOption.allCases) { option in
                    LarderChip(option.localizedTitle, isSelected: settingsStore.settings.sortOption == option) {
                        var settings = settingsStore.settings
                        settings.sortOption = option
                        settingsStore.save(settings)
                    }
                }
            }
            HStack {
                Spacer(minLength: 0)
                LarderButton(L10n.homeDone, action: onDone)
            }
            .padding(.top, LarderSpace.s3)
        }
    }
}

private extension Text {
    func sheetLabel() -> some View {
        larderFont(.label).foregroundStyle(LarderColor.onSurfaceVariant)
    }
}

/// A sheet rather than the native alert the design's other dialogs use, because an alert can't
/// hold the option. Names the items, and says the prompt only leaves the device when shared.
private struct RecipeIdeasSheet: View {
    let names: [String]
    let otherCount: Int
    @Binding var includeOtherItems: Bool
    let onCancel: () -> Void
    let onShare: () -> Void

    var body: some View {
        LarderSheet(L10n.shareRecipeDialogTitle) {
            Text(L10n.recipeIntro(HomeSummary.recipeNames(names)))
                .larderFont(.body)
                .foregroundStyle(LarderColor.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
            if otherCount > 0 {
                HStack(alignment: .top, spacing: LarderSpace.s1) {
                    LarderTick(isOn: $includeOtherItems, label: L10n.shareRecipeIncludeOtherItemsTitle)
                    VStack(alignment: .leading, spacing: 0) {
                        Text(L10n.shareRecipeIncludeOtherItemsTitle)
                            .larderFont(.body)
                            .foregroundStyle(LarderColor.onSurface)
                        Text(
                            includeOtherItems
                                ? L10n.recipeIncludeOthersCount(otherCount)
                                : L10n.shareRecipeIncludeOtherItemsSubtitle
                        )
                        .larderFont(.bodySm)
                        .foregroundStyle(LarderColor.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(.top, LarderSpace.s2)
                }
                .padding(.leading, -LarderSpace.s2)
            }
            HStack(spacing: LarderSpace.s2) {
                Spacer(minLength: 0)
                LarderButton(L10n.shareRecipeCancelButton, variant: .text, action: onCancel)
                LarderButton(L10n.shareRecipeShareButton, icon: .share2, action: onShare)
            }
        }
    }
}

/// Wraps `UIActivityViewController` so the share sheet can be triggered programmatically after
/// `RecipeIdeasSheet` is dismissed, rather than via `ShareLink`'s own tap gesture.
///
/// Internal rather than private because `RootTabView` presents the same sheet for the digest
/// notification's "Recipe ideas" action, which arrives with no dashboard on screen.
struct ActivityView: UIViewControllerRepresentable {
    let activityItems: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: activityItems, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
