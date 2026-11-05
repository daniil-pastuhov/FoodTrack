import SwiftUI

/// Everything the product detail can do to the item on show.
struct ProductDetailActions {
    let useOne: () -> Void
    let edit: () -> Void
    let moveToShoppingList: () -> Void
    let delete: () -> Void
}

/// The product detail route: a sheet on phones, the right-hand pane on expanded Home. The actions
/// stay put while the content scrolls, because long ingredients are never cut short.
struct PantryProductDetail: View {
    let item: PantryItem
    let categoryName: String?
    let expiringSoonDays: Int
    let nutrition: ProductNutrition
    /// The person's own allergens this product matched, shown first.
    let warnings: [String]
    let actions: ProductDetailActions
    /// Nil in the pane, which has no close button.
    var onClose: (() -> Void)? = nil

    private var isPane: Bool { onClose == nil }

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: LarderSpace.s4) {
                    header
                    AllergenCallout(warnings: warnings)
                    facts
                    NutritionSections(nutrition: nutrition, warnings: warnings)
                    if let notes = item.notes, !notes.isEmpty {
                        DetailSection(L10n.homeNotesLabel) { DetailBody(notes) }
                    }
                    if !nutrition.isEmpty {
                        Text(L10n.detailCredit)
                            .larderFont(.caption)
                            .foregroundStyle(LarderColor.onSurfaceVariant)
                    }
                }
                .padding(LarderSpace.s4)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            LarderDivider()
            buttons
                .padding(.horizontal, LarderSpace.s4)
                .padding(.top, LarderSpace.s3)
                .padding(.bottom, isPane ? 0 : LarderSpace.s4)
        }
    }

    private var header: some View {
        HStack(alignment: .top, spacing: LarderSpace.s3) {
            LarderThumb(
                imageReference: item.imageUrl,
                placeholderIcon: .forCategory(item.category),
                background: isPane ? LarderColor.surface : LarderColor.surfaceContainer,
                side: 72
            )
            VStack(alignment: .leading, spacing: 2) {
                Text(item.name)
                    .larderFont(.title)
                    .foregroundStyle(LarderColor.onSurface)
                    .accessibilityAddTraits(.isHeader)
                    .fixedSize(horizontal: false, vertical: true)
                if let brand = item.brand, !brand.isEmpty {
                    Text(brand)
                        .larderFont(.bodySm)
                        .foregroundStyle(LarderColor.onSurfaceVariant)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if let onClose {
                LarderIconButton(.x, label: L10n.detailClose, action: onClose)
                    .padding(.top, -LarderSpace.s2)
                    .padding(.trailing, -LarderSpace.s2)
            }
        }
    }

    /// Only the second column flexes. A Grid sizes flexible columns to the widest of them, so with
    /// both flexible a long Russian status chip pushed the sheet past the screen.
    private var facts: some View {
        Grid(alignment: .topLeading, horizontalSpacing: LarderSpace.s4, verticalSpacing: LarderSpace.s3) {
            GridRow {
                fact(L10n.homeBestBeforeLabel, date) {
                    StatusChip(item: item, expiringSoonDays: expiringSoonDays)
                        .padding(.top, LarderSpace.s1)
                }
                fact(L10n.homeLocationLabel, L10n.storageLocationName(item.storageLocation))
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            GridRow {
                if let categoryName {
                    fact(L10n.homeCategoryLabel, categoryName)
                }
                fact(quantityLabel, L10n.quantityFormat(item.quantity))
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(.horizontal, LarderSpace.s4)
        .padding(.vertical, LarderSpace.s3)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(LarderColor.surface, in: RoundedRectangle(cornerRadius: LarderRadius.lg))
        .overlay {
            RoundedRectangle(cornerRadius: LarderRadius.lg).strokeBorder(LarderColor.outlineVariant, lineWidth: 1)
        }
    }

    private var quantityLabel: String { L10n.quantityLabel }

    private var date: String {
        item.expiryDate.formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated).year())
    }

    private func fact<Extra: View>(
        _ label: String,
        _ value: String,
        @ViewBuilder extra: () -> Extra = { EmptyView() }
    ) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).larderFont(.caption).foregroundStyle(LarderColor.onSurfaceVariant)
            Text(value).larderFont(.titleSm).foregroundStyle(LarderColor.onSurface)
            extra()
        }
    }

    /// Use one leads, and each pair stacks when its labels run long, as Russian ones do.
    private var buttons: some View {
        VStack(spacing: LarderSpace.s2) {
            ButtonPair {
                LarderButton(L10n.useOneButton, icon: .circleMinus, fullWidth: true, action: actions.useOne)
                LarderButton(L10n.detailEdit, icon: .pencil, variant: .outline, action: actions.edit)
            }
            ButtonPair {
                LarderButton(
                    L10n.detailToShoppingList,
                    icon: .shoppingBasket,
                    variant: .outline,
                    fullWidth: true,
                    action: actions.moveToShoppingList
                )
                LarderButton(L10n.swipeDelete, icon: .trash2, variant: .dangerText, action: actions.delete)
            }
        }
    }
}

/// Two buttons side by side, the first taking the spare width, or stacked when they don't fit.
/// Never wider than it is offered: `ViewThatFits` reported more when neither option fitted, which
/// widened the whole sheet.
private struct ButtonPair: Layout {
    private let gap = LarderSpace.s2

    private func arrangement(_ subviews: Subviews, width: CGFloat) -> (stacked: Bool, sizes: [CGSize]) {
        let sizes = subviews.map { $0.sizeThatFits(.unspecified) }
        return (sizes.map(\.width).reduce(gap, +) > width, sizes)
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let (stacked, sizes) = arrangement(subviews, width: proposal.width ?? .infinity)
        let width = proposal.width ?? sizes.map(\.width).reduce(gap, +)
        let heights = sizes.map(\.height)
        return CGSize(
            width: width,
            height: stacked ? heights.reduce(gap, +) : heights.max() ?? 0
        )
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard subviews.count == 2 else { return }
        let (stacked, sizes) = arrangement(subviews, width: bounds.width)
        if stacked {
            subviews[0].place(
                at: bounds.origin,
                proposal: ProposedViewSize(width: bounds.width, height: sizes[0].height)
            )
            subviews[1].place(
                at: CGPoint(x: bounds.midX, y: bounds.minY + sizes[0].height + gap),
                anchor: .top,
                proposal: ProposedViewSize(sizes[1])
            )
        } else {
            subviews[0].place(
                at: bounds.origin,
                proposal: ProposedViewSize(width: bounds.width - sizes[1].width - gap, height: sizes[0].height)
            )
            subviews[1].place(
                at: CGPoint(x: bounds.maxX, y: bounds.minY),
                anchor: .topTrailing,
                proposal: ProposedViewSize(sizes[1])
            )
        }
    }
}

/// Nutrition alone, for a product that isn't in the pantry yet: the Add item screen's scanned
/// product. Everything here is often missing, because Open Food Facts is crowd-sourced, so each
/// section is left out rather than shown empty.
struct ProductDetailView: View {
    let productName: String
    let nutrition: ProductNutrition
    /// The person's own allergens this product matched, shown first.
    let warnings: [String]

    var body: some View {
        LarderSheet(productName) {
            VStack(alignment: .leading, spacing: LarderSpace.s4) {
                AllergenCallout(warnings: warnings)
                NutritionSections(nutrition: nutrition, warnings: warnings)
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .presentationBackground(LarderColor.surfaceContainerHigh)
    }
}

/// T4, for as long as the match holds, so it can't be dismissed.
private struct AllergenCallout: View {
    let warnings: [String]

    var body: some View {
        if !warnings.isEmpty {
            LarderCallout(
                .error,
                icon: .triangleAlert,
                title: L10n.allergenWarning(warnings.joined(separator: ", ")),
                message: L10n.detailAllergenNote
            )
        }
    }
}

private struct NutritionSections: View {
    let nutrition: ProductNutrition
    let warnings: [String]

    private var grade: String? {
        nutrition.nutriScore.map { $0.uppercased() }.flatMap { NutriScoreScale.grades.contains($0) ? $0 : nil }
    }

    private var nova: Int? {
        nutrition.novaGroup.flatMap { (1...4).contains($0) ? $0 : nil }
    }

    var body: some View {
        if grade != nil || nova != nil {
            HStack(alignment: .top, spacing: LarderSpace.s4) {
                Group {
                    if let grade {
                        NutriScoreScale(grade: grade)
                    } else if let nova {
                        NovaScale(group: nova)
                    }
                }
                .frame(maxWidth: .infinity)
                Group {
                    if grade != nil, let nova {
                        NovaScale(group: nova)
                    } else {
                        Color.clear.frame(height: 0)
                    }
                }
                .frame(maxWidth: .infinity)
            }
        }
        if !nutrition.allergens.isEmpty {
            DetailSection(L10n.productDetailAllergens) {
                FlowLayout(spacing: LarderSpace.s1) {
                    ForEach(nutrition.allergens, id: \.self) { allergen in
                        AllergenTag(name: allergen, isMatch: warnings.contains(allergen))
                    }
                }
            }
        }
        if let ingredients = nutrition.ingredients, !ingredients.isEmpty {
            DetailSection(L10n.productDetailIngredients) { DetailBody(ingredients) }
        }
    }
}

private struct AllergenTag: View {
    let name: String
    let isMatch: Bool

    var body: some View {
        Text(name.prefix(1).uppercased() + name.dropFirst())
            .larderFont(.caption)
            .foregroundStyle(isMatch ? LarderColor.onErrorContainer : LarderColor.onSurface)
            .padding(.vertical, 2)
            .padding(.horizontal, LarderSpace.s2)
            .background(
                isMatch ? LarderColor.errorContainer : LarderColor.surface,
                in: RoundedRectangle(cornerRadius: LarderRadius.sm)
            )
            .overlay {
                if !isMatch {
                    RoundedRectangle(cornerRadius: LarderRadius.sm).strokeBorder(LarderColor.outlineVariant, lineWidth: 1)
                }
            }
    }
}

/// The official colours, on a dark plate in both themes so that all five reach 3:1. The grade is
/// also the larger, ringed tile and the caption, so it doesn't rest on colour.
private struct NutriScoreScale: View {
    static let grades = ["A", "B", "C", "D", "E"]
    let grade: String

    private func colors(_ letter: String) -> (fill: Color, ink: Color) {
        switch letter {
        case "A": return (LarderColor.nutriA, LarderColor.onNutriA)
        case "B": return (LarderColor.nutriB, LarderColor.onNutriB)
        case "C": return (LarderColor.nutriC, LarderColor.onNutriC)
        case "D": return (LarderColor.nutriD, LarderColor.onNutriD)
        default: return (LarderColor.nutriE, LarderColor.onNutriE)
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: LarderSpace.s1) {
            HStack(spacing: LarderSpace.s1) {
                ForEach(Self.grades, id: \.self) { letter in
                    let isOn = letter == grade
                    Text(letter)
                        .larderFont(.label)
                        .foregroundStyle(colors(letter).ink)
                        .frame(maxWidth: .infinity, minHeight: isOn ? 36 : 28)
                        .background(colors(letter).fill, in: RoundedRectangle(cornerRadius: LarderRadius.sm))
                        .overlay {
                            if isOn {
                                RoundedRectangle(cornerRadius: LarderRadius.sm)
                                    .stroke(LarderColor.onNutriPlate, lineWidth: 2)
                            }
                        }
                }
            }
            .padding(.vertical, LarderSpace.s1)
            .padding(.horizontal, LarderSpace.s2)
            .background(LarderColor.nutriPlate, in: RoundedRectangle(cornerRadius: LarderRadius.md))
            ScaleCaption(L10n.detailNutriCaption(grade))
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(L10n.detailNutriCaption(grade))
    }
}

/// One marker on a labelled scale: processing isn't a traffic light.
private struct NovaScale: View {
    let group: Int

    private var word: String {
        switch group {
        case 1: return L10n.detailNova1
        case 2: return L10n.detailNova2
        case 3: return L10n.detailNova3
        default: return L10n.detailNova4
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: LarderSpace.s1) {
            HStack(spacing: 2) {
                ForEach(1...4, id: \.self) { number in
                    let isOn = number == group
                    Text("\(number)")
                        .larderFont(isOn ? .label : .caption)
                        .foregroundStyle(isOn ? LarderColor.onPrimary : LarderColor.onSurfaceVariant)
                        .frame(maxWidth: .infinity, minHeight: 28)
                        .background(
                            isOn ? LarderColor.primary : LarderColor.surface,
                            in: RoundedRectangle(cornerRadius: LarderRadius.sm)
                        )
                        .overlay {
                            if !isOn {
                                RoundedRectangle(cornerRadius: LarderRadius.sm)
                                    .strokeBorder(LarderColor.outlineVariant, lineWidth: 1)
                            }
                        }
                }
            }
            ScaleCaption(L10n.detailNovaCaption(group, word))
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(L10n.detailNovaDescription(group, word))
    }
}

private struct ScaleCaption: View {
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text).larderFont(.caption).foregroundStyle(LarderColor.onSurfaceVariant)
    }
}

private struct DetailSection<Content: View>: View {
    let title: String
    let content: Content

    init(_ title: String, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: LarderSpace.s1) {
            Text(title)
                .larderFont(.label)
                .foregroundStyle(LarderColor.onSurfaceVariant)
                .accessibilityAddTraits(.isHeader)
            content
        }
    }
}

private struct DetailBody: View {
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .larderFont(.bodySm)
            .foregroundStyle(LarderColor.onSurface)
            .fixedSize(horizontal: false, vertical: true)
    }
}
