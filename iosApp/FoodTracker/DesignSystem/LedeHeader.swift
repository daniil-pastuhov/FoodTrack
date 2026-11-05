import SwiftUI

/// The tab screens' header: the brand bar, then the sentence that answers the screen's question.
///
///     LedeHeader("Three things to use by tomorrow", brand: L10n.appName) {
///         LarderIconButton(.search, label: "Search") { ... }
///         LarderIconButton(.slidersHorizontal, label: "Filters") { ... }
///     } below: {
///         chips
///     }
///
/// Without `brand` it is just the lede, as on Settings.
struct LedeHeader<Actions: View, Below: View>: View {
    private let title: String
    private let brand: String?
    private let actions: Actions
    private let below: Below

    init(
        _ title: String,
        brand: String? = nil,
        @ViewBuilder actions: () -> Actions,
        @ViewBuilder below: () -> Below
    ) {
        self.title = title
        self.brand = brand
        self.actions = actions()
        self.below = below()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 0) {
                if let brand {
                    Text(brand).larderFont(.title)
                }
                Spacer(minLength: 0)
                actions
            }
            .frame(minHeight: LarderSize.touch)
            .padding(.trailing, -LarderSpace.s2)

            Text(title)
                .larderFont(.display)
                .accessibilityAddTraits(.isHeader)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, LarderSpace.s1)
                .padding(.bottom, LarderSpace.s3)

            below
        }
        .foregroundStyle(LarderColor.onBackground)
        .padding(.vertical, LarderSpace.s3)
        .padding(.horizontal, LarderSpace.s4)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

extension LedeHeader where Below == EmptyView {
    init(_ title: String, brand: String? = nil, @ViewBuilder actions: () -> Actions) {
        self.init(title, brand: brand, actions: actions) { EmptyView() }
    }
}

extension LedeHeader where Actions == EmptyView, Below == EmptyView {
    init(_ title: String, brand: String? = nil) {
        self.init(title, brand: brand) { EmptyView() } below: { EmptyView() }
    }
}

/// The bar on pushed screens that don't sit in a `NavigationStack`: back, a serif title, then
/// `actions`, on the page colour. Pass nil for `onBack` to leave the arrow out. Screens in a stack
/// keep the native bar, which `LarderTheme` already styles.
///
///     LarderTopBar("Categories", onBack: { dismiss() }) { LarderIconButton(...) }
struct LarderTopBar<Actions: View>: View {
    private let title: String
    private let onBack: (() -> Void)?
    private let actions: Actions

    init(_ title: String, onBack: (() -> Void)?, @ViewBuilder actions: () -> Actions) {
        self.title = title
        self.onBack = onBack
        self.actions = actions()
    }

    var body: some View {
        HStack(spacing: LarderSpace.s2) {
            if let onBack {
                LarderIconButton(.arrowLeft, label: L10n.backButton, action: onBack)
            }
            Text(title)
                .larderFont(.title)
                .lineLimit(1)
                .foregroundStyle(LarderColor.onBackground)
                .padding(.leading, onBack == nil ? LarderSpace.s2 : 0)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityAddTraits(.isHeader)
            actions
        }
        .padding(.horizontal, LarderSpace.s2)
        .frame(minHeight: 64)
        .background(LarderColor.background)
    }
}

extension LarderTopBar where Actions == EmptyView {
    init(_ title: String, onBack: (() -> Void)?) {
        self.init(title, onBack: onBack) { EmptyView() }
    }
}

private struct HeaderGallery: View {
    var body: some View {
        VStack(spacing: 0) {
            LedeHeader("Three things to use by tomorrow", brand: "BestBefore") {
                LarderIconButton(.search, label: "Search") {}
                LarderIconButton(.slidersHorizontal, label: "Filters") {}
            } below: {
                HStack(spacing: LarderSpace.s2) {
                    LarderChip("All", count: 13, isSelected: true) {}
                    LarderChip("Soon", count: 4) {}
                }
            }
            LedeHeader("Settings")
            LarderTopBar("Categories", onBack: {}) {
                LarderIconButton(.plus, label: "Add") {}
            }
            LarderTopBar("No back arrow", onBack: nil)
            Spacer()
        }
        .background(LarderColor.background)
    }
}

#Preview("Lede") { HeaderGallery() }
#Preview("Lede, dark") { HeaderGallery().preferredColorScheme(.dark) }
#Preview("Lede, large text") { HeaderGallery().dynamicTypeSize(.accessibility2) }
