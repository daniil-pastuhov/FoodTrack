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

    private var preferences: [CategoryPreference] {
        viewModel.categoryPreferences.sorted { $0.sortOrder < $1.sortOrder }
    }

    private var allEnabled: Bool {
        !preferences.isEmpty && preferences.allSatisfy { $0.enabled }
    }

    var body: some View {
        List {
            Section(footer: Text(L10n.categorySettingsDescription)) {
                if preferences.isEmpty {
                    Text(L10n.categorySettingsEmpty)
                        .foregroundColor(.secondary)
                } else {
                    Toggle(L10n.categorySettingsToggleAll, isOn: Binding(
                        get: { allEnabled },
                        set: { viewModel.setAllCategoriesEnabled($0) }
                    ))
                    .fontWeight(.semibold)

                    ForEach(preferences) { pref in
                        HStack(spacing: 10) {
                            // Decorative affordance only: the row itself is reorderable by
                            // long-press-and-drag via .onMove below (mirrors ShoppingListView).
                            Image(systemName: "line.3.horizontal")
                                .foregroundColor(.secondary)
                                .accessibilityHidden(true)

                            VStack(alignment: .leading, spacing: 2) {
                                Text(pref.displayName)
                                    .foregroundColor(pref.enabled ? .primary : .secondary)
                                if pref.displayName != pref.originName {
                                    Text(L10n.categorySettingsOriginalNameFormat(pref.originName))
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                }
                            }
                            Spacer()
                            Button {
                                editingOrigin = pref.originName
                                editingText = pref.displayName
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
        .alert(L10n.categorySettingsRenameTitle, isPresented: Binding(
            get: { editingOrigin != nil },
            set: { if !$0 { editingOrigin = nil } }
        )) {
            TextField("", text: $editingText)
            Button(L10n.saveButton) {
                if let origin = editingOrigin {
                    viewModel.renameCategory(origin, newDisplayName: editingText)
                }
                editingOrigin = nil
            }
            Button(L10n.cancelButton, role: .cancel) {
                editingOrigin = nil
            }
        }
    }

    private func moveCategories(from source: IndexSet, to destination: Int) {
        guard let sourceIndex = source.first else { return }
        let movedOrigin = preferences[sourceIndex].originName
        var reordered = preferences
        reordered.move(fromOffsets: source, toOffset: destination)
        guard let newIndex = reordered.firstIndex(where: { $0.originName == movedOrigin }) else { return }
        let beforeOrigin = newIndex > 0 ? reordered[newIndex - 1].originName : nil
        let afterOrigin = newIndex < reordered.count - 1 ? reordered[newIndex + 1].originName : nil
        viewModel.moveCategoryPreference(movedOrigin, beforeOrigin: beforeOrigin, afterOrigin: afterOrigin)
    }
}
