import SwiftUI

/// The three empty-state drawings: a thin line over soft shapes, in the theme's colours.
enum LarderArt {
    case shelf, basket, clear
}

/// `art-*` from `docs/design/src/art.svg.html`, on the same 160×120 grid.
struct LarderIllustration: View {
    let art: LarderArt

    var body: some View {
        Canvas { context, size in
            context.scaleBy(x: size.width / 160, y: size.height / 120)
            draw(in: &context)
        }
        .frame(width: 160, height: 120)
        .accessibilityHidden(true)
    }

    private func draw(in context: inout GraphicsContext) {
        let soft = GraphicsContext.Shading.color(LarderColor.primaryContainer)
        let shelf = GraphicsContext.Shading.color(LarderColor.surfaceContainer)
        let line = GraphicsContext.Shading.color(LarderColor.onSurfaceVariant)
        let stroke = StrokeStyle(lineWidth: 2.5, lineCap: .round, lineJoin: .round)

        func rounded(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat, _ r: CGFloat) -> Path {
            Path(roundedRect: CGRect(x: x, y: y, width: w, height: h), cornerRadius: r)
        }
        func lines(_ points: [CGPoint]) -> Path {
            var path = Path()
            path.addLines(points)
            return path
        }
        func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x, y: y) }

        switch art {
        case .shelf:
            context.fill(rounded(16, 84, 128, 8, 4), with: shelf)
            context.stroke(lines([p(16, 92), p(144, 92)]), with: line, style: stroke)
            context.fill(rounded(34, 44, 34, 40, 7), with: soft)
            context.stroke(rounded(34, 44, 34, 40, 7), with: line, style: stroke)
            context.stroke(lines([p(39, 44), p(39, 37), p(63, 37), p(63, 44)]), with: line, style: stroke)
            context.fill(rounded(82, 56, 42, 28, 6), with: shelf)
            context.stroke(rounded(82, 56, 42, 28, 6), with: line, style: stroke)
            context.stroke(lines([p(92, 68), p(114, 68)]), with: line, style: stroke)

        case .basket:
            var body = Path()
            body.move(to: p(36, 54))
            body.addLine(to: p(124, 54))
            body.addLine(to: p(115, 94))
            body.addQuadCurve(to: p(109, 99), control: p(114, 99))
            body.addLine(to: p(51, 99))
            body.addQuadCurve(to: p(45, 94), control: p(46, 99))
            body.closeSubpath()
            context.fill(body, with: soft)
            context.stroke(body, with: line, style: stroke)
            context.stroke(lines([p(28, 54), p(132, 54)]), with: line, style: stroke)
            context.stroke(lines([p(58, 54), p(72, 24)]), with: line, style: stroke)
            context.stroke(lines([p(102, 54), p(88, 24)]), with: line, style: stroke)
            for x: CGFloat in [66, 80, 94] {
                context.stroke(lines([p(x, 70), p(x, 84)]), with: line, style: stroke)
            }

        case .clear:
            context.fill(rounded(16, 84, 128, 8, 4), with: shelf)
            context.stroke(lines([p(16, 92), p(144, 92)]), with: line, style: stroke)
            context.fill(Path(ellipseIn: CGRect(x: 50, y: 20, width: 60, height: 60)), with: soft)
            context.stroke(lines([p(66, 50), p(76, 60), p(95, 40)]), with: line, style: stroke)
        }
    }
}

/// The centred layout `EmptyState` and `ScreenError` share.
private struct WholeAreaMessage<Header: View, Actions: View>: View {
    let title: String
    let message: String?
    let header: Header
    let actions: Actions

    var body: some View {
        // When the area is short, as with the keyboard up, the message scrolls rather than
        // squeezing its text onto one cut-off line.
        ViewThatFits(in: .vertical) {
            content
            ScrollView { content }
                .scrollBounceBehavior(.basedOnSize)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var content: some View {
        VStack(spacing: LarderSpace.s3) {
            header
            Text(title)
                .larderFont(.title)
                .foregroundStyle(LarderColor.onSurface)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            if let message {
                Text(message)
                    .larderFont(.body)
                    .foregroundStyle(LarderColor.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: 320)
            }
            actions
        }
        .multilineTextAlignment(.center)
        .padding(.vertical, LarderSpace.s8)
        .padding(.horizontal, LarderSpace.s6)
        .frame(maxWidth: .infinity)
    }
}

/// Nothing here yet, or nothing matches. Illustrations appear only here.
///
///     EmptyState(.shelf, title: "Your shelves are empty",
///                message: "Scan your first product and it will show up here, soonest date first.") {
///         LarderButton("Scan a product") { scan() }
///     }
struct EmptyState<Actions: View>: View {
    private let art: LarderArt?
    private let title: String
    private let message: String?
    private let actions: Actions

    init(_ art: LarderArt?, title: String, message: String? = nil, @ViewBuilder actions: () -> Actions) {
        self.art = art
        self.title = title
        self.message = message
        self.actions = actions()
    }

    var body: some View {
        WholeAreaMessage(
            title: title,
            message: message,
            header: art.map { LarderIllustration(art: $0) },
            actions: actions
        )
    }
}

extension EmptyState where Actions == EmptyView {
    init(_ art: LarderArt?, title: String, message: String? = nil) {
        self.init(art, title: title, message: message) { EmptyView() }
    }
}

/// The T5 tier: the screen's content can't be shown at all.
///
///     ScreenError(.cameraOff, title: "The camera is off",
///                 message: "BestBefore needs the camera to scan. You can still add food by hand.") {
///         LarderButton("Open settings") { openSettings() }
///     }
struct ScreenError<Actions: View>: View {
    private let icon: LarderIconName
    private let title: String
    private let message: String?
    private let actions: Actions

    init(_ icon: LarderIconName, title: String, message: String? = nil, @ViewBuilder actions: () -> Actions) {
        self.icon = icon
        self.title = title
        self.message = message
        self.actions = actions()
    }

    var body: some View {
        WholeAreaMessage(
            title: title,
            message: message,
            header: LarderIcon(icon, size: .custom(40)).foregroundStyle(LarderColor.onSurfaceVariant),
            actions: actions
        )
    }
}

extension ScreenError where Actions == EmptyView {
    init(_ icon: LarderIconName, title: String, message: String? = nil) {
        self.init(icon, title: title, message: message) { EmptyView() }
    }
}

private struct EmptyGallery: View {
    var body: some View {
        TabView {
            EmptyState(.shelf, title: "Your shelves are empty", message: "Scan your first product and it will show up here, soonest date first.") {
                LarderButton("Scan a product") {}
            }
            EmptyState(.basket, title: "Nothing to buy", message: "Add what you need, or let used-up essentials land here.")
            EmptyState(.clear, title: "Nothing needs using this week")
            ScreenError(.cameraOff, title: "The camera is off", message: "BestBefore needs the camera to scan. You can still add food by hand.") {
                LarderButton("Open settings") {}
            }
        }
        .tabViewStyle(.page)
        .background(LarderColor.background)
    }
}

#Preview("Empty and error") { EmptyGallery() }
#Preview("Empty and error, dark") { EmptyGallery().preferredColorScheme(.dark) }
