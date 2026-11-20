import SwiftUI

/// Everything written in the app, on this phone only: new ones first, then favourites, then the
/// rest, newest first in each (`SavedList.ordered`). Prompts shared to other apps leave nothing
/// here. Tabs above the list (All, a tab per meal that has recipes, Menus) show while there is more
/// than All. A swipe deletes, with Undo; the leading swipe, the star or the long-press menu marks a
/// favourite. Several go at once through Select (the toolbar, or a row's long-press menu): tap
/// rows of the current tab, then Delete, with one Undo for all of them.
struct SavedRecipesView: View {
    @ObservedObject var model: RecipeViewModel
    @Environment(\.showSnackbar) private var showSnackbar
    @State private var isSelecting = false
    /// Pruned to the listed ids wherever it is read (`SavedSelection.pruned`).
    @State private var picked: Set<String> = []
    /// The tab picked; All again once it is no longer shown (`SavedList.shown`).
    @State private var pickedTab: SavedTab = .all

    var body: some View {
        let tabs = model.savedTabs
        let tab = SavedList.shown(pickedTab, tabs: tabs)
        let entries = model.savedRows(tab: tab)
        let ids = entries.map(\.id)
        let selected = SavedSelection.pruned(picked, savedIDs: ids)
        Group {
            if entries.isEmpty {
                EmptyState(.clear, title: L10n.recipeSavedEmptyTitle, message: L10n.recipeSavedEmptyMessage)
            } else {
                VStack(spacing: 0) {
                    if !tabs.isEmpty { tabRow(tabs, shown: tab) }
                    list(entries, selected: selected)
                }
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
        .onChange(of: tabs) { _, tabs in
            // Its last row deleted: back to All, and an Undo leaves it there.
            if !tabs.contains(pickedTab) { pickedTab = .all }
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

    private func list(_ entries: [SavedRecipe], selected: Set<String>) -> some View {
        List {
            ForEach(Array(entries.enumerated()), id: \.element.id) { index, entry in
                let isSelected = isSelecting && selected.contains(entry.id)
                row(entry, isSelected: isSelected)
                    .larderGroupRow(.init(index: index, count: entries.count), isSelected: isSelected)
                    // Swipe is off while selecting, as on Home.
                    .larderSwipeActions(
                        leading: isSelecting ? [] : [
                            LarderSwipeAction(Self.favouriteLabel(entry), icon: entry.isFavorite ? .star : .starFilled, tint: .primary) {
                                toggleFavourite(entry)
                            },
                        ],
                        trailing: isSelecting ? [] : [
                            LarderSwipeAction(L10n.swipeDelete, icon: .trash2, tint: .destructive) {
                                delete(entry)
                            },
                        ]
                    )
            }
        }
        .larderList()
        .padding(.top, LarderSpace.s2)
    }

    /// All, the meals that have recipes, Menus: one line of chips that scrolls sideways.
    private func tabRow(_ tabs: [SavedTab], shown: SavedTab) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: LarderSpace.s2) {
                ForEach(tabs, id: \.self) { tab in
                    LarderChip(L10n.recipeSavedTabName(tab), isSelected: tab == shown) {
                        withAnimation(.easeInOut(duration: 0.2)) { pickedTab = tab }
                    }
                }
            }
            .padding(.horizontal, LarderSpace.s4)
            .padding(.vertical, LarderSpace.s2)
        }
        .scrollClipDisabled()
    }

    static func favouriteLabel(_ entry: SavedRecipe) -> String {
        entry.isFavorite ? L10n.recipeFavouriteRemove : L10n.recipeFavouriteAdd
    }

    private func toggleFavourite(_ entry: SavedRecipe) {
        withAnimation(.easeInOut(duration: 0.2)) { model.setFavorite(id: entry.id, !entry.isFavorite) }
    }

    private func row(_ entry: SavedRecipe, isSelected: Bool) -> some View {
        HStack(spacing: 0) {
            rowButton(entry, isSelected: isSelected)
            if !isSelecting {
                // Its own button beside the row's, so a tap on it never opens the recipe.
                LarderIconButton(
                    entry.isFavorite ? .starFilled : .star,
                    label: Self.favouriteLabel(entry),
                    color: entry.isFavorite ? LarderColor.primary : LarderColor.onSurfaceVariant
                ) { toggleFavourite(entry) }
                LarderIcon(.chevronRight)
                    .foregroundStyle(LarderColor.onSurfaceVariant)
                    .padding(.trailing, LarderSpace.s3)
                    .contentShape(Rectangle())
                    .onTapGesture { model.path.append(.result(id: entry.id)) }
                    .accessibilityHidden(true)
            }
        }
        .frame(minHeight: 68)
    }

    private func rowButton(_ entry: SavedRecipe, isSelected: Bool) -> some View {
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
                if let thumbnail = entry.thumbnail {
                    SavedRecipeThumbnail(id: entry.id, data: thumbnail)
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
                if entry.isFavorite && isSelecting {
                    // The star button is gone while selecting; the row still says it is one.
                    LarderIcon(.starFilled, size: .small)
                        .foregroundStyle(LarderColor.primary)
                        .accessibilityLabel(L10n.recipeSavedFavourite)
                }
            }
            .padding(.vertical, LarderSpace.s2)
            .padding(.leading, LarderSpace.s3)
            .padding(.trailing, isSelecting ? LarderSpace.s3 : 0)
            .frame(minHeight: 68)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        // VoiceOver says "Selected" on a selected row, as in the system's own lists.
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        // Swipe has a visible equivalent for VoiceOver users.
        .accessibilityAction(named: L10n.swipeDelete) { if !isSelecting { delete(entry) } }
        .accessibilityAction(named: Self.favouriteLabel(entry)) { if !isSelecting { toggleFavourite(entry) } }
        // A long press offers Select, the way in besides the toolbar's button, and the favourite.
        .contextMenu {
            if !isSelecting {
                Button(Self.favouriteLabel(entry), systemImage: entry.isFavorite ? "star.slash" : "star") { toggleFavourite(entry) }
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

/// A Saved row's picture: the stored square thumbnail (`RecipePictureThumbnail`, 240 px, a few KB),
/// decoded off the main actor and kept in a small cache, so scrolling the list never decodes a
/// JPEG on the main thread and a row scrolled back shows at once. While it is read, the tile's
/// plain surface of the same size, so nothing moves.
private struct SavedRecipeThumbnail: View {
    let id: String
    let data: Data

    @State private var image: UIImage?

    private static let cache: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.countLimit = 200
        return cache
    }()

    private var key: NSString { "\(id)#\(data.count)" as NSString }

    var body: some View {
        RoundedRectangle(cornerRadius: LarderRadius.md)
            .fill(LarderColor.surfaceContainer)
            .frame(width: 44, height: 44)
            .overlay {
                if let shown = image ?? Self.cache.object(forKey: key) {
                    Image(uiImage: shown)
                        .resizable()
                        .scaledToFill()
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: LarderRadius.md))
            .accessibilityHidden(true)
            .task(id: key) {
                if let cached = Self.cache.object(forKey: key) {
                    image = cached
                    return
                }
                let data = data
                let decoded = await Task.detached(priority: .userInitiated) {
                    UIImage(data: data)?.preparingForDisplay()
                }.value
                guard let decoded, !Task.isCancelled else { return }
                Self.cache.setObject(decoded, forKey: key)
                image = decoded
            }
    }
}
