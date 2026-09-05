import SwiftUI

struct ShoppingListView: View {
    @ObservedObject var viewModel: NativePantryViewModel
    @Environment(\.dismiss) private var dismiss
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
    @State private var showDidYouMeanDialog: Bool = false
    @State private var pendingMatchCandidates: [ShoppingListEntry] = []
    @State private var pendingMatchBarcode: String?
    @State private var pendingMatchName: String?
    @State private var pendingMatchBrand: String?
    @State private var pendingMatchImageUrl: String?
    @State private var pendingMatchDate: Date?
    @State private var pendingMatchCategory: String?
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
                            }
                        }
                        .padding(.horizontal, 16)
                    }
                    .padding(.bottom, 8)
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
                } else {
                    List {
                        ForEach(groupedEntries, id: \.category) { group in
                            Section(header: Text(group.category ?? L10n.shoppingListUncategorized)) {
                                ForEach(group.entries) { entry in
                                    HStack {
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(entry.productName)
                                                .font(.body)
                                            if let barcode = entry.barcode {
                                                Text(barcode)
                                                    .font(.caption)
                                                    .foregroundColor(.secondary)
                                            }
                                        }
                                        Spacer()
                                        Button {
                                            scanningEntry = entry
                                            isScanningForNewEntry = false
                                            viewModel.beginScanForShoppingListEntry(entry.id)
                                            showScanner = true
                                        } label: {
                                            Image(systemName: "barcode.viewfinder")
                                        }
                                        .buttonStyle(.plain)
                                        .accessibilityLabel(L10n.shoppingListScan)
                                        Button {
                                            viewModel.decrementShoppingListQuantity(entry.id)
                                        } label: {
                                            Image(systemName: "minus.circle")
                                        }
                                        .buttonStyle(.plain)
                                        Text("\(entry.quantity)")
                                            .frame(minWidth: 16)
                                        Button {
                                            viewModel.incrementShoppingListQuantity(entry.id)
                                        } label: {
                                            Image(systemName: "plus.circle")
                                        }
                                        .buttonStyle(.plain)
                                    }
                                    .swipeActions(edge: .trailing) {
                                        Button(role: .destructive) {
                                            viewModel.deleteShoppingListEntry(entry.id)
                                        } label: {
                                            Label(L10n.shoppingListCheckOff, systemImage: "checkmark.circle.fill")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle(L10n.shoppingListTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(L10n.doneButton) { dismiss() }
                        .fontWeight(.semibold)
                }
            }
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
                ScannerSheetView(viewModel: viewModel) { barcode, name, brand, imageUrl, date, category in
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
                            showAddEdit = true
                        case .suggestions(let candidates):
                            pendingMatchCandidates = candidates
                            pendingMatchBarcode = barcode
                            pendingMatchName = name
                            pendingMatchBrand = brand
                            pendingMatchImageUrl = imageUrl
                            pendingMatchDate = date
                            pendingMatchCategory = category
                            showDidYouMeanDialog = true
                        case .noMatch:
                            prefillBarcode = barcode
                            prefillName = name
                            prefillBrand = brand
                            prefillImageUrl = imageUrl
                            prefillDate = date
                            prefillCategory = category
                            showAddEdit = true
                        }
                    } else {
                        prefillBarcode = barcode
                        prefillName = name ?? scanningEntry?.productName
                        prefillBrand = brand
                        prefillImageUrl = imageUrl
                        prefillDate = date
                        prefillCategory = category ?? scanningEntry?.category
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
                    prefillCategory: prefillCategory
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

    private var groupedEntries: [(category: String?, entries: [ShoppingListEntry])] {
        let grouped = Dictionary(grouping: viewModel.shoppingList, by: { $0.category })
        return grouped.keys.sorted { ($0 ?? "\u{FFFF}") < ($1 ?? "\u{FFFF}") }.map { key in
            (category: key, entries: grouped[key] ?? [])
        }
    }

    private func submitNewEntry() {
        let trimmed = newEntryText.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        viewModel.addManualShoppingListEntry(trimmed)
        newEntryText = ""
    }
}
