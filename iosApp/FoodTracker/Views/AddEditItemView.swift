import SwiftUI
import UIKit

public struct AddEditItemView: View {
    /// The centred column on iPad.
    private static let columnWidth: CGFloat = 640
    /// The photo in the header, larger than a list row's.
    private static let heroThumb: CGFloat = 72
    private static let nameFieldID = "name"

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
    /// A suggestion produced by the embedded scan below, which supersedes whatever this screen
    /// was opened with.
    @State private var overlaySuggestion: ShelfLifeSuggestion?
    @State private var showProductDetail = false
    /// Set by Save with no name, so the field says why nothing happened.
    @State private var nameMissing = false
    @FocusState private var nameFocused: Bool
    /// The product on this form, kept here because the scan state it came from doesn't last: the
    /// embedded scan starts from a clean slate, and the allergen warning stays for as long as the
    /// product does.
    @State private var product: ProductInfo?
    @State private var allergens: [String] = []
    /// The scanner offers its own photo suggestions and hands a tapped one over as the name, so
    /// this form only finishes the job when Continue left it before the classifier was done.
    @State private var photoToIdentify: String?
    @State private var photoSuggestions: [FoodLabelSuggestion] = []

    /// The suggestion to caption the date field with, or nil when there is nothing to explain.
    ///
    /// Derived from whether `expiryDate` still *is* the suggested date rather than from an
    /// "edited" flag: the date picker's binding can't tell the person spinning the wheel from a
    /// programmatic assignment. Comparing values needs no such distinction - the caption
    /// disappears exactly when the date it describes stops being what's on screen, however it
    /// changed.
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

    /// The category to save: the field shows display names, so map what it says back to the
    /// stored key (an empty result is the "Other" bucket, i.e. no category).
    private var savedCategory: String? {
        let text = category.trimmingCharacters(in: .whitespaces)
        let stored = categoryForFieldText(text, original: categorySource, viewModel.categoryPreferences)
        return stored.isEmpty ? nil : stored
    }
    @State private var storageLocation: String = DefaultStorageLocations.pantry
    /// Set once the user taps a location chip, so a scan from the overlay doesn't move it.
    @State private var storageLocationPicked = false
    @State private var category: String = ""
    /// The stored category whatever is in the `category` field stands for (the field shows display
    /// names), so saving it unchanged keeps that exact category. See `categoryForFieldText`.
    @State private var categorySource: String?
    @State private var quantity: Int = 1
    @State private var notes: String = ""
    @State private var essential: Bool = true
    @State private var notificationsEnabled: Bool = true
    // Embedded scan flow: presents ScannerSheetView on top of this form instead of navigating
    // away, so fields already typed here (quantity, notes, storage location, essential) survive a
    // scan rather than being lost to a fresh screen instance.
    @State private var showScanner: Bool = false
    @State private var scannerMode: IOSCameraMode = .barcode
    @State private var showDatePicker = false
    @State private var showDiscardConfirmation = false
    /// The form as it stood once `onAppear` had filled it in, to tell whether there is anything
    /// a dismissal would throw away. Nil until then, so the prefill itself is not an edit.
    @State private var initialSnapshot: FormSnapshot?

    private var currentSnapshot: FormSnapshot {
        FormSnapshot(
            name: name, brand: brand, barcode: barcode, imageUrl: imageUrl, expiryDate: expiryDate,
            storageLocation: storageLocation, category: category, quantity: quantity, notes: notes,
            essential: essential, notificationsEnabled: notificationsEnabled
        )
    }

    private var hasUnsavedChanges: Bool {
        guard let initialSnapshot else { return false }
        return currentSnapshot != initialSnapshot
    }

    /// What Save would store, and what the date row's status is worked out from.
    private var draftItem: PantryItem {
        PantryItem(
            id: editingItem?.id ?? UUID().uuidString,
            name: name.trimmingCharacters(in: .whitespaces),
            brand: brand.trimmingCharacters(in: .whitespaces).isEmpty ? nil : brand,
            barcode: barcode.isEmpty ? nil : barcode,
            imageUrl: imageUrl,
            expiryDate: expiryDate,
            addedDate: editingItem?.addedDate ?? Date(),
            storageLocation: storageLocation,
            category: savedCategory,
            quantity: quantity,
            notes: notes.trimmingCharacters(in: .whitespaces).isEmpty ? nil : notes,
            essential: essential,
            notificationsEnabled: notificationsEnabled
        )
    }

    private var categorySuggestions: [String] {
        let current = category.trimmingCharacters(in: .whitespaces)
        guard !current.isEmpty else { return [] }
        // A disabled category is hidden everywhere but Settings' list of all categories.
        let matches = viewModel.categoryPreferences
            .filter(\.enabled)
            .map { L10n.categoryName($0.displayName) }
            .filter {
                $0.localizedCaseInsensitiveContains(current) &&
                    $0.localizedCaseInsensitiveCompare(current) != .orderedSame
            }
        return Array(Array(Set(matches)).sorted().prefix(5))
    }

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
            ScrollViewReader { scroller in
                ScrollView {
                    form
                        .padding(.horizontal, LarderSpace.s4)
                        .padding(.top, LarderSpace.s2)
                        .padding(.bottom, LarderSpace.s4)
                        .frame(maxWidth: Self.columnWidth)
                        .frame(maxWidth: .infinity)
                }
                .scrollDismissesKeyboard(.interactively)
                .background(LarderColor.background)
                // Pinned so saving never needs a scroll to the end of a long form. It rides above
                // the keyboard too, so a name can be typed and saved without dismissing it.
                .safeAreaInset(edge: .bottom, spacing: 0) {
                    saveBar(scroller)
                }
            }
            .navigationTitle(editingItem != nil ? L10n.editItemTitle : L10n.addItemTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.cancelButton, action: leave)
                }
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        openScanner(.barcode)
                    } label: {
                        LarderIcon(.camera)
                    }
                    .accessibilityLabel(L10n.scanButton)
                }
            }
            .onAppear(perform: fillIn)
            .task(id: photoToIdentify) {
                guard let reference = photoToIdentify else { return }
                // The classifier stops with the scanner, hence classifying again here.
                guard let url = ProductPhotoStore.fileURL(for: reference) else {
                    photoToIdentify = nil
                    return
                }
                let suggestions = await ProductImageLabeler.suggestNames(photoURL: url)
                guard !Task.isCancelled else { return }
                photoSuggestions = suggestions
                photoToIdentify = nil
            }
            .larderDatePicker(isPresented: $showDatePicker, date: $expiryDate, title: L10n.expiryPickerLabel)
            .larderDialog(
                L10n.discardChangesTitle,
                isPresented: $showDiscardConfirmation,
                message: editingItem.map { L10n.discardEditMessage($0.name) } ?? L10n.discardNewMessage,
                actions: [
                    .cancel(L10n.discardChangesKeep),
                    .destructive(L10n.discardChangesConfirm) { dismiss() }
                ]
            )
            // Only a scan the scanner didn't already ask about reaches here: one filling in a
            // shopping list entry, which is about buying the product. Adding a unit to the
            // existing item is almost always what a re-scan means, so creating a second row is
            // the secondary action.
            .larderDialog(
                L10n.duplicateDialogTitle,
                isPresented: Binding(
                    get: { editingItem == nil && viewModel.duplicateOf != nil },
                    set: { if !$0 { viewModel.dismissDuplicate() } }
                ),
                message: viewModel.duplicateOf.map {
                    L10n.duplicateDialogMessage($0.quantity > 1 ? "\($0.name) ×\($0.quantity)" : $0.name)
                },
                actions: [
                    .init(L10n.duplicateDialogAddOne) {
                        if let duplicate = viewModel.duplicateOf,
                           let updated = viewModel.incrementQuantity(duplicate.id) {
                            onDuplicateMerged(updated)
                            dismiss()
                        }
                    },
                    .cancel(L10n.duplicateDialogSeparate) { viewModel.dismissDuplicate() }
                ]
            )
            .sheet(isPresented: $showProductDetail) {
                if let product {
                    ProductDetailView(
                        productName: name.isEmpty ? product.name : name,
                        nutrition: product.nutrition,
                        warnings: allergens
                    )
                }
            }
            // Full screen like the main scanner: its dark camera UI and bottom mode bar are drawn
            // for that, and a sheet's swipe-down would compete with the camera.
            .fullScreenCover(isPresented: $showScanner) {
                ScannerSheetView(
                    viewModel: viewModel,
                    showManualEntryShortcut: false,
                    // Batch and receipt commit straight to the pantry, bypassing the form this
                    // sits on, and this host has no way to import a shared shopping list either.
                    // Offering a mode whose result lands nowhere is offering something that can't
                    // work.
                    enabledModes: [.barcode, .dateOcr, .photo],
                    initialMode: scannerMode,
                    // The "+1 to the one you already have" prompt belongs to the scan-first
                    // flow. Here the user is already filling in a specific item, and merging
                    // would discard whatever they had typed.
                    checkDuplicates: false,
                    onProceedToAdd: applyScan
                )
            }
        }
        // A swipe down would otherwise drop everything typed or scanned without asking; with
        // it blocked, Cancel is the way out, and it confirms first.
        .interactiveDismissDisabled(hasUnsavedChanges)
    }

    private var form: some View {
        VStack(alignment: .leading, spacing: LarderSpace.s4) {
            if imageUrl?.isEmpty == false || !barcode.isEmpty {
                hero
            }

            // Allergen warning, above everything else on the form: it is the one thing on this
            // screen someone might need to act on before reading anything else.
            if !allergens.isEmpty {
                LarderCallout(
                    .error,
                    icon: .triangleAlert,
                    title: L10n.allergenWarning(allergens.joined(separator: ", ")),
                    message: L10n.scanAllergenNote,
                    actionTitle: L10n.addSeeDetails
                ) {
                    showProductDetail = true
                }
            }

            // Only when there is something to show: Open Food Facts is crowd-sourced, so plenty
            // of products have nothing beyond a name.
            if let product, !product.nutrition.isEmpty {
                detailLink
            }

            // Only while the name is empty: once something is typed, replacing it from under the
            // person is worse than not helping.
            if name.isEmpty && (photoToIdentify != nil || !photoSuggestions.isEmpty) {
                photoSuggestionsPanel
            }

            nameField
            LarderTextField(L10n.brandLabel, text: $brand)
            dateCard

            VStack(alignment: .leading, spacing: LarderSpace.s2) {
                Text(L10n.storageLocationTitle)
                    .larderFont(.label)
                    .foregroundStyle(LarderColor.onSurface)
                FlowLayout(spacing: LarderSpace.s2, rowSpacing: LarderChip.wrapRowSpacing) {
                    ForEach(settingsStore.settings.storageLocations, id: \.self) { location in
                        LarderChip(L10n.storageLocationName(location), isSelected: storageLocation == location) {
                            storageLocation = location
                            storageLocationPicked = true
                        }
                    }
                }
                .padding(.vertical, LarderSpace.s2)
            }

            VStack(alignment: .leading, spacing: LarderSpace.s2) {
                LarderTextField(L10n.categoryLabel, text: $category)
                let suggestions = categorySuggestions
                if !suggestions.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: LarderSpace.s2) {
                            ForEach(suggestions, id: \.self) { suggestion in
                                LarderChip(suggestion) {
                                    category = suggestion
                                    UIApplication.shared.sendAction(
                                        #selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil
                                    )
                                }
                            }
                        }
                        .padding(.vertical, LarderSpace.s2)
                    }
                }
            }

            LarderGroup {
                LarderListItem(L10n.quantityLabel) {
                    LarderStepper(value: $quantity)
                }
            }

            LarderTextField(
                L10n.homeNotesLabel,
                text: $notes,
                placeholder: L10n.notesFieldPlaceholder,
                axis: .vertical
            )
            .lineLimit(3...4)

            LarderGroup {
                LarderSwitch(L10n.essentialItemLabel, subtitle: L10n.essentialItemDescription, isOn: $essential)
                    .padding(.vertical, LarderSpace.s2)
                    .padding(.horizontal, LarderSpace.s4)
                LarderSwitch(
                    L10n.itemNotificationsLabel,
                    subtitle: L10n.itemNotificationsDescription,
                    isOn: $notificationsEnabled
                )
                .padding(.vertical, LarderSpace.s2)
                .padding(.horizontal, LarderSpace.s4)
            }
        }
    }

    /// Appears once there is a photo or a barcode. Wraps the whole name, which the one-line field
    /// below can't show.
    private var hero: some View {
        HStack(spacing: LarderSpace.s4) {
            LarderThumb(
                imageReference: imageUrl,
                placeholderIcon: .forCategory(categorySource ?? category),
                side: Self.heroThumb
            )
            VStack(alignment: .leading, spacing: 0) {
                Text(name.isEmpty ? L10n.newItemPlaceholder : name)
                    .larderFont(.title)
                    .foregroundStyle(LarderColor.onSurface)
                Group {
                    if !brand.isEmpty {
                        Text(brand)
                    }
                    if !barcode.isEmpty {
                        Text(L10n.barcodeFormat(barcode)).monospacedDigit()
                    }
                }
                .larderFont(.bodySm)
                .foregroundStyle(LarderColor.onSurfaceVariant)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var detailLink: some View {
        Button {
            showProductDetail = true
        } label: {
            HStack(spacing: LarderSpace.s3) {
                LarderIcon(.info)
                Text(L10n.productDetailContentDescription)
                    .larderFont(.label)
                    .frame(maxWidth: .infinity, alignment: .leading)
                LarderIcon(.chevronRight)
                    .foregroundStyle(LarderColor.onSurfaceVariant)
            }
            .foregroundStyle(LarderColor.onSurface)
            .padding(.horizontal, LarderSpace.s3)
            .frame(minHeight: LarderSize.touch)
            .background(LarderColor.surfaceContainer, in: RoundedRectangle(cornerRadius: LarderRadius.md))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private var photoSuggestionsPanel: some View {
        VStack(alignment: .leading, spacing: LarderSpace.s1) {
            HStack(spacing: LarderSpace.s2) {
                if photoToIdentify != nil {
                    LarderSpinner(size: 16, lineWidth: 2)
                }
                Text(photoToIdentify != nil ? L10n.photoSuggestionsAnalyzing : L10n.photoSuggestionsTitle)
                    .larderFont(.bodySm)
                    .foregroundStyle(LarderColor.onSurfaceVariant)
            }
            if photoToIdentify == nil {
                FlowLayout(spacing: LarderSpace.s2, rowSpacing: LarderChip.wrapRowSpacing) {
                    ForEach(photoSuggestions) { suggestion in
                        if let label = L10n.foodLabelName(suggestion.key) {
                            LarderChip(label) {
                                name = label
                                // The classifier's category is coarse but real, and it is what
                                // lets the per-category default shelf life apply to an item with
                                // no barcode to remember one against.
                                if category.isEmpty {
                                    categorySource = suggestion.category
                                    category = categoryFieldText(suggestion.category, viewModel.categoryPreferences)
                                }
                            }
                        }
                    }
                }
                .padding(.vertical, LarderSpace.s2)
            }
        }
    }

    /// `LarderFieldContainer` rather than `LarderTextField`, because Save moves focus here.
    private var nameField: some View {
        LarderFieldContainer(
            L10n.productNameLabel,
            message: nameMissing ? .error(L10n.addNameMissing) : nil,
            isFocused: nameFocused
        ) {
            TextField(L10n.productNameLabel, text: $name, prompt: Text(""))
                .larderFont(.body)
                .focused($nameFocused)
                .padding(.vertical, LarderSpace.s3)
        }
        .id(Self.nameFieldID)
        .onChange(of: name) { _, newName in
            if !newName.trimmingCharacters(in: .whitespaces).isEmpty { nameMissing = false }
        }
    }

    /// The date, its status, why it was suggested, and the ways to change it.
    private var dateCard: some View {
        LarderCard {
            VStack(alignment: .leading, spacing: LarderSpace.s2) {
                ViewThatFits(in: .horizontal) {
                    HStack {
                        dateLabel
                        Spacer(minLength: LarderSpace.s2)
                        scanDateButton
                    }
                    VStack(alignment: .leading, spacing: 0) {
                        dateLabel
                        scanDateButton
                    }
                }
                // A year this far off is almost always a mis-read (e.g. "27" taken as year 27 AD)
                // or a lot number, so say so instead of quietly saving it. Saving stays allowed.
                LarderFieldContainer(
                    nil,
                    message: ExpiryDatePlausibility.isPlausible(expiryDate)
                        ? nil
                        : .error(L10n.expiryDateImplausibleWarning(maxYears: ExpiryDatePlausibility.maxYearsFromToday))
                ) {
                    dateButton
                }
                if let suggestion = activeSuggestion, let days = suggestion.days {
                    HStack(alignment: .top, spacing: LarderSpace.s1) {
                        LarderIcon(.sparkles, size: .small)
                            .foregroundStyle(LarderColor.primary)
                        Text(
                            suggestion.source == .categoryDefault
                                ? L10n.expirySuggestionCategory(
                                    category.isEmpty ? L10n.shoppingListUncategorized : category,
                                    days: days
                                  )
                                : L10n.expirySuggestionRemembered(days: days)
                        )
                        .larderFont(.caption)
                        .foregroundStyle(LarderColor.onSurfaceVariant)
                    }
                }
                FlowLayout(spacing: LarderSpace.s2, rowSpacing: LarderChip.wrapRowSpacing) {
                    ForEach(QuickDate.allCases) { quick in
                        LarderChip(quick.title, isSelected: quick.matches(expiryDate)) {
                            expiryDate = quick.date()
                        }
                    }
                }
                .padding(.vertical, LarderSpace.s2)
            }
        }
    }

    private var dateLabel: some View {
        Text(L10n.expiryPickerLabel)
            .larderFont(.label)
            .foregroundStyle(LarderColor.onSurface)
    }

    private var scanDateButton: some View {
        LarderButton(L10n.scanDateButton, icon: .scanText, variant: .text) {
            openScanner(.dateOcr)
        }
        .padding(.horizontal, -LarderSpace.s3)
    }

    /// The wheels live in their own small sheet: inside this scrolling form, a flick on a wheel
    /// could be taken by the form's scroll or by the sheet's swipe-to-dismiss instead.
    private var dateButton: some View {
        let item = draftItem
        let days = item.daysRemaining
        let expiringSoonDays = settingsStore.settings.expiringSoonDays
        return Button {
            showDatePicker = true
        } label: {
            HStack(spacing: LarderSpace.s2) {
                LarderIcon(.calendarDays)
                    .foregroundStyle(LarderColor.primary)
                Text(expiryDate.formatted(date: .abbreviated, time: .omitted))
                    .larderFont(.title)
                    .monospacedDigit()
                    .foregroundStyle(LarderColor.onSurface)
                    .frame(maxWidth: .infinity, alignment: .leading)
                StatusChip(
                    status: item.expiryStatus(expiringSoonDays: expiringSoonDays),
                    text: days >= 2 ? L10n.dateDistance(days: days) : item.statusLabel(expiringSoonDays: expiringSoonDays)
                )
            }
            .frame(minHeight: LarderSize.control)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func saveBar(_ scroller: ScrollViewProxy) -> some View {
        VStack(spacing: 0) {
            LarderDivider()
            LarderButton(
                editingItem != nil
                    ? L10n.updateItemButton
                    : L10n.saveItemButton(L10n.storageLocationName(storageLocation)),
                icon: .check,
                fullWidth: true
            ) {
                save(scroller)
            }
            .lineLimit(1)
            .frame(maxWidth: Self.columnWidth)
            .padding(.horizontal, LarderSpace.s4)
            .padding(.vertical, LarderSpace.s3)
        }
        .frame(maxWidth: .infinity)
        .background(LarderColor.background)
    }

    private func save(_ scroller: ScrollViewProxy) {
        guard !name.trimmingCharacters(in: .whitespaces).isEmpty else {
            nameMissing = true
            nameFocused = true
            withAnimation { scroller.scrollTo(Self.nameFieldID, anchor: .center) }
            return
        }
        let item = draftItem
        viewModel.saveItem(item)
        viewModel.homeConfirmation = .saved(name: item.name, location: item.storageLocation)
        dismiss()
    }

    private func leave() {
        if hasUnsavedChanges {
            showDiscardConfirmation = true
        } else {
            dismiss()
        }
    }

    private func openScanner(_ mode: IOSCameraMode) {
        // Not clearScanState: a shopping list entry this form is filling in outlives the scan.
        viewModel.clearScanResults()
        scannerMode = mode
        showScanner = true
    }

    private func fillIn() {
        if let item = editingItem {
            name = item.name
            brand = item.brand ?? ""
            barcode = item.barcode ?? ""
            imageUrl = item.imageUrl
            expiryDate = item.expiryDate
            storageLocation = item.storageLocation
            categorySource = item.category
            category = item.category.map { categoryFieldText($0, viewModel.categoryPreferences) } ?? ""
            quantity = item.quantity
            notes = item.notes ?? ""
            essential = item.essential
            notificationsEnabled = item.notificationsEnabled
        } else {
            name = prefillName ?? ""
            brand = prefillBrand ?? ""
            barcode = prefillBarcode ?? ""
            imageUrl = prefillImageUrl
            // A product stored before goes back where it was put last time; the
            // last-used/default location only stands for products not seen stored.
            storageLocation = viewModel.storageLocationForNewItem(barcode: prefillBarcode)
            categorySource = prefillCategory
            category = prefillCategory.map { categoryFieldText($0, viewModel.categoryPreferences) } ?? ""
            if let date = prefillDate {
                expiryDate = date
            } else if let suggested = prefillSuggestion?.date {
                expiryDate = suggested
            }
        }
        if initialSnapshot == nil {
            initialSnapshot = currentSnapshot
            takeProduct(forBarcode: barcode)
            if viewModel.isLabelingPhoto && name.isEmpty {
                photoToIdentify = imageUrl
            }
        }
    }

    /// The scanned product when it is the one on this form. Type it in on the scanner opens a
    /// blank form with the last scan still published, and that product's allergens aren't this
    /// item's.
    private func takeProduct(forBarcode barcode: String) {
        let scanned = viewModel.scannedProduct
        product = !barcode.isEmpty && scanned?.barcode == barcode ? scanned : nil
        allergens = product != nil ? viewModel.allergenWarnings : []
    }

    private func applyScan(
        barcode scannedBarcode: String?,
        name scannedName: String?,
        brand scannedBrand: String?,
        imageUrl scannedImageUrl: String?,
        date scannedDate: Date?,
        category scannedCategory: String?,
        suggestion scannedSuggestion: ShelfLifeSuggestion?
    ) {
        if let scannedBarcode {
            barcode = scannedBarcode
            // A product stored before goes back where it was put last time.
            if editingItem == nil && !storageLocationPicked {
                storageLocation = viewModel.storageLocationForNewItem(barcode: scannedBarcode)
            }
            // A new barcode is a new product, so the last one's warning goes with it.
            takeProduct(forBarcode: scannedBarcode)
        }
        if let scannedName { name = scannedName }
        if let scannedBrand { brand = scannedBrand }
        if let scannedImageUrl { imageUrl = scannedImageUrl }
        // A photo suggestion's category is a guess, so it leaves an existing one alone.
        if let scannedCategory, scannedBarcode != nil || category.isEmpty {
            categorySource = scannedCategory
            category = categoryFieldText(scannedCategory, viewModel.categoryPreferences)
        }
        // A date read off the packaging wins; otherwise fall back to what this barcode (or its
        // category) suggests. Either way this is the scan's answer rather than the user's, so it
        // does not count as an edit and the caption stays visible.
        if let resolved = scannedDate ?? scannedSuggestion?.date {
            expiryDate = resolved
        }
        overlaySuggestion = scannedDate == nil ? scannedSuggestion : nil
        photoSuggestions = []
        photoToIdentify = viewModel.isLabelingPhoto && name.isEmpty ? scannedImageUrl : nil
        viewModel.clearScanResults()
    }
}

/// The quick dates under the date row, counted from today.
private enum QuickDate: Int, CaseIterable, Identifiable {
    case threeDays = 3
    case oneWeek = 7
    case twoWeeks = 14
    case oneMonth = 30

    var id: Int { rawValue }

    var title: String {
        switch self {
        case .threeDays: return L10n.quickDate3Days
        case .oneWeek: return L10n.quickDate1Week
        case .twoWeeks: return L10n.quickDate2Weeks
        case .oneMonth: return L10n.quickDate1Month
        }
    }

    func date(from today: Date = Date()) -> Date {
        Calendar.current.date(byAdding: .day, value: rawValue, to: today) ?? today
    }

    /// Follows the date rather than the last tap, so a date picked on the wheels that lands on
    /// the same day explains itself the same way, and a new form opens with +1 Week on.
    func matches(_ expiryDate: Date) -> Bool {
        Calendar.current.isDate(expiryDate, inSameDayAs: date())
    }
}

/// Everything the form saves, for `AddEditItemView.hasUnsavedChanges`.
private struct FormSnapshot: Equatable {
    var name: String
    var brand: String
    var barcode: String
    var imageUrl: String?
    var expiryDate: Date
    var storageLocation: String
    var category: String
    var quantity: Int
    var notes: String
    var essential: Bool
    var notificationsEnabled: Bool
}
