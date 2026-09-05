import SwiftUI

struct ShoppingListView: View {
    @ObservedObject var viewModel: NativePantryViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var newEntryText: String = ""

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                HStack(spacing: 8) {
                    TextField(L10n.shoppingListAddPlaceholder, text: $newEntryText)
                        .textFieldStyle(.roundedBorder)
                    Button {
                        submitNewEntry()
                    } label: {
                        Image(systemName: "plus.circle.fill")
                            .font(.title2)
                    }
                    .disabled(newEntryText.trimmingCharacters(in: .whitespaces).isEmpty)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)

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
        }
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
