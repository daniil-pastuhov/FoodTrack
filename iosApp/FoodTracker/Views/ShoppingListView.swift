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
    @State private var showShareSheet = false
    @State private var showQrCode = false
    @State private var showImportSheet = false
    @State private var importText = ""
    @State private var importResult: String?
    @FocusState private var isInputFocused: Bool
    @Environment(\.showSnackbar) private var showSnackbar
    @State private var showNewSection = false
    @State private var showReorderSections = false
    @State private var sectionNameText = ""
    @State private var renamingSection: ShoppingSection?
    @State private var deletingSection: ShoppingSection?

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
                                        .background(LarderColor.surfaceContainer)
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
                            .foregroundColor(LarderColor.onSurfaceVariant)
                        Text(L10n.shoppingListEmptyTitle)
                            .font(.headline)
                            .foregroundColor(LarderColor.onSurfaceVariant)
                        Text(L10n.shoppingListEmptySubtitle)
                            .font(.subheadline)
                            .foregroundColor(LarderColor.onSurfaceVariant)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 24)
                        Spacer()
                    }
                    .frame(maxWidth: .infinity)
                    .transition(.opacity.combined(with: .scale(scale: 0.95)))
                } else {
                    ShoppingSectionedList(
                        viewModel: viewModel,
                        onItemRemoved: onItemRemoved,
                        onEntryTap: addEntryManually,
                        onRenameSection: { section in
                            sectionNameText = L10n.shoppingSectionName(section)
                            renamingSection = section
                        },
                        onDeleteSection: { deletingSection = $0 }
                    )
                    .transition(.opacity)
                }
            }
            .animation(.easeInOut(duration: 0.2), value: suggestions)
            .animation(.easeInOut(duration: 0.25), value: viewModel.shoppingList.isEmpty)
            .background(LarderColor.background.ignoresSafeArea())
            .navigationTitle(L10n.shoppingListTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button {
                            sectionNameText = ""
                            showNewSection = true
                        } label: {
                            Label(L10n.shoppingSectionNew, systemImage: "folder.badge.plus")
                        }
                        if viewModel.shoppingSections.count > 2 {
                            Button {
                                showReorderSections = true
                            } label: {
                                Label(L10n.shoppingSectionReorder, systemImage: "arrow.up.arrow.down")
                            }
                        }
                    } label: {
                        Image(systemName: "folder.badge.plus")
                    }
                    .accessibilityLabel(L10n.shoppingSectionNew)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        showShareSheet = true
                    } label: {
                        Image(systemName: "square.and.arrow.up")
                    }
                    .accessibilityLabel(L10n.shoppingListShareContentDescription)
                }
            }
            .confirmationDialog(
                L10n.shoppingListShareDialogTitle,
                isPresented: $showShareSheet,
                titleVisibility: .visible
            ) {
                // Sharing an empty list would hand someone a title and nothing else.
                if !viewModel.shoppingList.isEmpty {
                    ShareLink(item: shareText()) {
                        Text(L10n.shoppingListShareAsText)
                    }
                    Button(L10n.shoppingListShareAsQr) { showQrCode = true }
                }
                Button(L10n.shoppingListImport) { showImportSheet = true }
                Button(L10n.cancelButton, role: .cancel) {}
            } message: {
                Text(L10n.shoppingListShareDialogMessage)
            }
            .sheet(isPresented: $showQrCode) {
                ShoppingListQrSheet(payload: ShoppingListShare.toPayload(
                    entries: viewModel.shoppingList,
                    sections: viewModel.shoppingSections
                ))
            }
            .sheet(isPresented: $showReorderSections) {
                ShoppingSectionReorderSheet(viewModel: viewModel)
            }
            .alert(L10n.shoppingSectionNew, isPresented: $showNewSection) {
                TextField(L10n.shoppingSectionNamePlaceholder, text: $sectionNameText)
                Button(L10n.cancelButton, role: .cancel) {}
                Button(L10n.shoppingSectionSave) { viewModel.createShoppingSection(sectionNameText) }
                    .disabled(sectionNameText.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            .alert(
                L10n.shoppingSectionRename,
                isPresented: Binding(get: { renamingSection != nil }, set: { if !$0 { renamingSection = nil } })
            ) {
                TextField(L10n.shoppingSectionNamePlaceholder, text: $sectionNameText)
                Button(L10n.cancelButton, role: .cancel) {}
                Button(L10n.shoppingSectionSave) {
                    if let section = renamingSection {
                        viewModel.renameShoppingSection(section.id, to: sectionNameText)
                    }
                }
                .disabled(sectionNameText.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            .alert(
                deletingSection.map { L10n.shoppingSectionDeleteTitle(L10n.shoppingSectionName($0)) } ?? "",
                isPresented: Binding(get: { deletingSection != nil }, set: { if !$0 { deletingSection = nil } })
            ) {
                Button(L10n.cancelButton, role: .cancel) {}
                Button(L10n.shoppingSectionDelete, role: .destructive) {
                    if let section = deletingSection {
                        withAnimation { viewModel.deleteShoppingSection(section.id) }
                    }
                }
            } message: {
                Text(L10n.shoppingSectionDeleteMessage)
            }
            .sheet(isPresented: $showImportSheet) {
                NavigationStack {
                    Form {
                        Section {
                            TextEditor(text: $importText)
                                .frame(minHeight: 120)
                        } header: {
                            Text(L10n.shoppingListImportMessage)
                        }
                    }
                    .navigationTitle(L10n.shoppingListImport)
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button(L10n.cancelButton) {
                                importText = ""
                                showImportSheet = false
                            }
                        }
                        ToolbarItem(placement: .confirmationAction) {
                            Button(L10n.shoppingListImportConfirm) {
                                applyImport(importText)
                                importText = ""
                                showImportSheet = false
                            }
                            .disabled(importText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                            .fontWeight(.bold)
                        }
                    }
                }
            }
            .onChange(of: importResult) { _, message in
                guard let message else { return }
                showSnackbar(message)
                importResult = nil
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
                ScannerSheetView(
                    viewModel: viewModel,
                    onShoppingListScanned: { payload in
                        isScanningForNewEntry = false
                        scanningEntry = nil
                        applyImport(payload)
                    }
                ) { barcode, name, brand, imageUrl, date, category, suggestion in
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
                    prefillSuggestion: prefillSuggestion,
                    onDuplicateMerged: { updated in
                        importResult = L10n.duplicateAdded(updated.name, updated.quantity)
                    }
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

    /// The list as shareable text, grouped by section the way the screen shows it. Built from
    /// the whole list, not the rows, so a collapsed section's entries are still shared.
    private func shareText() -> String {
        ShoppingListShare.toText(
            entries: viewModel.shoppingList,
            title: L10n.shoppingListTitle,
            sections: viewModel.shoppingSections,
            sectionLabel: L10n.shoppingSectionName
        )
    }

    /// An import has three distinct outcomes worth telling apart: some entries were added, the
    /// list was understood but everything on it was already here, or nothing parseable was found
    /// at all. Collapsing the last two into one message would leave the user unsure whether the
    /// import even worked.
    private func applyImport(_ raw: String) {
        let result = viewModel.importShoppingList(raw)
        importResult = if result.added > 0 {
            L10n.shoppingListImportAdded(result.added)
        } else if result.found > 0 {
            L10n.shoppingListImportAllPresent
        } else {
            L10n.shoppingListImportNothing
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


/// Shows the shopping list as a QR code for someone else to scan with their own scanner.
private struct ShoppingListQrSheet: View {
    let payload: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                if let image = generateQrImage(contents: payload) {
                    Image(uiImage: image)
                        .interpolation(.none)
                        .resizable()
                        .scaledToFit()
                        .frame(maxWidth: 260, maxHeight: 260)
                        // The code is pure black-on-white, so it needs a light backing to stay
                        // scannable in dark mode.
                        .padding(12)
                        .background(Color.white)
                        .cornerRadius(12)
                    Text(L10n.shoppingListShareQrHint)
                        .font(.footnote)
                        .foregroundStyle(LarderColor.onSurfaceVariant)
                        .multilineTextAlignment(.center)
                } else {
                    // A QR code has a hard capacity limit and the generator refuses rather than
                    // truncating, so a long list simply can't be shared this way. Text has no
                    // such limit, so point at it instead of showing a broken sheet.
                    Text(L10n.shoppingListShareQrTooLarge)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)
                }
                Spacer()
            }
            .padding(.top, 24)
            .padding(.horizontal)
            .navigationTitle(L10n.shoppingListShareAsQr)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L10n.doneButton) { dismiss() }
                }
            }
        }
        .presentationDetents([.medium])
    }
}
