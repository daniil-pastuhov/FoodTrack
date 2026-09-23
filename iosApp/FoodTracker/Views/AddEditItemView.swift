import SwiftUI
import UIKit

public struct AddEditItemView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var viewModel: NativePantryViewModel
    @ObservedObject private var settingsStore = SettingsStore.shared

    var editingItem: PantryItem?
    var prefillBarcode: String?
    var prefillName: String?
    var prefillBrand: String?
    var prefillImageUrl: String?
    var prefillDate: Date?
    var prefillCategory: String?
    /// Expiry to start a *new* item from when the scan produced no date of its own, plus where
    /// it came from so the form can say so - see `ShelfLifeSuggestion`.
    var prefillSuggestion: ShelfLifeSuggestion?
    /// Called instead of a save when the user chose to add a unit to an item they already have,
    /// so the host can report it.
    var onDuplicateMerged: (PantryItem) -> Void = { _ in }

    @State private var name: String = ""
    @State private var brand: String = ""
    @State private var barcode: String = ""
    @State private var imageUrl: String? = nil
    @State private var expiryDate: Date = Calendar.current.date(byAdding: .day, value: 7, to: Date())!
    /// A suggestion produced by the embedded scan sheet below, which supersedes whatever this
    /// screen was opened with.
    @State private var overlaySuggestion: ShelfLifeSuggestion?
    @State private var showProductDetail = false

    /// The suggestion to caption the date field with, or nil when there is nothing to explain.
    ///
    /// Derived from whether `expiryDate` still *is* the suggested date rather than from an
    /// "edited" flag: on iOS the date is a `DatePicker` binding, and its `onChange` fires for
    /// programmatic assignments (the `onAppear` prefill included) just as it does for the user
    /// spinning the wheel, so a flag set there could not tell the two apart. Comparing values
    /// needs no such distinction - the caption disappears exactly when the date it describes
    /// stops being what's on screen, however it changed.
    private var activeSuggestion: ShelfLifeSuggestion? {
        // Editing an existing item: the date shown is the item's own, not a guess.
        guard editingItem == nil else { return nil }
        let candidate: ShelfLifeSuggestion?
        if let overlaySuggestion {
            candidate = overlaySuggestion
        } else if prefillDate != nil {
            // A real date was handed in (scanned, or carried from a shopping list entry).
            candidate = nil
        } else {
            candidate = prefillSuggestion
        }
        guard let candidate, candidate.isInformative else { return nil }
        guard Calendar.current.isDate(expiryDate, inSameDayAs: candidate.date) else { return nil }
        return candidate
    }
    @State private var storageLocation: String = "Pantry"
    @State private var category: String = ""
    @State private var quantity: Int = 1
    @State private var notes: String = ""
    @State private var essential: Bool = true
    // Embedded scan flow: presents ScannerSheetView on top of this form instead of navigating
    // away, so fields already typed here (quantity, notes, storage location, essential)
    // survive a scan rather than being lost to a fresh screen instance.
    @State private var showScanner: Bool = false

    public init(
        viewModel: NativePantryViewModel,
        editingItem: PantryItem? = nil,
        prefillBarcode: String? = nil,
        prefillName: String? = nil,
        prefillBrand: String? = nil,
        prefillImageUrl: String? = nil,
        prefillDate: Date? = nil,
        prefillCategory: String? = nil,
        prefillSuggestion: ShelfLifeSuggestion? = nil,
        onDuplicateMerged: @escaping (PantryItem) -> Void = { _ in }
    ) {
        self.viewModel = viewModel
        self.editingItem = editingItem
        self.prefillBarcode = prefillBarcode
        self.prefillName = prefillName
        self.prefillBrand = prefillBrand
        self.prefillImageUrl = prefillImageUrl
        self.prefillDate = prefillDate
        self.prefillCategory = prefillCategory
        self.prefillSuggestion = prefillSuggestion
        self.onDuplicateMerged = onDuplicateMerged
    }

    public var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack {
                        Spacer()
                        imagePreviewContent
                            .frame(width: 96, height: 96)
                            .background(Color(.secondarySystemFill))
                            .clipShape(RoundedRectangle(cornerRadius: 16))
                        Spacer()
                    }
                    .listRowBackground(Color.clear)
                }
                .listRowSeparator(.hidden)

                // Allergen warning, above everything else on the form: it is the one thing on
                // this screen someone might need to act on before reading anything else.
                if !viewModel.allergenWarnings.isEmpty {
                    Section {
                        Button {
                            showProductDetail = true
                        } label: {
                            Label(
                                L10n.allergenWarning(viewModel.allergenWarnings.joined(separator: ", ")),
                                systemImage: "exclamationmark.triangle.fill"
                            )
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.red)
                        }
                    }
                }

                Section(header: Text(L10n.sectionProductDetails)) {
                    // Name suggestions from the on-device classifier, for a photographed product
                    // with no barcode to look up. Only offered while the name is still empty:
                    // once the user has typed something, replacing it from under them is worse
                    // than not helping.
                    if name.isEmpty && (viewModel.isLabelingPhoto || !viewModel.photoLabelSuggestions.isEmpty) {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(viewModel.isLabelingPhoto ? L10n.photoSuggestionsAnalyzing : L10n.photoSuggestionsTitle)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            if !viewModel.isLabelingPhoto {
                                ScrollView(.horizontal, showsIndicators: false) {
                                    HStack(spacing: 8) {
                                        ForEach(viewModel.photoLabelSuggestions) { suggestion in
                                            if let label = L10n.foodLabelName(suggestion.key) {
                                                Button {
                                                    name = label
                                                    // The classifier's category is coarse but
                                                    // real, and it is what lets the per-category
                                                    // default shelf life apply to an item with
                                                    // no barcode to remember one against.
                                                    if category.isEmpty { category = suggestion.category }
                                                } label: {
                                                    Text(label)
                                                        .font(.caption)
                                                        .fontWeight(.medium)
                                                        .padding(.horizontal, 10)
                                                        .padding(.vertical, 6)
                                                        .background(Color.accentColor.opacity(0.12))
                                                        .foregroundColor(.accentColor)
                                                        .cornerRadius(8)
                                                }
                                                .buttonStyle(.plain)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    TextField(L10n.productNameLabel, text: $name)
                    TextField(L10n.brandLabel, text: $brand)
                    // Only offered when there is something to show: Open Food Facts is
                    // crowd-sourced, so plenty of products have nothing beyond a name.
                    if let nutrition = viewModel.scannedProduct?.nutrition, !nutrition.isEmpty {
                        Button {
                            showProductDetail = true
                        } label: {
                            HStack {
                                Label(L10n.productDetailContentDescription, systemImage: "info.circle")
                                Spacer()
                                Image(systemName: "chevron.right")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                    if !barcode.isEmpty {
                        HStack {
                            Text(L10n.barcodeWord)
                                .foregroundColor(.secondary)
                            Spacer()
                            Text(barcode)
                                .font(.footnote)
                                .foregroundColor(.secondary)
                        }
                    }
                }

                Section(header: Text(L10n.sectionExpirationDate)) {
                    // Day/month/year wheels: the graphical calendar collapses into a
                    // month/year-only wheel when its header is tapped, and users got
                    // stuck there with no obvious way to pick the day.
                    DatePicker(L10n.expiryPickerLabel, selection: $expiryDate, displayedComponents: [.date])
                        .datePickerStyle(.wheel)
                        .labelsHidden()
                        .frame(maxWidth: .infinity)
                        .clipped()

                    if let suggestion = activeSuggestion, let days = suggestion.days {
                        Label {
                            Text(
                                suggestion.source == .categoryDefault
                                    ? L10n.expirySuggestionCategory(
                                        category.isEmpty ? L10n.shoppingListUncategorized : category,
                                        days: days
                                      )
                                    : L10n.expirySuggestionRemembered(days: days)
                            )
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                        } icon: {
                            Image(systemName: "sparkles")
                                .foregroundStyle(Color.accentColor)
                        }
                    }

                    if !ExpiryDatePlausibility.isPlausible(expiryDate) {
                        Label(
                            L10n.expiryDateImplausibleWarning(maxYears: ExpiryDatePlausibility.maxYearsFromToday),
                            systemImage: "exclamationmark.triangle.fill"
                        )
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.red)
                    }

                    // Quick offset buttons
                    HStack(spacing: 8) {
                        QuickOffsetButton(title: L10n.quickDate3Days, days: 3, expiryDate: $expiryDate)
                        QuickOffsetButton(title: L10n.quickDate1Week, days: 7, expiryDate: $expiryDate)
                        QuickOffsetButton(title: L10n.quickDate2Weeks, days: 14, expiryDate: $expiryDate)
                        QuickOffsetButton(title: L10n.quickDate1Month, days: 30, expiryDate: $expiryDate)
                    }
                    .padding(.vertical, 4)
                }

                Section(header: Text(L10n.sectionStorage)) {
                    FlowLayout(spacing: 8) {
                        ForEach(settingsStore.settings.storageLocations, id: \.self) { loc in
                            StorageLocationChip(
                                title: loc,
                                isSelected: storageLocation == loc
                            ) {
                                storageLocation = loc
                            }
                        }
                    }
                    .padding(.vertical, 4)
                }

                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        TextField(L10n.categoryLabel, text: $category)

                        let suggestions = viewModel.categoryPreferences
                            .map { $0.displayName }
                            .filter {
                                let current = category.trimmingCharacters(in: .whitespaces)
                                return !current.isEmpty &&
                                    $0.localizedCaseInsensitiveContains(current) &&
                                    $0.localizedCaseInsensitiveCompare(current) != .orderedSame
                            }

                        if !suggestions.isEmpty {
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 8) {
                                    ForEach(Array(Set(suggestions)).sorted().prefix(5), id: \.self) { suggestion in
                                        Button(action: {
                                            category = suggestion
                                            UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
                                        }) {
                                            Text(suggestion)
                                                .font(.caption)
                                                .fontWeight(.medium)
                                                .padding(.horizontal, 10)
                                                .padding(.vertical, 6)
                                                .background(Color.accentColor.opacity(0.12))
                                                .foregroundColor(.accentColor)
                                                .cornerRadius(8)
                                        }
                                        .buttonStyle(.plain)
                                    }
                                }
                            }
                        }
                    }

                    Stepper(value: $quantity, in: 1...99) {
                        HStack {
                            Text(L10n.quantityLabel)
                            Spacer()
                            Text("\(quantity)")
                                .foregroundColor(.secondary)
                                .contentTransition(.numericText())
                                .animation(.easeInOut(duration: 0.2), value: quantity)
                        }
                    }
                }

                Section(header: Text(L10n.sectionNotes)) {
                    TextField(L10n.notesFieldPlaceholder, text: $notes, axis: .vertical)
                        .lineLimit(2...4)
                }

                Section {
                    Toggle(isOn: $essential) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(L10n.essentialItemLabel)
                            Text(L10n.essentialItemDescription)
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }
                }
            }
            .navigationTitle(editingItem != nil ? L10n.editItemTitle : L10n.addItemTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.cancelButton) { dismiss() }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(action: { showScanner = true }) {
                        Image(systemName: "camera")
                    }
                    .accessibilityLabel(L10n.scanButton)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L10n.saveButton) {
                        let item = PantryItem(
                            id: editingItem?.id ?? UUID().uuidString,
                            name: name.trimmingCharacters(in: .whitespaces),
                            brand: brand.trimmingCharacters(in: .whitespaces).isEmpty ? nil : brand,
                            barcode: barcode.isEmpty ? nil : barcode,
                            imageUrl: imageUrl,
                            expiryDate: expiryDate,
                            addedDate: editingItem?.addedDate ?? Date(),
                            storageLocation: storageLocation,
                            category: category.trimmingCharacters(in: .whitespaces).isEmpty ? nil : category,
                            quantity: quantity,
                            notes: notes.trimmingCharacters(in: .whitespaces).isEmpty ? nil : notes,
                            essential: essential
                        )
                        viewModel.saveItem(item)
                        dismiss()
                    }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                    .fontWeight(.bold)
                }
            }
            .onAppear {
                if let item = editingItem {
                    name = item.name
                    brand = item.brand ?? ""
                    barcode = item.barcode ?? ""
                    imageUrl = item.imageUrl
                    expiryDate = item.expiryDate
                    storageLocation = item.storageLocation
                    category = item.category ?? ""
                    quantity = item.quantity
                    notes = item.notes ?? ""
                    essential = item.essential
                } else {
                    name = prefillName ?? ""
                    brand = prefillBrand ?? ""
                    barcode = prefillBarcode ?? ""
                    imageUrl = prefillImageUrl
                    storageLocation = settingsStore.settings.storageLocationForNewItem
                    category = prefillCategory ?? ""
                    if let date = prefillDate {
                        expiryDate = date
                    } else if let suggested = prefillSuggestion?.date {
                        expiryDate = suggested
                    }
                }
            }
            // Offered when the scan matched something already in the pantry. Adding a unit to
            // the existing item is almost always what a re-scan of the same product means;
            // creating a second row for it is the exception, so it is the secondary action.
            .confirmationDialog(
                L10n.duplicateDialogTitle,
                isPresented: Binding(
                    get: { editingItem == nil && viewModel.duplicateOf != nil },
                    set: { if !$0 { viewModel.dismissDuplicate() } }
                ),
                titleVisibility: .visible
            ) {
                Button(L10n.duplicateDialogAddOne) {
                    if let duplicate = viewModel.duplicateOf,
                       let updated = viewModel.incrementQuantity(duplicate.id) {
                        onDuplicateMerged(updated)
                        dismiss()
                    }
                }
                Button(L10n.duplicateDialogSeparate, role: .cancel) {
                    viewModel.dismissDuplicate()
                }
            } message: {
                if let duplicate = viewModel.duplicateOf {
                    Text(L10n.duplicateDialogMessage(
                        duplicate.quantity > 1 ? "\(duplicate.name) ×\(duplicate.quantity)" : duplicate.name
                    ))
                }
            }
            .sheet(isPresented: $showProductDetail) {
                if let nutrition = viewModel.scannedProduct?.nutrition {
                    ProductDetailView(
                        productName: name.isEmpty ? (viewModel.scannedProduct?.name ?? "") : name,
                        nutrition: nutrition,
                        warnings: viewModel.allergenWarnings
                    )
                }
            }
            .sheet(isPresented: $showScanner) {
                ScannerSheetView(viewModel: viewModel, showManualEntryShortcut: false) { scannedBarcode, scannedName, scannedBrand, scannedImageUrl, scannedDate, scannedCategory, scannedSuggestion in
                    if let scannedBarcode { barcode = scannedBarcode }
                    if let scannedName { name = scannedName }
                    if let scannedBrand { brand = scannedBrand }
                    if let scannedImageUrl { imageUrl = scannedImageUrl }
                    if let scannedCategory { category = scannedCategory }
                    // A date read off the packaging wins; otherwise fall back to what this
                    // barcode (or its category) suggests. Either way this is the scan's answer
                    // rather than the user's, so it does not count as an edit and the caption
                    // stays visible.
                    if let resolved = scannedDate ?? scannedSuggestion?.date {
                        expiryDate = resolved
                    }
                    overlaySuggestion = scannedDate == nil ? scannedSuggestion : nil
                    viewModel.clearScanState()
                }
            }
        }
    }

    private var imagePreviewContent: some View {
        ProductImageView(reference: imageUrl) {
            placeholderIcon
        }
    }

    private var placeholderIcon: some View {
        Image(systemName: "takeoutbag.and.cup.and.straw.fill")
            .font(.system(size: 32))
            .foregroundColor(.accentColor)
    }
}

private struct StorageLocationChip: View {
    let title: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline)
                .fontWeight(.medium)
                .lineLimit(1)
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(isSelected ? Color.accentColor : Color(.secondarySystemFill))
                .foregroundColor(isSelected ? .white : .primary)
                .cornerRadius(16)
        }
        .buttonStyle(.plain)
    }
}

private struct QuickOffsetButton: View {
    let title: String
    let days: Int
    @Binding var expiryDate: Date

    var body: some View {
        Button(action: {
            expiryDate = Calendar.current.date(byAdding: .day, value: days, to: Date()) ?? expiryDate
        }) {
            Text(title)
                .font(.caption)
                .fontWeight(.medium)
                .padding(.horizontal, 8)
                .padding(.vertical, 6)
                .background(Color.accentColor.opacity(0.12))
                .foregroundColor(.accentColor)
                .cornerRadius(8)
        }
        .buttonStyle(.plain)
    }
}
