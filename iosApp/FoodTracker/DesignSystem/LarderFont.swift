import SwiftUI
import UIKit

/// The eight type styles from `docs/design/tokens.json`.
///
/// Display, headline and title are the bundled Vollkorn; the rest are the system font. All of
/// them follow Dynamic Type.
///
/// Use `.larderFont(.title)` on a view: it applies the exact token size, the tracking and the
/// line height. `LarderFont.title.font` is for the few places that need a `Font` value, such as
/// `Text` concatenation. Its system styles use the nearest text style, because SwiftUI can only
/// scale an arbitrary system size through a view, so `.bodySm` comes out at 15pt rather than 14.
enum LarderFont: CaseIterable {
    case display, headline, title, titleSm, body, bodySm, label, caption

    /// PostScript name of the file in `Resources/Fonts`, listed under `UIAppFonts` in Info.plist.
    /// All three display styles are weight 600, so it is the only face bundled.
    static let serifName = "Vollkorn-SemiBold"

    var size: CGFloat {
        switch self {
        case .display: return 32
        case .headline: return 24
        case .title: return 19
        case .titleSm, .body: return 16
        case .bodySm, .label: return 14
        case .caption: return 12
        }
    }

    var lineHeight: CGFloat {
        switch self {
        case .display: return 40
        case .headline: return 30
        case .title: return 26
        case .titleSm: return 22
        case .body: return 24
        case .bodySm, .label: return 20
        case .caption: return 16
        }
    }

    var tracking: CGFloat {
        switch self {
        case .display: return -0.3
        case .label: return 0.1
        case .caption: return 0.2
        default: return 0
        }
    }

    var isSerif: Bool {
        switch self {
        case .display, .headline, .title: return true
        default: return false
        }
    }

    var weight: Font.Weight {
        switch self {
        case .display, .headline, .title, .titleSm, .label: return .semibold
        case .body, .bodySm: return .regular
        case .caption: return .medium
        }
    }

    /// The text style this one scales with.
    var textStyle: Font.TextStyle {
        switch self {
        case .display: return .largeTitle
        case .headline: return .title2
        case .title: return .title3
        case .titleSm, .body: return .callout
        case .bodySm, .label: return .subheadline
        case .caption: return .caption
        }
    }

    var font: Font {
        isSerif
            ? .custom(Self.serifName, size: size, relativeTo: textStyle)
            : .system(textStyle, weight: weight)
    }

    /// For UIKit appearance proxies. Scaled for the text size in force when it is called.
    var uiFont: UIFont {
        let style: UIFont.TextStyle
        switch self {
        case .display: style = .largeTitle
        case .headline: style = .title2
        case .title: style = .title3
        case .titleSm, .body: style = .callout
        case .bodySm, .label: style = .subheadline
        case .caption: style = .caption1
        }
        return UIFontMetrics(forTextStyle: style).scaledFont(for: unscaledUIFont(size: size))
    }

    fileprivate func unscaledUIFont(size: CGFloat) -> UIFont {
        if isSerif, let serif = UIFont(name: Self.serifName, size: size) { return serif }
        let uiWeight: UIFont.Weight
        switch weight {
        case .semibold: uiWeight = .semibold
        case .medium: uiWeight = .medium
        default: uiWeight = .regular
        }
        return .systemFont(ofSize: size, weight: uiWeight)
    }
}

private struct LarderFontModifier: ViewModifier {
    let style: LarderFont
    @ScaledMetric private var scaledSize: CGFloat

    init(style: LarderFont) {
        self.style = style
        _scaledSize = ScaledMetric(wrappedValue: style.size, relativeTo: style.textStyle)
    }

    func body(content: Content) -> some View {
        content
            .font(style.isSerif
                ? .custom(LarderFont.serifName, size: style.size, relativeTo: style.textStyle)
                : .system(size: scaledSize, weight: style.weight))
            .tracking(style.tracking)
            .lineSpacing(extraLeading)
    }

    /// The token's line height, less what the font already provides.
    private var extraLeading: CGFloat {
        let scaledLine = style.lineHeight * scaledSize / style.size
        return max(0, scaledLine - style.unscaledUIFont(size: scaledSize).lineHeight)
    }
}

extension View {
    func larderFont(_ style: LarderFont) -> some View {
        modifier(LarderFontModifier(style: style))
    }
}

private struct TypeScale: View {
    var body: some View {
        VStack(alignment: .leading, spacing: LarderSpace.s3) {
            ForEach(LarderFont.allCases, id: \.self) { style in
                Text("Best before 24 November").larderFont(style)
            }
            Text("Срокоед · Греческий йогурт").larderFont(.headline)
            Text("Matvakt · Blåbær og rømme").larderFont(.headline)
        }
        .padding()
        .foregroundStyle(LarderColor.onBackground)
        .background(LarderColor.background)
    }
}

#Preview("Type scale") { TypeScale() }
#Preview("Type scale, dark") { TypeScale().preferredColorScheme(.dark) }
#Preview("Type scale, large text") { TypeScale().dynamicTypeSize(.accessibility2) }
