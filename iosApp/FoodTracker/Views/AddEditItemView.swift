import SwiftUI

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

    @State private var name: String = ""
    @State private var brand: String = ""
    @State private var barcode: String = ""
    @State private var imageUrl: String? = nil
    @State private var expiryDate: Date = Calendar.current.date(byAdding: .day, value: 7, to: Date())!
    @State private var storageLocation: String = "Pantry"
    @State private var notes: String = ""

    public init(
        viewModel: NativePantryViewModel,
        editingItem: PantryItem? = nil,
        prefillBarcode: String? = nil,
        prefillName: String? = nil,
        prefillBrand: String? = nil,
        prefillImageUrl: String? = nil,
        prefillDate: Date? = nil
    ) {
        self.viewModel = viewModel
        self.editingItem = editingItem
        self.prefillBarcode = prefillBarcode
        self.prefillName = prefillName
        self.prefillBrand = prefillBrand
        self.prefillImageUrl = prefillImageUrl
        self.prefillDate = prefillDate
    }

    public var body: some View {
        NavigationStack {
            Form {
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
                    DatePicker(L10n.expiryPickerLabel, selection: $expiryDate, displayedComponents: [.date])
                        .datePickerStyle(.graphical)

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
                    Picker(L10n.locationPickerLabel, selection: $storageLocation) {
                        ForEach(settingsStore.settings.storageLocations, id: \.self) { loc in
                            Text(loc).tag(loc)
                        }
                    }
                    .pickerStyle(.segmented)
                }

                Section(header: Text(L10n.sectionNotes)) {
                    TextField(L10n.notesFieldPlaceholder, text: $notes, axis: .vertical)
                        .lineLimit(2...4)
                }
            }
            .navigationTitle(editingItem != nil ? L10n.editItemTitle : L10n.addItemTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.cancelButton) { dismiss() }
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
                            notes: notes.trimmingCharacters(in: .whitespaces).isEmpty ? nil : notes
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
                    notes = item.notes ?? ""
                } else {
                    name = prefillName ?? ""
                    brand = prefillBrand ?? ""
                    barcode = prefillBarcode ?? ""
                    imageUrl = prefillImageUrl
                    storageLocation = settingsStore.settings.defaultStorageLocation
                    if let date = prefillDate {
                        expiryDate = date
                    }
                }
            }
        }
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
