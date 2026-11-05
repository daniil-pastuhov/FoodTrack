import SwiftUI

/// The inside of a sheet: its serif title and scrolling content. Filters, sort and product
/// details live in these; decisions go in a `larderDialog`.
///
///     .larderSheet(isPresented: $showSort) {
///         LarderSheet("Sort by") { chips }
///     }
struct LarderSheet<Content: View>: View {
    private let title: String?
    private let content: Content

    init(_ title: String? = nil, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: LarderSpace.s3) {
                if let title {
                    Text(title)
                        .larderFont(.title)
                        .foregroundStyle(LarderColor.onSurface)
                        .accessibilityAddTraits(.isHeader)
                }
                content
            }
            .padding(.top, LarderSpace.s6)
            .padding(.horizontal, LarderSpace.s4)
            .padding(.bottom, LarderSpace.s6)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

private struct LarderSheetPresentation: ViewModifier {
    let detents: Set<PresentationDetent>

    func body(content: Content) -> some View {
        content
            .presentationDetents(detents)
            .presentationDragIndicator(.visible)
            .presentationBackground(LarderColor.surfaceContainerHigh)
            .presentationCornerRadius(LarderRadius.xl)
    }
}

extension View {
    /// A native sheet with the Larder surface, medium and large detents by default.
    func larderSheet<Content: View>(
        isPresented: Binding<Bool>,
        detents: Set<PresentationDetent> = [.medium, .large],
        onDismiss: (() -> Void)? = nil,
        @ViewBuilder content: @escaping () -> Content
    ) -> some View {
        sheet(isPresented: isPresented, onDismiss: onDismiss) {
            content().modifier(LarderSheetPresentation(detents: detents))
        }
    }

    func larderSheet<Item: Identifiable, Content: View>(
        item: Binding<Item?>,
        detents: Set<PresentationDetent> = [.medium, .large],
        onDismiss: (() -> Void)? = nil,
        @ViewBuilder content: @escaping (Item) -> Content
    ) -> some View {
        sheet(item: item, onDismiss: onDismiss) { value in
            content(value).modifier(LarderSheetPresentation(detents: detents))
        }
    }

    /// Picks a date with the wheels. The graphical calendar stays out on purpose: tapping its
    /// header collapses it into a month and year wheel, and people got stuck there with no
    /// obvious way back to the day.
    ///
    ///     .larderDatePicker(isPresented: $pickingDate, date: $expiryDate, title: L10n.expiryPickerLabel)
    func larderDatePicker(
        isPresented: Binding<Bool>,
        date: Binding<Date>,
        title: String
    ) -> some View {
        larderSheet(isPresented: isPresented, detents: [.height(320)]) {
            LarderDatePickerSheet(title: title, date: date)
        }
    }
}

/// Nothing else on this sheet scrolls, so a drag on the wheel always spins the wheel.
private struct LarderDatePickerSheet: View {
    @Environment(\.dismiss) private var dismiss
    let title: String
    @Binding var date: Date

    var body: some View {
        NavigationStack {
            DatePicker(title, selection: $date, displayedComponents: [.date])
                .datePickerStyle(.wheel)
                .labelsHidden()
                .frame(maxWidth: .infinity)
                .navigationTitle(title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(L10n.doneButton) { dismiss() }
                            .fontWeight(.bold)
                    }
                }
                .toolbarBackground(.hidden, for: .navigationBar)
        }
    }
}

private struct SheetPreview: View {
    @State private var isPresented = true
    @State private var sort = "Expiry date"

    var body: some View {
        LarderButton("Show sheet") { isPresented = true }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(LarderColor.background)
            .larderSheet(isPresented: $isPresented) {
                LarderSheet("Sort by") {
                    FlowLayout(spacing: LarderSpace.s2) {
                        ForEach(["Expiry date", "Name", "Date added", "Storage location"], id: \.self) { option in
                            LarderChip(option, isSelected: sort == option) { sort = option }
                        }
                    }
                }
            }
    }
}

private struct DatePickerPreview: View {
    @State private var isPresented = true
    @State private var date = Date()

    var body: some View {
        LarderButton("Pick a date") { isPresented = true }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(LarderColor.background)
            .larderDatePicker(isPresented: $isPresented, date: $date, title: "Best before")
    }
}

#Preview("Sheet") { SheetPreview() }
#Preview("Sheet, dark") { SheetPreview().preferredColorScheme(.dark) }
#Preview("Date picker") { DatePickerPreview() }
#Preview("Date picker, dark") { DatePickerPreview().preferredColorScheme(.dark) }
