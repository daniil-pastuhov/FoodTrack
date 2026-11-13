import SwiftUI

/// A condition that lasts: the T4 tier, and with `links` the T2 form summary.
///
///     LarderCallout(.warning, icon: .bellOff, title: "Reminders are off",
///                   message: "Notifications are turned off for BestBefore in your phone's settings.",
///                   actionTitle: "Open settings", action: openSettings)
///
///     LarderCallout(.error, title: "2 things to fix before saving",
///                   links: [.init("Product name") { focusName() }, .init("Best before") { focusDate() }])
struct LarderCallout: View {
    enum Kind {
        case info, warning, error, success

        fileprivate var defaultIcon: LarderIconName {
            switch self {
            case .info: return .info
            case .warning: return .triangleAlert
            case .error: return .circleAlert
            case .success: return .circleCheck
            }
        }

        fileprivate var colors: (background: Color, foreground: Color) {
            switch self {
            case .info: return (LarderColor.infoContainer, LarderColor.onInfoContainer)
            case .warning: return (LarderColor.warningContainer, LarderColor.onWarningContainer)
            case .error: return (LarderColor.errorContainer, LarderColor.onErrorContainer)
            case .success: return (LarderColor.successContainer, LarderColor.onSuccessContainer)
            }
        }
    }

    struct Link: Identifiable {
        let id = UUID()
        let title: String
        let action: () -> Void

        init(_ title: String, action: @escaping () -> Void) {
            self.title = title
            self.action = action
        }
    }

    private let kind: Kind
    private let icon: LarderIconName?
    private let title: String?
    private let message: String?
    private let links: [Link]
    private let actionTitle: String?
    private let action: (() -> Void)?
    private let onDismiss: (() -> Void)?

    init(
        _ kind: Kind,
        icon: LarderIconName? = nil,
        title: String? = nil,
        message: String? = nil,
        links: [Link] = [],
        actionTitle: String? = nil,
        action: (() -> Void)? = nil,
        onDismiss: (() -> Void)? = nil
    ) {
        self.kind = kind
        self.icon = icon
        self.title = title
        self.message = message
        self.links = links
        self.actionTitle = actionTitle
        self.action = action
        self.onDismiss = onDismiss
    }

    var body: some View {
        HStack(alignment: .top, spacing: LarderSpace.s3) {
            LarderIcon(icon ?? kind.defaultIcon)
            VStack(alignment: .leading, spacing: 0) {
                if let title {
                    Text(title).larderFont(.label)
                }
                if let message {
                    Text(message).larderFont(.bodySm)
                }
                ForEach(links) { link in
                    Button(action: link.action) {
                        Text("•  ") + Text(link.title).underline()
                    }
                    .buttonStyle(.plain)
                    .larderFont(.bodySm)
                    .frame(minHeight: 44)
                }
                if let actionTitle, let action {
                    LarderButton(actionTitle, variant: .text, action: action)
                        .environment(\.larderTextButtonColor, kind.colors.foreground)
                        .padding(.leading, -LarderSpace.s3)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if let onDismiss {
                LarderIconButton(.x, label: L10n.dismissButton, color: kind.colors.foreground, action: onDismiss)
                    .padding(.top, -LarderSpace.s3)
                    .padding(.trailing, -LarderSpace.s3)
            }
        }
        .foregroundStyle(kind.colors.foreground)
        .padding(.vertical, LarderSpace.s3)
        .padding(.horizontal, LarderSpace.s4)
        .background(kind.colors.background, in: RoundedRectangle(cornerRadius: LarderRadius.md))
        .accessibilityElement(children: .contain)
    }
}

private struct CalloutGallery: View {
    var body: some View {
        ScrollView {
            VStack(spacing: LarderSpace.s3) {
                LarderCallout(.info, title: "Good to know", message: "Barcodes are looked up on Open Food Facts.")
                LarderCallout(.warning, icon: .bellOff, title: "Reminders are off", message: "Notifications are turned off for BestBefore in your phone's settings.", actionTitle: "Open settings", action: {})
                LarderCallout(.error, title: "Contains milk", message: "This product lists an allergen you asked to be warned about.")
                LarderCallout(.success, message: "Backup saved")
                LarderCallout(.error, title: "2 things to fix before saving", links: [.init("Product name") {}, .init("Best before") {}])
                LarderCallout(.warning, icon: .wifiOff, title: "You're offline", message: "Can't look this barcode up right now.", onDismiss: {})
            }
            .padding(LarderSpace.s4)
        }
        .background(LarderColor.background)
    }
}

#Preview("Callouts") { CalloutGallery() }
#Preview("Callouts, dark") { CalloutGallery().preferredColorScheme(.dark) }
