import SwiftUI

/// A three-day menu: one section a day, saying which dated items it uses, with a row per course
/// that opens the dish's full recipe. Saved as one entry.
struct RecipeMenuView: View {
    let menu: RecipeMenu
    let onOpenDish: (_ day: Int, _ course: Int) -> Void

    @State private var sharing = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text(L10n.recipeMenuHeading)
                    .larderFont(.headline)
                    .foregroundStyle(LarderColor.onBackground)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityAddTraits(.isHeader)
                    .padding(.bottom, LarderSpace.s2)
                Text(L10n.recipeMenuSummary(courses: Self.courseCount(menu), servings: Self.servings(menu)))
                    .larderFont(.body)
                    .foregroundStyle(LarderColor.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.bottom, LarderSpace.s3)

                ForEach(Array(menu.days.enumerated()), id: \.offset) { dayIndex, day in
                    SectionHeader(Self.dayTitle(day.date))
                        .padding(.top, LarderSpace.s2)
                    if !day.uses.isEmpty {
                        Text(L10n.recipeMenuUses(day.uses))
                            .larderFont(.bodySm)
                            .foregroundStyle(LarderColor.onSurfaceVariant)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.bottom, LarderSpace.s2)
                    }
                    LarderGroup {
                        ForEach(Array(day.courses.enumerated()), id: \.offset) { courseIndex, course in
                            courseRow(course) { onOpenDish(dayIndex, courseIndex) }
                        }
                    }
                }

                HStack(alignment: .top, spacing: LarderSpace.s2) {
                    LarderIcon(.cloud, size: .custom(16))
                    Text(L10n.recipeSource(.server))
                        .larderFont(.caption)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .foregroundStyle(LarderColor.onSurfaceVariant)
                .padding(.top, LarderSpace.s6)
            }
            .padding(.horizontal, LarderSpace.s4)
            .padding(.top, LarderSpace.s2)
            .padding(.bottom, LarderSpace.s8)
            .frame(maxWidth: 640, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                LarderIconButton(.share2, label: L10n.recipeShareMenu, color: LarderColor.primary) { sharing = true }
            }
        }
        .sheet(isPresented: $sharing) {
            ActivityView(activityItems: [RecipePromptBuilder.plainText(menu)])
        }
    }

    private func courseRow(_ course: RecipeMenuCourse, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: LarderSpace.s3) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(L10n.recipeCourseName(course.course))
                        .larderFont(.caption)
                        .foregroundStyle(LarderColor.onSurfaceVariant)
                    Text(course.recipe.title)
                        .larderFont(.body)
                        .foregroundStyle(LarderColor.onSurface)
                        .fixedSize(horizontal: false, vertical: true)
                    if course.recipe.minutes > 0 {
                        Text(L10n.recipeMinutes(course.recipe.minutes))
                            .larderFont(.bodySm)
                            .foregroundStyle(LarderColor.onSurfaceVariant)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                LarderIcon(.chevronRight).foregroundStyle(LarderColor.onSurfaceVariant)
            }
            .padding(.vertical, LarderSpace.s3)
            .padding(.horizontal, LarderSpace.s4)
            .frame(minHeight: LarderSize.touch)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
    }

    // MARK: Pure rules

    static func courseCount(_ menu: RecipeMenu) -> Int {
        menu.days.map(\.courses.count).max() ?? 2
    }

    static func servings(_ menu: RecipeMenu) -> Int {
        menu.days.first?.courses.first?.recipe.servings ?? 0
    }

    static func date(fromISO iso: String, calendar: Calendar = .current) -> Date? {
        let parts = iso.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        return calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2]))
    }

    /// "Today, Wed 4 Nov", then "Thu 5 Nov".
    static func dayTitle(_ iso: String, now: Date = Date(), calendar: Calendar = .current) -> String {
        guard let date = date(fromISO: iso, calendar: calendar) else { return iso }
        let text = shortDay(date, calendar: calendar)
        return calendar.isDate(date, inSameDayAs: now) ? L10n.recipeMenuToday(text) : text
    }

    static func shortDay(_ date: Date, calendar: Calendar = .current) -> String {
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.setLocalizedDateFormatFromTemplate("EEEdMMM")
        return formatter.string(from: date)
    }
}

/// One dish from a menu: the recipe, titled with its day and course.
struct RecipeDishView: View {
    let recipe: RecipeMenuCourse
    let date: String

    @State private var sharing = false

    var body: some View {
        RecipeDocument(recipe: recipe.recipe, footer: L10n.recipePartOfMenu)
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    LarderIconButton(.share2, label: L10n.recipeShareRecipe, color: LarderColor.primary) { sharing = true }
                }
            }
            .sheet(isPresented: $sharing) {
                ActivityView(activityItems: [RecipePromptBuilder.plainText(recipe.recipe)])
            }
            .navigationTitle(Self.title(date: date, course: recipe.course))
    }

    /// "Wed 4 Nov · Main".
    static func title(date: String, course: RecipeCourse) -> String {
        let day = RecipeMenuView.date(fromISO: date).map { RecipeMenuView.shortDay($0) } ?? date
        return "\(day) · \(L10n.recipeCourseName(course))"
    }
}
