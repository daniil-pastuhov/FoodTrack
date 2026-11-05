import SwiftUI

/// The interaction states a static preview can't reach by hand. Previews only: real controls get
/// these from touch, pointer and keyboard.
enum LarderForcedState {
    case hover, focus, pressed
}

private struct ForcedStateKey: EnvironmentKey {
    static let defaultValue: LarderForcedState? = nil
}

/// What a `.text` button is drawn in on a coloured surface: the callout's own text colour, or
/// the snackbar's inverse primary.
private struct TextButtonColorKey: EnvironmentKey {
    static let defaultValue: Color? = nil
}

extension EnvironmentValues {
    var larderForcedState: LarderForcedState? {
        get { self[ForcedStateKey.self] }
        set { self[ForcedStateKey.self] = newValue }
    }

    var larderTextButtonColor: Color? {
        get { self[TextButtonColorKey.self] }
        set { self[TextButtonColorKey.self] = newValue }
    }
}

/// One primary per view. Labels are verbs that say what happens: "Save to Fridge", not "OK".
/// Disable with `.disabled(_:)`.
///
///     LarderButton("Save to Fridge", fullWidth: true) { save() }
///     LarderButton("Undo", variant: .text) { undo() }
///     LarderButton("Clear all data", variant: .danger, isLoading: isClearing) { clear() }
struct LarderButton: View {
    enum Variant {
        case primary, secondary, outline, text
        /// Confirmations only.
        case danger
        case dangerText
    }

    private let title: String
    private let icon: LarderIconName?
    private let variant: Variant
    private let isLoading: Bool
    private let fullWidth: Bool
    private let action: () -> Void

    init(
        _ title: String,
        icon: LarderIconName? = nil,
        variant: Variant = .primary,
        isLoading: Bool = false,
        fullWidth: Bool = false,
        action: @escaping () -> Void
    ) {
        self.title = title
        self.icon = icon
        self.variant = variant
        self.isLoading = isLoading
        self.fullWidth = fullWidth
        self.action = action
    }

    var body: some View {
        Button {
            if !isLoading { action() }
        } label: {
            HStack(spacing: LarderSpace.s2) {
                if let icon { LarderIcon(icon) }
                Text(title).larderFont(.label)
            }
            // Hidden rather than removed, so loading keeps the button's width.
            .opacity(isLoading ? 0 : 1)
            .frame(maxWidth: fullWidth ? .infinity : nil)
        }
        .buttonStyle(LarderButtonStyle(variant: variant, isLoading: isLoading))
        .accessibilityAddTraits(isLoading ? .updatesFrequently : [])
    }
}

private struct LarderButtonStyle: ButtonStyle {
    let variant: LarderButton.Variant
    let isLoading: Bool

    func makeBody(configuration: Configuration) -> some View {
        StyledButton(variant: variant, isLoading: isLoading, label: configuration.label, isPressed: configuration.isPressed)
    }

    private struct StyledButton<Label: View>: View {
        let variant: LarderButton.Variant
        let isLoading: Bool
        let label: Label
        let isPressed: Bool

        @Environment(\.isEnabled) private var isEnabled
        @Environment(\.isFocused) private var isFocused
        @Environment(\.larderForcedState) private var forcedState
        @Environment(\.larderTextButtonColor) private var textButtonColor
        @State private var isHovering = false

        private var pressed: Bool { isPressed || forcedState == .pressed }
        private var hovered: Bool { isHovering || forcedState == .hover }
        private var focused: Bool { isFocused || forcedState == .focus }

        private var foreground: Color {
            guard isEnabled else { return LarderColor.onSurface.opacity(0.38) }
            switch variant {
            case .primary: return LarderColor.onPrimary
            case .secondary: return LarderColor.onPrimaryContainer
            case .outline: return LarderColor.primary
            case .text: return textButtonColor ?? LarderColor.primary
            case .danger: return LarderColor.onError
            case .dangerText: return LarderColor.error
            }
        }

        private var background: Color {
            guard isEnabled else {
                switch variant {
                case .text, .dangerText: return .clear
                default: return LarderColor.onSurface.opacity(0.12)
                }
            }
            switch variant {
            case .primary: return LarderColor.primary
            case .secondary: return LarderColor.primaryContainer
            case .danger: return LarderColor.error
            case .outline, .text, .dangerText: return .clear
            }
        }

        /// Every variant moves towards its own text colour when hovered or pressed.
        private var stateLayer: Color {
            guard isEnabled else { return .clear }
            return foreground.opacity(pressed ? 0.14 : (hovered ? 0.08 : 0))
        }

        var body: some View {
            label
                .foregroundStyle(foreground)
                .padding(.horizontal, variant == .text || variant == .dangerText ? LarderSpace.s3 : LarderSpace.s5)
                .frame(minWidth: LarderSize.touch, minHeight: LarderSize.control)
                .background {
                    ZStack {
                        Capsule().fill(background)
                        Capsule().fill(stateLayer)
                    }
                }
                .overlay {
                    if variant == .outline, isEnabled {
                        Capsule().strokeBorder(LarderColor.outline, lineWidth: 1)
                    }
                }
                .overlay {
                    if isLoading {
                        LarderSpinner(size: 18, lineWidth: 2, color: foreground)
                    }
                }
                .overlay {
                    if focused {
                        Capsule()
                            .stroke(LarderColor.focusRing, lineWidth: 2)
                            .padding(-4)
                    }
                }
                .contentShape(Capsule())
                .onHover { isHovering = $0 }
        }
    }
}

/// 48pt round button around one icon. `label` is required: an icon alone says nothing to
/// VoiceOver.
///
///     LarderIconButton(.search, label: L10n.searchPlaceholder) { startSearch() }
struct LarderIconButton: View {
    private let icon: LarderIconName
    private let label: String
    private let color: Color
    private let action: () -> Void

    init(
        _ icon: LarderIconName,
        label: String,
        color: Color = LarderColor.onSurfaceVariant,
        action: @escaping () -> Void
    ) {
        self.icon = icon
        self.label = label
        self.color = color
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            LarderIcon(icon)
        }
        .buttonStyle(LarderIconButtonStyle(color: color))
        .accessibilityLabel(label)
    }
}

private struct LarderIconButtonStyle: ButtonStyle {
    let color: Color

    func makeBody(configuration: Configuration) -> some View {
        StyledIconButton(color: color, label: configuration.label, isPressed: configuration.isPressed)
    }

    private struct StyledIconButton<Label: View>: View {
        let color: Color
        let label: Label
        let isPressed: Bool

        @Environment(\.isEnabled) private var isEnabled
        @Environment(\.isFocused) private var isFocused
        @Environment(\.larderForcedState) private var forcedState
        @State private var isHovering = false

        var body: some View {
            label
                .foregroundStyle(isEnabled ? color : LarderColor.onSurface.opacity(0.38))
                .frame(width: LarderSize.touch, height: LarderSize.touch)
                .background(
                    LarderColor.onSurface.opacity(
                        isPressed || forcedState == .pressed ? 0.14 : (isHovering || forcedState == .hover ? 0.08 : 0)
                    ),
                    in: Circle()
                )
                .overlay {
                    if isFocused || forcedState == .focus {
                        Circle().stroke(LarderColor.focusRing, lineWidth: 2).padding(-2)
                    }
                }
                .contentShape(Circle())
                .onHover { isHovering = isEnabled && $0 }
        }
    }
}

/// Count badge: the number on the Shopping tab. Past 99 it reads "99+".
struct LarderBadge: View {
    let count: Int
    /// The pill's badge is smaller than the standalone one.
    var isCompact: Bool = false

    var body: some View {
        let side: CGFloat = isCompact ? 16 : 20
        Text(count > 99 ? "99+" : "\(count)")
            .font(.system(size: isCompact ? 10 : 12, weight: .medium).monospacedDigit())
            .foregroundStyle(LarderColor.onPrimary)
            .padding(.horizontal, isCompact ? LarderSpace.s1 : 6)
            .frame(minWidth: side, minHeight: side)
            .background(LarderColor.primary, in: Capsule())
    }
}

/// For barcode lookup, receipts and image reads only: pantry data opens instantly and has no
/// loading state.
struct LarderSpinner: View {
    var size: CGFloat = 32
    var lineWidth: CGFloat = 3
    var color: Color = LarderColor.primary

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isSpinning = false

    var body: some View {
        Circle()
            .trim(from: 0, to: 0.75)
            .stroke(color, style: StrokeStyle(lineWidth: lineWidth, lineCap: .butt))
            .frame(width: size - lineWidth, height: size - lineWidth)
            .frame(width: size, height: size)
            .rotationEffect(.degrees(isSpinning ? 360 : 0))
            .animation(reduceMotion ? nil : .linear(duration: 0.8).repeatForever(autoreverses: false), value: isSpinning)
            .onAppear { isSpinning = true }
            .accessibilityHidden(true)
    }
}

/// Thin indeterminate bar.
struct LarderProgressBar: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isRunning = false

    var body: some View {
        GeometryReader { proxy in
            let barWidth = proxy.size.width * 0.4
            Rectangle()
                .fill(LarderColor.primary)
                .frame(width: barWidth)
                .offset(x: isRunning ? proxy.size.width : -barWidth)
                .animation(
                    reduceMotion ? nil : .easeInOut(duration: 1.4).repeatForever(autoreverses: false),
                    value: isRunning
                )
        }
        .frame(height: 4)
        .background(LarderColor.surfaceContainerHigh)
        .clipped()
        .onAppear { isRunning = true }
        .accessibilityHidden(true)
    }
}

private struct ButtonGallery: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: LarderSpace.s4) {
                LarderButton("Save to Fridge") {}
                LarderButton("Save to Fridge") {}.environment(\.larderForcedState, .hover)
                LarderButton("Save to Fridge") {}.environment(\.larderForcedState, .focus)
                LarderButton("Save to Fridge") {}.environment(\.larderForcedState, .pressed)
                LarderButton("Save to Fridge", isLoading: true) {}
                LarderButton("Save to Fridge") {}.disabled(true)
                LarderButton("Add one more", variant: .secondary) {}
                LarderButton("Keep separate", variant: .outline) {}
                LarderButton("Undo", variant: .text) {}
                LarderButton("Clear all data", variant: .danger) {}
                LarderButton("Remove", variant: .dangerText) {}
                LarderButton("Scan date", icon: .scanText, variant: .text) {}
                LarderButton("Scan a product", fullWidth: true) {}
                HStack {
                    LarderIconButton(.search, label: "Search") {}
                    LarderIconButton(.search, label: "Search") {}.environment(\.larderForcedState, .focus)
                    LarderIconButton(.search, label: "Search") {}.environment(\.larderForcedState, .hover)
                    LarderIconButton(.search, label: "Search") {}.disabled(true)
                }
                HStack {
                    LarderBadge(count: 5)
                    LarderBadge(count: 120)
                    LarderBadge(count: 5, isCompact: true)
                }
                LarderSpinner()
                LarderProgressBar()
            }
            .padding(LarderSpace.s6)
        }
        .background(LarderColor.background)
    }
}

#Preview("Buttons") { ButtonGallery() }
#Preview("Buttons, dark") { ButtonGallery().preferredColorScheme(.dark) }
