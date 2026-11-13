import SwiftUI

/// Everything written in the app, newest first, on this phone only. Prompts shared to other apps
/// leave nothing here. A swipe deletes, with Undo.
struct SavedRecipesView: View {
    @ObservedObject var model: RecipeViewModel
    @Environment(\.showSnackbar) private var showSnackbar

    var body: some View {
        let entries = model.savedRecipes
        Group {
            if entries.isEmpty {
                EmptyState(.clear, title: L10n.recipeSavedEmptyTitle, message: L10n.recipeSavedEmptyMessage)
            } else {
                List {
                    ForEach(Array(entries.enumerated()), id: \.element.id) { index, entry in
                        row(entry)
                            .larderGroupRow(.init(index: index, count: entries.count))
                            .larderSwipeActions(trailing: [
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
        .navigationTitle(L10n.recipeSavedButton)
        .navigationBarTitleDisplayMode(.inline)
        .shellChromeEffect(hideNav: true)
    }

    private func row(_ entry: SavedRecipe) -> some View {
        let recipe = entry.recipe
        return Button {
            model.path.append(.result(id: entry.id))
        } label: {
            HStack(spacing: LarderSpace.s3) {
                LarderIcon(.utensils, size: .large)
                    .foregroundStyle(LarderColor.onSurfaceVariant)
                    .frame(width: 44, height: 44)
                    .background(LarderColor.surfaceContainer, in: RoundedRectangle(cornerRadius: LarderRadius.md))
                VStack(alignment: .leading, spacing: 2) {
                    Text(recipe?.title ?? "")
                        .larderFont(.titleSm)
                        .foregroundStyle(LarderColor.onSurface)
                        .lineLimit(2)
                    Text(Self.supporting(entry))
                        .larderFont(.bodySm)
                        .foregroundStyle(LarderColor.onSurfaceVariant)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                LarderIcon(.chevronRight).foregroundStyle(LarderColor.onSurfaceVariant)
            }
            .padding(.vertical, LarderSpace.s2)
            .padding(.horizontal, LarderSpace.s3)
            .frame(minHeight: 68)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        // Swipe has a visible equivalent for VoiceOver users.
        .accessibilityAction(named: L10n.swipeDelete) { delete(entry) }
    }

    private func delete(_ entry: SavedRecipe) {
        guard let removed = model.deleteSaved(id: entry.id) else { return }
        showSnackbar(L10n.recipeSavedDeleted) { model.restoreSaved(removed) }
    }

    /// "Today · 15 min · Written on this phone".
    static func supporting(_ entry: SavedRecipe, now: Date = Date(), calendar: Calendar = .current) -> String {
        var parts = [day(entry.createdAt, now: now, calendar: calendar)]
        if let minutes = entry.recipe?.minutes, minutes > 0 { parts.append(L10n.recipeMinutes(minutes)) }
        parts.append(L10n.recipeSavedOrigin(entry.source))
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
