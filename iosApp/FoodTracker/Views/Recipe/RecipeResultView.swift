import SwiftUI

/// One recipe, read on a counter at arm's length: large type, numbered steps. It is saved as
/// soon as it arrives, so there is no Save button; Share sends it as text. A saved menu opens
/// as its three days instead, and `dish` one recipe of it.
struct RecipeResultView: View {
    /// Nil when the entry was deleted while this screen was on the stack.
    let entry: SavedRecipe?
    var dish: (day: Int, course: Int)? = nil
    /// The tab's model, for the pictures kept with the entry, the lines it arrived with and the
    /// report action; nil in previews.
    var model: RecipeViewModel? = nil
    var onOpenDish: (Int, Int) -> Void = { _, _ in }

    @State private var sharing = false
    /// The entry's picture bytes once read (off the main actor); nil until then.
    @State private var pictures: SavedRecipePictures?

    var body: some View {
        Group {
            switch entry?.content {
            case .recipe(let recipe)?:
                RecipeDocument(
                    recipe: recipe,
                    pictures: pictures,
                    notice: entry.flatMap { model?.pictureNotices[$0.id] },
                    foot: reportFoot(isMenu: false)
                )
                .task(id: entry?.id) {
                    guard recipe.hasPictures, let id = entry?.id, let model else { return }
                    pictures = await model.pictures(for: id)
                }
                    .toolbar {
                        ToolbarItem(placement: .primaryAction) {
                            LarderIconButton(.share2, label: L10n.recipeShareRecipe, color: LarderColor.primary) {
                                sharing = true
                            }
                        }
                        favouriteItem
                    }
                    .sheet(isPresented: $sharing) {
                        ActivityView(activityItems: [RecipePromptBuilder.plainText(recipe)])
                    }
                    .navigationTitle(L10n.recipeResultTitle)
            case .menu(let menu)?:
                if let dish, let course = Self.course(in: menu, day: dish.day, course: dish.course) {
                    RecipeDishView(recipe: course, date: menu.days[dish.day].date, menuDays: menu.days.count, foot: reportFoot(isMenu: false))
                } else {
                    RecipeMenuView(menu: menu, onOpenDish: onOpenDish, foot: reportFoot(isMenu: true))
                        .toolbar { favouriteItem }
                        .navigationTitle(L10n.recipeMenuResultTitle)
                }
            case nil:
                EmptyState(.clear, title: L10n.recipeSavedEmptyTitle, message: L10n.recipeNotFound)
                    .navigationTitle(L10n.recipeResultTitle)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(LarderColor.background.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
        .shellChromeEffect(hideNav: true)
    }

    /// The star beside Share on a saved recipe's or menu's page (not a menu's single dish).
    @ToolbarContentBuilder
    private var favouriteItem: some ToolbarContent {
        if let model, let id = entry?.id {
            ToolbarItem(placement: .primaryAction) {
                RecipeFavouriteButton(model: model, id: id)
            }
        }
    }

    /// "Report this recipe / menu" at the very end, where it applies (`RecipeViewModel.reportFoot`).
    private func reportFoot(isMenu: Bool) -> AnyView? {
        guard let model, let entry else { return nil }
        return AnyView(RecipeReportFootHost(model: model, entry: entry, isMenu: isMenu))
    }

    static func course(in menu: RecipeMenu, day: Int, course: Int) -> RecipeMenuCourse? {
        guard menu.days.indices.contains(day), menu.days[day].courses.indices.contains(course) else { return nil }
        return menu.days[day].courses[course]
    }
}

/// A saved recipe's or menu's favourite star, following the saved row as it changes.
struct RecipeFavouriteButton: View {
    @ObservedObject var model: RecipeViewModel
    let id: String

    var body: some View {
        if let entry = model.savedRecipe(id: id) {
            LarderIconButton(
                entry.isFavorite ? .starFilled : .star,
                label: SavedRecipesView.favouriteLabel(entry),
                color: LarderColor.primary
            ) {
                withAnimation(.easeInOut(duration: 0.2)) { model.setFavorite(id: id, !entry.isFavorite) }
            }
        }
    }
}

/// The recipe itself. Titles wrap, never cut; amounts keep a fixed column so names line up.
struct RecipeDocument: View {
    let recipe: Recipe
    /// The line at the end, in place of where the recipe was written.
    var footer: String? = nil
    /// The bytes of the pictures the recipe was saved with (`Recipe.picture` / `stepPictures`
    /// say which); nil while they are read, when a plain surface holds their place.
    var pictures: SavedRecipePictures? = nil
    /// The quiet lines of a result that has just arrived with fewer pictures than asked.
    var notice: RecipePictureNotice? = nil
    /// "Report this recipe" at the very end, after the method and tips.
    var foot: AnyView? = nil

    @ScaledMetric(relativeTo: .body) private var amountWidth: CGFloat = 72
    @ScaledMetric(relativeTo: .body) private var stepNumberSide: CGFloat = 28
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                // The dish picture opens the recipe, its caption always under it.
                if recipe.picture != nil {
                    RecipePictureFrame(data: pictures?.dish, label: L10n.recipeImageDescription(recipe.title))
                    RecipePictureLine(L10n.recipeImageCaption)
                        .padding(.top, LarderSpace.s2)
                        .padding(.bottom, LarderSpace.s4)
                }
                Text(recipe.title)
                    .larderFont(.headline)
                    .foregroundStyle(LarderColor.onBackground)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityAddTraits(.isHeader)
                    .padding(.bottom, LarderSpace.s2)
                if !recipe.summary.isEmpty {
                    Text(recipe.summary)
                        .larderFont(.body)
                        .foregroundStyle(LarderColor.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.bottom, LarderSpace.s3)
                }
                facts
                if notice?.dishFailed == true {
                    RecipePictureLine(L10n.recipeImageFailed)
                        .padding(.top, LarderSpace.s2)
                }
                // Above the ingredients, never in a footer: seen as the recipe opens.
                if let warning = L10n.recipePastDateWarning(recipe.pastDate) {
                    LarderCallout(.error, icon: .triangleAlert, message: warning)
                        .padding(.top, LarderSpace.s3)
                }

                SectionHeader(L10n.recipeIngredientsTitle)
                    .padding(.top, LarderSpace.s2)
                LarderGroup {
                    ForEach(Array(recipe.ingredients.enumerated()), id: \.offset) { _, ingredient in
                        ingredientRow(ingredient)
                    }
                }
                // True for staples and extras alike, so no row is labelled wrongly; not needed when
                // every such row says it is one of the person's staples.
                if recipe.ingredients.contains(where: { !$0.fromPantry && $0.stapleName == nil }) {
                    Text(L10n.recipeNotFromPantryHint)
                        .larderFont(.caption)
                        .foregroundStyle(LarderColor.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, LarderSpace.s2)
                }
                RecipeShoppingButton(recipes: [recipe])
                    .padding(.top, LarderSpace.s3)

                SectionHeader(L10n.recipeMethodTitle)
                    .padding(.top, LarderSpace.s2)
                // One caption for every step picture, never one per picture.
                if !recipe.stepPictures.isEmpty {
                    RecipePictureLine(L10n.recipeImageStepsCaption)
                        .padding(.bottom, LarderSpace.s2)
                }
                if notice?.stepsFailed == true {
                    RecipePictureLine(L10n.recipeImageStepsFailed)
                        .padding(.bottom, LarderSpace.s2)
                }
                VStack(alignment: .leading, spacing: LarderSpace.s4) {
                    ForEach(Array(recipe.steps.enumerated()), id: \.offset) { index, step in
                        stepRow(number: index + 1, text: step, hasPicture: recipe.stepPictures.contains { $0.step == index }, picture: pictures?.steps[index])
                    }
                }

                if let tip = recipe.tip, !tip.isEmpty {
                    LarderCallout(.info, icon: .lightbulb, title: L10n.recipeTipTitle, message: tip)
                        .padding(.top, LarderSpace.s4)
                }

                // A dish says which menu it is part of; a recipe from the phone's model, under its
                // chip, that nothing was sent. A server-written recipe has no source line.
                if let line = footer ?? (recipe.source == .device ? L10n.recipeSourceDevice : nil) {
                    HStack(alignment: .top, spacing: LarderSpace.s2) {
                        LarderIcon(footer == nil ? .smartphone : .utensils, size: .custom(16))
                        Text(line)
                            .larderFont(.caption)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .foregroundStyle(LarderColor.onSurfaceVariant)
                    .padding(.top, LarderSpace.s6)
                }
                if let foot { foot }
            }
            .padding(.horizontal, LarderSpace.s4)
            .padding(.top, LarderSpace.s2)
            .padding(.bottom, LarderSpace.s8)
            .frame(maxWidth: 640, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .defaultScrollAnchor(Self.opensAt)
    }

    /// `-demoRecipeResultAt middle|end` (Debug): the result opens scrolled there, for layout
    /// checks of the step pictures and the report foot.
    static var opensAt: UnitPoint {
        #if DEBUG
        switch UserDefaults.standard.string(forKey: "demoRecipeResultAt") {
        case "middle": return .center
        case "end": return .bottom
        default: return .top
        }
        #else
        return .top
        #endif
    }

    private var facts: some View {
        FlowLayout(spacing: LarderSpace.s2, rowSpacing: LarderSpace.s2) {
            if recipe.minutes > 0 { fact(.timer, L10n.recipeMinutes(recipe.minutes)) }
            if recipe.servings > 0 { fact(.users, L10n.recipeServings(recipe.servings)) }
            fact(.gauge, L10n.recipeDifficultyName(recipe.difficulty))
            // Never mistaken for one from our server: the phone's model is the weaker writer.
            if recipe.source == .device, footer == nil { fact(.smartphone, L10n.recipeSavedFromPhone) }
        }
    }

    private func fact(_ icon: LarderIconName, _ text: String) -> some View {
        HStack(spacing: LarderSpace.s1) {
            LarderIcon(icon, size: .custom(16))
            Text(text).larderFont(.label)
        }
        .foregroundStyle(LarderColor.onSurfaceVariant)
        .padding(.vertical, LarderSpace.s1)
        .padding(.horizontal, LarderSpace.s2)
        .background(LarderColor.surfaceContainer, in: RoundedRectangle(cornerRadius: LarderRadius.sm))
        .accessibilityElement(children: .combine)
    }

    private func ingredientRow(_ ingredient: RecipeIngredient) -> some View {
        // At accessibility sizes the chip goes under the name: beside it, the amount column, the
        // name and the chip are wider than a phone and the whole recipe would scroll sideways.
        let stacked = dynamicTypeSize.isAccessibilitySize
        return HStack(alignment: .firstTextBaseline, spacing: LarderSpace.s3) {
            Text(ingredient.amount)
                .larderFont(.label)
                .monospacedDigit()
                .foregroundStyle(LarderColor.onSurfaceVariant)
                .frame(width: stacked ? amountWidth * 0.75 : amountWidth, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
            if stacked {
                VStack(alignment: .leading, spacing: LarderSpace.s1) {
                    ingredientName(ingredient)
                    ingredientChip(ingredient)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                ingredientName(ingredient)
                ingredientChip(ingredient)
            }
        }
        .padding(.vertical, LarderSpace.s3)
        .padding(.horizontal, LarderSpace.s4)
        .accessibilityElement(children: .combine)
    }

    private func ingredientName(_ ingredient: RecipeIngredient) -> some View {
        Text(ingredient.name)
            .larderFont(.body)
            .foregroundStyle(LarderColor.onSurface)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private func ingredientChip(_ ingredient: RecipeIngredient) -> some View {
        if ingredient.fromPantry {
            HStack(spacing: 2) {
                LarderIcon(.check, size: .custom(14))
                Text(L10n.recipeInYourPantry).larderFont(.caption)
            }
            .foregroundStyle(LarderColor.onSuccessContainer)
            .padding(.vertical, 1)
            .padding(.leading, LarderSpace.s1)
            .padding(.trailing, LarderSpace.s2)
            .background(LarderColor.successContainer, in: RoundedRectangle(cornerRadius: LarderRadius.sm))
            .fixedSize(horizontal: !dynamicTypeSize.isAccessibilitySize, vertical: true)
        } else if ingredient.stapleName != nil {
            // One of the person's staples: the same shape, neutral.
            HStack(spacing: 2) {
                LarderIcon(.wheat, size: .custom(14))
                Text(L10n.recipeYourStaple).larderFont(.caption)
            }
            .foregroundStyle(LarderColor.onSurfaceVariant)
            .padding(.vertical, 1)
            .padding(.leading, LarderSpace.s1)
            .padding(.trailing, LarderSpace.s2)
            .background(LarderColor.surfaceContainerHigh, in: RoundedRectangle(cornerRadius: LarderRadius.sm))
            .fixedSize(horizontal: !dynamicTypeSize.isAccessibilitySize, vertical: true)
        }
    }

    /// A step, and its picture under its text at the text's width (past the step number) when it has one.
    private func stepRow(number: Int, text: String, hasPicture: Bool = false, picture: Data? = nil) -> some View {
        HStack(alignment: .top, spacing: LarderSpace.s3) {
            Text("\(number)")
                .larderFont(.label)
                .monospacedDigit()
                .foregroundStyle(LarderColor.onPrimaryContainer)
                .frame(width: stepNumberSide, height: stepNumberSide)
                .background(LarderColor.primaryContainer, in: Circle())
                .accessibilityHidden(hasPicture)
            VStack(alignment: .leading, spacing: LarderSpace.s3) {
                Text(text)
                    .larderFont(.body)
                    .foregroundStyle(LarderColor.onSurface)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, 2)
                    .accessibilityLabel(hasPicture ? "\(number). \(text)" : text)
                if hasPicture {
                    RecipePictureFrame(data: picture, label: L10n.recipeImageStepDescription(number))
                }
            }
        }
        .accessibilityElement(children: hasPicture ? .contain : .combine)
    }
}

#Preview("Recipe, from the phone") {
    NavigationStack {
        RecipeResultView(entry: SavedRecipe(content: .recipe(Recipe(
            title: "Slow-roasted salmon with spinach, yoghurt and sourdough crumb",
            summary: "A longer cook for a weekend, using the salmon before Saturday.",
            minutes: 70,
            servings: 4,
            difficulty: .ambitious,
            ingredients: [
                RecipeIngredient(amount: "2", name: "Salmon fillet", fromPantry: true),
                RecipeIngredient(amount: "4 slices", name: "Sourdough bread, blitzed to coarse crumbs", fromPantry: true),
                RecipeIngredient(amount: "", name: "Flaky salt", fromPantry: false),
            ],
            steps: ["Heat the oven to 120°C.", "Toast the crumbs with the oil until deep gold."],
            tip: "Low oven temperatures vary.",
            source: .device
        ))))
    }
}

// MARK: Add to shopping list

/// What a result screen needs to offer "Add to shopping list": the shopping list's names now, and
/// how to add some (returning how many were added). Handed down by `RecipeView`; without it
/// (previews) nothing is offered. Android: `RecipeShoppingActions`.
struct RecipeShoppingContext {
    var listedNames: [String] = []
    /// The person's staples not on the list (`Staples.currentKeys`): never offered.
    var stapleKeys: Set<String> = []
    var add: (([String]) -> Int)? = nil
    var openShoppingList: () -> Void = {}
}

private struct RecipeShoppingKey: EnvironmentKey {
    static let defaultValue = RecipeShoppingContext()
}

extension EnvironmentValues {
    var recipeShopping: RecipeShoppingContext {
        get { self[RecipeShoppingKey.self] }
        set { self[RecipeShoppingKey.self] = newValue }
    }
}

/// "Add to shopping list" under a recipe's ingredients, or under a menu's title for every day of
/// it: shown only when `recipes` has an ingredient the cook doesn't have that isn't on the list
/// already. Opens the sheet; nothing
/// opens by itself.
struct RecipeShoppingButton: View {
    let recipes: [Recipe]

    @Environment(\.recipeShopping) private var shopping
    @Environment(\.showSnackbar) private var showSnackbar
    @State private var candidates: [ShoppingCandidate]?

    var body: some View {
        // Hidden when nothing can be added (nothing to buy, or every row on the list already);
        // `listedNames` is the live list, so it comes back when the list changes.
        if shopping.add != nil, RecipeShopping.hasSomethingToAdd(RecipeShopping.candidates(recipes, listedNames: shopping.listedNames, stapleKeys: shopping.stapleKeys)) {
            LarderButton(L10n.recipeShoppingAdd, icon: .listPlus, variant: .outline, fullWidth: true) {
                candidates = RecipeShopping.candidates(recipes, listedNames: shopping.listedNames, stapleKeys: shopping.stapleKeys)
            }
            .larderSheet(isPresented: Binding(get: { candidates != nil }, set: { if !$0 { candidates = nil } }), fitsContent: true) {
                if let candidates {
                    RecipeShoppingSheet(candidates: candidates) { names in
                        self.candidates = nil
                        guard let add = shopping.add else { return }
                        let added = add(names)
                        let open = shopping.openShoppingList
                        showSnackbar(L10n.recipeShoppingAdded(added), actionTitle: L10n.navShopping) { open() }
                    } onCancel: {
                        self.candidates = nil
                    }
                }
            }
        }
    }
}

/// The ingredients to buy, every one ticked at first (the owner's rule) except those already on
/// the list, which are shown but can't be ticked. The confirm button counts the ticks.
struct RecipeShoppingSheet: View {
    let candidates: [ShoppingCandidate]
    let onAdd: ([String]) -> Void
    let onCancel: () -> Void

    @State private var ticked: Set<String>

    init(candidates: [ShoppingCandidate], onAdd: @escaping ([String]) -> Void, onCancel: @escaping () -> Void) {
        self.candidates = candidates
        self.onAdd = onAdd
        self.onCancel = onCancel
        _ticked = State(initialValue: RecipeShopping.defaultSelection(candidates))
    }

    var body: some View {
        LarderSheet(L10n.recipeShoppingTitle(candidates.filter { !$0.alreadyListed }.count)) {
            Text(L10n.recipeShoppingMessage)
                .larderFont(.body)
                .foregroundStyle(LarderColor.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
            LarderGroup {
                ForEach(candidates, id: \.key) { candidate in
                    row(candidate)
                }
            }
            VStack(spacing: LarderSpace.s1) {
                LarderButton(L10n.recipeShoppingConfirm(ticked.count), fullWidth: true) {
                    onAdd(candidates.filter { ticked.contains($0.key) }.map(\.name))
                }
                .disabled(ticked.isEmpty)
                LarderButton(L10n.cancelButton, variant: .text, fullWidth: true, action: onCancel)
            }
            .padding(.top, LarderSpace.s2)
        }
    }

    private func row(_ candidate: ShoppingCandidate) -> some View {
        let enabled = !candidate.alreadyListed
        return HStack(spacing: LarderSpace.s2) {
            LarderTick(isOn: ticked.contains(candidate.key), label: candidate.name) {
                if ticked.contains(candidate.key) { ticked.remove(candidate.key) } else { ticked.insert(candidate.key) }
            }
            .disabled(!enabled)
            VStack(alignment: .leading, spacing: 0) {
                Text(candidate.name)
                    .larderFont(.body)
                    .foregroundStyle(enabled ? LarderColor.onSurface : LarderColor.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                if !enabled {
                    Text(L10n.recipeShoppingAlready)
                        .larderFont(.caption)
                        .foregroundStyle(LarderColor.onSurfaceVariant)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, LarderSpace.s2)
        .padding(.vertical, LarderSpace.s1)
        .accessibilityElement(children: .combine)
    }
}
