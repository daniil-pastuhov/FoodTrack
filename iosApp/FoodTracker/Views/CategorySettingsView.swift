import SwiftUI

/// Lets the user reorder (long press and drag, or Move up and Move down), rename, or disable each
/// category detected from a scanned or added product's raw category value (see
/// `CategoryPreference`). A rename/disable applies everywhere that category is shown - pantry
/// filter chips, item row badges, and shopping list group headers - without rewriting the raw
/// value stored on any product, so it also applies to the same product scanned again in the
/// future.
struct CategorySettingsView: View {
    @ObservedObject var viewModel: NativePantryViewModel
    @State private var editingOrigin: String?
    @State private var editingText: String = ""
    @State private var shelfLifeOrigin: String?
    @State private var shelfLifeText: String = ""
    /// Set once the field has been left or Save pressed, so an error doesn't appear mid-typing.
    @State private var shelfLifeChecked = false
    /// The row showing Move up and Move down, by origin so it follows the row as it moves.
    @State private var movingOrigin: String?

    private static let maxShelfLifeDays = ShelfLife.maxLearnableDays
    /// Keeps the column readable on a tablet.
    private static let maxWidth: CGFloat = 640

    private var groupedPreferences: [CategoryPreference] {
        let all = viewModel.categoryPreferences
        let grouped = Dictionary(grouping: all, by: { $0.displayName })
        return grouped.values.compactMap { group -> CategoryPreference? in
            guard let first = group.first else { return nil }
            // Represent the group by the record with the lowest sort order (or the sentinel)
            let representative = group.min { $0.sortOrder < $1.sortOrder } ?? first
            return representative
        }
        // Display name breaks a sortOrder tie (as Android's ORDER BY does), so rows can't shuffle
        // between launches: `Dictionary(grouping:)` above has no defined iteration order and
        // `sorted(by:)` is not stable. Ties are only possible on a database written before
        // reordering renumbered the list, but that is exactly the state an existing install is in.
        .sorted { ($0.sortOrder, $0.displayName) < ($1.sortOrder, $1.displayName) }
    }

    private var allEnabled: Bool {
        !viewModel.categoryPreferences.isEmpty && viewModel.categoryPreferences.allSatisfy { $0.enabled }
    }

    var body: some View {
        let rows = groupedPreferences
        Group {
            if rows.isEmpty {
                EmptyState(.shelf, title: L10n.categorySettingsEmptyTitle, message: L10n.categorySettingsEmpty) {
                    LarderButton(L10n.categorySettingsScan) {
                        AppRouter.shared.navigate(to: .scanner(.barcode))
                    }
                }
            } else {
                list(rows)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(LarderColor.background)
        .navigationTitle(L10n.categorySettingsTitle)
        .navigationBarTitleDisplayMode(.inline)
        .shellChromeEffect(hideNav: true)
        .larderSheet(
            isPresented: Binding(
                get: { editingOrigin != nil },
                set: { if !$0 { editingOrigin = nil } }
            ),
            detents: [.medium]
        ) {
            renameSheet
        }
        .larderSheet(
            isPresented: Binding(
                get: { shelfLifeOrigin != nil },
                set: { if !$0 { shelfLifeOrigin = nil } }
            ),
            detents: [.medium]
        ) {
            shelfLifeSheet
        }
    }

    private func list(_ rows: [CategoryPreference]) -> some View {
        List {
            Text(L10n.categorySettingsDescription)
                .larderFont(.bodySm)
                .foregroundStyle(LarderColor.onSurfaceVariant)
                .padding(.top, LarderSpace.s1)
                .padding(.bottom, LarderSpace.s3)
                .listRowInsets(EdgeInsets(top: 0, leading: LarderSpace.s4, bottom: 0, trailing: LarderSpace.s4))
                .listRowSeparator(.hidden)
                .listRowBackground(Color.clear)

            LarderSwitch(L10n.categorySettingsToggleAll, isOn: Binding(
                get: { allEnabled },
                set: { viewModel.setAllCategoriesEnabled($0) }
            ))
            .padding(.horizontal, LarderSpace.s4)
            .padding(.vertical, LarderSpace.s1)
            .larderGroupRow(.only)

            Color.clear
                .frame(height: LarderSpace.s3)
                .listRowInsets(EdgeInsets())
                .listRowSeparator(.hidden)
                .listRowBackground(Color.clear)
                .accessibilityHidden(true)

            ForEach(Array(rows.enumerated()), id: \.element.displayName) { index, pref in
                let isMoving = movingOrigin == pref.originName
                row(pref, index: index, rows: rows, isMoving: isMoving)
                    .larderGroupRow(LarderGroupPosition(index: index, count: rows.count), isSelected: isMoving)
            }
            .onMove { source, destination in
                var reordered = rows
                reordered.move(fromOffsets: source, toOffset: destination)
                saveOrder(reordered)
            }
        }
        .larderList()
        .frame(maxWidth: Self.maxWidth)
    }

    private func row(_ pref: CategoryPreference, index: Int, rows: [CategoryPreference], isMoving: Bool) -> some View {
        let name = L10n.categoryName(pref.displayName)
        let canMoveUp = index > 0
        let canMoveDown = index < rows.count - 1
        return VStack(spacing: 0) {
            HStack(spacing: LarderSpace.s1) {
                LarderIconButton(.gripVertical, label: L10n.categorySettingsDragHandleFormat(name)) {
                    movingOrigin = isMoving ? nil : pref.originName
                }
                // Screen readers get the same two moves as the buttons a tap brings up.
                .accessibilityActions {
                    if canMoveUp {
                        Button(L10n.categorySettingsMoveUp) { move(index, by: -1, in: rows) }
                    }
                    if canMoveDown {
                        Button(L10n.categorySettingsMoveDown) { move(index, by: 1, in: rows) }
                    }
                }

                VStack(alignment: .leading, spacing: LarderSpace.s1) {
                    Text(name)
                        .larderFont(.titleSm)
                        .foregroundStyle(pref.enabled ? LarderColor.onSurface : LarderColor.onSurfaceVariant)
                        .lineLimit(1)

                    // Show original names if different, comma-separated if multiple
                    let allOrigins = viewModel.categoryPreferences
                        .filter { $0.displayName == pref.displayName }
                        .map { $0.originName }
                        .filter { $0 != pref.displayName && !$0.isEmpty }
                        .sorted()

                    if !allOrigins.isEmpty {
                        Text(L10n.categorySettingsOriginalNameFormat(allOrigins.joined(separator: ", ")))
                            .larderFont(.caption)
                            .foregroundStyle(LarderColor.onSurfaceVariant)
                            .lineLimit(1)
                    }

                    shelfLifeChip(pref, name: name, isOnTint: isMoving)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                LarderIconButton(.pencil, label: L10n.categorySettingsRenameFormat(name)) {
                    editingOrigin = pref.originName
                    editingText = name
                }

                LarderSwitch(accessibilityLabel: L10n.categorySettingsShowFormat(name), isOn: Binding(
                    get: { pref.enabled },
                    set: { viewModel.setCategoryEnabled(pref.originName, enabled: $0) }
                ))
            }

            if isMoving {
                FlowLayout(spacing: LarderSpace.s1) {
                    LarderButton(L10n.categorySettingsMoveUp, variant: .text) { move(index, by: -1, in: rows) }
                        .disabled(!canMoveUp)
                    LarderButton(L10n.categorySettingsMoveDown, variant: .text) { move(index, by: 1, in: rows) }
                        .disabled(!canMoveDown)
                    LarderButton(L10n.categorySettingsMoveDone, variant: .text) { movingOrigin = nil }
                }
                .frame(maxWidth: .infinity, alignment: .trailing)
            }
        }
        .padding(.leading, LarderSpace.s1)
        .padding(.trailing, LarderSpace.s3)
        .padding(.vertical, LarderSpace.s2)
    }

    /// The category's default shelf life, or the offer to set one.
    private func shelfLifeChip(_ pref: CategoryPreference, name: String, isOnTint: Bool) -> some View {
        let days = pref.defaultShelfLifeDays
        let text = days.map(L10n.categorySettingsShelfLifeDaysFormat) ?? L10n.categorySettingsShelfLifeNone
        let colors: (fill: Color, text: Color) = isOnTint
            ? (LarderColor.surface, LarderColor.onSurface)
            : days != nil
                ? (LarderColor.primaryContainer, LarderColor.onPrimaryContainer)
                : (LarderColor.surfaceContainer, LarderColor.onSurfaceVariant)
        return Button {
            shelfLifeOrigin = pref.originName
            shelfLifeText = days.map(String.init) ?? ""
            shelfLifeChecked = false
        } label: {
            HStack(spacing: LarderSpace.s1) {
                LarderIcon(.clock, size: .small)
                Text(text).larderFont(.label).lineLimit(1)
            }
            .foregroundStyle(colors.text)
            .padding(.leading, LarderSpace.s1)
            .padding(.trailing, LarderSpace.s2)
            .frame(minHeight: 28)
            .background(colors.fill, in: Capsule())
            // 28pt to look at, 44pt to tap.
            .contentShape(Rectangle().inset(by: -LarderSpace.s2))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L10n.categorySettingsShelfLifeFormat(name, text))
    }

    private var renameSheet: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: LarderSpace.s3) {
                LarderTextField(L10n.categorySettingsRenameFieldLabel, text: $editingText)

                let suggestions = viewModel.categoryPreferences
                    .map { L10n.categoryName($0.displayName) }
                    .filter {
                        let current = editingText.trimmingCharacters(in: .whitespaces)
                        return !current.isEmpty &&
                            $0.localizedCaseInsensitiveContains(current) &&
                            $0.localizedCaseInsensitiveCompare(current) != .orderedSame
                    }

                if !suggestions.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: LarderSpace.s2) {
                            ForEach(Array(Set(suggestions)).sorted().prefix(5), id: \.self) { suggestion in
                                LarderChip(suggestion) {
                                    editingText = suggestion
                                    UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
                                }
                            }
                        }
                        .padding(.vertical, LarderSpace.s2)
                    }
                }
                Spacer()
            }
            .padding(LarderSpace.s4)
            .navigationTitle(L10n.categorySettingsRenameTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.cancelButton) { editingOrigin = nil }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L10n.categorySettingsRenameConfirm) {
                        if let origin = editingOrigin {
                            // The field shows translated names: see `categoryRename`.
                            if let current = viewModel.categoryPreferences.first(where: { $0.originName == origin })?.displayName,
                               let newName = categoryRename(
                                   current: current,
                                   input: editingText,
                                   viewModel.categoryPreferences.map { $0.displayName }
                               ) {
                                viewModel.renameCategory(origin, newDisplayName: newName)
                            }
                        }
                        editingOrigin = nil
                    }
                    .disabled(editingText.trimmingCharacters(in: .whitespaces).isEmpty)
                    .fontWeight(.bold)
                }
            }
        }
    }

    private var shelfLifeIsOutOfRange: Bool {
        guard !shelfLifeText.isEmpty else { return false }
        guard let days = Int(shelfLifeText) else { return true }
        return !(0...Self.maxShelfLifeDays).contains(days)
    }

    private var shelfLifeSheet: some View {
        let name = viewModel.categoryPreferences
            .first { $0.originName == shelfLifeOrigin }
            .map { L10n.categoryName($0.displayName) } ?? ""
        return NavigationStack {
            VStack(alignment: .leading, spacing: LarderSpace.s3) {
                Text(L10n.categorySettingsShelfLifeDialogMessage)
                    .larderFont(.bodySm)
                    .foregroundStyle(LarderColor.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)

                LarderTextField(
                    L10n.categorySettingsShelfLifeFieldLabel,
                    text: $shelfLifeText,
                    message: shelfLifeChecked && shelfLifeIsOutOfRange
                        ? .error(L10n.categorySettingsShelfLifeError)
                        : .help(L10n.categorySettingsShelfLifeRange),
                    onEditingEnded: { shelfLifeChecked = true }
                )
                // Digits only: the value is a day count.
                .keyboardType(.numberPad)
                .onChange(of: shelfLifeText) { _, newValue in
                    let digits = String(newValue.filter { $0.isNumber }.prefix(4))
                    if digits != newValue { shelfLifeText = digits }
                    if !shelfLifeIsOutOfRange { shelfLifeChecked = false }
                }

                LarderButton(L10n.categorySettingsShelfLifeClear, variant: .text) { saveShelfLife(nil) }
                Spacer()
            }
            .padding(LarderSpace.s4)
            .navigationTitle(L10n.categorySettingsShelfLifeTitleFormat(name))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.cancelButton) { shelfLifeOrigin = nil }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L10n.categorySettingsShelfLifeSave) {
                        if shelfLifeIsOutOfRange {
                            shelfLifeChecked = true
                        } else {
                            saveShelfLife(Int(shelfLifeText))
                        }
                    }
                    .fontWeight(.bold)
                }
            }
        }
    }

    private func saveShelfLife(_ days: Int?) {
        if let origin = shelfLifeOrigin {
            viewModel.setCategoryDefaultShelfLife(origin, days: days)
        }
        shelfLifeOrigin = nil
    }

    private func move(_ index: Int, by offset: Int, in rows: [CategoryPreference]) {
        let target = index + offset
        guard rows.indices.contains(index), rows.indices.contains(target) else { return }
        var reordered = rows
        reordered.swapAt(index, target)
        saveOrder(reordered)
    }

    /// The whole visible order is what gets persisted, not just where the dragged row landed
    /// relative to its neighbours - see NativePantryViewModel.reorderCategoryPreferences.
    private func saveOrder(_ rows: [CategoryPreference]) {
        viewModel.reorderCategoryPreferences(rows.map { $0.originName })
    }
}
