import SwiftUI

/// The line under a field. `.error` is the T1 tier: a problem with this one input.
enum LarderFieldMessage: Equatable {
    case help(String)
    case warning(String)
    case error(String)

    fileprivate var text: String {
        switch self {
        case .help(let text), .warning(let text), .error(let text): return text
        }
    }
}

/// Label, control outline and message line, around any control. `LarderTextField` is the common
/// case; use this directly for something that opens a picker, like the Best before date.
///
///     LarderFieldContainer("Best before", message: dateMessage) {
///         Button(action: showPicker) { ... }
///     }
struct LarderFieldContainer<Control: View>: View {
    private let label: String?
    private let message: LarderFieldMessage?
    private let count: String?
    private let isFocused: Bool
    private let control: Control

    @Environment(\.isEnabled) private var isEnabled

    init(
        _ label: String?,
        message: LarderFieldMessage? = nil,
        count: String? = nil,
        isFocused: Bool = false,
        @ViewBuilder control: () -> Control
    ) {
        self.label = label
        self.message = message
        self.count = count
        self.isFocused = isFocused
        self.control = control()
    }

    private var isError: Bool {
        if case .error = message { return true }
        return false
    }

    private var border: (color: Color, width: CGFloat) {
        if !isEnabled { return (LarderColor.onSurface.opacity(0.12), 1) }
        if isError { return (LarderColor.error, 2) }
        if isFocused { return (LarderColor.primary, 2) }
        return (LarderColor.outline, 1)
    }

    private var messageColor: Color {
        switch message {
        case .error: return LarderColor.error
        case .warning: return LarderColor.onWarningContainer
        default: return LarderColor.onSurfaceVariant
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: LarderSpace.s1) {
            if let label {
                Text(label)
                    .larderFont(.label)
                    .foregroundStyle(isEnabled ? LarderColor.onSurface : LarderColor.onSurface.opacity(0.38))
            }
            HStack(spacing: LarderSpace.s2) { control }
                .padding(.horizontal, LarderSpace.s3)
                .frame(minHeight: LarderSize.control)
                .foregroundStyle(isEnabled ? LarderColor.onSurface : LarderColor.onSurface.opacity(0.38))
                .background(LarderColor.surface, in: RoundedRectangle(cornerRadius: LarderRadius.md))
                .overlay {
                    RoundedRectangle(cornerRadius: LarderRadius.md)
                        .strokeBorder(border.color, lineWidth: border.width)
                }
            if message != nil || count != nil {
                HStack(alignment: .top, spacing: LarderSpace.s1) {
                    if let message {
                        if isError { LarderIcon(.circleAlert, size: .small) }
                        if case .warning = message { LarderIcon(.triangleAlert, size: .small) }
                        Text(message.text)
                    }
                    if let count {
                        Spacer(minLength: LarderSpace.s2)
                        Text(count).monospacedDigit()
                    }
                }
                .larderFont(.caption)
                .foregroundStyle(messageColor)
                .accessibilityElement(children: .combine)
            }
        }
    }
}

/// Always has a visible label. Validate when the field loses focus, through `onEditingEnded`,
/// not on each keystroke.
///
///     LarderTextField("Product name", text: $name, placeholder: "e.g. Greek yoghurt",
///                     message: nameError.map { .error($0) }, onEditingEnded: validateName)
///
///     LarderTextField("Search", text: $query, icon: .search, showsClear: true)
///
/// Disable with `.disabled(_:)`. Keyboard modifiers such as `.keyboardType` and
/// `.textInputAutocapitalization` work on it as on a `TextField`.
struct LarderTextField: View {
    private let label: String?
    @Binding private var text: String
    private let placeholder: String
    private let icon: LarderIconName?
    private let showsClear: Bool
    private let message: LarderFieldMessage?
    private let count: String?
    private let axis: Axis
    private let onEditingEnded: (() -> Void)?

    @FocusState private var isFocused: Bool
    @Environment(\.larderForcedState) private var forcedState

    /// `label` is nil only where the design has none and something else names the field, such
    /// as the Add an item row on Shopping; pass an `.accessibilityLabel` then.
    init(
        _ label: String?,
        text: Binding<String>,
        placeholder: String = "",
        icon: LarderIconName? = nil,
        showsClear: Bool = false,
        message: LarderFieldMessage? = nil,
        count: String? = nil,
        axis: Axis = .horizontal,
        onEditingEnded: (() -> Void)? = nil
    ) {
        self.label = label
        _text = text
        self.placeholder = placeholder
        self.icon = icon
        self.showsClear = showsClear
        self.message = message
        self.count = count
        self.axis = axis
        self.onEditingEnded = onEditingEnded
    }

    var body: some View {
        LarderFieldContainer(
            label,
            message: message,
            count: count,
            isFocused: isFocused || forcedState == .focus
        ) {
            if let icon {
                LarderIcon(icon).foregroundStyle(LarderColor.onSurfaceVariant)
            }
            TextField(
                label ?? placeholder,
                text: $text,
                prompt: Text(placeholder).foregroundStyle(LarderColor.onSurfaceVariant),
                axis: axis
            )
            .larderFont(.body)
            .focused($isFocused)
            .padding(.vertical, LarderSpace.s3)
            if showsClear, !text.isEmpty {
                LarderIconButton(.x, label: L10n.clearFieldButton) { text = "" }
                    .padding(.trailing, -LarderSpace.s3)
            }
        }
        .onChange(of: isFocused) { _, focused in
            if !focused { onEditingEnded?() }
        }
    }
}

private struct FieldGallery: View {
    @State private var empty = ""
    @State private var brand = "Fage"
    @State private var search = "yogh"

    var body: some View {
        ScrollView {
            VStack(spacing: LarderSpace.s4) {
                LarderTextField("Product name", text: $empty, placeholder: "e.g. Greek yoghurt")
                LarderTextField("Brand", text: $brand).environment(\.larderForcedState, .focus)
                LarderTextField("Product name", text: $empty, message: .error("Add a name so you can find this later"))
                LarderTextField("Barcode", text: .constant("5012345678900")).disabled(true)
                LarderTextField("Search", text: $search, icon: .search, showsClear: true)
                LarderTextField("Notes", text: $brand, message: .help("Only you see this"), count: "4/200", axis: .vertical)
                LarderFieldContainer("Best before", message: .warning("This date is over 10 years away. Check the year.")) {
                    LarderIcon(.calendarDays)
                    Text("24 Nov 2062").larderFont(.body)
                    Spacer()
                }
            }
            .padding(LarderSpace.s4)
        }
        .background(LarderColor.background)
    }
}

#Preview("Text fields") { FieldGallery() }
#Preview("Text fields, dark") { FieldGallery().preferredColorScheme(.dark) }
