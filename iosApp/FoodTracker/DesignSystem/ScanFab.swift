import SwiftUI

/// The one action button on Home and Calendar. `RootTabView` places it: above the pill on
/// phones, level with it at the bottom right when `isExpanded`.
///
///     ScanFab { presentScanner() }
struct ScanFab: View {
    static let minHeight: CGFloat = 56

    let action: () -> Void

    @Environment(\.isFocused) private var isFocused
    @Environment(\.larderForcedState) private var forcedState

    var body: some View {
        Button(action: action) {
            HStack(spacing: LarderSpace.s2) {
                LarderIcon(.scanBarcode, size: .large)
                Text(L10n.scanButton)
                    .larderFont(.label)
                    .lineLimit(1)
            }
            .foregroundStyle(LarderColor.onPrimary)
            .padding(.horizontal, LarderSpace.s4)
            .frame(minWidth: Self.minHeight, minHeight: Self.minHeight)
            .background(LarderColor.primary, in: RoundedRectangle(cornerRadius: LarderRadius.lg))
            .larderElevation(.level3)
            .overlay {
                if isFocused || forcedState == .focus {
                    RoundedRectangle(cornerRadius: LarderRadius.lg + 4)
                        .stroke(LarderColor.focusRing, lineWidth: 2)
                        .padding(-4)
                }
            }
            .contentShape(RoundedRectangle(cornerRadius: LarderRadius.lg))
        }
        .buttonStyle(.plain)
        .dynamicTypeSize(...DynamicTypeSize.xxLarge)
    }
}

private struct FabPreview: View {
    var body: some View {
        VStack(spacing: LarderSpace.s6) {
            ScanFab {}
            ScanFab {}.environment(\.larderForcedState, .focus)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(LarderColor.background)
    }
}

#Preview("Scan") { FabPreview() }
#Preview("Scan, dark") { FabPreview().preferredColorScheme(.dark) }
