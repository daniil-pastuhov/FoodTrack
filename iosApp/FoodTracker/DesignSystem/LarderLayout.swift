import SwiftUI

enum LarderLayout {
    /// Window width at which two-pane layouts start, and Scan moves level with the pill.
    static let expandedMinWidth: CGFloat = 700
}

private struct IsExpandedKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    /// True at 700pt of window width and up. It follows the window, not the device, so an iPad
    /// in a narrow Split View or Stage Manager window gets the phone layout. Set once by
    /// `readsWindowWidth()` at the root of the app and read with `@Environment(\.isExpanded)`.
    var isExpanded: Bool {
        get { self[IsExpandedKey.self] }
        set { self[IsExpandedKey.self] = newValue }
    }
}

extension View {
    /// Publishes `isExpanded` to everything below. Goes on the root view, where the proposed
    /// size is the window's. A GeometryReader rather than `onGeometryChange`, so the first frame
    /// already has the right layout on iPad.
    func readsWindowWidth() -> some View {
        GeometryReader { proxy in
            let width = proxy.size.width + proxy.safeAreaInsets.leading + proxy.safeAreaInsets.trailing
            self.environment(\.isExpanded, width >= LarderLayout.expandedMinWidth)
        }
    }
}

/// Lets a screen ask the shell's floating parts to step aside, without touching `RootTabView`.
/// Requests are counted, so two screens crossing in a transition can't undo one another. Use
/// `.shellChromeEffect(hideNav:hideScan:)`.
final class ShellChrome: ObservableObject {
    @Published private var navHolds = 0
    @Published private var scanHolds = 0

    var navHidden: Bool { navHolds > 0 }
    var scanHidden: Bool { scanHolds > 0 }

    fileprivate func hold(nav: Bool, scan: Bool) {
        if nav { navHolds += 1 }
        if scan { scanHolds += 1 }
    }

    fileprivate func release(nav: Bool, scan: Bool) {
        if nav { navHolds -= 1 }
        if scan { scanHolds -= 1 }
    }
}

private struct ShellChromeKey: EnvironmentKey {
    static let defaultValue = ShellChrome()
}

extension EnvironmentValues {
    var shellChrome: ShellChrome {
        get { self[ShellChromeKey.self] }
        set { self[ShellChromeKey.self] = newValue }
    }
}

private struct ShellChromeEffect: ViewModifier {
    let hideNav: Bool
    let hideScan: Bool

    @Environment(\.shellChrome) private var chrome
    /// What this view currently holds, so a change releases exactly that.
    @State private var held: (nav: Bool, scan: Bool)?

    private func hold() {
        chrome.hold(nav: hideNav, scan: hideScan)
        held = (hideNav, hideScan)
    }

    private func release() {
        guard let held else { return }
        chrome.release(nav: held.nav, scan: held.scan)
        self.held = nil
    }

    func body(content: Content) -> some View {
        content
            .onAppear(perform: hold)
            .onDisappear(perform: release)
            .onChange(of: hideNav) { _, _ in
                release()
                hold()
            }
            .onChange(of: hideScan) { _, _ in
                release()
                hold()
            }
    }
}

extension View {
    /// Hides the pill and/or Scan for as long as this view is on screen with those flags set:
    /// Home's multi-select hides both on phones, and its empty state hides Scan because the body
    /// offers it.
    ///
    ///     .shellChromeEffect(hideNav: isSelecting && !isExpanded, hideScan: isSelecting || isEmpty)
    func shellChromeEffect(hideNav: Bool = false, hideScan: Bool = false) -> some View {
        modifier(ShellChromeEffect(hideNav: hideNav, hideScan: hideScan))
    }
}

/// Where the shell's floating parts go, all measured up from the bottom of the safe area.
/// Comes from the `.has-nav` rules in `components.css`, but starts from the pill's real height
/// because that grows with Dynamic Type.
struct ShellMetrics: Equatable {
    /// `FloatingNav`'s measured height.
    var pillHeight: CGFloat = ShellMetrics.defaultPillHeight
    /// Bottom safe area of the window: 0 on devices with a home button.
    var bottomSafeArea: CGFloat = 0
    /// Scan's measured height.
    var scanHeight: CGFloat = ScanFab.minHeight
    var isNavVisible: Bool = true
    var isScanVisible: Bool = false
    var isExpanded: Bool = false

    static let defaultPillHeight: CGFloat = 72
    private static let gap = LarderSpace.s3

    /// The design's 12pt from the screen edge, or just off the home indicator's safe area.
    var pillBottom: CGFloat { bottomSafeArea > 0 ? LarderSpace.s1 : LarderSpace.s3 }
    /// With the pill stepped aside, Scan and snackbars come down to where it was.
    private var pillTop: CGFloat { isNavVisible ? pillBottom + pillHeight : 0 }

    var scanBottom: CGFloat { isExpanded ? pillBottom : pillTop + Self.gap }
    var scanTrailing: CGFloat { isExpanded ? LarderSpace.s5 : LarderSpace.s4 }

    /// Above Scan on phones, above the pill when Scan sits beside it.
    var snackbarBottom: CGFloat {
        isScanVisible && !isExpanded ? scanBottom + scanHeight + Self.gap : pillTop + Self.gap
    }

    /// How much room a scrolling screen needs at its end, so its last row clears everything
    /// floating over it. `RootTabView` applies it as a safe area inset around every tab, so
    /// screens don't reserve any of their own.
    var contentClearance: CGFloat {
        let floatingTop = isScanVisible && !isExpanded ? scanBottom + scanHeight : pillTop
        return floatingTop + LarderSpace.s5
    }
}
