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

/// The tab's one primary action, pinned above the pill where Scan sits on Home: a wide block with
/// the cost said on the right ("Free"), so it reads as what it takes before it is tapped.
struct RecipeGoButton: View {
    let title: String
    let icon: LarderIconName
    var cost: String? = nil
    /// As wide as its label, like Scan on Home, for an action with no cost (the shared prompt
    /// pinned alone), and drawn to Scan's metrics (`ScanFab`: 56pt high, 16pt side padding, the
    /// large icon, 8pt to the label); the label still wraps when large text needs it.
    var compact = false
    let action: () -> Void

    @Environment(\.isEnabled) private var isEnabled

    var body: some View {
        Button(action: action) {
            HStack(spacing: compact ? LarderSpace.s2 : LarderSpace.s3) {
                LarderIcon(icon, size: compact ? .large : .medium)
                Text(title)
                    .larderFont(.label)
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: compact ? nil : .infinity, alignment: .leading)
                if let cost {
                    Text(cost)
                        .larderFont(.caption)
                        .padding(.vertical, 2)
                        .padding(.horizontal, LarderSpace.s2)
                        .overlay { Capsule().strokeBorder(lineWidth: 1) }
                        .fixedSize()
                }
            }
            .foregroundStyle(isEnabled ? LarderColor.onPrimary : LarderColor.onSurfaceVariant)
            .padding(.vertical, LarderSpace.s2)
            .padding(.horizontal, compact ? LarderSpace.s4 : LarderSpace.s5)
            .frame(maxWidth: compact ? nil : .infinity, minHeight: ScanFab.minHeight)
            .background(
                isEnabled ? LarderColor.primary : LarderColor.surfaceContainerHigh,
                in: RoundedRectangle(cornerRadius: LarderRadius.lg)
            )
            .contentShape(RoundedRectangle(cornerRadius: LarderRadius.lg))
        }
        .buttonStyle(RecipeGoButtonStyle())
        .larderElevation(isEnabled ? .level2 : .level1)
        .accessibilityLabel(cost.map { "\(title), \($0)" } ?? title)
    }
}

private struct RecipeGoButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .overlay {
                RoundedRectangle(cornerRadius: LarderRadius.lg)
                    .fill(LarderColor.onPrimary.opacity(configuration.isPressed ? 0.14 : 0))
            }
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
                    OnboardingRow(
                        .sparkles,
                        title: L10n.recipeIntroPacksTitle,
                        supporting: L10n.recipeIntroPacksMessage(credits.costs, lowestPrice: credits.lowestPackPrice)
                    )
                }
            }
            Text(L10n.recipeIntroAgain)
                .larderFont(.bodySm)
                .foregroundStyle(LarderColor.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
            VStack(spacing: LarderSpace.s1) {
                LarderButton(L10n.recipeIntroStart, fullWidth: true) { model.dismissIntro() }
                if credits.canBuy {
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
                RecipeGoButton(title: "Share prompt", icon: .share2) {}.disabled(true)
            }
            .padding(LarderSpace.s4)
        }
        .background(LarderColor.background)
    }
}

#Preview("Recipe form") { FormPreview() }
#Preview("Recipe form, dark") { FormPreview().preferredColorScheme(.dark) }
#Preview("Recipe form, Russian") { FormPreview().environment(\.locale, Locale(identifier: "ru")) }
