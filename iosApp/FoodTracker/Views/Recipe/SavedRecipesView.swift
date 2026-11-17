import SwiftUI

/// Everything written in the app, newest first, on this phone only. Prompts shared to other apps
/// leave nothing here. A swipe deletes, with Undo. Several go at once through Select (the toolbar,
/// or a row's long-press menu): tap rows, then Delete, with one Undo for all of them.
struct SavedRecipesView: View {
    @ObservedObject var model: RecipeViewModel
    @Environment(\.showSnackbar) private var showSnackbar
    @State private var isSelecting = false
    /// Pruned to the listed ids wherever it is read (`SavedSelection.pruned`).
    @State private var picked: Set<String> = []

    var body: some View {
        let entries = model.savedRecipes
        let ids = entries.map(\.id)
        let selected = SavedSelection.pruned(picked, savedIDs: ids)
        Group {
            if entries.isEmpty {
                EmptyState(.clear, title: L10n.recipeSavedEmptyTitle, message: L10n.recipeSavedEmptyMessage)
            } else {
                List {
                    ForEach(Array(entries.enumerated()), id: \.element.id) { index, entry in
                        let isSelected = isSelecting && selected.contains(entry.id)
                        row(entry, isSelected: isSelected)
                            .larderGroupRow(.init(index: index, count: entries.count), isSelected: isSelected)
                            // Swipe is off while selecting, as on Home.
                            .larderSwipeActions(trailing: isSelecting ? [] : [
                                LarderSwipeAction(L10n.swipeDelete, icon: .trash2, tint: .destructive) {
                                    delete(entry)
                                },
                            ])
                    }
                }
                .larderList()
                .padding(.top, LarderSpace.s2)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(LarderColor.background.ignoresSafeArea())
        .navigationTitle(isSelecting ? L10n.selectionCount(selected.count) : L10n.recipeSavedButton)
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(isSelecting)
        .toolbar {
            if isSelecting {
                ToolbarItem(placement: .topBarLeading) {
                    let all = SavedSelection.allSelected(selected, savedIDs: ids)
                    Button(all ? L10n.deselectAllButton : L10n.selectAllButton) {
                        withAnimation(.easeInOut(duration: 0.2)) { picked = SavedSelection.toggledAll(selected, savedIDs: ids) }
                    }
                    .tint(LarderColor.primary)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(L10n.exitSelectionButton) { endSelection() }
                        .tint(LarderColor.primary)
                }
            } else if !entries.isEmpty {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(L10n.selectItemsButton) { startSelection(with: nil) }
                        .tint(LarderColor.primary)
                }
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if isSelecting { selectionActions(selected) }
        }
        .onChange(of: ids) { _, ids in
            // One deleted elsewhere (its own page, an Undo) drops out; none left ends the mode.
            picked = SavedSelection.pruned(picked, savedIDs: ids)
            if ids.isEmpty { endSelection() }
        }
        .shellChromeEffect(hideNav: true)
        #if DEBUG
        .onAppear {
            // `-demoSavedSelect YES`: the list in selection mode with its first row selected.
            if UserDefaults.standard.bool(forKey: "demoSavedSelect"), let first = ids.first {
                isSelecting = true
                picked = [first]
            }
        }
        #endif
    }

    private func row(_ entry: SavedRecipe, isSelected: Bool) -> some View {
        let unread = model.isUnread(entry.id)
        return Button {
            if isSelecting {
                withAnimation(.easeInOut(duration: 0.2)) { picked = SavedSelection.toggled(picked, id: entry.id) }
            } else {
                model.path.append(.result(id: entry.id))
            }
        } label: {
            HStack(spacing: LarderSpace.s3) {
                if isSelecting {
                    // The row is what toggles; the tick only shows it.
                    LarderTick(isOn: isSelected, label: Self.title(entry)) {}
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                        .padding(.horizontal, -LarderSpace.s2)
                }
                // A recipe saved with its dish picture shows it, cropped square; the others their icon.
                if let thumbnail = entry.thumbnail.flatMap(UIImage.init(data:)) {
                    Image(uiImage: thumbnail)
                        .resizable()
                        .scaledToFill()
                        .frame(width: 44, height: 44)
                        .clipShape(RoundedRectangle(cornerRadius: LarderRadius.md))
                        .accessibilityHidden(true)
                } else {
                    LarderIcon(entry.kind == .menu ? .calendarRange : .utensils, size: .large)
                        .foregroundStyle(LarderColor.onSurfaceVariant)
                        .frame(width: 44, height: 44)
                        .background(isSelected ? LarderColor.surface : LarderColor.surfaceContainer, in: RoundedRectangle(cornerRadius: LarderRadius.md))
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(Self.title(entry))
                        .larderFont(.titleSm)
                        .foregroundStyle(LarderColor.onSurface)
                        .lineLimit(2)
                    Text(Self.supporting(entry))
                        .larderFont(.bodySm)
                        .foregroundStyle(LarderColor.onSurfaceVariant)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                // Not opened yet (saved while the writing screen was away): a small dot.
                if unread {
                    Circle()
                        .fill(LarderColor.primary)
                        .frame(width: 8, height: 8)
                        .accessibilityLabel(L10n.recipeSavedNew)
                }
                if !isSelecting {
                    LarderIcon(.chevronRight).foregroundStyle(LarderColor.onSurfaceVariant)
                }
            }
            .padding(.vertical, LarderSpace.s2)
            .padding(.horizontal, LarderSpace.s3)
            .frame(minHeight: 68)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        // VoiceOver says "Selected" on a selected row, as in the system's own lists.
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        // Swipe has a visible equivalent for VoiceOver users.
        .accessibilityAction(named: L10n.swipeDelete) { if !isSelecting { delete(entry) } }
        // A long press offers Select, the way in besides the toolbar's button.
        .contextMenu {
            if !isSelecting {
                Button(L10n.selectItemsButton, systemImage: "checkmark.circle") { startSelection(with: entry.id) }
            }
        }
    }

    /// Delete for what is selected, at the bottom in thumb reach as on Home; off with nothing selected.
    private func selectionActions(_ selected: Set<String>) -> some View {
        VStack(spacing: 0) {
            LarderDivider()
            LarderButton(L10n.swipeDelete, icon: .trash2, variant: .dangerText, fullWidth: true) {
                deleteSelected(selected)
            }
            .disabled(selected.isEmpty)
            .padding(.vertical, LarderSpace.s3)
            .padding(.horizontal, LarderSpace.s4)
        }
        .background(LarderColor.surface.ignoresSafeArea(edges: .bottom))
    }

    private func startSelection(with id: String?) {
        withAnimation(.easeInOut(duration: 0.2)) {
            isSelecting = true
            picked = id.map { [$0] } ?? []
        }
    }

    private func endSelection() {
        withAnimation(.easeInOut(duration: 0.2)) {
            isSelecting = false
            picked = []
        }
    }

    private func delete(_ entry: SavedRecipe) {
        guard let removed = model.deleteSaved(id: entry.id) else { return }
        showSnackbar(L10n.recipeSavedDeleted) { model.restoreSaved(removed) }
    }

    /// One store call and one Undo for all of them, as single delete has one.
    private func deleteSelected(_ selected: Set<String>) {
        let removed = model.deleteSaved(ids: selected)
        endSelection()
        guard !removed.isEmpty else { return }
        showSnackbar(L10n.recipeSavedDeletedCount(removed.count)) { model.restoreSaved(removed) }
    }

    static func title(_ entry: SavedRecipe) -> String {
        switch entry.content {
        case .recipe(let recipe): return recipe.title
        case .menu(let menu): return L10n.recipeMenuHeading(days: menu.days.count)
        }
    }

    /// "Today · 15 min · Written on this iPhone", "Today · 9 dishes": only the phone's model's
    /// recipes say where they were written.
    static func supporting(_ entry: SavedRecipe, now: Date = Date(), calendar: Calendar = .current) -> String {
        var parts = [day(entry.createdAt, now: now, calendar: calendar)]
        switch entry.content {
        case .recipe(let recipe):
            if recipe.minutes > 0 { parts.append(L10n.recipeMinutes(recipe.minutes)) }
        case .menu(let menu):
            parts.append(L10n.recipeDishes(menu.days.reduce(0) { $0 + $1.courses.count }))
        }
        if entry.source == .device { parts.append(L10n.recipeSavedFromPhone) }
        return parts.joined(separator: " · ")
    }

    static func day(_ date: Date, now: Date, calendar: Calendar) -> String {
        if calendar.isDate(date, inSameDayAs: now) { return L10n.recipeSavedToday }
        if let yesterday = calendar.date(byAdding: .day, value: -1, to: now),
           calendar.isDate(date, inSameDayAs: yesterday) {
            return L10n.recipeSavedYesterday
        }
        let formatter = DateFormatter()
        formatter.calendar = calendar
        let sameYear = calendar.component(.year, from: date) == calendar.component(.year, from: now)
        formatter.setLocalizedDateFormatFromTemplate(sameYear ? "dMMM" : "dMMMy")
        return formatter.string(from: date)
    }
}
