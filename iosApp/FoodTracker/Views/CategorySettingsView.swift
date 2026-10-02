import SwiftUI

/// Lets the user reorder (drag), rename, or disable each category detected from a scanned or
/// added product's raw category value (see `CategoryPreference`). A rename/disable applies
/// everywhere that category is shown - pantry filter chips, item row badges, and shopping list
/// group headers - without rewriting the raw value stored on any product, so it also applies to
/// the same product scanned again in the future.
struct CategorySettingsView: View {
    @ObservedObject var viewModel: NativePantryViewModel
    @State private var editingOrigin: String?
    @State private var editingText: String = ""
    @State private var shelfLifeOrigin: String?
    @State private var shelfLifeText: String = ""

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
        List {
            Section(footer: Text(L10n.categorySettingsDescription)) {
                if groupedPreferences.isEmpty {
                    Text(L10n.categorySettingsEmpty)
                        .foregroundColor(.secondary)
                } else {
                    Toggle(L10n.categorySettingsToggleAll, isOn: Binding(
                        get: { allEnabled },
                        set: { viewModel.setAllCategoriesEnabled($0) }
                    ))
                    .fontWeight(.semibold)

                    ForEach(groupedPreferences, id: \.displayName) { pref in
                        HStack(spacing: 10) {
                            // Decorative affordance only: the row itself is reorderable by
                            // long-press-and-drag via .onMove below (mirrors ShoppingListView).
                            Image(systemName: "line.3.horizontal")
                                .foregroundColor(.secondary)
                                .accessibilityHidden(true)

                            VStack(alignment: .leading, spacing: 2) {
                                Text(L10n.categoryName(pref.displayName))
                                    .foregroundColor(pref.enabled ? .primary : .secondary)
                                
                                // Show original names if different, comma-separated if multiple
                                let allOrigins = viewModel.categoryPreferences
                                    .filter { $0.displayName == pref.displayName }
                                    .map { $0.originName }
                                    .filter { $0 != pref.displayName && !$0.isEmpty }
                                    .sorted()
                                
                                if !allOrigins.isEmpty {
                                    Text(L10n.categorySettingsOriginalNameFormat(allOrigins.joined(separator: ", ")))
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                }
                            }
                            Spacer()
                            // Shows the configured default shelf life, or invites setting one.
                            // Text rather than an icon so the current value is readable at a
                            // glance - it's the only per-category setting with a value worth
                            // showing on the row.
                            Button {
                                shelfLifeOrigin = pref.originName
                                shelfLifeText = pref.defaultShelfLifeDays.map(String.init) ?? ""
                            } label: {
                                if let days = pref.defaultShelfLifeDays {
                                    Text(L10n.categorySettingsShelfLifeDaysFormat(days))
                                        .font(.caption)
                                        .foregroundColor(.accentColor)
                                } else {
                                    Text(L10n.categorySettingsShelfLifeNone)
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                }
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(L10n.categorySettingsShelfLifeContentDescription)

                            Button {
                                editingOrigin = pref.originName
                                editingText = L10n.categoryName(pref.displayName)
                            } label: {
                                Image(systemName: "pencil")
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(L10n.categorySettingsRenameContentDescription)

                            Toggle("", isOn: Binding(
                                get: { pref.enabled },
                                set: { viewModel.setCategoryEnabled(pref.originName, enabled: $0) }
                            ))
                            .labelsHidden()
                        }
                    }
                    .onMove(perform: moveCategories)
                }
            }
        }
        .navigationTitle(L10n.categorySettingsTitle)
        .navigationBarTitleDisplayMode(.inline)
        // Clears the floating pill nav bar that overlays this screen (see RootTabView)
        // so the last row can still scroll fully into view above them.
        .safeAreaInset(edge: .bottom) { Color.clear.frame(height: 170) }
        .sheet(isPresented: Binding(
            get: { editingOrigin != nil },
            set: { if !$0 { editingOrigin = nil } }
        )) {
            NavigationStack {
                VStack(spacing: 16) {
                    TextField(L10n.categorySettingsRenameTitle, text: $editingText)
                        .textFieldStyle(.roundedBorder)
                        .padding(.horizontal)

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
                            HStack(spacing: 8) {
                                ForEach(Array(Set(suggestions)).sorted().prefix(5), id: \.self) { suggestion in
                                    Button(action: {
                                        editingText = suggestion
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
                            .padding(.horizontal)
                        }
                    }
                    Spacer()
                }
                .padding(.top)
                .navigationTitle(L10n.categorySettingsRenameTitle)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(L10n.cancelButton) { editingOrigin = nil }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button(L10n.saveButton) {
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
            .presentationDetents([.height(200)])
        }
        .sheet(isPresented: Binding(
            get: { shelfLifeOrigin != nil },
            set: { if !$0 { shelfLifeOrigin = nil } }
        )) {
            NavigationStack {
                Form {
                    Section {
                        TextField(L10n.categorySettingsShelfLifeFieldLabel, text: $shelfLifeText)
                            // Digits only: the value is a day count, and letting anything else
                            // in just means the save button silently does nothing.
                            .keyboardType(.numberPad)
                            .onChange(of: shelfLifeText) { _, newValue in
                                let digits = String(newValue.filter { $0.isNumber }.prefix(4))
                                if digits != newValue { shelfLifeText = digits }
                            }
                    } footer: {
                        Text(L10n.categorySettingsShelfLifeDialogMessage)
                    }

                    Section {
                        Button(L10n.categorySettingsShelfLifeClear, role: .destructive) {
                            if let origin = shelfLifeOrigin {
                                viewModel.setCategoryDefaultShelfLife(origin, days: nil)
                            }
                            shelfLifeOrigin = nil
                        }
                    }
                }
                .navigationTitle(L10n.categorySettingsShelfLifeDialogTitle)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(L10n.cancelButton) { shelfLifeOrigin = nil }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button(L10n.saveButton) {
                            if let origin = shelfLifeOrigin {
                                viewModel.setCategoryDefaultShelfLife(origin, days: Int(shelfLifeText))
                            }
                            shelfLifeOrigin = nil
                        }
                        .fontWeight(.bold)
                    }
                }
            }
            .presentationDetents([.medium])
        }
    }

    private func moveCategories(from source: IndexSet, to destination: Int) {
        var reordered = groupedPreferences
        reordered.move(fromOffsets: source, toOffset: destination)
        // The whole visible order is what gets persisted, not just where the dragged row landed
        // relative to its neighbours - see NativePantryViewModel.reorderCategoryPreferences.
        viewModel.reorderCategoryPreferences(reordered.map { $0.originName })
    }
}
