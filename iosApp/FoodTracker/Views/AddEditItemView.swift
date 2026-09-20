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

    @State private var name: String = ""
    @State private var brand: String = ""
    @State private var barcode: String = ""
    @State private var imageUrl: String? = nil
    @State private var expiryDate: Date = Calendar.current.date(byAdding: .day, value: 7, to: Date())!
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
        prefillCategory: String? = nil
    ) {
        self.viewModel = viewModel
        self.editingItem = editingItem
        self.prefillBarcode = prefillBarcode
        self.prefillName = prefillName
        self.prefillBrand = prefillBrand
        self.prefillImageUrl = prefillImageUrl
        self.prefillDate = prefillDate
        self.prefillCategory = prefillCategory
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

                Section(header: Text(L10n.sectionProductDetails)) {
                    TextField(L10n.productNameLabel, text: $name)
                    TextField(L10n.brandLabel, text: $brand)
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
                    }
                }
            }
            .sheet(isPresented: $showScanner) {
                ScannerSheetView(viewModel: viewModel, showManualEntryShortcut: false) { scannedBarcode, scannedName, scannedBrand, scannedImageUrl, scannedDate, scannedCategory in
                    if let scannedBarcode { barcode = scannedBarcode }
                    if let scannedName { name = scannedName }
                    if let scannedBrand { brand = scannedBrand }
                    if let scannedImageUrl { imageUrl = scannedImageUrl }
                    if let scannedDate { expiryDate = scannedDate }
                    if let scannedCategory { category = scannedCategory }
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
