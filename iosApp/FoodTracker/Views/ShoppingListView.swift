import SwiftUI

struct ShoppingListView: View {
    @ObservedObject var viewModel: NativePantryViewModel
    /// Fires with the removed entry whenever the "-" button or the swipe "check off" action
    /// fully removes it from the shopping list, so the root can offer it back through an undo
    /// toast — hosted at the root (see `RootTabView`), matching `PantryDashboardView`.
    var onItemRemoved: (ShoppingListEntry) -> Void = { _ in }
    @State private var newEntryText: String = ""
    @State private var showScanner: Bool = false
    @State private var showAddEdit: Bool = false
    @State private var scanningEntry: ShoppingListEntry?
    @State private var isScanningForNewEntry: Bool = false
    @State private var prefillBarcode: String?
    @State private var prefillName: String?
    @State private var prefillBrand: String?
    @State private var prefillImageUrl: String?
    @State private var prefillDate: Date?
    @State private var prefillCategory: String?
    /// Expiry to start a new item from when the scan produced no date of its own - the
    /// shelf life this barcode was last given, or its category's default.
    @State private var prefillSuggestion: ShelfLifeSuggestion?
    @State private var showDidYouMeanDialog: Bool = false
    @State private var pendingMatchCandidates: [ShoppingListEntry] = []
    @State private var pendingMatchBarcode: String?
    @State private var pendingMatchName: String?
    @State private var pendingMatchBrand: String?
    @State private var pendingMatchImageUrl: String?
    @State private var pendingMatchDate: Date?
    @State private var pendingMatchCategory: String?
    @State private var pendingMatchSuggestion: ShelfLifeSuggestion?
    @FocusState private var isInputFocused: Bool

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                HStack(spacing: 8) {
                    TextField(L10n.shoppingListAddPlaceholder, text: $newEntryText)
                        .textFieldStyle(.roundedBorder)
                        .focused($isInputFocused)
                        .submitLabel(.done)
                        .onSubmit { submitNewEntry() }
                    // A blank field opens the scanner (the fast path most of the time); once
                    // the user starts typing, the same button submits that text instead.
                    Button {
                        if newEntryText.trimmingCharacters(in: .whitespaces).isEmpty {
                            scanningEntry = nil
                            isScanningForNewEntry = true
                            showScanner = true
                        } else {
                            submitNewEntry()
                        }
                    } label: {
                        Image(systemName: newEntryText.trimmingCharacters(in: .whitespaces).isEmpty
                              ? "barcode.viewfinder" : "plus.circle.fill")
                            .font(.title2)
                    }
                    .accessibilityLabel(newEntryText.trimmingCharacters(in: .whitespaces).isEmpty
                                         ? L10n.shoppingListScan : L10n.shoppingListAddButton)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)

                if !suggestions.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(suggestions, id: \.self) { suggestion in
                                Button {
                                    viewModel.addManualShoppingListEntry(suggestion)
                                    newEntryText = ""
                                } label: {
                                    Text(suggestion)
                                        .font(.subheadline)
                                        .padding(.horizontal, 12)
                                        .padding(.vertical, 6)
                                        .background(Color(.secondarySystemFill))
                                        .clipShape(Capsule())
                                }
                                .buttonStyle(.plain)
                                .transition(.scale.combined(with: .opacity))
                            }
                        }
                        .padding(.horizontal, 16)
                    }
                    .padding(.bottom, 8)
                    .transition(.opacity.combined(with: .move(edge: .top)))
                }

                if viewModel.shoppingList.isEmpty {
                    VStack(spacing: 12) {
                        Spacer()
                        Image(systemName: "cart")
                            .font(.system(size: 48))
                            .foregroundColor(.secondary)
                        Text(L10n.shoppingListEmptyTitle)
                            .font(.headline)
                            .foregroundColor(.secondary)
                        Text(L10n.shoppingListEmptySubtitle)
                            .font(.subheadline)
                            .foregroundColor(.secondary.opacity(0.8))
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 24)
                        Spacer()
                    }
                    .frame(maxWidth: .infinity)
                    .transition(.opacity.combined(with: .scale(scale: 0.95)))
                } else {
                    List {
                        ForEach(groupedEntries, id: \.category) { group in
                            Section {
                                ForEach(group.entries) { entry in
                                    HStack(spacing: 14) {
                                        // Decorative affordance only: the row itself is
                                        // reorderable by long-press-and-drag via .onMove below.
                                        // The system's own grabber is not used because it needs
                                        // edit mode, and edit mode suppresses .swipeActions.
                                        Image(systemName: "line.3.horizontal")
                                            .font(.title3)
                                            .foregroundColor(.secondary)
                                            .accessibilityHidden(true)

                                        VStack(alignment: .leading, spacing: 4) {
                                            Text(entry.productName)
                                                .font(.title3)
                                            if let barcode = entry.barcode {
                                                Text(barcode)
                                                    .font(.subheadline)
                                                    .foregroundColor(.secondary)
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
                                    .onTapGesture {
                                        addEntryManually(entry)
                                    }
                                    .swipeActions(edge: .trailing) {
                                        Button(role: .destructive) {
                                            viewModel.deleteShoppingListEntry(entry.id, onItemRemoved: onItemRemoved)
                                        } label: {
                                            Label(L10n.shoppingListCheckOff, systemImage: "checkmark.circle.fill")
                                        }
                                    }
                                    // No .onDelete is attached, so this is only insurance
                                    // against a stray delete affordance appearing alongside
                                    // the reorder support added by .onMove.
                                    .deleteDisabled(true)
                                }
                                .onMove { from, to in
                                    guard let sourceIndex = from.first else { return }
                                    let movedId = group.entries[sourceIndex].id
                                    var reordered = group.entries
                                    reordered.move(fromOffsets: from, toOffset: to)
                                    guard let newIndex = reordered.firstIndex(where: { $0.id == movedId }) else { return }
                                    let beforeId = newIndex > 0 ? reordered[newIndex - 1].id : nil
                                    let afterId = newIndex < reordered.count - 1 ? reordered[newIndex + 1].id : nil
                                    viewModel.moveShoppingListEntry(movedId, beforeId: beforeId, afterId: afterId)
                                }
                            } header: {
                                if let category = group.category {
                                    Text(category)
                                } else if group.category != groupedEntries.first?.category {
                                    Divider()
                                        .padding(.vertical, 8)
                                }
                            }
                        }
                    }
                    .listStyle(.plain)
                    // Clears the floating pill nav bar that overlays this screen (see RootTabView).
                    .safeAreaInset(edge: .bottom) { Color.clear.frame(height: 170) }
                    .animation(.default, value: viewModel.shoppingList)
                    .transition(.opacity)
                }
            }
            .animation(.easeInOut(duration: 0.2), value: suggestions)
            .animation(.easeInOut(duration: 0.25), value: viewModel.shoppingList.isEmpty)
            .navigationTitle(L10n.shoppingListTitle)
            .navigationBarTitleDisplayMode(.inline)
            .sheet(isPresented: $showScanner, onDismiss: {
                // Covers the scanner's own "x" cancel button, which dismisses without ever
                // calling the completion closure below. Skipped when the scan is instead
                // proceeding into Add/Edit or the "Did you mean?" dialog (showAddEdit/
                // showDidYouMeanDialog already true) — those flows need scanningEntry/
                // scanningShoppingListEntryId to survive until they resolve.
                guard !showAddEdit && !showDidYouMeanDialog else { return }
                if isScanningForNewEntry || scanningEntry != nil {
                    isScanningForNewEntry = false
                    scanningEntry = nil
                    viewModel.clearScanState()
                }
            }) {
                ScannerSheetView(viewModel: viewModel) { barcode, name, brand, imageUrl, date, category, suggestion in
                    if isScanningForNewEntry {
                        isScanningForNewEntry = false
                        switch ShoppingListMatcher.match(scannedName: name, scannedBarcode: barcode, shoppingList: viewModel.shoppingList) {
                        case .exactBarcodeMatch(let entry):
                            viewModel.beginScanForShoppingListEntry(entry.id)
                            prefillBarcode = barcode
                            prefillName = name ?? entry.productName
                            prefillBrand = brand
                            prefillImageUrl = imageUrl
                            prefillDate = date
                            prefillCategory = category ?? entry.category
                            prefillSuggestion = suggestion
                            showAddEdit = true
                        case .suggestions(let candidates):
                            pendingMatchCandidates = candidates
                            pendingMatchBarcode = barcode
                            pendingMatchName = name
                            pendingMatchBrand = brand
                            pendingMatchImageUrl = imageUrl
                            pendingMatchDate = date
                            pendingMatchCategory = category
                            pendingMatchSuggestion = suggestion
                            showDidYouMeanDialog = true
                        case .noMatch:
                            prefillBarcode = barcode
                            prefillName = name
                            prefillBrand = brand
                            prefillImageUrl = imageUrl
                            prefillDate = date
                            prefillCategory = category
                            prefillSuggestion = suggestion
                            showAddEdit = true
                        }
                    } else {
                        prefillBarcode = barcode
                        prefillName = name ?? scanningEntry?.productName
                        prefillBrand = brand
                        prefillImageUrl = imageUrl
                        prefillDate = date
                        prefillCategory = category ?? scanningEntry?.category
                        prefillSuggestion = suggestion
                        showAddEdit = true
                    }
                }
            }
            .sheet(isPresented: $showAddEdit, onDismiss: {
                viewModel.clearScanState()
                scanningEntry = nil
            }) {
                AddEditItemView(
                    viewModel: viewModel,
                    prefillBarcode: prefillBarcode,
                    prefillName: prefillName,
                    prefillBrand: prefillBrand,
                    prefillImageUrl: prefillImageUrl,
                    prefillDate: prefillDate,
                    prefillCategory: prefillCategory,
                    prefillSuggestion: prefillSuggestion
                )
            }
            .confirmationDialog(
                L10n.shoppingListDidYouMeanTitle,
                isPresented: $showDidYouMeanDialog,
                titleVisibility: .visible
            ) {
                ForEach(pendingMatchCandidates) { candidate in
                    Button("\(candidate.productName) (\(candidate.quantity))") {
                        viewModel.beginScanForShoppingListEntry(candidate.id)
                        prefillBarcode = pendingMatchBarcode
                        prefillName = pendingMatchName ?? candidate.productName
                        prefillBrand = pendingMatchBrand
                        prefillImageUrl = pendingMatchImageUrl
                        prefillDate = pendingMatchDate
                        prefillCategory = pendingMatchCategory ?? candidate.category
                        prefillSuggestion = pendingMatchSuggestion
                        showAddEdit = true
                    }
                }
                Button(L10n.shoppingListDidYouMeanNoneOfThese) {
                    prefillBarcode = pendingMatchBarcode
                    prefillName = pendingMatchName
                    prefillBrand = pendingMatchBrand
                    prefillImageUrl = pendingMatchImageUrl
                    prefillDate = pendingMatchDate
                    prefillCategory = pendingMatchCategory
                    prefillSuggestion = pendingMatchSuggestion
                    showAddEdit = true
                }
                Button(L10n.cancelButton, role: .cancel) {
                    viewModel.clearScanState()
                }
            }
            .onAppear {
                if viewModel.shoppingList.isEmpty {
                    isInputFocused = true
                }
            }
            .onChange(of: viewModel.shoppingList.isEmpty) { _, isEmpty in
                if isEmpty {
                    isInputFocused = true
                }
            }
        }
    }

    private var suggestions: [String] {
        let trimmed = newEntryText.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return [] }
        return viewModel.knownProductNames.filter {
            $0.localizedCaseInsensitiveContains(trimmed) && $0.caseInsensitiveCompare(trimmed) != .orderedSame
        }.prefix(5).map { $0 }
    }

    // Groups are keyed by the *resolved* category (so a disabled category, or two origins
    // renamed to the same name, fold into one group here) and ordered by the user's category
    // preference order (see CategorySettingsView) rather than alphabetically.
    private var groupedEntries: [(category: String?, entries: [ShoppingListEntry])] {
        let preferences = viewModel.categoryPreferences
        let grouped = Dictionary(grouping: viewModel.shoppingList, by: { resolveCategoryDisplayName($0.category, preferences) })
        return grouped.keys.sorted { lhs, rhs in
            let lhsOrder = grouped[lhs]?.map { categoryPreferenceSortOrder($0.category, preferences) }.min() ?? .greatestFiniteMagnitude
            let rhsOrder = grouped[rhs]?.map { categoryPreferenceSortOrder($0.category, preferences) }.min() ?? .greatestFiniteMagnitude
            if lhsOrder != rhsOrder { return lhsOrder < rhsOrder }
            return (lhs ?? "\u{FFFF}") < (rhs ?? "\u{FFFF}")
        }.map { key in
            (category: key, entries: grouped[key] ?? [])
        }
    }

    /// Tapping a row (rather than its scan icon) skips the camera entirely and opens Add/Edit
    /// prefilled from the entry, for items bought without a barcode to scan (produce, bakery,
    /// etc). Marking the entry via `beginScanForShoppingListEntry` means saving still removes
    /// it from the shopping list, same as the scan-to-complete path does.
    private func addEntryManually(_ entry: ShoppingListEntry) {
        scanningEntry = entry
        isScanningForNewEntry = false
        viewModel.beginScanForShoppingListEntry(entry.id)
        prefillBarcode = entry.barcode
        prefillName = entry.productName
        prefillBrand = nil
        prefillImageUrl = nil
        prefillDate = nil
        prefillCategory = entry.category
        showAddEdit = true
    }

    private func submitNewEntry() {
        let trimmed = newEntryText.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        viewModel.addManualShoppingListEntry(trimmed)
        newEntryText = ""
    }
}
