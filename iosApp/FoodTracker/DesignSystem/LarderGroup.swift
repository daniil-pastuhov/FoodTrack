import SwiftUI

/// Rows on one sheet of paper: a white surface with a hairline round it and between its rows.
/// Dividers go in by themselves.
///
///     LarderGroup {
///         PantryRow(...)
///         PantryRow(...)
///     }
///
/// A `LarderGroup` isn't a `List`, so its rows have no swipe actions. For those, use a `List`
/// styled with `.larderList()` and `.larderGroupRow(_:)` on each row, which draws the same thing.
struct LarderGroup<Content: View>: View {
    private let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        // `Group(subviews:)` does this officially, from iOS 18.
        _VariadicView.Tree(GroupLayout()) { content }
            .background(LarderColor.surface)
            .clipShape(RoundedRectangle(cornerRadius: LarderRadius.lg))
            .overlay {
                RoundedRectangle(cornerRadius: LarderRadius.lg)
                    .strokeBorder(LarderColor.outlineVariant, lineWidth: 1)
            }
    }
}

private struct GroupLayout: _VariadicView_UnaryViewRoot {
    func body(children: _VariadicView.Children) -> some View {
        VStack(spacing: 0) {
            ForEach(children) { child in
                child
                if child.id != children.last?.id {
                    LarderDivider()
                }
            }
        }
    }
}

/// The hairline between rows. `LarderGroup` adds its own.
struct LarderDivider: View {
    var body: some View {
        Rectangle()
            .fill(LarderColor.outlineVariant)
            .frame(height: 1)
            .accessibilityHidden(true)
    }
}

/// A plain row for a `LarderGroup`: settings, lists of names, anything that isn't a product. At
/// least 56pt tall. The whole row is one target when `onTap` is set.
///
///     LarderListItem("Reminders", supporting: "1 day before, at 09:00", onTap: open) {
///         LarderIcon(.bell)
///     } trailing: {
///         LarderIcon(.chevronRight)
///     }
struct LarderListItem<Leading: View, Trailing: View>: View {
    private let title: String
    private let supporting: String?
    private let onTap: (() -> Void)?
    private let leading: Leading
    private let trailing: Trailing

    init(
        _ title: String,
        supporting: String? = nil,
        onTap: (() -> Void)? = nil,
        @ViewBuilder leading: () -> Leading,
        @ViewBuilder trailing: () -> Trailing
    ) {
        self.title = title
        self.supporting = supporting
        self.onTap = onTap
        self.leading = leading()
        self.trailing = trailing()
    }

    private var row: some View {
        HStack(spacing: LarderSpace.s3) {
            leading
            VStack(alignment: .leading, spacing: 0) {
                Text(title).larderFont(.body).foregroundStyle(LarderColor.onSurface)
                if let supporting {
                    Text(supporting).larderFont(.bodySm).foregroundStyle(LarderColor.onSurfaceVariant)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            trailing.foregroundStyle(LarderColor.onSurfaceVariant)
        }
        .padding(.vertical, LarderSpace.s2)
        .padding(.horizontal, LarderSpace.s4)
        .frame(minHeight: 56)
        .contentShape(Rectangle())
    }

    var body: some View {
        if let onTap {
            Button(action: onTap) { row }.buttonStyle(.plain)
        } else {
            row
        }
    }
}

extension LarderListItem where Leading == EmptyView, Trailing == EmptyView {
    init(_ title: String, supporting: String? = nil, onTap: (() -> Void)? = nil) {
        self.init(title, supporting: supporting, onTap: onTap) { EmptyView() } trailing: { EmptyView() }
    }
}

extension LarderListItem where Leading == EmptyView {
    init(_ title: String, supporting: String? = nil, onTap: (() -> Void)? = nil, @ViewBuilder trailing: () -> Trailing) {
        self.init(title, supporting: supporting, onTap: onTap, leading: { EmptyView() }, trailing: trailing)
    }
}

/// Where a row sits in a `List`-based group, for its corners and hairlines.
enum LarderGroupPosition {
    case only, first, middle, last

    init(index: Int, count: Int) {
        switch (index, count) {
        case (_, ...1): self = .only
        case (0, _): self = .first
        case (count - 1, _): self = .last
        default: self = .middle
        }
    }
}

/// Open outline of one row: the sides, its rounded ends, and a bottom edge that doubles as the
/// divider to the next row, so two rows never draw a line on the same spot.
private struct GroupRowBorder: Shape {
    let position: LarderGroupPosition
    let radius: CGFloat

    func path(in rect: CGRect) -> Path {
        let inner = rect.insetBy(dx: 0.5, dy: 0.5)
        let (minX, maxX, minY, maxY) = (inner.minX, inner.maxX, inner.minY, inner.maxY)
        var path = Path()
        let roundsTop = position == .first || position == .only
        let roundsBottom = position == .last || position == .only

        path.move(to: CGPoint(x: minX, y: roundsBottom ? maxY - radius : maxY))
        if roundsTop {
            path.addLine(to: CGPoint(x: minX, y: minY + radius))
            path.addArc(tangent1End: CGPoint(x: minX, y: minY), tangent2End: CGPoint(x: minX + radius, y: minY), radius: radius)
            path.addLine(to: CGPoint(x: maxX - radius, y: minY))
            path.addArc(tangent1End: CGPoint(x: maxX, y: minY), tangent2End: CGPoint(x: maxX, y: minY + radius), radius: radius)
        } else {
            path.addLine(to: CGPoint(x: minX, y: rect.minY))
            path.move(to: CGPoint(x: maxX, y: rect.minY))
        }
        if roundsBottom {
            path.addLine(to: CGPoint(x: maxX, y: maxY - radius))
            path.addArc(tangent1End: CGPoint(x: maxX, y: maxY), tangent2End: CGPoint(x: maxX - radius, y: maxY), radius: radius)
            path.addLine(to: CGPoint(x: minX + radius, y: maxY))
            path.addArc(tangent1End: CGPoint(x: minX, y: maxY), tangent2End: CGPoint(x: minX, y: maxY - radius), radius: radius)
        } else {
            path.addLine(to: CGPoint(x: maxX, y: maxY))
            path.addLine(to: CGPoint(x: minX, y: maxY))
        }
        return path
    }
}

private struct GroupRowBackground: View {
    let position: LarderGroupPosition
    let fill: Color

    private var shape: UnevenRoundedRectangle {
        let top = position == .first || position == .only ? LarderRadius.lg : 0
        let bottom = position == .last || position == .only ? LarderRadius.lg : 0
        return UnevenRoundedRectangle(
            topLeadingRadius: top, bottomLeadingRadius: bottom,
            bottomTrailingRadius: bottom, topTrailingRadius: top
        )
    }

    var body: some View {
        ZStack {
            shape.fill(fill)
            GroupRowBorder(position: position, radius: LarderRadius.lg)
                .stroke(LarderColor.outlineVariant, lineWidth: 1)
        }
        .padding(.horizontal, LarderSpace.s4)
    }
}

extension View {
    /// Page colour and plain style for a `List` of `.larderGroupRow(_:)` rows, sections and
    /// headers.
    func larderList() -> some View {
        listStyle(.plain)
            .scrollContentBackground(.hidden)
            .background(LarderColor.background)
            .environment(\.defaultMinListRowHeight, 0)
    }

    /// Makes a `List` row look like a row of a `LarderGroup`, keeping its native swipe actions.
    /// `isSelected` tints it for multi-select.
    func larderGroupRow(_ position: LarderGroupPosition, isSelected: Bool = false) -> some View {
        listRowInsets(EdgeInsets(top: 0, leading: LarderSpace.s4, bottom: 0, trailing: LarderSpace.s4))
            .listRowSeparator(.hidden)
            .listRowBackground(
                GroupRowBackground(
                    position: position,
                    fill: isSelected ? LarderColor.primaryContainer : LarderColor.surface
                )
            )
    }
}

/// Plain heading over a group: "Use first". `trailing` holds an action, if the section has one.
///
///     SectionHeader("Use first")
///     SectionHeader("Queue") { LarderButton("Clear", variant: .text) { clear() } }
struct SectionHeader<Trailing: View>: View {
    private let title: String
    private let trailing: Trailing

    init(_ title: String, @ViewBuilder trailing: () -> Trailing) {
        self.title = title
        self.trailing = trailing()
    }

    var body: some View {
        HStack(spacing: LarderSpace.s2) {
            Text(title)
                .larderFont(.label)
                .foregroundStyle(LarderColor.onSurfaceVariant)
                .accessibilityAddTraits(.isHeader)
            Spacer(minLength: 0)
            trailing
        }
        .padding(.top, LarderSpace.s4)
        .padding(.bottom, LarderSpace.s2)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

extension SectionHeader where Trailing == EmptyView {
    init(_ title: String) {
        self.init(title) { EmptyView() }
    }
}

/// A panel of related content. `.filled` is the default; `.elevated` lifts it onto a surface
/// with a slight shadow.
///
///     LarderCard(title: "Shopping reminders", message: "Shows your list when you stop at a shop you saved.")
///     LarderCard(style: .elevated) { ... }
struct LarderCard<Content: View>: View {
    enum Style { case filled, elevated }

    private let style: Style
    private let content: Content

    init(style: Style = .filled, @ViewBuilder content: () -> Content) {
        self.style = style
        self.content = content()
    }

    var body: some View {
        switch style {
        case .filled:
            panel.background(LarderColor.surfaceContainer, in: RoundedRectangle(cornerRadius: LarderRadius.lg))
        case .elevated:
            panel
                .background(LarderColor.surface, in: RoundedRectangle(cornerRadius: LarderRadius.lg))
                .larderElevation(.level1)
        }
    }

    private var panel: some View {
        content
            .foregroundStyle(LarderColor.onSurface)
            .padding(LarderSpace.s4)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

extension LarderCard where Content == LarderCardText {
    init(style: Style = .filled, title: String, message: String? = nil) {
        self.init(style: style) { LarderCardText(title: title, message: message) }
    }
}

struct LarderCardText: View {
    let title: String
    let message: String?

    var body: some View {
        VStack(alignment: .leading, spacing: LarderSpace.s1) {
            Text(title).larderFont(.title)
            if let message {
                Text(message).larderFont(.body).foregroundStyle(LarderColor.onSurfaceVariant)
            }
        }
    }
}

private struct GroupGallery: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: LarderSpace.s2) {
                SectionHeader("Use first")
                LarderGroup {
                    Text("First row").padding()
                    Text("Second row").padding()
                    Text("Third row").padding()
                }
                SectionHeader("Queue") { LarderButton("Clear", variant: .text) {} }
                LarderGroup {
                    LarderListItem("Reminders", supporting: "1 day before, at 09:00", onTap: {}) {
                        LarderIcon(.bell)
                    } trailing: {
                        LarderIcon(.chevronRight)
                    }
                    LarderListItem("Allergens")
                }
                LarderCard(title: "Shopping reminders", message: "Shows your list when you stop at a shop you saved.")
                LarderCard(style: .elevated, title: "Elevated")
            }
            .foregroundStyle(LarderColor.onSurface)
            .padding(LarderSpace.s4)
        }
        .background(LarderColor.background)
    }
}

private struct ListGallery: View {
    var body: some View {
        List {
            ForEach(0..<4, id: \.self) { index in
                Text("Row \(index)")
                    .padding()
                    .larderGroupRow(.init(index: index, count: 4), isSelected: index == 2)
                    .larderSwipeActions(trailing: [LarderSwipeAction("Delete", icon: .trash2, tint: .destructive) {}])
            }
        }
        .larderList()
    }
}

#Preview("Group") { GroupGallery() }
#Preview("Group, dark") { GroupGallery().preferredColorScheme(.dark) }
#Preview("List rows") { ListGallery() }
#Preview("List rows, dark") { ListGallery().preferredColorScheme(.dark) }
