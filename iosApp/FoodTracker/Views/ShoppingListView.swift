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
    /// One sheet at a time, so going from Share to QR is one change.
    @State private var sheet: ShoppingSheetRoute?
    @State private var importResult: String?
    @FocusState private var isInputFocused: Bool
    @Environment(\.showSnackbar) private var showSnackbar
    @Environment(\.isExpanded) private var isExpanded
    @State private var showNewSection = false
    @State private var sectionNameText = ""
    @State private var renamingSection: ShoppingSection?
    @State private var deletingSection: DeletingSection?
    @State private var scrollTarget: String?
    /// Every section folded to its header, for dragging the headers into order.
    @State private var isReorderingSections = false

    var body: some View {
        content
            .animation(.easeInOut(duration: 0.2), value: suggestions)
            .animation(.easeInOut(duration: 0.25), value: viewModel.shoppingList.isEmpty)
            .background(LarderColor.background.ignoresSafeArea())
            .larderSheet(item: $sheet) { route in
                sheetContent(route)
            }
            .alert(L10n.shoppingSectionNew, isPresented: $showNewSection) {
                TextField(L10n.shoppingSectionNamePlaceholder, text: $sectionNameText)
                Button(L10n.cancelButton, role: .cancel) {}
                Button(L10n.shoppingSectionSave) { viewModel.createShoppingSection(sectionNameText) }
                    .disabled(sectionNameText.trimmingCharacters(in: .whitespaces).isEmpty)
            } message: {
                Text(L10n.shoppingSectionNameLabel)
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
            } message: {
                Text(L10n.shoppingSectionNameLabel)
            }
            .larderDialog(
                deletingSection.map { L10n.shoppingSectionDeleteTitle(L10n.shoppingSectionName($0.section)) } ?? "",
                isPresented: Binding(get: { deletingSection != nil }, set: { if !$0 { deletingSection = nil } }),
                message: deletingSection.flatMap { $0.itemCount > 0 ? L10n.shoppingSectionDeleteCount($0.itemCount) : nil },
                actions: [
                    .cancel(L10n.shoppingSectionKeep),
                    .destructive(L10n.shoppingSectionDeleteConfirm) {
                        if let section = deletingSection?.section {
                            withAnimation { viewModel.deleteShoppingSection(section.id) }
                        }
                    }
                ]
            )
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
            .larderDialog(
                L10n.shoppingListDidYouMeanTitle,
                isPresented: $showDidYouMeanDialog,
                actions: didYouMeanActions
            )
            .onAppear {
                if viewModel.shoppingList.isEmpty {
                    isInputFocused = true
                }
                #if DEBUG
                applyDemoOverlay()
                #endif
            }
            .onChange(of: viewModel.shoppingList.isEmpty) { _, isEmpty in
                if isEmpty {
                    isInputFocused = true
                }
            }
    }

    // MARK: - Layout

    /// Stacked on phones; from 700pt the list and add field get a pane beside the rows.
    @ViewBuilder
    private var content: some View {
        if isExpanded {
            HStack(spacing: 0) {
                VStack(spacing: 0) {
                    header
                    if !viewModel.shoppingList.isEmpty {
                        sectionIndex
                    }
                    Spacer(minLength: 0)
                }
                .frame(width: Self.sidePaneWidth)
                Rectangle()
                    .fill(LarderColor.outlineVariant)
                    .frame(width: 1)
                    .ignoresSafeArea()
                listPane
                    .padding(.top, LarderSpace.s3)
            }
        } else {
            VStack(spacing: 0) {
                header
                listPane
            }
        }
    }

    private var header: some View {
        LedeHeader(lede, brand: L10n.shoppingListBrand) {
            if !isExpanded {
                newSectionMenu {
                    LarderIcon(.listPlus)
                        .foregroundStyle(LarderColor.onSurfaceVariant)
                        .frame(width: LarderSize.touch, height: LarderSize.touch)
                        .contentShape(Rectangle())
                }
            }
            LarderIconButton(.share2, label: L10n.shoppingListShareContentDescription) {
                sheet = .share
            }
        } below: {
            addField
            suggestionChips
        }
    }

    private var lede: String {
        let count = viewModel.shoppingList.count
        return count == 0 ? L10n.shoppingListLedeEmpty : L10n.shoppingListLede(count)
    }

    /// New section, plus Reorder sections once there are two of your own to put in order.
    @ViewBuilder
    private func newSectionMenu<Label: View>(@ViewBuilder label: () -> Label) -> some View {
        if viewModel.shoppingSections.count > 2 {
            Menu {
                Button(L10n.shoppingSectionNew, action: promptForNewSection)
                Button(L10n.shoppingSectionReorder, action: startReorderingSections)
            } label: {
                label()
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L10n.shoppingSectionNew)
        } else {
            Button(action: promptForNewSection, label: label)
                .buttonStyle(.plain)
                .accessibilityLabel(L10n.shoppingSectionNew)
        }
    }

    private func startReorderingSections() {
        withAnimation(.easeInOut(duration: 0.3)) { isReorderingSections = true }
    }

    private func promptForNewSection() {
        sectionNameText = ""
        showNewSection = true
    }

    private var isFieldBlank: Bool {
        newEntryText.trimmingCharacters(in: .whitespaces).isEmpty
    }

    /// A blank field offers the scanner, the fast path most of the time; once there's text, the
    /// same spot adds it.
    private var addField: some View {
        LarderFieldContainer(nil, isFocused: isInputFocused) {
            TextField(
                L10n.shoppingListAddPlaceholder,
                text: $newEntryText,
                prompt: Text(L10n.shoppingListAddPlaceholder).foregroundStyle(LarderColor.onSurfaceVariant)
            )
            .larderFont(.body)
            .focused($isInputFocused)
            .submitLabel(.done)
            .onSubmit { addEntry(newEntryText) }
            .padding(.vertical, LarderSpace.s3)

            Group {
                if isFieldBlank {
                    LarderIconButton(.scanBarcode, label: L10n.shoppingListScan, color: LarderColor.primary) {
                        scanningEntry = nil
                        isScanningForNewEntry = true
                        showScanner = true
                    }
                } else {
                    LarderIconButton(.plus, label: L10n.shoppingListAddButton, color: LarderColor.onPrimary) {
                        addEntry(newEntryText)
                    }
                    .background { Circle().fill(LarderColor.primary).frame(width: Self.addDiscSize, height: Self.addDiscSize) }
                }
            }
            .padding(.trailing, -LarderSpace.s3)
        }
    }

    /// Names used before: a scrolling row on phones, wrapped in the narrow pane on tablets.
    @ViewBuilder
    private var suggestionChips: some View {
        if !suggestions.isEmpty {
            Group {
                if isExpanded {
                    FlowLayout(spacing: LarderSpace.s2, rowSpacing: LarderChip.wrapRowSpacing) { chips }
                } else {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: LarderSpace.s2) { chips }
                            .padding(.vertical, LarderSpace.s2)
                    }
                }
            }
            .padding(.top, LarderSpace.s2)
            .transition(.opacity.combined(with: .move(edge: .top)))
        }
    }

    private var chips: some View {
        ForEach(suggestions, id: \.self) { suggestion in
            LarderChip(suggestion) { addEntry(suggestion) }
        }
    }

    /// Tablets only: every section with its count, beside the list. A tap brings it into view.
    private var sectionIndex: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                SectionHeader(L10n.shoppingSectionsLabel)
                LarderGroup {
                    ForEach(viewModel.shoppingRows) { row in
                        if case .header(let section, let count) = row {
                            LarderListItem(L10n.shoppingSectionName(section), onTap: { scrollTarget = row.id }) {
                                Text("\(count)").larderFont(.label).monospacedDigit()
                            }
                        }
                    }
                }
                newSectionMenu {
                    HStack(spacing: LarderSpace.s2) {
                        LarderIcon(.plus)
                        Text(L10n.shoppingSectionNew).larderFont(.label)
                    }
                    .foregroundStyle(LarderColor.primary)
                    .frame(minHeight: LarderSize.touch)
                    .contentShape(Rectangle())
                }
                .padding(.top, LarderSpace.s2)
            }
            .padding(.horizontal, LarderSpace.s4)
            .padding(.bottom, LarderSpace.s4)
        }
    }

    @ViewBuilder
    private var listPane: some View {
        // Reordering works on an empty list too: the headers are all it shows.
        if viewModel.shoppingList.isEmpty && !isReorderingSections {
            EmptyState(.basket, title: L10n.shoppingListEmptyTitle, message: L10n.shoppingListEmptySubtitle)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .transition(.opacity)
        } else {
            ShoppingSectionedList(
                viewModel: viewModel,
                onItemRemoved: onItemRemoved,
                onEntryTap: addEntryManually,
                onMoveEntry: { sheet = .move($0, sectionId: $1) },
                onRenameSection: { section in
                    sectionNameText = L10n.shoppingSectionName(section)
                    renamingSection = section
                },
                onDeleteSection: { deletingSection = DeletingSection(section: $0, itemCount: $1) },
                onReorderSections: viewModel.shoppingSections.count > 2 ? startReorderingSections : nil,
                isReorderingSections: $isReorderingSections,
                scrollTarget: $scrollTarget
            )
            .transition(.opacity)
        }
    }

    // MARK: - Sheets and dialogs

    @ViewBuilder
    private func sheetContent(_ route: ShoppingSheetRoute) -> some View {
        switch route {
        case .share:
            ShoppingShareSheet(
                isEmpty: viewModel.shoppingList.isEmpty,
                onShareAsText: { sheet = .activity(shareText()) },
                onShowQr: { sheet = .qr },
                onImport: { sheet = .importList },
                onCancel: { sheet = nil }
            )
        case .qr:
            ShoppingListQrSheet(
                payload: ShoppingListShare.toPayload(
                    entries: viewModel.shoppingList,
                    sections: viewModel.shoppingSections
                ),
                onShareAsText: { sheet = .activity(shareText()) },
                onClose: { sheet = nil }
            )
        case .importList:
            ShoppingImportSheet(
                onImport: { text in
                    sheet = nil
                    applyImport(text)
                },
                onCancel: { sheet = nil }
            )
        case .move(let entry, let sectionId):
            ShoppingMoveSheet(
                entryName: entry.productName,
                targets: viewModel.shoppingSections.filter { $0.id != sectionId },
                onMove: { target in
                    sheet = nil
                    viewModel.moveShoppingListEntry(entry.id, toSection: target.id)
                },
                onCancel: { sheet = nil }
            )
        case .activity(let text):
            ActivityView(activityItems: [text])
        }
    }

    private var didYouMeanActions: [LarderDialogAction] {
        var actions = pendingMatchCandidates.map { candidate in
            LarderDialogAction("\(candidate.productName) (\(candidate.quantity))") {
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
        actions.append(LarderDialogAction(L10n.shoppingListDidYouMeanNoneOfThese) {
            prefillBarcode = pendingMatchBarcode
            prefillName = pendingMatchName
            prefillBrand = pendingMatchBrand
            prefillImageUrl = pendingMatchImageUrl
            prefillDate = pendingMatchDate
            prefillCategory = pendingMatchCategory
            prefillSuggestion = pendingMatchSuggestion
            showAddEdit = true
        })
        actions.append(.cancel(L10n.cancelButton) { viewModel.clearScanState() })
        return actions
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

    private func addEntry(_ name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        viewModel.addManualShoppingListEntry(trimmed) { existing in
            showSnackbar(L10n.shoppingListAlreadyListed(existing))
        }
        newEntryText = ""
    }

    #if DEBUG
    /// Screenshot support, compiled out of release builds: `-demoShoppingSheet <name>` (share,
    /// qr, import, move) opens that sheet, or `reorder` folds the sections for reordering, and
    /// `-demoShoppingText <text>` fills the field.
    private func applyDemoOverlay() {
        let arguments = UserDefaults.standard
        if let text = arguments.string(forKey: "demoShoppingText") {
            newEntryText = text
            isInputFocused = true
            // `-demoShoppingSubmit YES` then adds it, as Done on the keyboard would.
            if arguments.bool(forKey: "demoShoppingSubmit") {
                Task { @MainActor in
                    try? await Task.sleep(for: .seconds(4))
                    addEntry(newEntryText)
                }
            }
        }
        switch arguments.string(forKey: "demoShoppingSheet") {
        case "share": sheet = .share
        case "qr": sheet = .qr
        case "import": sheet = .importList
        case "reorder": isReorderingSections = true
        case "move":
            if let entry = viewModel.shoppingList.first {
                sheet = .move(entry, sectionId: ShoppingSection.defaultId)
            }
        default: break
        }
    }
    #endif

    private static let sidePaneWidth: CGFloat = 300
    private static let addDiscSize: CGFloat = 36
}

private struct DeletingSection {
    let section: ShoppingSection
    let itemCount: Int
}

private enum ShoppingSheetRoute: Identifiable {
    case share, qr, importList
    case move(ShoppingListEntry, sectionId: String)
    /// The system share sheet, for the list as text.
    case activity(String)

    var id: String {
        switch self {
        case .share: return "share"
        case .qr: return "qr"
        case .importList: return "import"
        case .move(let entry, _): return "move_\(entry.id)"
        case .activity: return "activity"
        }
    }
}

/// The buttons at the foot of a sheet, on the right.
private struct ShoppingSheetActions<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        HStack(spacing: LarderSpace.s2) {
            Spacer(minLength: 0)
            content
        }
        .padding(.top, LarderSpace.s4)
    }
}

/// One action in a sheet. A disabled row dims rather than leaves, so people can still see what
/// sharing offers on an empty list.
private struct ShoppingSheetAction: View {
    let icon: LarderIconName
    let title: String
    var isEnabled = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: LarderSpace.s3) {
                LarderIcon(icon)
                    .foregroundStyle(isEnabled ? LarderColor.primary : LarderColor.onSurface.opacity(0.38))
                Text(title)
                    .larderFont(.body)
                    .foregroundStyle(isEnabled ? LarderColor.onSurface : LarderColor.onSurface.opacity(0.38))
                Spacer(minLength: 0)
            }
            .padding(.vertical, LarderSpace.s2)
            .padding(.horizontal, LarderSpace.s1)
            .frame(minHeight: LarderSize.touch)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!isEnabled)
    }
}

private struct ShoppingSheetLead: View {
    let text: String

    var body: some View {
        Text(text)
            .larderFont(.bodySm)
            .foregroundStyle(LarderColor.onSurfaceVariant)
            .fixedSize(horizontal: false, vertical: true)
    }
}

private struct ShoppingShareSheet: View {
    let isEmpty: Bool
    let onShareAsText: () -> Void
    let onShowQr: () -> Void
    let onImport: () -> Void
    let onCancel: () -> Void

    var body: some View {
        LarderSheet(L10n.shoppingListShareDialogTitle) {
            ShoppingSheetLead(text: L10n.shoppingListShareDialogMessage)
            VStack(spacing: 0) {
                // Sharing an empty list would hand someone a title and nothing else.
                ShoppingSheetAction(icon: .share2, title: L10n.shoppingListShareAsText, isEnabled: !isEmpty, action: onShareAsText)
                ShoppingSheetAction(icon: .qrCode, title: L10n.shoppingListShareAsQr, isEnabled: !isEmpty, action: onShowQr)
                ShoppingSheetAction(icon: .download, title: L10n.shoppingListImport, action: onImport)
            }
            ShoppingSheetActions {
                LarderButton(L10n.cancelButton, variant: .text, action: onCancel)
            }
        }
    }
}

private struct ShoppingImportSheet: View {
    let onImport: (String) -> Void
    let onCancel: () -> Void
    @State private var text = ""
    @FocusState private var isFocused: Bool

    var body: some View {
        LarderSheet(L10n.shoppingListImport) {
            ShoppingSheetLead(text: L10n.shoppingListImportMessage)
            // The container rather than LarderTextField, so the line limit reaches only the
            // input: on the whole field it makes the label reserve four lines too.
            LarderFieldContainer(L10n.shoppingListImportLabel, isFocused: isFocused) {
                TextField(
                    L10n.shoppingListImportLabel,
                    text: $text,
                    prompt: Text(L10n.shoppingListImportPlaceholder).foregroundStyle(LarderColor.onSurfaceVariant),
                    axis: .vertical
                )
                .lineLimit(4...8)
                .larderFont(.body)
                .focused($isFocused)
                .padding(.vertical, LarderSpace.s3)
            }
            ShoppingSheetActions {
                LarderButton(L10n.cancelButton, variant: .text, action: onCancel)
                LarderButton(L10n.shoppingListImportConfirm) { onImport(text) }
                    .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
    }
}

private struct ShoppingMoveSheet: View {
    let entryName: String
    let targets: [ShoppingSection]
    let onMove: (ShoppingSection) -> Void
    let onCancel: () -> Void

    var body: some View {
        LarderSheet(L10n.shoppingSectionMoveTo) {
            ShoppingSheetLead(text: entryName)
            VStack(spacing: 0) {
                ForEach(targets) { target in
                    ShoppingSheetAction(
                        icon: target.isDefault ? .shoppingBasket : .layers,
                        title: L10n.shoppingSectionName(target)
                    ) {
                        onMove(target)
                    }
                }
            }
            ShoppingSheetActions {
                LarderButton(L10n.cancelButton, variant: .text, action: onCancel)
            }
        }
    }
}

/// Shows the shopping list as a QR code for someone else to scan with their own scanner.
private struct ShoppingListQrSheet: View {
    let payload: String
    let onShareAsText: () -> Void
    let onClose: () -> Void

    private static let codeSize: CGFloat = 240

    var body: some View {
        LarderSheet(L10n.shoppingListShareAsQr) {
            if let image = generateQrImage(contents: payload) {
                VStack(spacing: LarderSpace.s3) {
                    Image(uiImage: image)
                        .interpolation(.none)
                        .resizable()
                        .scaledToFit()
                        .frame(width: Self.codeSize, height: Self.codeSize)
                        .padding(LarderSpace.s3)
                        .background(LarderColor.surface, in: RoundedRectangle(cornerRadius: LarderRadius.md))
                        // Pinned to the light theme, so the code stays dark on light in dark mode.
                        .environment(\.colorScheme, .light)
                        .accessibilityLabel(L10n.shoppingListQrDescription)
                    Text(L10n.shoppingListShareQrHint)
                        .larderFont(.bodySm)
                        .foregroundStyle(LarderColor.onSurfaceVariant)
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity)
                ShoppingSheetActions {
                    LarderButton(L10n.doneButton, action: onClose)
                }
            } else {
                // T4: a QR code has a hard capacity limit and the generator refuses rather than
                // truncating. This lasts until the list gets shorter, so the way out is here.
                LarderCallout(.warning, message: L10n.shoppingListShareQrTooLarge)
                ShoppingSheetActions {
                    LarderButton(L10n.shoppingListClose, variant: .text, action: onClose)
                    LarderButton(L10n.shoppingListShareAsText, action: onShareAsText)
                }
            }
        }
    }
}
