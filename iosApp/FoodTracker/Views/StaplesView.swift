import SwiftUI

/// Staples: basics the person always has, which every recipe route may use (`Staples`). A checklist
/// of the presets, then the person's own entries with an add field, "Ran out" on each staple
/// (onto the shopping list, with Undo; it counts as out until the entry leaves the list), and the
/// note on what is sent, last. Opened from Settings' Staples card and the Recipe form's staples
/// line. Android: `StaplesScreen`, the same sections, order and behaviour.
struct StaplesView: View {
    @ObservedObject var viewModel: NativePantryViewModel

    @Environment(\.showSnackbar) private var showSnackbar
    @State private var newName = ""
    /// "Salt is already a staple" under the field, until the text changes.
    @State private var duplicateOf: String?

    private static let maxWidth: CGFloat = 640

    var body: some View {
        let language = Staples.appLanguage
        let rows = viewModel.stapleRows
        let full = viewModel.staples.count >= Staples.maxCount
        let own = rows.filter { row in row.staple.presetId.flatMap(StaplePresets.byId) == nil }
        List {
            Text(L10n.staplesIntro)
                .larderFont(.body)
                .foregroundStyle(LarderColor.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, LarderSpace.s1)
                .listRowInsets(EdgeInsets(top: 0, leading: LarderSpace.s4, bottom: 0, trailing: LarderSpace.s4))
                .listRowSeparator(.hidden)
                .listRowBackground(Color.clear)

            header(L10n.staplesCommonHeader)
            ForEach(Array(StaplePresets.all.enumerated()), id: \.element.id) { index, preset in
                presetRow(preset, row: rows.first { $0.staple.presetId == preset.id }, language: language, full: full)
                    .larderGroupRow(LarderGroupPosition(index: index, count: StaplePresets.all.count))
            }

            header(L10n.staplesOwnHeader)
            ForEach(Array(own.enumerated()), id: \.element.staple.id) { index, row in
                ownRow(row)
                    .larderGroupRow(LarderGroupPosition(index: index, count: own.count + 1))
            }
            addRow(language: language, full: full)
                .larderGroupRow(own.isEmpty ? .only : .last)
            if full {
                Text(L10n.staplesFull(Staples.maxCount))
                    .larderFont(.bodySm)
                    .foregroundStyle(LarderColor.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, LarderSpace.s2)
                    .listRowInsets(EdgeInsets(top: 0, leading: LarderSpace.s4, bottom: 0, trailing: LarderSpace.s4))
                    .listRowSeparator(.hidden)
                    .listRowBackground(Color.clear)
            }

            // Always last: what is sent, and when not.
            HStack(alignment: .top, spacing: LarderSpace.s2) {
                LarderIcon(.info, size: .custom(16))
                Text(L10n.staplesNote)
                    .larderFont(.bodySm)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .foregroundStyle(LarderColor.onSurfaceVariant)
            .accessibilityElement(children: .combine)
            .padding(.top, LarderSpace.s4)
            .padding(.bottom, LarderSpace.s8)
            .listRowInsets(EdgeInsets(top: 0, leading: LarderSpace.s4, bottom: 0, trailing: LarderSpace.s4))
            .listRowSeparator(.hidden)
            .listRowBackground(Color.clear)
        }
        .larderList()
        .frame(maxWidth: Self.maxWidth)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(LarderColor.background)
        .navigationTitle(L10n.staplesTitle)
        .navigationBarTitleDisplayMode(.inline)
        .shellChromeEffect(hideNav: true)
    }

    private func header(_ title: String) -> some View {
        SectionHeader(title)
            .padding(.top, LarderSpace.s4)
            .listRowInsets(EdgeInsets(top: 0, leading: LarderSpace.s4, bottom: 0, trailing: LarderSpace.s4))
            .listRowSeparator(.hidden)
            .listRowBackground(Color.clear)
    }

    // MARK: Rows

    /// A preset: its tick (a checkbox), its name in the app's language and, ticked, Ran out or
    /// "On your shopping list". With 20 staples an unticked one can't be ticked.
    private func presetRow(_ preset: StaplePreset, row: StapleRow?, language: String, full: Bool) -> some View {
        let name = preset.name(language)
        let ticked = row != nil
        let toggle = {
            if let row {
                remove(row)
            } else if !full {
                viewModel.addStaple(name: name, presetId: preset.id)
            }
        }
        return StaplePresetRow(name: name, ticked: ticked, disabled: !ticked && full, toggle: toggle) {
            if let row { trailing(row) }
        }
    }

    /// An own entry: its name, Ran out or "On your shopping list", and remove.
    private func ownRow(_ row: StapleRow) -> some View {
        HStack(spacing: LarderSpace.s3) {
            Text(row.name)
                .larderFont(.body)
                .foregroundStyle(LarderColor.onSurface)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
            trailing(row)
            LarderIconButton(.x, label: L10n.staplesRemove(row.name)) { remove(row) }
        }
        .padding(.leading, LarderSpace.s4)
        .padding(.trailing, LarderSpace.s1)
        .frame(minHeight: LarderSize.control)
        .padding(.vertical, LarderSpace.s1)
    }

    @ViewBuilder
    private func trailing(_ row: StapleRow) -> some View {
        if row.out {
            HStack(spacing: LarderSpace.s1) {
                LarderIcon(.shoppingBasket, size: .custom(16))
                Text(L10n.staplesOnList).larderFont(.caption)
            }
            .foregroundStyle(LarderColor.onSurfaceVariant)
            .accessibilityElement(children: .combine)
        } else {
            LarderButton(L10n.staplesRanOut, variant: .text) { ranOut(row) }
                .accessibilityLabel(L10n.staplesRanOutDescription(row.name))
        }
    }

    private func addRow(language: String, full: Bool) -> some View {
        HStack(alignment: .top, spacing: LarderSpace.s2) {
            LarderTextField(
                nil,
                text: $newName,
                placeholder: L10n.staplesAddPlaceholder,
                message: duplicateOf.map { .error(L10n.staplesAlready($0)) }
            )
            .accessibilityLabel(L10n.staplesAddPlaceholder)
            .submitLabel(.done)
            .onSubmit { add(language: language) }
            .onChange(of: newName) { _, text in
                // 40 code points, as Android counts them.
                let limited = Staples.limitedName(text)
                if limited != text { newName = limited }
                duplicateOf = nil
            }
            LarderButton(L10n.staplesAdd, variant: .text) { add(language: language) }
                .disabled(newName.trimmingCharacters(in: .whitespaces).isEmpty)
                .padding(.top, LarderSpace.s1)
        }
        .disabled(full)
        .padding(.horizontal, LarderSpace.s4)
        .padding(.vertical, LarderSpace.s2)
    }

    // MARK: Actions

    private func add(language: String) {
        switch Staples.addCheck(viewModel.staples, typed: newName, language: language) {
        case .empty, .full:
            return
        case .duplicate(let name):
            duplicateOf = name
        case .preset(let id):
            guard let preset = StaplePresets.byId(id) else { return }
            viewModel.addStaple(name: preset.name(language), presetId: id)
            newName = ""
        case .own(let name):
            viewModel.addStaple(name: name)
            newName = ""
        }
    }

    private func remove(_ row: StapleRow) {
        guard let removed = viewModel.removeStaple(id: row.staple.id) else { return }
        showSnackbar(L10n.staplesRemoved(row.name)) { [viewModel] in viewModel.restoreStaple(removed) }
    }

    private func ranOut(_ row: StapleRow) {
        let name = row.name
        viewModel.stapleRanOut(displayName: name)
        showSnackbar(L10n.staplesAddedToList(name)) { [viewModel] in viewModel.undoStapleRanOut(displayName: name) }
    }
}

/// A preset's row: its tick (a checkbox) and its name, the name tapping the tick too, and what
/// follows it. The Staples screen's "Common staples" and onboarding's Staples step.
struct StaplePresetRow<Trailing: View>: View {
    let name: String
    let ticked: Bool
    /// Unticked at the 20 cap: can't be ticked.
    let disabled: Bool
    let toggle: () -> Void
    @ViewBuilder let trailing: () -> Trailing

    var body: some View {
        HStack(spacing: LarderSpace.s3) {
            LarderTick(isOn: ticked, label: name, onToggle: toggle)
                .disabled(disabled)
            Text(name)
                .larderFont(.body)
                .foregroundStyle(disabled ? LarderColor.onSurfaceVariant : LarderColor.onSurface)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
                .onTapGesture { if !disabled { toggle() } }
                .accessibilityHidden(true)
            trailing()
        }
        .padding(.horizontal, LarderSpace.s4)
        .frame(minHeight: LarderSize.control)
        .padding(.vertical, LarderSpace.s1)
    }
}

extension StaplePresetRow where Trailing == EmptyView {
    init(name: String, ticked: Bool, disabled: Bool, toggle: @escaping () -> Void) {
        self.init(name: name, ticked: ticked, disabled: disabled, toggle: toggle) { EmptyView() }
    }
}
