import SwiftUI

/// The three shadows in `tokens.json`, which the token generator doesn't emit for iOS. Only
/// floating things get one: Scan, the pill, snackbars and cards marked elevated. Lists sit on
/// the page with a hairline.
enum LarderElevation {
    case level1, level2, level3

    fileprivate var y: CGFloat {
        switch self {
        case .level1: return 1
        case .level2: return 4
        case .level3: return 10
        }
    }

    /// SwiftUI's radius is about half a CSS blur.
    fileprivate var radius: CGFloat {
        switch self {
        case .level1: return 1
        case .level2: return 6
        case .level3: return 14
        }
    }

    fileprivate func color(in scheme: ColorScheme) -> Color {
        switch (self, scheme) {
        case (.level1, .dark): return .black.opacity(0.45)
        case (.level2, .dark): return .black.opacity(0.5)
        case (.level3, .dark): return .black.opacity(0.55)
        case (.level1, _): return Self.lightShadow.opacity(0.08)
        case (.level2, _): return Self.lightShadow.opacity(0.10)
        case (.level3, _): return Self.lightShadow.opacity(0.16)
        }
    }

    private static let lightShadow = Color(red: 47 / 255, green: 43 / 255, blue: 34 / 255)
}

private struct LarderElevationModifier: ViewModifier {
    let level: LarderElevation
    @Environment(\.colorScheme) private var colorScheme

    func body(content: Content) -> some View {
        content.shadow(color: level.color(in: colorScheme), radius: level.radius, y: level.y)
    }
}

extension View {
    func larderElevation(_ level: LarderElevation) -> some View {
        modifier(LarderElevationModifier(level: level))
    }
}
