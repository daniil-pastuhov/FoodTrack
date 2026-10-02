import SwiftUI
import UIKit

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
    /// Fires with the removed item whenever the "-" button, swipe-to-delete, or the "move to
    /// shopping list" swipe action fully removes it from the pantry, plus whether that removal
    /// added a shopping-list entry for it, so the root can offer it back through an undo toast
    /// that reverses both — hosted at the root (rather than here) so it renders above the
    /// floating nav bar/scan buttons that overlay this screen, instead of underneath them.
    var onItemRemoved: (PantryItem, Bool) -> Void
    @State private var showFilters: Bool = false
    @State private var isSelecting: Bool = false
    @State private var selectedItemIDs: Set<String> = []
    @State private var showShareDialog: Bool = false
    @State private var showDeleteSelectedDialog: Bool = false
    @State private var includeOtherItemsInShare: Bool = false
    @State private var shareAfterDialogDismiss: Bool = false
    @State private var showActivitySheet: Bool = false
    @State private var shareText: String = ""

    public init(
        viewModel: NativePantryViewModel,
        onEditItem: @escaping (PantryItem) -> Void,
        onScrollingDownChanged: @escaping (Bool) -> Void = { _ in },
        onItemRemoved: @escaping (PantryItem, Bool) -> Void = { _, _ in }
    ) {
        self.viewModel = viewModel
        self.onEditItem = onEditItem
        self.onScrollingDownChanged = onScrollingDownChanged
        self.onItemRemoved = onItemRemoved
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
                                categoryPreferences: viewModel.categoryPreferences,
                                onDecrement: isSelecting ? nil : {
                                    viewModel.decrementQuantity(item.id, onItemRemoved: onItemRemoved)
                                }
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
                                    viewModel.deleteItem(item.id, onItemRemoved: onItemRemoved)
                                } label: {
                                    Label(L10n.swipeDelete, systemImage: "trash.fill")
                                }
                            }
                        }
                        .swipeActions(edge: .leading) {
                            if !isSelecting {
                                Button {
                                    viewModel.moveToShoppingList(item.id, onItemRemoved: onItemRemoved)
                                } label: {
                                    Label(L10n.swipeAddToShoppingList, systemImage: "cart.badge.plus")
                                }
                                .tint(.blue)
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
            .safeAreaInset(edge: .bottom) { Color.clear.frame(height: 170) }
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
                        Button(action: { showShareDialog = true }) {
                            Image(systemName: "fork.knife.circle")
                        }
                        .disabled(selectedItemIDs.isEmpty)
                        .accessibilityLabel(L10n.actionGetRecipeIdeas)
                    }
                    ToolbarItem(placement: .navigationBarTrailing) {
                        Button(role: .destructive, action: { showDeleteSelectedDialog = true }) {
                            Image(systemName: "trash")
                        }
                        .disabled(selectedItemIDs.isEmpty)
                        .accessibilityLabel(L10n.swipeDelete)
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
            // `onDismiss` (rather than triggering `showActivitySheet` directly from `onShare`)
            // waits for this sheet's dismissal animation to finish before the next sheet
            // presents — presenting two sheets back-to-back in the same action is unreliable.
            .sheet(isPresented: $showShareDialog, onDismiss: {
                if shareAfterDialogDismiss {
                    shareAfterDialogDismiss = false
                    performShare()
                }
            }) {
                ShareRecipeDialog(
                    includeOtherItems: $includeOtherItemsInShare,
                    onCancel: { showShareDialog = false },
                    onShare: {
                        shareAfterDialogDismiss = true
                        showShareDialog = false
                    }
                )
                .presentationDetents([.height(220)])
            }
            .sheet(isPresented: $showActivitySheet) {
                ActivityView(activityItems: [shareText])
            }
            .confirmationDialog(
                L10n.deleteSelectedDialogTitle,
                isPresented: $showDeleteSelectedDialog,
                titleVisibility: .visible
            ) {
                Button(L10n.swipeDelete, role: .destructive) {
                    deleteSelectedItems()
                }
                Button(L10n.cancelButton, role: .cancel) {}
            }
        }
    }

    private func deleteSelectedItems() {
        // Goes through the same `deleteItem` the row's own swipe-to-delete uses, so each
        // removal still fires `onItemRemoved` for its usual undo toast — there's no separate
        // bulk-undo, just one toast per item.
        for id in selectedItemIDs {
            viewModel.deleteItem(id, onItemRemoved: onItemRemoved)
        }
        selectedItemIDs = []
        isSelecting = false
    }

    private func performShare() {
        let selectedItems = viewModel.items.filter { selectedItemIDs.contains($0.id) }
        guard !selectedItems.isEmpty else { return }
        let otherItems = includeOtherItemsInShare
            ? viewModel.items.filter { !selectedItemIDs.contains($0.id) }
            : []
        shareText = NativePantryViewModel.recipePrompt(for: selectedItems, otherItems: otherItems)
        showActivitySheet = true
        selectedItemIDs = []
        isSelecting = false
        includeOtherItemsInShare = false
    }

    private func toggleSelection(_ id: String) {
        if selectedItemIDs.contains(id) {
            selectedItemIDs.remove(id)
            // Deselecting the last item ends selection mode, same as tapping Cancel — an empty
            // selection has nothing to share or delete, so there's no reason to stay in it.
            if selectedItemIDs.isEmpty {
                isSelecting = false
            }
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
                            LocationChip(title: L10n.storageLocationName(loc), isSelected: viewModel.selectedLocation == loc) {
                                viewModel.selectedLocation = loc
                            }
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 4)
                }
                .padding(.top, 8)

                // Category Filter Chips — derived from categories present on current items
                // (filtered to enabled ones and ordered per user preference; see
                // CategorySettingsView), rather than a fixed settings list.
                if !viewModel.availableCategories.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            LocationChip(title: L10n.categoryFilterAll, isSelected: viewModel.selectedCategory == nil) {
                                viewModel.selectedCategory = nil
                            }
                            ForEach(viewModel.availableCategories, id: \.self) { category in
                                LocationChip(title: viewModel.categoryLabel(for: category), isSelected: viewModel.selectedCategory == category) {
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

/// Mirrors the Android "Get recipe ideas" dialog: lets the user opt in to including the rest
/// of their pantry (as extra ingredient names only) before the share sheet opens, since a plain
/// `ShareLink` can't host a toggle.
private struct ShareRecipeDialog: View {
    @Binding var includeOtherItems: Bool
    let onCancel: () -> Void
    let onShare: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text(L10n.shareRecipeDialogTitle)
                .font(.headline)

            Toggle(isOn: $includeOtherItems) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(L10n.shareRecipeIncludeOtherItemsTitle)
                        .font(.body)
                    Text(L10n.shareRecipeIncludeOtherItemsSubtitle)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
            .toggleStyle(.switch)

            HStack {
                Spacer()
                Button(L10n.shareRecipeCancelButton, action: onCancel)
                Button(L10n.shareRecipeShareButton, action: onShare)
                    .fontWeight(.semibold)
            }
        }
        .padding(20)
    }
}

/// Wraps `UIActivityViewController` so the share sheet can be triggered programmatically after
/// `ShareRecipeDialog` is dismissed, rather than via `ShareLink`'s own tap gesture.
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
