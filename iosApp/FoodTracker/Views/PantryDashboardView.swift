import SwiftUI

public struct PantryDashboardView: View {
    @ObservedObject var viewModel: NativePantryViewModel
    @ObservedObject private var settingsStore = SettingsStore.shared
    /// Row tap notifies the root (`RootTabView`) to open the shared Add/Edit sheet, since
    /// scanning and adding/editing are handled globally now rather than by this tab itself
    /// — see `FloatingTabBar`/`ScanAndAddButtons`.
    var onEditItem: (PantryItem) -> Void
    /// Reports scroll direction so the root can collapse the scan/add buttons on the same
    /// downward scroll that scrolls the metrics row out of view, and bring them back on the
    /// way up — matching the large-title's own native collapse behavior.
    var onScrollingDownChanged: (Bool) -> Void
    @State private var showFilters: Bool = false
    @State private var isSelecting: Bool = false
    @State private var selectedItemIDs: Set<String> = []

    public init(
        viewModel: NativePantryViewModel,
        onEditItem: @escaping (PantryItem) -> Void,
        onScrollingDownChanged: @escaping (Bool) -> Void = { _ in }
    ) {
        self.viewModel = viewModel
        self.onEditItem = onEditItem
        self.onScrollingDownChanged = onScrollingDownChanged
    }

    public var body: some View {
        NavigationStack {
            List {
                // Urgency Metrics Cards — also the primary status filter.
                // Kept inside the List (rather than a fixed header above it) so it
                // scrolls together with the items and the large title collapses in
                // sync with it instead of the title reacting to list scroll while
                // this row sits static.
                Section {
                    HStack(spacing: 10) {
                        MetricBadge(
                            title: L10n.metricExpired,
                            count: viewModel.expiredCount,
                            color: .red,
                            isSelected: viewModel.selectedFilter == .expired
                        ) {
                            viewModel.toggleMetricFilter(.expired)
                        }
                        MetricBadge(
                            title: L10n.metricExpiringSoon,
                            count: viewModel.expiringSoonCount,
                            color: .orange,
                            isSelected: viewModel.selectedFilter == .expiringSoon
                        ) {
                            viewModel.toggleMetricFilter(.expiringSoon)
                        }
                        MetricBadge(
                            title: L10n.metricFresh,
                            count: viewModel.freshCount,
                            color: .green,
                            isSelected: viewModel.selectedFilter == .fresh
                        ) {
                            viewModel.toggleMetricFilter(.fresh)
                        }
                    }
                    .animation(.easeInOut(duration: 0.2), value: viewModel.selectedFilter)
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                    .padding(.bottom, 6)
                }
                .listRowInsets(EdgeInsets())
                .listRowSeparator(.hidden)
                .listRowBackground(Color.clear)

                // Items List
                if viewModel.filteredItems.isEmpty {
                    VStack(spacing: 12) {
                        Spacer()
                        Image(systemName: "takeoutbag.and.cup.and.straw")
                            .font(.system(size: 48))
                            .foregroundColor(.secondary)
                        Text(L10n.emptyStateTitle)
                            .font(.headline)
                            .foregroundColor(.secondary)
                        Text(L10n.emptyStateSubtitle)
                            .font(.subheadline)
                            .foregroundColor(.secondary.opacity(0.8))
                        Spacer()
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.top, 48)
                    .listRowInsets(EdgeInsets())
                    .listRowSeparator(.hidden)
                    .listRowBackground(Color.clear)
                    .transition(.opacity.combined(with: .scale(scale: 0.95)))
                } else {
                    ForEach(viewModel.filteredItems) { item in
                        HStack(spacing: 10) {
                            if isSelecting {
                                Image(systemName: selectedItemIDs.contains(item.id) ? "checkmark.circle.fill" : "circle")
                                    .foregroundColor(selectedItemIDs.contains(item.id) ? .accentColor : .secondary)
                                    .font(.system(size: 20))
                                    .contentTransition(.symbolEffect(.replace))
                                    .transition(.scale.combined(with: .opacity))
                            }
                            ItemRowView(
                                item: item,
                                expiringSoonDays: settingsStore.settings.expiringSoonDays,
                                onDecrement: isSelecting ? nil : { viewModel.decrementQuantity(item.id) }
                            )
                        }
                        .contentShape(Rectangle())
                        .animation(.easeInOut(duration: 0.2), value: isSelecting)
                        .onTapGesture {
                            if isSelecting {
                                withAnimation(.easeInOut(duration: 0.2)) { toggleSelection(item.id) }
                            } else {
                                onEditItem(item)
                            }
                        }
                        .onLongPressGesture {
                            withAnimation(.easeInOut(duration: 0.2)) {
                                isSelecting = true
                                selectedItemIDs.insert(item.id)
                            }
                        }
                        .swipeActions(edge: .trailing) {
                            if !isSelecting {
                                Button(role: .destructive) {
                                    viewModel.deleteItem(item.id)
                                } label: {
                                    Label(L10n.swipeDelete, systemImage: "trash.fill")
                                }
                            }
                        }
                    }
                }
            }
            .animation(.default, value: viewModel.filteredItems)
            .animation(.easeInOut(duration: 0.2), value: isSelecting)
            .listStyle(.insetGrouped)
            // Clears the floating pill nav bar and scan/add buttons that overlay this screen
            // (see RootTabView) so the last row can still scroll fully into view above them.
            .safeAreaInset(edge: .bottom) { Color.clear.frame(height: 150) }
            // `.onScrollGeometryChange` needs iOS 18, so scroll direction is inferred from the
            // drag gesture instead — `.simultaneousGesture` lets it coexist with the List's own
            // scrolling and the row taps/long-presses/swipe actions above.
            .simultaneousGesture(
                DragGesture(minimumDistance: 8)
                    .onChanged { value in
                        onScrollingDownChanged(value.translation.height < 0)
                    }
            )
            .navigationTitle(isSelecting ? L10n.selectionCount(selectedItemIDs.count) : L10n.appName)
            .toolbar {
                if isSelecting {
                    ToolbarItem(placement: .navigationBarLeading) {
                        Button(action: {
                            withAnimation(.easeInOut(duration: 0.2)) {
                                isSelecting = false
                                selectedItemIDs.removeAll()
                            }
                        }) {
                            Text(L10n.exitSelectionButton)
                        }
                    }
                    ToolbarItem(placement: .navigationBarTrailing) {
                        ShareLink(item: recipePromptText) {
                            Image(systemName: "fork.knife.circle")
                        }
                        .disabled(selectedItemIDs.isEmpty)
                        .accessibilityLabel(L10n.actionGetRecipeIdeas)
                    }
                } else {
                    ToolbarItem(placement: .navigationBarTrailing) {
                        Button(action: { showFilters = true }) {
                            Image(systemName: viewModel.hasActiveFilters
                                ? "line.3.horizontal.decrease.circle.fill"
                                : "line.3.horizontal.decrease.circle")
                                .contentTransition(.symbolEffect(.replace))
                        }
                        .accessibilityLabel(L10n.filtersButton)
                    }
                }
            }
            .sheet(isPresented: $showFilters) {
                FilterSheetView(viewModel: viewModel, settingsStore: settingsStore)
                    .presentationDetents([.medium])
            }
        }
    }

    private var recipePromptText: String {
        let selectedItems = viewModel.items.filter { selectedItemIDs.contains($0.id) }
        return NativePantryViewModel.recipePrompt(for: selectedItems)
    }

    private func toggleSelection(_ id: String) {
        if selectedItemIDs.contains(id) {
            selectedItemIDs.remove(id)
        } else {
            selectedItemIDs.insert(id)
        }
    }
}

/// Groups search, the storage-location filter, and the status tabs behind one toolbar
/// button so they don't have to permanently occupy space on the main dashboard.
private struct FilterSheetView: View {
    @ObservedObject var viewModel: NativePantryViewModel
    @ObservedObject var settingsStore: SettingsStore
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // Search
                HStack(spacing: 8) {
                    Image(systemName: "magnifyingglass")
                        .foregroundColor(.secondary)
                    TextField(L10n.searchPlaceholder, text: $viewModel.searchQuery)
                    if !viewModel.searchQuery.isEmpty {
                        Button {
                            viewModel.searchQuery = ""
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundColor(.secondary)
                        }
                    }
                }
                .padding(10)
                .background(Color(.secondarySystemFill))
                .cornerRadius(12)
                .padding(.horizontal, 16)
                .padding(.top, 12)

                // Location Filter Chips
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        LocationChip(title: L10n.locationFilterAll, isSelected: viewModel.selectedLocation == nil) {
                            viewModel.selectedLocation = nil
                        }
                        ForEach(settingsStore.settings.storageLocations, id: \.self) { loc in
                            LocationChip(title: loc, isSelected: viewModel.selectedLocation == loc) {
                                viewModel.selectedLocation = loc
                            }
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 4)
                }
                .padding(.top, 8)

                // Category Filter Chips — derived from categories present on current items
                // rather than a fixed settings list, since there's no user-edited category list.
                if !viewModel.availableCategories.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            LocationChip(title: L10n.categoryFilterAll, isSelected: viewModel.selectedCategory == nil) {
                                viewModel.selectedCategory = nil
                            }
                            ForEach(viewModel.availableCategories, id: \.self) { category in
                                LocationChip(title: category, isSelected: viewModel.selectedCategory == category) {
                                    viewModel.selectedCategory = category
                                }
                            }
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 4)
                    }
                    .padding(.top, 8)
                }

                // Status Filter Chips (Fresh is intentionally not included — it's only
                // reachable via the Fresh metric card on the dashboard)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(NativeStatusFilter.tabCases) { filter in
                            LocationChip(title: filter.localizedTitle, isSelected: viewModel.selectedFilter == filter) {
                                viewModel.selectedFilter = filter
                            }
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 4)
                }
                .padding(.top, 8)

                // Sort Order
                Text(L10n.sortSheetTitle)
                    .font(.subheadline)
                    .fontWeight(.bold)
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(SortOption.allCases) { option in
                            LocationChip(title: option.localizedTitle, isSelected: settingsStore.settings.sortOption == option) {
                                var newSettings = settingsStore.settings
                                newSettings.sortOption = option
                                settingsStore.save(newSettings)
                            }
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 4)
                }
                .padding(.top, 4)

                Spacer()
            }
            .navigationTitle(L10n.filtersSheetTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(L10n.doneButton) { dismiss() }
                        .fontWeight(.semibold)
                }
            }
        }
    }
}

private struct MetricBadge: View {
    let title: String
    let count: Int
    let color: Color
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.caption2)
                    .fontWeight(.medium)
                    .foregroundColor(color)
                Text("\(count)")
                    .font(.title3)
                    .fontWeight(.bold)
                    .foregroundColor(color)
                    .contentTransition(.numericText(value: Double(count)))
                    .animation(.easeInOut(duration: 0.2), value: count)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(10)
            .background(color.opacity(isSelected ? 0.22 : 0.12))
            .overlay(
                RoundedRectangle(cornerRadius: 10)
                    .stroke(color, lineWidth: isSelected ? 2 : 0)
            )
            .cornerRadius(10)
            .animation(.easeInOut(duration: 0.2), value: isSelected)
        }
        .buttonStyle(.plain)
    }
}

private struct LocationChip: View {
    let title: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.caption)
                .fontWeight(.semibold)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(isSelected ? Color.accentColor : Color(.secondarySystemFill))
                .foregroundColor(isSelected ? .white : .primary)
                .cornerRadius(14)
                .animation(.easeInOut(duration: 0.15), value: isSelected)
        }
        .buttonStyle(.plain)
    }
}
