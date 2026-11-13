import SwiftUI
import UIKit

/// The Recipe tab: what can I cook from what is about to expire? The soonest items are ticked,
/// a short form sets the kind of dish, and one primary action writes it, pinned above the pill
/// where Scan sits on Home. Free routes only for now: the phone's own model where it has one,
/// and a prompt shared to another AI app.
struct RecipeView: View {
    @ObservedObject var pantry: NativePantryViewModel
    @ObservedObject var model: RecipeViewModel
    let onScan: () -> Void

    @ObservedObject private var settingsStore = SettingsStore.shared
    @Environment(\.isExpanded) private var isExpanded
    @Environment(\.showSnackbar) private var showSnackbar
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.shellClearance) private var shellClearance

    @State private var pinnedHeight: CGFloat = 0
    @State private var sharedPrompt: SharedText?
    @State private var addingItems: OtherItems?

    private var expiringSoonDays: Int { settingsStore.settings.expiringSoonDays }
    private var nothingTicked: Bool { model.selectedItems(from: pantry.items).isEmpty }
    private var routes: RecipeRoutes { model.routes }

    var body: some View {
        NavigationStack(path: $model.path) {
            root
                .toolbar(.hidden, for: .navigationBar)
                .navigationDestination(for: RecipeDestination.self) { destination in
                    switch destination {
                    case .result(let id):
                        RecipeResultView(entry: model.savedRecipe(id: id))
                    case .saved:
                        SavedRecipesView(model: model)
                    case .settings:
                        RecipeSettingsView(model: model)
                    }
                }
        }
        // Outside the stack, which doesn't pass the shell's clearance on to its content's own
        // insets: here it sits on top of that clearance, and the form scrolls clear of it.
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if showsPinnedAction {
                pinnedAction
                    .padding(.horizontal, LarderSpace.s4)
                    .padding(.vertical, LarderSpace.s2)
                    .frame(maxWidth: isExpanded ? 440 + 2 * LarderSpace.s4 : .infinity)
                    .frame(maxWidth: .infinity, alignment: isExpanded ? .trailing : .center)
                    .padding(.trailing, isExpanded ? LarderSpace.s2 : 0)
                    .onGeometryChange(for: CGFloat.self, of: \.size.height) { pinnedHeight = $0 }
            }
        }
        .shellPinnedAction(height: showsPinnedAction ? pinnedHeight : 0)
        .onAppear {
            model.syncSelection(with: pantry.items)
            model.refreshAvailability()
        }
        .onChange(of: pantry.items) { _, items in model.syncSelection(with: items) }
        // The model can finish downloading, or Apple Intelligence be switched on, meanwhile.
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { model.refreshAvailability() }
        }
        .onChange(of: model.failure) { _, failure in
            guard let failure else { return }
            model.failure = nil
            switch failure {
            case .onDeviceFailed:
                showSnackbar(L10n.recipeFailedOnDevice, actionTitle: L10n.settingsTryAgain) {
                    model.writeOnDevice(from: pantry.items)
                }
            case .onDeviceRefused:
                showSnackbar(L10n.recipeRefusedOnDevice)
            }
        }
        .sheet(item: $sharedPrompt) { prompt in
            ActivityView(activityItems: [prompt.text])
        }
        .larderSheet(item: $addingItems, detents: [.large]) { other in
            AddOtherItemsSheet(
                items: other.items,
                expiringSoonDays: expiringSoonDays,
                model: model,
                onDone: { addingItems = nil }
            )
        }
    }

    // MARK: Layout

    @ViewBuilder
    private var root: some View {
        Group {
            if pantry.items.isEmpty {
                VStack(spacing: 0) {
                    header
                    EmptyState(.shelf, title: L10n.recipeEmptyTitle, message: L10n.recipeEmptyMessage) {
                        LarderButton(L10n.scanButton, icon: .scanBarcode, action: onScan)
                    }
                }
                .padding(.bottom, shellClearance)
            } else if model.phase == .writingOnDevice {
                VStack(spacing: 0) {
                    header
                    writingOnDevice
                }
                .padding(.bottom, shellClearance)
            } else {
                form
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(LarderColor.background.ignoresSafeArea())
    }

    private var header: some View {
        LedeHeader(L10n.recipeLede, brand: L10n.appName) {
            LarderIconButton(.bookmark, label: L10n.recipeSavedButton) { model.path.append(.saved) }
            LarderIconButton(.slidersHorizontal, label: L10n.recipeSettingsButton) { model.path.append(.settings) }
        }
    }

    private var form: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header
                VStack(alignment: .leading, spacing: LarderSpace.s4) {
                    if routes.onDevicePreparing {
                        LarderCallout(
                            .info,
                            icon: .download,
                            title: L10n.recipePreparingTitle,
                            message: L10n.recipePreparingMessage
                        )
                    }
                    if isExpanded {
                        HStack(alignment: .top, spacing: LarderSpace.s6) {
                            ingredients.frame(maxWidth: .infinity)
                            VStack(alignment: .leading, spacing: LarderSpace.s4) {
                                quickForm
                                actions
                            }
                            .frame(maxWidth: .infinity)
                            // Lines the form up with the first row, under "Cook with".
                            .padding(.top, LarderSpace.s8 + LarderSpace.s2)
                        }
                    } else {
                        ingredients
                        quickForm
                        actions
                    }
                }
                .padding(.horizontal, LarderSpace.s4)
                // The stack keeps the shell's clearance and the pinned action from reaching
                // this scroll view as insets, so the form leaves room for them itself.
                .padding(.bottom, LarderSpace.s4 + shellClearance + (showsPinnedAction ? pinnedHeight : 0))
            }
            .frame(maxWidth: isExpanded ? 960 : .infinity)
            .frame(maxWidth: .infinity)
        }
    }

    /// Whether the pinned action shows: on the form, not over a pushed screen or a state that
    /// has nothing to act on.
    private var showsPinnedAction: Bool {
        model.path.isEmpty && !pantry.items.isEmpty && model.phase == .idle
    }

    // MARK: Cook with

    private var ingredients: some View {
        let items = pantry.items
        let shown = RecipeViewModel.shownItems(items, selected: model.selectedIDs, expiringSoonDays: expiringSoonDays)
        let others = RecipeViewModel.otherItems(items, selected: model.selectedIDs, expiringSoonDays: expiringSoonDays)
        return VStack(alignment: .leading, spacing: 0) {
            SectionHeader(L10n.recipeCookWith)
            LarderGroup {
                ForEach(shown) { item in
                    ingredientRow(item)
                }
                if !others.isEmpty {
                    LarderListItem(
                        L10n.recipeAddOtherItems,
                        supporting: L10n.recipeMoreInPantry(others.count),
                        onTap: { addingItems = OtherItems(items: others) }
                    ) {
                        LarderIcon(.plus).foregroundStyle(LarderColor.primary)
                    } trailing: {
                        LarderIcon(.chevronRight)
                    }
                }
            }
        }
    }

    private func ingredientRow(_ item: PantryItem) -> some View {
        let isSelected = model.selectedIDs.contains(item.id)
        return PantryRow(
            item: item,
            expiringSoonDays: expiringSoonDays,
            place: "",
            selection: .init(isSelected: isSelected, toggle: { model.toggle(item.id) })
        )
        // The whole row is the control, as in Home's multi-select.
        .contentShape(Rectangle())
        .onTapGesture { model.toggle(item.id) }
    }

    // MARK: Quick form

    private var quickForm: some View {
        RecipeFormCard {
            RecipeDifficultyField(selection: model.binding(\.difficulty))
            RecipeServingsField(servings: model.binding(\.servings))
            RecipeTimeField(maxMinutes: model.binding(\.maxMinutes))
            Button { model.path.append(.settings) } label: {
                HStack(spacing: LarderSpace.s3) {
                    LarderIcon(.slidersHorizontal).foregroundStyle(LarderColor.onSurfaceVariant)
                    VStack(alignment: .leading, spacing: 0) {
                        Text(L10n.recipeMoreOptions)
                            .larderFont(.label)
                            .foregroundStyle(LarderColor.onSurface)
                        Text(L10n.recipeOptionsSummary(model.config))
                            .larderFont(.bodySm)
                            .foregroundStyle(LarderColor.onSurfaceVariant)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    LarderIcon(.chevronRight).foregroundStyle(LarderColor.onSurfaceVariant)
                }
                .padding(.vertical, LarderSpace.s2)
                .frame(minHeight: LarderSize.touch)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
    }

    // MARK: Actions

    /// The routes that aren't the pinned one, and the line that explains a disabled button.
    @ViewBuilder
    private var actions: some View {
        if nothingTicked {
            hint(L10n.recipeNoneSelectedHint)
        } else if routes.onDevice {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: LarderSpace.s2) { shareButton; copyButton }
                VStack(spacing: 0) { shareButton; copyButton }
            }
            .frame(maxWidth: .infinity)
        } else {
            VStack(spacing: LarderSpace.s2) {
                LarderButton(L10n.recipeCopyPrompt, icon: .copy, variant: .outline, fullWidth: true, action: copyPrompt)
                hint(L10n.recipePromptOnlyHint)
            }
        }
    }

    private var shareButton: some View {
        LarderButton(L10n.recipeSharePrompt, icon: .share2, variant: .text, action: sharePrompt)
    }

    private var copyButton: some View {
        LarderButton(L10n.recipeCopyPrompt, icon: .copy, variant: .text, action: copyPrompt)
    }

    private func hint(_ text: String) -> some View {
        Text(text)
            .larderFont(.bodySm)
            .foregroundStyle(LarderColor.onSurfaceVariant)
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity)
    }

    /// The free route that writes in the app where the phone can, else the shared prompt.
    @ViewBuilder
    private var pinnedAction: some View {
        if routes.onDevice {
            RecipeGoButton(title: L10n.recipeGenerateOnPhone, icon: .smartphone, cost: L10n.recipeCostFree) {
                model.writeOnDevice(from: pantry.items)
            }
            .disabled(nothingTicked)
        } else {
            RecipeGoButton(title: L10n.recipeSharePrompt, icon: .share2, action: sharePrompt)
                .disabled(nothingTicked)
        }
    }

    private var writingOnDevice: some View {
        VStack(spacing: LarderSpace.s3) {
            LarderSpinner()
            Text(L10n.recipeWritingOnPhoneTitle)
                .larderFont(.title)
                .foregroundStyle(LarderColor.onSurface)
                .padding(.top, LarderSpace.s2)
                .accessibilityAddTraits(.isHeader)
            Text(L10n.recipeWritingOnPhoneMessage)
                .larderFont(.body)
                .foregroundStyle(LarderColor.onSurfaceVariant)
                .frame(maxWidth: 320)
                .fixedSize(horizontal: false, vertical: true)
            LarderButton(L10n.recipeStop, variant: .outline) { model.stopWriting() }
                .padding(.top, LarderSpace.s2)
        }
        .multilineTextAlignment(.center)
        .padding(.vertical, LarderSpace.s8)
        .padding(.horizontal, LarderSpace.s6)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .contain)
    }

    private func sharePrompt() {
        sharedPrompt = SharedText(text: model.sharePrompt(for: pantry.items))
    }

    private func copyPrompt() {
        UIPasteboard.general.string = model.sharePrompt(for: pantry.items)
        showSnackbar(L10n.recipePromptCopied)
    }
}

private struct SharedText: Identifiable {
    let id = UUID()
    let text: String
}

/// What "Add other items" lists, fixed when it opens so a row doesn't jump away as it is ticked.
private struct OtherItems: Identifiable {
    let id = UUID()
    let items: [PantryItem]
}

/// The rest of the pantry, to tick more items to cook with.
private struct AddOtherItemsSheet: View {
    let items: [PantryItem]
    let expiringSoonDays: Int
    @ObservedObject var model: RecipeViewModel
    let onDone: () -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                LarderGroup {
                    ForEach(items) { item in
                        PantryRow(
                            item: item,
                            expiringSoonDays: expiringSoonDays,
                            place: "",
                            selection: .init(isSelected: model.selectedIDs.contains(item.id), toggle: { model.toggle(item.id) })
                        )
                        .contentShape(Rectangle())
                        .onTapGesture { model.toggle(item.id) }
                    }
                }
                .padding(LarderSpace.s4)
            }
            .background(LarderColor.surfaceContainerHigh)
            .navigationTitle(L10n.recipeAddOtherItems)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L10n.doneButton, action: onDone).fontWeight(.bold)
                }
            }
        }
    }
}
