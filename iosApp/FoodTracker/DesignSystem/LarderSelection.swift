import SwiftUI

/// Chips filter, and pick one place. 32pt tall with a 48pt hit area, so wrapped rows need
/// `LarderChip.wrapRowSpacing` between them to keep those areas apart.
///
///     LarderChip("Fridge", isSelected: location == "Fridge") { location = "Fridge" }
///     LarderChip("Soon", count: 4, isSelected: filter == .soon) { ... }
struct LarderChip: View {
    /// Row gap for chips in a wrapping layout.
    static let wrapRowSpacing = LarderSpace.s4

    private let title: String
    private let icon: LarderIconName?
    private let count: String?
    private let isSelected: Bool
    private let action: () -> Void

    @Environment(\.isFocused) private var isFocused
    @Environment(\.larderForcedState) private var forcedState

    init(
        _ title: String,
        icon: LarderIconName? = nil,
        count: Int? = nil,
        isSelected: Bool = false,
        action: @escaping () -> Void
    ) {
        self.init(title, icon: icon, countText: count.map { "\($0)" }, isSelected: isSelected, action: action)
    }

    /// For a count that isn't a plain number, such as a capped "999+".
    init(
        _ title: String,
        icon: LarderIconName? = nil,
        countText: String?,
        isSelected: Bool = false,
        action: @escaping () -> Void
    ) {
        self.title = title
        self.icon = icon
        self.count = countText
        self.isSelected = isSelected
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: LarderSpace.s1) {
                if let icon { LarderIcon(icon, size: .custom(16)) }
                Text(title)
                if let count { Text(count).monospacedDigit() }
            }
            .larderFont(.label)
            .lineLimit(1)
            .foregroundStyle(isSelected ? LarderColor.onPrimaryContainer : LarderColor.onSurface)
            .padding(.horizontal, LarderSpace.s3)
            .frame(minHeight: 32)
            .background(isSelected ? LarderColor.primaryContainer : Color.clear, in: Capsule())
            .overlay {
                if !isSelected {
                    Capsule().strokeBorder(LarderColor.outlineVariant, lineWidth: 1)
                }
            }
            .overlay {
                if isFocused || forcedState == .focus {
                    Capsule().stroke(LarderColor.focusRing, lineWidth: 2).padding(-2)
                }
            }
            .contentShape(Rectangle().inset(by: -LarderSpace.s2))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

/// The round tick that marks something done. The off state is an empty ring. Has a 48pt target
/// and reads as a checkbox, named by `label`.
///
///     LarderTick(isOn: $isBought, label: "Oat milk")
///     LarderTick(isOn: isSelected, label: item.name) { toggle(item) }
struct LarderTick: View {
    private let isOn: Bool
    private let label: String
    private let onToggle: () -> Void

    init(isOn: Binding<Bool>, label: String) {
        self.isOn = isOn.wrappedValue
        self.label = label
        onToggle = { isOn.wrappedValue.toggle() }
    }

    init(isOn: Bool, label: String, onToggle: @escaping () -> Void) {
        self.isOn = isOn
        self.label = label
        self.onToggle = onToggle
    }

    var body: some View {
        Button(action: onToggle) {
            ZStack {
                if isOn {
                    Circle().fill(LarderColor.primary)
                    TickMark()
                        .stroke(LarderColor.onPrimary, style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
                        .frame(width: 16, height: 16)
                } else {
                    Circle().strokeBorder(LarderColor.outline, lineWidth: 2)
                }
            }
            .frame(width: 24, height: 24)
            .frame(width: LarderSize.touch, height: LarderSize.touch)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityRepresentation {
            Toggle(label, isOn: Binding(get: { isOn }, set: { _ in onToggle() }))
        }
    }
}

/// Lucide's `check`, drawn here because the tick needs a heavier stroke than the icon set's 1.75.
private struct TickMark: Shape {
    func path(in rect: CGRect) -> Path {
        let scale = rect.width / 24
        var path = Path()
        path.move(to: CGPoint(x: 20 * scale, y: 6 * scale))
        path.addLine(to: CGPoint(x: 9 * scale, y: 17 * scale))
        path.addLine(to: CGPoint(x: 4 * scale, y: 12 * scale))
        return path
    }
}

/// A setting that changes straight away.
///
///     LarderSwitch("Remind me", isOn: $remind)                     // label left, switch right
///     LarderSwitch("Essential", subtitle: "Back on the list when used up", isOn: $essential)
///     LarderSwitch(accessibilityLabel: "Remind me", isOn: $remind) // just the switch
struct LarderSwitch: View {
    private let title: String?
    private let subtitle: String?
    private let accessibilityText: String
    @Binding private var isOn: Bool

    init(_ title: String, subtitle: String? = nil, isOn: Binding<Bool>) {
        self.title = title
        self.subtitle = subtitle
        accessibilityText = title
        _isOn = isOn
    }

    init(accessibilityLabel: String, isOn: Binding<Bool>) {
        title = nil
        subtitle = nil
        accessibilityText = accessibilityLabel
        _isOn = isOn
    }

    var body: some View {
        Toggle(isOn: $isOn) {
            if let title {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).larderFont(.body).foregroundStyle(LarderColor.onSurface)
                    if let subtitle {
                        Text(subtitle).larderFont(.bodySm).foregroundStyle(LarderColor.onSurfaceVariant)
                    }
                }
            } else {
                Text(accessibilityText)
            }
        }
        .toggleStyle(LarderSwitchStyle(showsLabel: title != nil))
    }
}

private struct LarderSwitchStyle: ToggleStyle {
    let showsLabel: Bool

    func makeBody(configuration: Configuration) -> some View {
        SwitchBody(configuration: configuration, showsLabel: showsLabel)
    }

    private struct SwitchBody: View {
        let configuration: Configuration
        let showsLabel: Bool

        @Environment(\.isEnabled) private var isEnabled

        var body: some View {
            HStack(spacing: LarderSpace.s3) {
                if showsLabel {
                    configuration.label
                    Spacer(minLength: LarderSpace.s2)
                }
                track
            }
            .frame(minHeight: LarderSize.touch)
            .contentShape(Rectangle())
            .onTapGesture {
                guard isEnabled else { return }
                withAnimation(.easeInOut(duration: 0.15)) { configuration.isOn.toggle() }
            }
            .opacity(isEnabled ? 1 : 0.38)
            .accessibilityRepresentation {
                Toggle(isOn: configuration.$isOn) { configuration.label }
            }
        }

        private var track: some View {
            let isOn = configuration.isOn
            return ZStack(alignment: isOn ? .trailing : .leading) {
                Capsule().fill(isOn ? LarderColor.primary : LarderColor.surfaceContainerHigh)
                if !isOn {
                    Capsule().strokeBorder(LarderColor.outline, lineWidth: 2)
                }
                Circle()
                    .fill(isOn ? LarderColor.onPrimary : LarderColor.outline)
                    .frame(width: isOn ? 24 : 16, height: isOn ? 24 : 16)
                    .padding(.horizontal, isOn ? LarderSpace.s1 : LarderSpace.s2)
            }
            .frame(width: 52, height: 32)
        }
    }
}

/// Quantity. The app's limit is 99 on both platforms.
///
///     LarderStepper(value: $quantity)
struct LarderStepper: View {
    @Binding private var value: Int
    private let range: ClosedRange<Int>
    private let outlined: Bool

    /// `outlined: false` is the compact form for dense rows: no border, a narrower count.
    init(value: Binding<Int>, range: ClosedRange<Int> = 1...maxItemQuantity, outlined: Bool = true) {
        _value = value
        self.range = range
        self.outlined = outlined
    }

    var body: some View {
        HStack(spacing: 0) {
            LarderIconButton(.minus, label: L10n.quantityDecrease, color: LarderColor.primary) {
                value -= 1
            }
            .disabled(value <= range.lowerBound)

            Text("\(value)")
                .larderFont(outlined ? .titleSm : .label)
                .monospacedDigit()
                .foregroundStyle(LarderColor.onSurface)
                .frame(minWidth: outlined ? 32 : 24)
                .contentTransition(.numericText())

            LarderIconButton(.plus, label: L10n.quantityIncrease, color: LarderColor.primary) {
                value += 1
            }
            .disabled(value >= range.upperBound)
        }
        .overlay { Capsule().strokeBorder(outlined ? LarderColor.outline : Color.clear, lineWidth: 1) }
        .animation(.easeInOut(duration: 0.15), value: value)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(L10n.quantityLabel)
        .accessibilityValue("\(value)")
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: value = min(value + 1, range.upperBound)
            case .decrement: value = max(value - 1, range.lowerBound)
            @unknown default: break
            }
        }
    }
}

/// One choice from a few short options.
///
///     LarderSegmented(selection: $style, options: ReminderStyle.allCases) { $0.title }
struct LarderSegmented<Option: Hashable>: View {
    @Binding private var selection: Option
    private let options: [Option]
    private let title: (Option) -> String
    private let fill: Bool

    /// `fill: true` shares the full width equally between the segments.
    init(selection: Binding<Option>, options: [Option], fill: Bool = false, title: @escaping (Option) -> String) {
        _selection = selection
        self.options = options
        self.fill = fill
        self.title = title
    }

    var body: some View {
        HStack(spacing: 0) {
            ForEach(options, id: \.self) { option in
                let isSelected = option == selection
                Button {
                    withAnimation(.easeInOut(duration: 0.15)) { selection = option }
                } label: {
                    Text(title(option))
                        .larderFont(.label)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .foregroundStyle(isSelected ? LarderColor.onPrimaryContainer : LarderColor.onSurface)
                        .padding(.horizontal, LarderSpace.s4)
                        .frame(maxWidth: fill ? .infinity : nil, maxHeight: .infinity)
                        .background(isSelected ? LarderColor.primaryContainer : Color.clear)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(isSelected ? .isSelected : [])
            }
        }
        .frame(minHeight: 44)
        .fixedSize(horizontal: false, vertical: true)
        .clipShape(Capsule())
        .overlay { Capsule().strokeBorder(LarderColor.outline, lineWidth: 1) }
    }
}

private struct SelectionGallery: View {
    @State private var location = "Fridge"
    @State private var bought = true
    @State private var remind = true
    @State private var essential = false
    @State private var quantity = 2
    @State private var style = "Each item"

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: LarderSpace.s5) {
                FlowLayout(spacing: LarderSpace.s2) {
                    ForEach(["Fridge", "Freezer", "Pantry"], id: \.self) { name in
                        LarderChip(name, isSelected: location == name) { location = name }
                    }
                    LarderChip("Cellar") {}.environment(\.larderForcedState, .focus)
                    LarderChip("Soon", count: 4) {}
                }
                HStack {
                    LarderTick(isOn: .constant(false), label: "Oat milk")
                    LarderTick(isOn: $bought, label: "Eggs")
                }
                LarderSwitch("Remind me", isOn: $remind)
                LarderSwitch("Essential", subtitle: "Back on the list when used up", isOn: $essential)
                LarderSwitch("Not available here", isOn: .constant(true)).disabled(true)
                HStack {
                    LarderSwitch(accessibilityLabel: "Remind me", isOn: $remind)
                    LarderSwitch(accessibilityLabel: "Essential", isOn: $essential)
                }
                HStack {
                    LarderStepper(value: $quantity)
                    LarderStepper(value: .constant(1))
                }
                LarderStepper(value: .constant(maxItemQuantity))
                LarderSegmented(selection: $style, options: ["Each item", "Summary", "Both"]) { $0 }
            }
            .padding(LarderSpace.s4)
        }
        .background(LarderColor.background)
    }
}

#Preview("Selection") { SelectionGallery() }
#Preview("Selection, dark") { SelectionGallery().preferredColorScheme(.dark) }
