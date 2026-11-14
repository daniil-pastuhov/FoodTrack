import StoreKit
import SwiftUI
import UIKit

/// The Recipe tab: what can I cook from what is about to expire? The soonest items are ticked,
/// a short form sets the kind of dish, and one primary action writes it, pinned above the pill
/// where Scan sits on Home. Three routes: our server while the pack has recipes, the phone's
/// own model where it has one, and a prompt shared to another AI app. A menu for three days is
/// the server's only.
struct RecipeView: View {
    @ObservedObject var pantry: NativePantryViewModel
    @ObservedObject var model: RecipeViewModel
    let onScan: () -> Void

    @ObservedObject private var settingsStore = SettingsStore.shared
    @Environment(\.isExpanded) private var isExpanded
    @Environment(\.showSnackbar) private var showSnackbar
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.shellClearance) private var shellClearance
    @Environment(\.requestReview) private var requestReview

    @State private var pinnedHeight: CGFloat = 0
    @State private var sharedPrompt: SharedText?
    @State private var addingItems: OtherItems?

    private var expiringSoonDays: Int { settingsStore.settings.expiringSoonDays }
    private var nothingTicked: Bool { model.selectedItems(from: pantry.items).isEmpty }
    private var routes: RecipeRoutes { model.routes }
    private var credits: RecipeCredits { model.credits }
    private var paid: Bool { credits.isPaidRouteAvailable }
    /// Menu mode only while the server route exists; otherwise the form is the recipe one.
    private var isMenu: Bool { paid && model.mode == .menu }
    private var isWriting: Bool { model.phase != .idle }

    var body: some View {
        NavigationStack(path: $model.path) {
            root
                .toolbar(.hidden, for: .navigationBar)
                .navigationDestination(for: RecipeDestination.self) { destination in
                    switch destination {
                    case .result(let id):
                        RecipeResultView(entry: model.savedRecipe(id: id)) { day, course in
                            model.path.append(.dish(id: id, day: day, course: course))
                        }
                    case .dish(let id, let day, let course):
                        RecipeResultView(entry: model.savedRecipe(id: id), dish: (day, course))
                    case .saved:
                        SavedRecipesView(model: model)
                    case .settings:
                        RecipeSettingsView(model: model)
                    }
                }
        }
        // Outside the stack, which doesn't pass the shell's clearance on to its content's own
        // insets, so the form pads for it itself. The inset starts at the content clearance,
        // the content gap above the pill; the button comes down by the difference to sit where
        // Scan sits on Home and Android puts it, the shell gap above the pill. It used to sit
        // 16pt higher, over more of the form at rest.
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if showsPinnedAction {
                pinnedAction
                    .onGeometryChange(for: CGFloat.self, of: \.size.height) { pinnedHeight = $0 }
                    .padding(.horizontal, LarderSpace.s4)
                    .frame(maxWidth: isExpanded ? 440 + 2 * LarderSpace.s4 : .infinity)
                    .frame(maxWidth: .infinity, alignment: isExpanded ? .trailing : .center)
                    .padding(.trailing, isExpanded ? LarderSpace.s2 : 0)
                    .padding(.bottom, ShellMetrics.shellGap - ShellMetrics.contentGap)
            }
        }
        .shellPinnedAction(height: showsPinnedAction ? pinnedHeight : 0)
        .onAppear {
            model.syncSelection(with: pantry.items)
            model.refreshAvailability()
        }
        // Prices from the store, and the count for an install that already has a user. Neither
        // creates a user nor calls our server for anyone who never bought.
        .task {
            await credits.loadPacksIfNeeded()
            await credits.refreshBalance()
        }
        .onChange(of: pantry.items) { _, items in model.syncSelection(with: items) }
        // Leaving a recipe that was just written (for anything but its own dishes) is one of the
        // rating prompt's moments: asked then, never over the recipe as it appears.
        .onChange(of: model.path) { _, path in
            let showsRecipe = path.contains { destination in
                switch destination {
                case .result, .dish: return true
                case .saved, .settings: return false
                }
            }
            if !showsRecipe { ReviewPrompter.shared.recipeLeft { requestReview() } }
        }
        // The model can finish downloading, or Apple Intelligence be switched on, meanwhile; a
        // purchase waiting for the server is tried again.
        .onChange(of: scenePhase) { _, phase in
            guard phase == .active else { return }
            model.refreshAvailability()
            if !credits.unconfirmedHere.isEmpty {
                Task { await credits.resubmitUnfinished(waitingOnly: true) }
            }
        }
        .onChange(of: model.failure) { _, failure in
            guard let failure else { return }
            model.failure = nil
            show(failure)
        }
        .onChange(of: credits.notice) { _, notice in
            guard let notice else { return }
            credits.notice = nil
            show(notice)
        }
        .larderSheet(isPresented: $model.showsBuySheet, fitsContent: true) {
            BuyRecipesSheet(model: model) { model.showsBuySheet = false }
        }
        .larderDialog(
            L10n.recipeRejectedTitle,
            isPresented: Binding(get: { credits.rejectedPurchase }, set: { credits.rejectedPurchase = $0 }),
            message: L10n.recipeRejectedMessage,
            actions: [.init(L10n.recipeOK)]
        )
        .larderDialog(
            L10n.recipeRejectedTitle,
            isPresented: Binding(get: { credits.rejectedByBuild }, set: { credits.rejectedByBuild = $0 }),
            message: L10n.recipeRejectedBuildMessage,
            actions: [.init(L10n.recipeOK)]
        )
        // Allergens are health data: what is sent, why, to whom, and that it isn't kept, before
        // the first server request that carries them (`AllergenConsent`).
        .larderDialog(
            L10n.recipeAllergenConsentTitle,
            // Closed only by its two buttons, which both clear the prompt (an alert has no other
            // way out), so the order SwiftUI resets the binding in doesn't matter.
            isPresented: Binding(get: { model.consentPrompt == .beforeWriting }, set: { _ in }),
            message: L10n.recipeAllergenConsentMessage(SettingsStore.shared.settings.allergens.sorted()),
            actions: [
                .cancel(L10n.cancelButton) { model.declineAllergenConsent() },
                .init(L10n.recipeAllergenConsentAgree) {
                    if model.agreeToAllergenConsent() { model.writeOnServer(from: pantry.items) }
                },
            ]
        )
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
            } else if case .writingOnServer(let kind) = model.phase {
                VStack(spacing: 0) {
                    header
                    writingOnServer(kind)
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
            if paid {
                balanceChip
            }
            LarderIconButton(.bookmark, label: L10n.recipeSavedButton) { model.path.append(.saved) }
            LarderIconButton(.slidersHorizontal, label: L10n.recipeSettingsButton) { model.path.append(.settings) }
        }
    }

    private var form: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header
                VStack(alignment: .leading, spacing: LarderSpace.s4) {
                    if paid {
                        LarderSegmented(selection: $model.mode, options: [RecipeMode.recipe, .menu], fill: true) {
                            $0 == .menu ? L10n.recipeModeMenu : L10n.recipeModeRecipe
                        }
                    }
                    paidCallouts
                    if routes.onDevicePreparing && !isMenu {
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
                // Ends the content gap above the pinned action, as Android's form does.
                .padding(.bottom, shellClearance + (showsPinnedAction ? pinnedHeight + ShellMetrics.shellGap : LarderSpace.s4))
            }
            .frame(maxWidth: isExpanded ? 960 : .infinity)
            .frame(maxWidth: .infinity)
        }
    }

    /// Whether the pinned action shows: on the form, not over a pushed screen or a state that
    /// has nothing to act on.
    private var showsPinnedAction: Bool {
        model.path.isEmpty && !pantry.items.isEmpty && model.phase == .idle && (!isMenu || model.serverCanWriteMenu)
    }

    // MARK: Paid route

    /// The count left in the pack; opens the packs.
    private var balanceChip: some View {
        let count = credits.balance ?? 0
        return Button {
            if credits.canBuy { model.showsBuySheet = true }
        } label: {
            HStack(spacing: LarderSpace.s1) {
                LarderIcon(.ticket, size: .custom(18))
                Text("\(count)")
                    .larderFont(.label)
                    .monospacedDigit()
            }
            .foregroundStyle(LarderColor.onSurface)
            .padding(.horizontal, LarderSpace.s3)
            .padding(.vertical, LarderSpace.s1)
            .background(LarderColor.surfaceContainer, in: Capsule())
            .frame(minHeight: LarderSize.touch)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L10n.recipeBalanceLeft(count))
        .accessibilityHint(credits.canBuy ? L10n.recipeBuy : "")
    }

    /// The lasting conditions of the paid route, most important first: money that has moved
    /// without recipes, a payment on its way, generation paused, no connection.
    @ViewBuilder
    private var paidCallouts: some View {
        if !credits.unconfirmedHere.isEmpty {
            LarderCallout(
                .warning,
                icon: .clock,
                title: L10n.recipeUnconfirmedTitle,
                message: L10n.recipeUnconfirmedMessage(credits.unconfirmedCredits),
                actionTitle: credits.isConfirming ? nil : L10n.recipeTryNow,
                action: { Task { await credits.resubmitUnfinished() } }
            )
        } else if credits.paymentPending {
            LarderCallout(
                .info,
                icon: .clock,
                title: L10n.recipePaymentPendingTitle,
                message: L10n.recipePaymentPendingMessage
            )
        }
        if credits.showsOtherAccountPurchases {
            // PURCHASE_ACCOUNT_MISMATCH: kept for the account that bought it.
            LarderCallout(
                .info,
                icon: .userRound,
                title: L10n.recipeOtherAccountTitle,
                message: L10n.recipeOtherAccountMessage(credits.otherAccountCredits)
            )
        }
        if paid && credits.serverPaused {
            LarderCallout(
                .warning,
                icon: .triangleAlert,
                title: L10n.recipePausedTitle,
                message: L10n.recipePausedMessage(credits.balance)
            )
        } else if credits.showsOffline {
            LarderCallout(
                .warning,
                icon: .wifiOff,
                title: L10n.recipeOfflineTitle,
                message: L10n.recipeOfflineMessage,
                actionTitle: L10n.settingsTryAgain,
                action: {
                    if model.hasInterruptedServerWrite {
                        model.resumeServerWrite()
                    } else {
                        Task { await credits.refreshBalance() }
                    }
                }
            )
        }
    }

    private var buyButton: some View {
        LarderButton(L10n.recipeBuy, icon: .ticket, variant: .outline, fullWidth: true) {
            model.showsBuySheet = true
        }
    }

    /// Buy recipes, or where it would be, the line saying why this account can't buy.
    @ViewBuilder
    private var buyOrBlocked: some View {
        if credits.canBuy {
            buyButton
        } else if credits.showsPurchasesBlocked {
            hint(L10n.recipePurchasesBlocked)
        }
    }

    private func writingOnServer(_ kind: RecipeKind) -> some View {
        VStack(spacing: LarderSpace.s3) {
            LarderSpinner()
            Text(model.serverBusyRetrying ? L10n.recipeServerBusyRetryingTitle : L10n.recipeWritingServerTitle(kind))
                .larderFont(.title)
                .foregroundStyle(LarderColor.onSurface)
                .padding(.top, LarderSpace.s2)
                .accessibilityAddTraits(.isHeader)
            // All server instances busy: the same request is sent again shortly, said calmly.
            Text(model.serverBusyRetrying ? L10n.recipeServerBusyRetrying : L10n.recipeWritingServerMessage(kind))
                .larderFont(.body)
                .foregroundStyle(LarderColor.onSurfaceVariant)
                .frame(maxWidth: 320)
                .fixedSize(horizontal: false, vertical: true)
        }
        .multilineTextAlignment(.center)
        .padding(.vertical, LarderSpace.s8)
        .padding(.horizontal, LarderSpace.s6)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .contain)
    }

    private func show(_ failure: RecipeFailure) {
        switch failure {
        case .onDeviceFailed:
            showSnackbar(L10n.recipeFailedOnDevice, actionTitle: L10n.settingsTryAgain) {
                model.writeOnDevice(from: pantry.items)
            }
        case .onDeviceRefused:
            showSnackbar(L10n.recipeRefusedOnDevice)
        case .serverFailed(let kind, let sameRequest):
            showSnackbar(L10n.recipeServerFailed(kind), actionTitle: L10n.settingsTryAgain) {
                if sameRequest {
                    model.resumeServerWrite()
                } else {
                    model.mode = kind == .menu ? .menu : .recipe
                    model.writeOnServer(from: pantry.items)
                }
            }
        case .serverRefused(let kind, let taken):
            showSnackbar(L10n.recipeServerRefused(kind, taken: taken))
        case .serverUnrecognised(let kind, let taken):
            showSnackbar(L10n.recipeServerUnrecognised(kind, taken: taken))
        case .busy:
            showSnackbar(L10n.recipeServerBusy)
        case .rateLimited:
            showSnackbar(L10n.recipeServerRateLimited)
        case .userDailyLimit:
            showSnackbar(L10n.recipeServerUserDailyLimit)
        case .infraFailureLimit:
            showSnackbar(L10n.recipeServerInfraFailureLimit)
        case .dailyCap:
            showSnackbar(L10n.recipeServerDailyCap)
        case .arrived(let balance):
            showSnackbar(L10n.recipeArrived(balance: balance))
        case .earlierArrived(let kind):
            showSnackbar(L10n.recipeEarlierArrived(kind))
        case .earlierRefused(let taken):
            showSnackbar(L10n.recipeEarlierRefused(taken: taken))
        case .itemLeftOut(let name):
            showSnackbar(L10n.recipeItemLeftOut(name))
        case .itemNotSent(let name):
            showSnackbar(L10n.recipeItemNotSent(name))
        case .allergenRefused(let name):
            showSnackbar(L10n.recipeAllergenRefused(name))
        case .tooManyAllergens(let limit):
            showSnackbar(L10n.recipeAllergensTooMany(limit))
        case .allergenConsentDeclined:
            // With the way to the switch that turns sending them back on.
            showSnackbar(L10n.recipeAllergenConsentDeclined, actionTitle: L10n.recipeSettingsButton) {
                model.path.append(.settings)
            }
        }
    }

    private func show(_ notice: RecipeCreditsNotice) {
        switch notice {
        case .added(let count): showSnackbar(L10n.recipeAdded(count))
        case .signInFailed: showSnackbar(L10n.recipeSignInFailed)
        case .providerInUse: showSnackbar(L10n.recipeProviderInUse)
        case .accountDeleted: showSnackbar(L10n.recipeAccountDeleted)
        case .accountActionFailed: showSnackbar(L10n.recipeAccountActionFailed)
        case .accountBusy: showSnackbar(L10n.recipeAccountBusy)
        case .merged(let moved): showSnackbar(L10n.recipeMerged(moved: moved))
        }
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
            if isMenu {
                RecipeFormField(label: L10n.recipeCoursesLabel) {
                    LarderSegmented(
                        selection: Binding(get: { model.menuCourses }, set: { model.setMenuCourses($0) }),
                        options: [2, 3],
                        fill: true
                    ) { L10n.recipeCourses($0) }
                }
            }
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
        if isMenu {
            menuActions
        } else if nothingTicked {
            hint(L10n.recipeNoneSelectedHint)
        } else if model.serverCanWriteRecipe {
            // The server is pinned; the free routes follow, the phone's own first.
            VStack(spacing: LarderSpace.s2) {
                if routes.onDevice {
                    LarderButton(L10n.recipeGenerateOnPhone, icon: .smartphone, variant: .outline, fullWidth: true) {
                        model.writeOnDevice(from: pantry.items)
                    }
                    .accessibilityLabel("\(L10n.recipeGenerateOnPhone), \(L10n.recipeCostFree)")
                }
                shareAndCopy
            }
        } else if routes.onDevice {
            VStack(spacing: LarderSpace.s2) {
                buyOrBlocked
                shareAndCopy
            }
        } else {
            VStack(spacing: LarderSpace.s2) {
                LarderButton(L10n.recipeCopyPrompt, icon: .copy, variant: .outline, fullWidth: true, action: copyPrompt)
                hint(L10n.recipePromptOnlyHint)
                buyOrBlocked
            }
        }
    }

    /// A menu has no free route: the line under the button says why, and with too few recipes
    /// a callout takes the button's place.
    @ViewBuilder
    private var menuActions: some View {
        if model.serverCanWriteMenu {
            hint(nothingTicked ? L10n.recipeNoneSelectedHint : L10n.recipeMenuServerHint(model.menuCost))
        } else if !credits.serverPaused {
            LarderCallout(
                .info,
                title: L10n.recipeMenuShortTitle(model.menuCost),
                message: L10n.recipeMenuShortMessage(credits.balance ?? 0, recipeCost: model.recipeCost)
            )
            buyOrBlocked
        }
    }

    private var shareAndCopy: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: LarderSpace.s2) { shareButton; copyButton }
            VStack(spacing: 0) { shareButton; copyButton }
        }
        .frame(maxWidth: .infinity)
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

    /// The server while the pack has recipes, else the free route that writes in the app where
    /// the phone can, else the shared prompt. A menu is the server's only.
    @ViewBuilder
    private var pinnedAction: some View {
        if isMenu {
            RecipeGoButton(title: L10n.recipeGenerateMenu, icon: .sparkles, cost: L10n.recipeCost(model.menuCost)) {
                model.writeOnServer(from: pantry.items)
            }
            .disabled(nothingTicked)
        } else if model.serverCanWriteRecipe {
            RecipeGoButton(title: L10n.recipeGenerateRecipe, icon: .sparkles, cost: L10n.recipeCost(model.recipeCost)) {
                model.writeOnServer(from: pantry.items)
            }
            .disabled(nothingTicked)
        } else if routes.onDevice {
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
