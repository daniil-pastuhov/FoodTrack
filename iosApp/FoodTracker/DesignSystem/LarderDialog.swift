import SwiftUI

/// One button of a `larderDialog`.
struct LarderDialogAction: Identifiable {
    let id = UUID()
    let title: String
    let role: ButtonRole?
    let action: () -> Void

    init(_ title: String, role: ButtonRole? = nil, action: @escaping () -> Void = {}) {
        self.title = title
        self.role = role
        self.action = action
    }

    /// "Keep my pantry": takes the cancel slot, and is what the alert does when dismissed.
    static func cancel(_ title: String, action: @escaping () -> Void = {}) -> Self {
        Self(title, role: .cancel, action: action)
    }

    /// The irreversible choice, in the system's destructive style.
    static func destructive(_ title: String, action: @escaping () -> Void) -> Self {
        Self(title, role: .destructive, action: action)
    }
}

extension View {
    /// The T6 tier, a decision or something that can't be undone, as a native alert. Reversible
    /// actions don't get one: they happen straight away with Undo.
    ///
    ///     .larderDialog("Clear all pantry data?", isPresented: $confirmClear,
    ///                   message: "This removes all 13 items and cancels their reminders. You can't undo this.",
    ///                   actions: [.cancel("Keep my pantry"), .destructive("Clear all data") { clear() }])
    func larderDialog(
        _ title: String,
        isPresented: Binding<Bool>,
        message: String? = nil,
        actions: [LarderDialogAction]
    ) -> some View {
        alert(title, isPresented: isPresented) {
            ForEach(actions) { action in
                Button(action.title, role: action.role, action: action.action)
            }
        } message: {
            if let message { Text(message) }
        }
    }

    /// The same, for a dialog about something: shown while `item` is non-nil.
    func larderDialog<Item>(
        item: Binding<Item?>,
        title: (Item) -> String,
        message: ((Item) -> String)? = nil,
        actions: (Item) -> [LarderDialogAction]
    ) -> some View {
        let presented = Binding(
            get: { item.wrappedValue != nil },
            set: { if !$0 { item.wrappedValue = nil } }
        )
        return larderDialog(
            item.wrappedValue.map(title) ?? "",
            isPresented: presented,
            message: item.wrappedValue.flatMap { value in message.map { $0(value) } },
            actions: item.wrappedValue.map(actions) ?? []
        )
    }
}

private struct DialogPreview: View {
    @State private var isPresented = true
    var title = "Clear all pantry data?"
    var message = "This removes all 13 items and cancels their reminders. You can't undo this."
    var actions: [LarderDialogAction] = [.cancel("Keep my pantry"), .destructive("Clear all data") {}]

    var body: some View {
        LarderButton("Show dialog") { isPresented = true }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(LarderColor.background)
            .larderDialog(title, isPresented: $isPresented, message: message, actions: actions)
    }
}

private let choiceActions: [LarderDialogAction] = [.init("Add one more"), .cancel("Keep separate")]

#Preview("Destructive") { DialogPreview() }
#Preview("Destructive, dark") { DialogPreview().preferredColorScheme(.dark) }
#Preview("Choice") {
    DialogPreview(title: "Already in your pantry", message: "You already have Greek yoghurt. Add one more to it, or keep this one separate?", actions: choiceActions)
}
#Preview("Choice, dark") {
    DialogPreview(title: "Already in your pantry", message: "You already have Greek yoghurt. Add one more to it, or keep this one separate?", actions: choiceActions)
        .preferredColorScheme(.dark)
}
