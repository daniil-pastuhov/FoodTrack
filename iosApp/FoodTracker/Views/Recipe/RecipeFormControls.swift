import SwiftUI

// Pieces the Recipe tab's quick form and Recipe settings share, built from Larder components.

extension RecipeViewModel {
    /// A binding to one configuration field, saved as it changes.
    func binding<Value>(_ keyPath: WritableKeyPath<RecipeConfig, Value>) -> Binding<Value> {
        Binding(
            get: { self.config[keyPath: keyPath] },
            set: { value in self.updateConfig { $0[keyPath: keyPath] = value } }
        )
    }
}

/// The small grey label over a control: "Difficulty".
struct RecipeFormLabel: View {
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .larderFont(.label)
            .foregroundStyle(LarderColor.onSurfaceVariant)
            .accessibilityAddTraits(.isHeader)
    }
}

/// A label over a control.
struct RecipeFormField<Control: View>: View {
    let label: String
    @ViewBuilder let control: Control

    var body: some View {
        VStack(alignment: .leading, spacing: LarderSpace.s2) {
            RecipeFormLabel(label)
            control
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// White card holding a few controls, as the design's form groups.
struct RecipeFormCard<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        LarderGroup {
            VStack(alignment: .leading, spacing: LarderSpace.s3) {
                content
            }
            .padding(LarderSpace.s4)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

struct RecipeDifficultyField: View {
    @Binding var selection: RecipeDifficulty

    var body: some View {
        RecipeFormField(label: L10n.recipeLabelDifficulty) {
            LarderSegmented(selection: $selection, options: RecipeDifficulty.allCases, fill: true) {
                L10n.recipeDifficultyName($0)
            }
        }
    }
}

struct RecipeServingsField: View {
    @Binding var servings: Int

    var body: some View {
        HStack(spacing: LarderSpace.s3) {
            RecipeFormLabel(L10n.recipeLabelServings)
                .frame(maxWidth: .infinity, alignment: .leading)
            LarderStepper(value: $servings, range: RecipeConfig.servingsRange, label: L10n.recipeLabelServings)
        }
        .frame(minHeight: LarderSize.touch)
    }
}

/// Total cooking time: a two-handle slider from `RecipeTime.floor` to `RecipeTime.ceiling` in
/// steps of `RecipeTime.step`, the range written beside the label ("20–45 min", "Any"). A handle
/// at its end is no bound (nil). Each handle is its own VoiceOver element, adjusted a step at a
/// time by swiping up and down, so dragging is never needed. Android: `TimeControl`.
struct RecipeTimeField: View {
    @Binding var minMinutes: Int?
    @Binding var maxMinutes: Int?

    /// While a handle is dragged: shown at once, stored when it is let go.
    @State private var dragging: (low: Int, high: Int)?

    private var low: Int { dragging?.low ?? minMinutes ?? RecipeTime.floor }
    private var high: Int { dragging?.high ?? maxMinutes ?? RecipeTime.ceiling }
    private static func bound(_ value: Int, none: Int) -> Int? { value == none ? nil : value }

    var body: some View {
        let lowBound = Self.bound(low, none: RecipeTime.floor)
        let highBound = Self.bound(high, none: RecipeTime.ceiling)
        VStack(alignment: .leading, spacing: LarderSpace.s1) {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: LarderSpace.s3) {
                    RecipeFormLabel(L10n.recipeLabelTime)
                    Spacer(minLength: 0)
                    rangeText(lowBound, highBound)
                }
                VStack(alignment: .leading, spacing: LarderSpace.s1) {
                    RecipeFormLabel(L10n.recipeLabelTime)
                    rangeText(lowBound, highBound)
                }
            }
            RecipeRangeSlider(
                low: low,
                high: high,
                onDrag: { dragging = ($0, $1) },
                onCommit: { newLow, newHigh in
                    dragging = nil
                    minMinutes = Self.bound(newLow, none: RecipeTime.floor)
                    maxMinutes = Self.bound(newHigh, none: RecipeTime.ceiling)
                }
            )
            if lowBound != nil {
                Text(L10n.recipeTimeGuideHint)
                    .larderFont(.caption)
                    .foregroundStyle(LarderColor.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func rangeText(_ low: Int?, _ high: Int?) -> some View {
        Text(L10n.recipeTimeRange(min: low, max: high))
            .larderFont(.label)
            .foregroundStyle(LarderColor.onSurface)
            .monospacedDigit()
    }
}

/// Two handles on one track, in the Larder colours. Laid out left to right and mirrored by hand
/// in right-to-left languages, so the drag arithmetic stays the same either way.
struct RecipeRangeSlider: View {
    let low: Int
    let high: Int
    let onDrag: (Int, Int) -> Void
    let onCommit: (Int, Int) -> Void

    @Environment(\.layoutDirection) private var direction
    private let thumb: CGFloat = 26
    /// The handle the current drag moves: true the lower, false the upper, nil between drags.
    @State private var grabbedLow: Bool?

    /// Where values sit on a track `size` wide, mirrored in right-to-left layouts.
    private struct Track {
        let width: CGFloat
        let inset: CGFloat
        let rtl: Bool
        static let span = CGFloat(RecipeTime.ceiling - RecipeTime.floor)

        func x(_ value: Int) -> CGFloat {
            let fraction = CGFloat(value - RecipeTime.floor) / Self.span
            return inset + (rtl ? 1 - fraction : fraction) * width
        }

        func value(at location: CGFloat) -> Int {
            var fraction = min(max((location - inset) / width, 0), 1)
            if rtl { fraction = 1 - fraction }
            return RecipeTime.snap(RecipeTime.floor + Int((fraction * Self.span).rounded()))
        }
    }

    var body: some View {
        GeometryReader { geometry in
            content(Track(width: max(geometry.size.width - thumb, 1), inset: thumb / 2, rtl: direction == .rightToLeft), centerY: geometry.size.height / 2)
                .environment(\.layoutDirection, .leftToRight)
        }
        .frame(height: LarderSize.touch)
    }

    private func content(_ track: Track, centerY: CGFloat) -> some View {
        let lowX = track.x(low)
        let highX = track.x(high)
        return ZStack(alignment: .topLeading) {
            Capsule()
                .fill(LarderColor.surfaceContainerHigh)
                .frame(width: track.width, height: 4)
                .position(x: track.inset + track.width / 2, y: centerY)
            Capsule()
                .fill(LarderColor.primary)
                .frame(width: max(abs(highX - lowX), 4), height: 4)
                .position(x: (lowX + highX) / 2, y: centerY)
            handle(isLow: true)
                .position(x: lowX, y: centerY)
            handle(isLow: false)
                .position(x: highX, y: centerY)
        }
        .contentShape(Rectangle())
        .gesture(drag(track, lowX: lowX, highX: highX))
    }

    /// One drag for the whole track, as Material's RangeSlider: it moves the handle nearer to
    /// where it starts. When the two handles sit on each other (both at the top end, say), the
    /// first movement decides: towards shorter moves the lower one, towards longer the upper one.
    private func drag(_ track: Track, lowX: CGFloat, highX: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { gesture in
                guard let isLow = grabbedLow ?? pick(track, gesture, lowX: lowX, highX: highX) else { return }
                grabbedLow = isLow
                let (newLow, newHigh) = values(track, gesture.location.x, isLow: isLow)
                onDrag(newLow, newHigh)
            }
            .onEnded { gesture in
                let isLow = grabbedLow ?? pick(track, gesture, lowX: lowX, highX: highX) ?? (abs(gesture.location.x - lowX) < abs(gesture.location.x - highX))
                grabbedLow = nil
                let (newLow, newHigh) = values(track, gesture.location.x, isLow: isLow)
                onCommit(newLow, newHigh)
            }
    }

    /// Which handle a drag moves, or nil while that can't be told yet (overlapping handles, no
    /// movement so far).
    private func pick(_ track: Track, _ gesture: DragGesture.Value, lowX: CGFloat, highX: CGFloat) -> Bool? {
        let start = gesture.startLocation.x
        if abs(lowX - highX) >= 1 { return abs(start - lowX) <= abs(start - highX) }
        guard abs(gesture.translation.width) >= 2 else { return nil }
        return track.value(at: gesture.location.x) < track.value(at: start)
            || (track.value(at: gesture.location.x) == track.value(at: start) && low == RecipeTime.ceiling)
    }

    private func values(_ track: Track, _ x: CGFloat, isLow: Bool) -> (Int, Int) {
        let value = track.value(at: x)
        return isLow ? (min(value, high), high) : (low, max(value, low))
    }

    /// A 26pt knob on a 48pt target. VoiceOver adjusts it one step per swipe, never past the
    /// other handle.
    private func handle(isLow: Bool) -> some View {
        let label = isLow ? L10n.recipeTimeShortest : L10n.recipeTimeLongest
        let value = isLow ? low : high
        let atEnd = isLow ? value == RecipeTime.floor : value == RecipeTime.ceiling
        let spoken = L10n.recipeTimeSpoken(atEnd ? nil : value, lowEnd: isLow)
        return Circle()
            .fill(LarderColor.primary)
            .frame(width: thumb, height: thumb)
            .overlay { Circle().strokeBorder(LarderColor.onPrimary, lineWidth: 2) }
            .shadow(color: .black.opacity(0.15), radius: 2, y: 1)
            .frame(width: LarderSize.touch, height: LarderSize.touch)
            .contentShape(Rectangle())
            .accessibilityElement()
            .accessibilityLabel(label)
            .accessibilityValue(spoken)
            .accessibilityAdjustableAction { direction in
                adjust(isLow: isLow, by: direction == .increment ? RecipeTime.step : direction == .decrement ? -RecipeTime.step : 0)
            }
    }

    private func adjust(isLow: Bool, by step: Int) {
        guard step != 0 else { return }
        if isLow {
            onCommit(min(max(low + step, RecipeTime.floor), high), high)
        } else {
            onCommit(low, max(min(high + step, RecipeTime.ceiling), low))
        }
    }
}

/// A multi-choice row whose first chip ("Any", "None") is the empty set: it clears the others,
/// another chip toggles itself and turns the first off, and the last one off turns it back on.
/// Android: `MultiChipRow`.
struct RecipeMultiChips<Option: Hashable>: View {
    @Binding var selection: Set<Option>
    let options: [Option]
    let emptyTitle: String
    let title: (Option) -> String

    var body: some View {
        FlowLayout(spacing: LarderSpace.s2, rowSpacing: LarderChip.wrapRowSpacing) {
            LarderChip(emptyTitle, isSelected: selection.isEmpty) {
                selection = RecipeChoices.toggled(selection, nil)
            }
            ForEach(options, id: \.self) { option in
                LarderChip(title(option), isSelected: selection.contains(option)) {
                    selection = RecipeChoices.toggled(selection, option)
                }
            }
        }
        .padding(.vertical, LarderSpace.s2)
    }
}

/// One of a few chips, wrapping onto more rows as needed.
struct RecipeChoiceChips<Option: Hashable>: View {
    @Binding var selection: Option
    let options: [Option]
    let title: (Option) -> String

    var body: some View {
        FlowLayout(spacing: LarderSpace.s2, rowSpacing: LarderChip.wrapRowSpacing) {
            ForEach(options, id: \.self) { option in
                LarderChip(title(option), isSelected: option == selection) {
                    selection = option
                }
            }
        }
        .padding(.vertical, LarderSpace.s2)
    }
}

/// The form's primary action: the first button of its button group, filled and full width like a
/// primary `LarderButton`, with what it takes in a pill beside the label ("1 recipe", "Free") so
/// it reads as the cost before it is tapped. `subtitle` is a quieter line inside the button under
/// the label ("Uses your taste profile"). The label and the subtitle take at most two lines each
/// beside the icon and the pill, which never wraps or shrinks; when they need more, or the text is
/// very large, the icon goes and the label, subtitle and pill stack. The shape is a rounded
/// rectangle of radius 28: a pill at its usual height, never an oval when taller. Android:
/// `LarderPrimaryAction`.
struct RecipeGoButton: View {
    let title: String
    let icon: LarderIconName
    var cost: String? = nil
    var subtitle: String? = nil
    let action: () -> Void

    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    static let radius: CGFloat = 28
    static var shape: RoundedRectangle { RoundedRectangle(cornerRadius: radius) }

    var body: some View {
        Button(action: action) {
            RecipeGoLayout(
                alwaysStacked: dynamicTypeSize >= .accessibility1,
                hasSubtitle: subtitle != nil,
                hasCost: cost != nil
            ) {
                // Shrinks to nothing (and is clipped) when the layout stacks without it.
                LarderIcon(icon)
                    .frame(minWidth: 0, maxWidth: LarderIcon.Size.medium.points, minHeight: 0, maxHeight: LarderIcon.Size.medium.points)
                    .clipped()
                Text(title)
                    .larderFont(.label)
                    .multilineTextAlignment(.center)
                if let subtitle {
                    Text(subtitle)
                        .larderFont(.caption)
                        .multilineTextAlignment(.center)
                        .opacity(0.8)
                }
                if let cost {
                    Text(cost)
                        .larderFont(.caption)
                        .lineLimit(1)
                        .padding(.vertical, 2)
                        .padding(.horizontal, LarderSpace.s2)
                        .overlay { Capsule().strokeBorder(lineWidth: 1) }
                        .fixedSize()
                }
            }
            .foregroundStyle(isEnabled ? LarderColor.onPrimary : LarderColor.onSurface.opacity(0.38))
            .padding(.vertical, LarderSpace.s2)
            .padding(.horizontal, LarderSpace.s5)
            .frame(maxWidth: .infinity, minHeight: LarderSize.control)
            .background(isEnabled ? LarderColor.primary : LarderColor.onSurface.opacity(0.12), in: Self.shape)
            .contentShape(Self.shape)
        }
        .buttonStyle(RecipeGoButtonStyle())
        // One element: the label, what it takes, then the subtitle.
        .accessibilityLabel([title, cost, subtitle].compactMap { $0 }.joined(separator: ", "))
    }
}

private struct RecipeGoButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .overlay {
                RecipeGoButton.shape.fill(LarderColor.onPrimary.opacity(configuration.isPressed ? 0.14 : 0))
            }
    }
}

/// The primary's content: icon, label (and subtitle) and pill in a row, centred, while the label
/// and the subtitle fit in two lines each there; otherwise (or always, at very large text) the
/// label, the subtitle and the pill stacked, centred, without the icon. Subviews in order: icon,
/// title, subtitle (when `hasSubtitle`), pill (when `hasCost`).
private struct RecipeGoLayout: Layout {
    let alwaysStacked: Bool
    let hasSubtitle: Bool
    let hasCost: Bool

    private let iconGap = LarderSpace.s2
    private let pillGap = LarderSpace.s3
    private let subtitleGap: CGFloat = 2
    private let stackGap = LarderSpace.s1

    private struct Parts {
        let icon: LayoutSubview
        let title: LayoutSubview
        let subtitle: LayoutSubview?
        let pill: LayoutSubview?
    }

    private func parts(_ subviews: Subviews) -> Parts {
        var index = 2
        let subtitle = hasSubtitle ? subviews[index] : nil
        if hasSubtitle { index += 1 }
        return Parts(icon: subviews[0], title: subviews[1], subtitle: subtitle, pill: hasCost ? subviews[index] : nil)
    }

    private struct Plan {
        var stacked: Bool
        var icon: CGSize
        var title: CGSize
        var subtitle: CGSize
        var pill: CGSize
        var labelWidth: CGFloat
    }

    /// At most two lines: a wrapped text's height against its one-line height.
    private func fitsTwoLines(_ view: LayoutSubview, width: CGFloat) -> (Bool, CGSize) {
        let line = view.sizeThatFits(.unspecified).height
        let size = view.sizeThatFits(ProposedViewSize(width: width, height: nil))
        return (size.height <= line * 2.5, size)
    }

    private func plan(width: CGFloat?, _ p: Parts) -> Plan {
        let pill = p.pill?.sizeThatFits(.unspecified) ?? .zero
        let pillSpace = p.pill == nil ? 0 : pill.width + pillGap
        guard let width else {
            // Ideal: everything on one line.
            let icon = p.icon.sizeThatFits(.unspecified)
            let title = p.title.sizeThatFits(.unspecified)
            let subtitle = p.subtitle?.sizeThatFits(.unspecified) ?? .zero
            return Plan(stacked: alwaysStacked, icon: icon, title: title, subtitle: subtitle, pill: pill, labelWidth: max(title.width, subtitle.width))
        }
        if !alwaysStacked {
            let icon = p.icon.sizeThatFits(.unspecified)
            let labelWidth = width - icon.width - iconGap - pillSpace
            if labelWidth >= 40 {
                let (titleFits, title) = fitsTwoLines(p.title, width: labelWidth)
                let (subtitleFits, subtitle) = p.subtitle.map { fitsTwoLines($0, width: labelWidth) } ?? (true, .zero)
                if titleFits && subtitleFits {
                    return Plan(stacked: false, icon: icon, title: title, subtitle: subtitle, pill: pill, labelWidth: max(title.width, subtitle.width))
                }
            }
        }
        let title = p.title.sizeThatFits(ProposedViewSize(width: width, height: nil))
        let subtitle = p.subtitle?.sizeThatFits(ProposedViewSize(width: width, height: nil)) ?? .zero
        return Plan(stacked: true, icon: .zero, title: title, subtitle: subtitle, pill: pill, labelWidth: max(title.width, subtitle.width))
    }

    private func labelHeight(_ plan: Plan) -> CGFloat {
        plan.title.height + (hasSubtitle ? subtitleGap + plan.subtitle.height : 0)
    }

    private func size(_ plan: Plan) -> CGSize {
        if plan.stacked {
            let width = max(plan.labelWidth, plan.pill.width)
            return CGSize(width: width, height: labelHeight(plan) + (hasCost ? stackGap + plan.pill.height : 0))
        }
        let width = plan.icon.width + iconGap + plan.labelWidth + (hasCost ? pillGap + plan.pill.width : 0)
        return CGSize(width: width, height: max(plan.icon.height, labelHeight(plan), plan.pill.height))
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let plan = plan(width: proposal.width, parts(subviews))
        let natural = size(plan)
        return CGSize(width: proposal.width ?? natural.width, height: natural.height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let p = parts(subviews)
        let plan = plan(width: bounds.width, p)
        let total = size(plan)
        if plan.stacked {
            p.icon.place(at: CGPoint(x: bounds.midX, y: bounds.minY), anchor: .top, proposal: .zero)
            var y = bounds.midY - total.height / 2
            p.title.place(at: CGPoint(x: bounds.midX, y: y), anchor: .top, proposal: ProposedViewSize(width: bounds.width, height: nil))
            y += plan.title.height
            if let subtitle = p.subtitle {
                y += subtitleGap
                subtitle.place(at: CGPoint(x: bounds.midX, y: y), anchor: .top, proposal: ProposedViewSize(width: bounds.width, height: nil))
                y += plan.subtitle.height
            }
            if let pill = p.pill {
                pill.place(at: CGPoint(x: bounds.midX, y: y + stackGap), anchor: .top, proposal: .unspecified)
            }
            return
        }
        var x = bounds.midX - total.width / 2
        let midY = bounds.midY
        p.icon.place(at: CGPoint(x: x, y: midY), anchor: .leading, proposal: .unspecified)
        x += plan.icon.width + iconGap
        let labelMidX = x + plan.labelWidth / 2
        var y = midY - labelHeight(plan) / 2
        p.title.place(at: CGPoint(x: labelMidX, y: y), anchor: .top, proposal: ProposedViewSize(width: plan.labelWidth, height: nil))
        y += plan.title.height
        if let subtitle = p.subtitle {
            subtitle.place(at: CGPoint(x: labelMidX, y: y + subtitleGap), anchor: .top, proposal: ProposedViewSize(width: plan.labelWidth, height: nil))
        }
        x += plan.labelWidth
        if let pill = p.pill {
            pill.place(at: CGPoint(x: x + pillGap, y: midY), anchor: .leading, proposal: .unspecified)
        }
    }
}

/// "Other ways": the form's other routes, one card of rows under the primary (and its caption).
/// A small label over the group; each row is one target. The icon tiles go at very large text,
/// and at large text on a narrow screen, so the words keep the room. Android: `RecipeOtherWays`.
struct RecipeOtherWays<Rows: View>: View {
    @ViewBuilder let rows: (_ showsTiles: Bool) -> Rows

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var width: CGFloat = 400

    private var showsTiles: Bool {
        if dynamicTypeSize >= .accessibility1 { return false }
        if dynamicTypeSize >= .xxxLarge && width < 360 { return false }
        return true
    }

    var body: some View {
        VStack(alignment: .leading, spacing: LarderSpace.s2) {
            Text(L10n.recipeOtherWays)
                .larderFont(.label)
                .foregroundStyle(LarderColor.onSurfaceVariant)
                .accessibilityAddTraits(.isHeader)
                .padding(.horizontal, LarderSpace.s1)
            LarderGroup { rows(showsTiles) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
    }
}

/// One row of "Other ways": a 40pt icon tile, the title and a supporting line (neither cut
/// short), a chevron where it opens a sheet. The whole row is the target, at least 56pt tall.
struct RecipeWayRow<Tile: View>: View {
    let title: String
    var titleColor: Color = LarderColor.onSurface
    /// "Free · …": the word in bold, in the accent colour.
    var free: String? = nil
    var supporting: String? = nil
    var chevron = false
    var showsTile = true
    var action: (() -> Void)? = nil
    @ViewBuilder let tile: () -> Tile

    @Environment(\.isEnabled) private var isEnabled

    private var supportingText: Text? {
        switch (free, supporting) {
        case let (free?, supporting?):
            return Text(free).fontWeight(.semibold).foregroundColor(LarderColor.primary) + Text(" · " + supporting)
        case let (free?, nil):
            return Text(free).fontWeight(.semibold).foregroundColor(LarderColor.primary)
        case let (nil, supporting?):
            return Text(supporting)
        default:
            return nil
        }
    }

    private var row: some View {
        HStack(spacing: LarderSpace.s3) {
            if showsTile {
                tile()
                    .frame(width: 40, height: 40)
                    .accessibilityHidden(true)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .larderFont(.body)
                    .foregroundStyle(titleColor)
                    .fixedSize(horizontal: false, vertical: true)
                if let supportingText {
                    supportingText
                        .larderFont(.bodySm)
                        .foregroundStyle(LarderColor.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if chevron {
                LarderIcon(.chevronRight)
                    .foregroundStyle(LarderColor.onSurfaceVariant)
                    .accessibilityHidden(true)
            }
        }
        .padding(.vertical, LarderSpace.s2)
        .padding(.horizontal, LarderSpace.s4)
        .frame(minHeight: 56)
        .contentShape(Rectangle())
        .opacity(isEnabled ? 1 : 0.38)
    }

    var body: some View {
        if let action {
            Button(action: action) { row }
                .buttonStyle(.plain)
                .accessibilityElement(children: .combine)
        } else {
            row.accessibilityElement(children: .combine)
        }
    }
}

extension RecipeWayRow where Tile == RecipeWayTile {
    init(
        _ title: String,
        icon: LarderIconName,
        free: String? = nil,
        supporting: String? = nil,
        chevron: Bool = false,
        showsTile: Bool = true,
        action: (() -> Void)? = nil
    ) {
        self.init(title: title, free: free, supporting: supporting, chevron: chevron, showsTile: showsTile, action: action) {
            RecipeWayTile(icon: icon)
        }
    }
}

/// A row's 40pt tile: the route's icon on a quiet square.
struct RecipeWayTile: View {
    let icon: LarderIconName
    var background: Color = LarderColor.surfaceContainer
    var foreground: Color = LarderColor.onSurfaceVariant

    var body: some View {
        LarderIcon(icon, size: .custom(20))
            .foregroundStyle(foreground)
            .frame(width: 40, height: 40)
            .background(background, in: RoundedRectangle(cornerRadius: LarderRadius.md))
    }
}

/// "How recipes work": one short sheet, shown by itself the first time the tab opens and from
/// Recipe settings after that. It names only the ways this device and build really offer, shows a
/// price only once the store has given one, and its one way out is just "Got it"; "See recipe
/// packs" is there only where buying is offered. Opening it calls nothing: no account, no App
/// Check token, nothing from our server. Android: `RecipeIntroSheet`.
struct RecipeIntroSheet: View {
    @ObservedObject var model: RecipeViewModel

    private var credits: RecipeCredits { model.credits }
    /// The phone's model is there, ready or still downloading.
    private var onDevice: Bool { model.routes.onDevice || model.routes.onDevicePreparing }
    /// The free recipes' offer as it was when the sheet opened: it doesn't change while open.
    @State private var welcomeHint: WelcomeHint?

    init(model: RecipeViewModel) {
        self.model = model
        _welcomeHint = State(initialValue: model.credits.welcomeHint)
    }

    var body: some View {
        LarderSheet(L10n.recipeIntroTitle) {
            Text(L10n.recipeIntroMessage)
                .larderFont(.body)
                .foregroundStyle(LarderColor.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
            LarderGroup {
                OnboardingRow(.share2, title: L10n.recipeIntroPromptTitle, supporting: L10n.recipeIntroPromptMessage)
                if onDevice {
                    OnboardingRow(.smartphone, title: L10n.recipeIntroDeviceTitle, supporting: L10n.recipeIntroDeviceMessage)
                }
                if credits.isPaidRouteAvailable {
                    if let welcomeHint {
                        WelcomeIntroPacksRow(
                            title: L10n.recipeIntroPacksTitle,
                            message: L10n.recipeIntroPacksMessage(
                                credits.costs, menuSets: credits.serverTakesMenuDays, lowestPrice: credits.lowestPackPrice, freeStays: false
                            ),
                            offer: L10n.welcomeIntro(welcomeHint),
                            freeStays: L10n.recipeIntroFreeStays
                        )
                    } else {
                        OnboardingRow(
                            .sparkles,
                            title: L10n.recipeIntroPacksTitle,
                            supporting: L10n.recipeIntroPacksMessage(credits.costs, menuSets: credits.serverTakesMenuDays, lowestPrice: credits.lowestPackPrice)
                        )
                    }
                    // One sentence per picture level our server prices; nothing when it prices none.
                    if credits.serverTakesRecipeImage, let pictures = L10n.recipeImageCostLines(credits.costs) {
                        OnboardingRow(.image, title: L10n.recipeImageTitle, supporting: pictures)
                    }
                }
            }
            Text(L10n.recipeIntroAgain)
                .larderFont(.bodySm)
                .foregroundStyle(LarderColor.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
            VStack(spacing: LarderSpace.s1) {
                LarderButton(L10n.recipeIntroStart, fullWidth: true) { model.dismissIntro() }
                // With an offer, the second action is the offer: the packs stay one tap away from
                // the count and Buy recipes.
                if let welcomeHint {
                    LarderButton(L10n.welcomeOffer(welcomeHint), icon: .gift, variant: .text, fullWidth: true) {
                        model.introToWelcome()
                    }
                } else if credits.canBuy {
                    LarderButton(L10n.recipeIntroPacksAction, variant: .text, fullWidth: true) { model.introToPacks() }
                }
            }
            .padding(.top, LarderSpace.s2)
        }
    }
}

private struct FormPreview: View {
    @State private var difficulty = RecipeDifficulty.easy
    @State private var servings = 2
    @State private var minutes: Int? = 30
    @State private var minimum: Int? = 20

    var body: some View {
        ScrollView {
            VStack(spacing: LarderSpace.s4) {
                RecipeFormCard {
                    RecipeDifficultyField(selection: $difficulty)
                    RecipeServingsField(servings: $servings)
                    RecipeTimeField(minMinutes: $minimum, maxMinutes: $minutes)
                }
                RecipeGoButton(title: "Generate on this phone", icon: .smartphone, cost: "Free") {}
                RecipeGoButton(title: "Generate recipe", icon: .sparkles, cost: "1 recipe", subtitle: "Uses your taste profile") {}
                RecipeGoButton(title: "Share prompt", icon: .share2) {}.disabled(true)
                RecipeOtherWays { tiles in
                    RecipeWayRow("Generate on this phone", icon: .smartphone, free: "Free", supporting: "Still weak at cooking. Nothing leaves this phone.", showsTile: tiles) {}
                    RecipeWayRow("Buy recipes", icon: .ticket, chevron: true, showsTile: tiles) {}
                }
            }
            .padding(LarderSpace.s4)
        }
        .background(LarderColor.background)
    }
}

#Preview("Recipe form") { FormPreview() }
#Preview("Recipe form, dark") { FormPreview().preferredColorScheme(.dark) }
#Preview("Recipe form, Russian") { FormPreview().environment(\.locale, Locale(identifier: "ru")) }
