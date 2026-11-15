import SwiftUI

/// Everything the product detail can do to the item on show.
struct ProductDetailActions {
    let useOne: () -> Void
    let edit: () -> Void
    let moveToShoppingList: () -> Void
    let delete: () -> Void
}

/// The product detail route: a sheet on phones, the right-hand pane on expanded Home. The actions
/// stay put while the content scrolls, because long ingredients are never cut short; at the
/// accessibility text sizes they scroll with it instead, where pinned they would fill the sheet.
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

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private var isPane: Bool { onClose == nil }
    private var actionsScroll: Bool { dynamicTypeSize.isAccessibilitySize }

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
                    if OpenFoodFacts.isSource(imageUrl: item.imageUrl, nutrition: nutrition) {
                        // Open Food Facts' terms: credit it and name the licences, here as a
                        // link to the product's page there (the site without a barcode).
                        Link(destination: OpenFoodFacts.pageURL(barcode: item.barcode)) {
                            Text(L10n.detailCredit)
                                .larderFont(.caption)
                                .underline()
                                .foregroundStyle(LarderColor.onSurfaceVariant)
                                .multilineTextAlignment(.leading)
                                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                        }
                    }
                    if actionsScroll {
                        buttons.padding(.top, LarderSpace.s2)
                    }
                }
                .padding(LarderSpace.s4)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            if !actionsScroll {
                LarderDivider()
                buttons
                    .padding(.horizontal, LarderSpace.s4)
                    .padding(.top, LarderSpace.s3)
                    .padding(.bottom, isPane ? 0 : LarderSpace.s4)
            }
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

    /// The icons go at the three largest text sizes, where one beside a label leaves a long word
    /// (Russian "Использовать") too little room to stay whole.
    private func icon(_ name: LarderIconName) -> LarderIconName? {
        dynamicTypeSize >= .accessibility3 ? nil : name
    }

    /// Use one leads, the one filled button; Delete stays a quiet text button below the rest.
    private var buttons: some View {
        VStack(spacing: LarderSpace.s2) {
            DetailActionRows {
                LarderButton(L10n.useOneButton, icon: icon(.circleMinus), fullWidth: true, action: actions.useOne)
                LarderButton(
                    L10n.detailEdit,
                    icon: icon(.pencil),
                    variant: .outline,
                    fullWidth: true,
                    action: actions.edit
                )
                LarderButton(
                    L10n.detailToShoppingList,
                    icon: icon(.shoppingBasket),
                    variant: .outline,
                    fullWidth: true,
                    action: actions.moveToShoppingList
                )
            }
            LarderButton(L10n.swipeDelete, icon: icon(.trash2), variant: .dangerText, action: actions.delete)
        }
    }
}

/// Use one, Edit and To shopping list, laid out so that no label wraps inside a word or is cut
/// short: one row when all three fit at their natural width (Use one taking the spare width);
/// otherwise Use one alone above Edit and To shopping list sharing a row (equal halves when both
/// fit in one); otherwise Use one and Edit above To shopping list; otherwise one per row, where
/// a label still too long for the whole width (the largest text sizes) wraps between its words. A shared row only ever gets labels that fit on
/// one line. Never wider than it is offered: `ViewThatFits` reported more when no option fitted,
/// which widened the whole sheet.
private struct DetailActionRows: Layout {
    private let gap = LarderSpace.s2

    private func frames(_ subviews: Subviews, width proposed: CGFloat?) -> [CGRect] {
        guard subviews.count == 3 else { return [] }
        let natural = subviews.map { $0.sizeThatFits(.unspecified) }
        let widths = natural.map(\.width)
        let width = proposed ?? (widths.reduce(0, +) + 2 * gap)
        func height(_ index: Int, _ itsWidth: CGFloat) -> CGFloat {
            subviews[index].sizeThatFits(ProposedViewSize(width: itsWidth, height: nil)).height
        }

        if widths.reduce(0, +) + 2 * gap <= width {
            let lead = width - widths[1] - widths[2] - 2 * gap
            let rowHeight = natural.map(\.height).max() ?? 0
            return [
                CGRect(x: 0, y: 0, width: lead, height: rowHeight),
                CGRect(x: lead + gap, y: 0, width: widths[1], height: rowHeight),
                CGRect(x: lead + gap + widths[1] + gap, y: 0, width: widths[2], height: rowHeight)
            ]
        }

        let leadHeight = height(0, width)
        let pairSpare = width - widths[1] - widths[2] - gap
        if widths[0] <= width, pairSpare >= 0 {
            let half = (width - gap) / 2
            let pair: (CGFloat, CGFloat) = max(widths[1], widths[2]) <= half
                ? (half, half)
                : (widths[1] + pairSpare / 2, widths[2] + pairSpare / 2)
            let y = leadHeight + gap
            let rowHeight = max(height(1, pair.0), height(2, pair.1))
            return [
                CGRect(x: 0, y: 0, width: width, height: leadHeight),
                CGRect(x: 0, y: y, width: pair.0, height: rowHeight),
                CGRect(x: pair.0 + gap, y: y, width: pair.1, height: rowHeight)
            ]
        }

        // The pair is too wide for one row, but Use one and Edit fit together: two rows rather
        // than three, which a small phone needs to keep some of the sheet in view.
        if widths[0] + widths[1] + gap <= width, widths[2] <= width {
            let lead = width - widths[1] - gap
            let rowHeight = max(natural[0].height, natural[1].height)
            return [
                CGRect(x: 0, y: 0, width: lead, height: rowHeight),
                CGRect(x: lead + gap, y: 0, width: widths[1], height: rowHeight),
                CGRect(x: 0, y: rowHeight + gap, width: width, height: height(2, width))
            ]
        }

        var y: CGFloat = 0
        return (0..<3).map { index in
            let rowHeight = index == 0 ? leadHeight : height(index, width)
            defer { y += rowHeight + gap }
            return CGRect(x: 0, y: y, width: width, height: rowHeight)
        }
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let frames = frames(subviews, width: proposal.width)
        return CGSize(
            width: proposal.width ?? frames.map(\.maxX).max() ?? 0,
            height: frames.map(\.maxY).max() ?? 0
        )
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for (subview, frame) in zip(subviews, frames(subviews, width: bounds.width)) {
            subview.place(
                at: CGPoint(x: bounds.minX + frame.minX, y: bounds.minY + frame.minY),
                proposal: ProposedViewSize(width: frame.width, height: frame.height)
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
