import SwiftUI
import UIKit

/// The T3 tier: something just happened. iOS has no native equivalent, so this is the same
/// component as on Android.
///
/// Screens never place one themselves. They ask the shell, which keeps it above Scan and the
/// pill:
///
///     @Environment(\.showSnackbar) private var showSnackbar
///
///     showSnackbar("Mature cheddar saved to Fridge")
///     showSnackbar("Baby spinach removed", undo: { viewModel.restoreItem(item) })
///     showSnackbar("Backup saved", actionTitle: "Share") { share() }
struct LarderSnackbar: View {
    let message: String
    var actionTitle: String? = nil
    var action: (() -> Void)? = nil

    var body: some View {
        HStack(spacing: LarderSpace.s3) {
            Text(message)
                .larderFont(.bodySm)
                .lineLimit(2)
                .foregroundStyle(LarderColor.onInverseSurface)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let actionTitle, let action {
                LarderButton(actionTitle, variant: .text, action: action)
                    .environment(\.larderTextButtonColor, LarderColor.inversePrimary)
            }
        }
        .padding(.vertical, LarderSpace.s1)
        .padding(.leading, LarderSpace.s4)
        .padding(.trailing, LarderSpace.s2)
        .frame(maxWidth: 560, minHeight: LarderSize.control)
        .background(LarderColor.inverseSurface, in: RoundedRectangle(cornerRadius: LarderRadius.md))
        .larderElevation(.level3)
        .accessibilityElement(children: .contain)
    }
}

/// What the shell is showing. One at a time: a new one replaces the last, as it did for the old
/// undo toast.
struct LarderSnackbarContent: Identifiable, Equatable {
    let id = UUID()
    let message: String
    var actionTitle: String? = nil
    var action: (() -> Void)? = nil

    static func == (lhs: Self, rhs: Self) -> Bool { lhs.id == rhs.id }
}

/// Handed down through the environment, like SwiftUI's own `openURL`.
struct ShowSnackbarAction {
    fileprivate var present: (LarderSnackbarContent) -> Void = { _ in }

    init(present: @escaping (LarderSnackbarContent) -> Void = { _ in }) {
        self.present = present
    }

    func callAsFunction(_ message: String) {
        present(LarderSnackbarContent(message: message))
    }

    func callAsFunction(_ message: String, undo: @escaping () -> Void) {
        present(LarderSnackbarContent(message: message, actionTitle: L10n.undoAction, action: undo))
    }

    func callAsFunction(_ message: String, actionTitle: String, action: @escaping () -> Void) {
        present(LarderSnackbarContent(message: message, actionTitle: actionTitle, action: action))
    }
}

private struct ShowSnackbarKey: EnvironmentKey {
    static let defaultValue = ShowSnackbarAction()
}

extension EnvironmentValues {
    var showSnackbar: ShowSnackbarAction {
        get { self[ShowSnackbarKey.self] }
        set { self[ShowSnackbarKey.self] = newValue }
    }
}

/// Shows `content`, dismisses it after a few seconds, and clears it when its action is tapped.
struct LarderSnackbarHost: View {
    @Binding var content: LarderSnackbarContent?

    /// VoiceOver needs time to reach the Undo button.
    private var duration: Duration {
        UIAccessibility.isVoiceOverRunning ? .seconds(10) : .seconds(4)
    }

    var body: some View {
        Group {
            if let content {
                LarderSnackbar(
                    message: content.message,
                    actionTitle: content.actionTitle,
                    action: content.action.map { action in
                        {
                            action()
                            self.content = nil
                        }
                    }
                )
                .padding(.horizontal, LarderSpace.s4)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.25), value: content?.id)
        .task(id: content?.id) {
            guard let content else { return }
            UIAccessibility.post(notification: .announcement, argument: content.message)
            try? await Task.sleep(for: duration)
            if !Task.isCancelled { self.content = nil }
        }
    }
}

private struct SnackbarGallery: View {
    var body: some View {
        VStack(spacing: LarderSpace.s3) {
            LarderSnackbar(message: "Baby spinach removed", actionTitle: "Undo", action: {})
            LarderSnackbar(message: "Mature cheddar saved to Fridge")
            LarderSnackbar(message: "A very long product name that needs both of its lines was removed from your pantry", actionTitle: "Undo", action: {})
        }
        .padding(LarderSpace.s4)
        .frame(maxHeight: .infinity)
        .background(LarderColor.background)
    }
}

#Preview("Snackbar") { SnackbarGallery() }
#Preview("Snackbar, dark") { SnackbarGallery().preferredColorScheme(.dark) }
