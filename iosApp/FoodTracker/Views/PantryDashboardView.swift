import SwiftUI

public struct PantryDashboardView: View {
    @StateObject private var viewModel = NativePantryViewModel()
    @ObservedObject private var settingsStore = SettingsStore.shared
    @State private var showScanner: Bool = false
    @State private var showAddEdit: Bool = false
    @State private var showSettings: Bool = false
    @State private var showFilters: Bool = false
    @State private var selectedItem: PantryItem? = nil

    @State private var prefillBarcode: String? = nil
    @State private var prefillName: String? = nil
    @State private var prefillBrand: String? = nil
    @State private var prefillImageUrl: String? = nil
    @State private var prefillDate: Date? = nil

    public init() {}

    public var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // Urgency Metrics Cards — also the primary status filter
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
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 6)

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
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List {
                        ForEach(viewModel.filteredItems) { item in
                            ItemRowView(item: item, expiringSoonDays: settingsStore.settings.expiringSoonDays)
                                .contentShape(Rectangle())
                                .onTapGesture {
                                    selectedItem = item
                                    showAddEdit = true
                                }
                                .swipeActions(edge: .trailing) {
                                    Button(role: .destructive) {
                                        viewModel.deleteItem(item.id)
                                    } label: {
                                        Label(L10n.swipeDelete, systemImage: "trash.fill")
                                    }
                                }
                        }
                    }
                    .listStyle(.insetGrouped)
                }
            }
            .navigationTitle(L10n.appName)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(action: { showScanner = true }) {
                        HStack(spacing: 4) {
                            Image(systemName: "barcode.viewfinder")
                            Text(L10n.scanButton)
                        }
                        .fontWeight(.semibold)
                    }
                }
                ToolbarItem(placement: .navigationBarLeading) {
                    Button(action: {
                        selectedItem = nil
                        prefillBarcode = nil
                        prefillName = nil
                        prefillBrand = nil
                        prefillImageUrl = nil
                        prefillDate = nil
                        showAddEdit = true
                    }) {
                        Image(systemName: "plus")
                    }
                }
                ToolbarItem(placement: .navigationBarLeading) {
                    Button(action: { showFilters = true }) {
                        Image(systemName: viewModel.hasActiveFilters
                            ? "line.3.horizontal.decrease.circle.fill"
                            : "line.3.horizontal.decrease.circle")
                    }
                    .accessibilityLabel(L10n.filtersButton)
                }
                ToolbarItem(placement: .navigationBarLeading) {
                    Button(action: { showSettings = true }) {
                        Image(systemName: "gearshape")
                    }
                }
            }
            .sheet(isPresented: $showScanner) {
                ScannerSheetView(viewModel: viewModel) { barcode, name, brand, imageUrl, date in
                    selectedItem = nil
                    prefillBarcode = barcode
                    prefillName = name
                    prefillBrand = brand
                    prefillImageUrl = imageUrl
                    prefillDate = date
                    showAddEdit = true
                }
            }
            .sheet(isPresented: $showAddEdit) {
                AddEditItemView(
                    viewModel: viewModel,
                    editingItem: selectedItem,
                    prefillBarcode: prefillBarcode,
                    prefillName: prefillName,
                    prefillBrand: prefillBrand,
                    prefillImageUrl: prefillImageUrl,
                    prefillDate: prefillDate
                )
            }
            .sheet(isPresented: $showSettings) {
                SettingsView(viewModel: viewModel)
            }
            .sheet(isPresented: $showFilters) {
                FilterSheetView(viewModel: viewModel, settingsStore: settingsStore)
                    .presentationDetents([.medium])
            }
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

                // Status Filter Picker (Fresh is intentionally not a tab — it's only
                // reachable via the Fresh metric card on the dashboard)
                Picker(L10n.statusPickerLabel, selection: $viewModel.selectedFilter) {
                    ForEach(NativeStatusFilter.tabCases) { filter in
                        Text(filter.localizedTitle).tag(filter)
                    }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 16)
                .padding(.top, 12)

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
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(10)
            .background(color.opacity(isSelected ? 0.22 : 0.12))
            .overlay(
                RoundedRectangle(cornerRadius: 10)
                    .stroke(color, lineWidth: isSelected ? 2 : 0)
            )
            .cornerRadius(10)
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
        }
        .buttonStyle(.plain)
    }
}
