import StoreKit
import SwiftUI
import UIKit

/// The Recipe tab: what can I cook from what is about to expire? The soonest items are ticked,
/// a short form sets the kind of dish, and the button group at the form's end writes it: the
/// primary action first, filled, then the other routes. Three routes: our server while the pack
/// has recipes, the phone's own model where it has one, and a prompt shared to another AI app. A
/// menu set is the server's only.
struct RecipeView: View {
    @ObservedObject var pantry: NativePantryViewModel
    @ObservedObject var model: RecipeViewModel
    let onScan: () -> Void
    /// Switches to the Shopping tab, from "Added N to your shopping list".
    var onOpenShopping: () -> Void = {}

    @ObservedObject private var settingsStore = SettingsStore.shared
    @Environment(\.isExpanded) private var isExpanded
    @Environment(\.showSnackbar) private var showSnackbar
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.shellClearance) private var shellClearance
    @Environment(\.requestReview) private var requestReview

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

    /// "Add to shopping list" on a result: the list's names now, the ordinary add path, and the
    /// way to the Shopping tab.
    private var shoppingContext: RecipeShoppingContext {
        RecipeShoppingContext(
            listedNames: pantry.shoppingList.map(\.productName),
            stapleKeys: pantry.currentStapleKeys,
            add: { [pantry] names in pantry.addShoppingListEntries(names) },
            openShoppingList: onOpenShopping
        )
    }

    var body: some View {
        NavigationStack(path: $model.path) {
            root
                .toolbar(.hidden, for: .navigationBar)
                .navigationDestination(for: RecipeDestination.self) { destination in
                    switch destination {
                    case .result(let id):
                        // Shown, so read: on arrival, from Saved recipes or anywhere else.
                        RecipeResultView(entry: model.savedRecipe(id: id)) { day, course in
                            model.path.append(.dish(id: id, day: day, course: course))
                        }
                        .onAppear { model.markRead(id) }
                    case .dish(let id, let day, let course):
                        RecipeResultView(entry: model.savedRecipe(id: id), dish: (day, course))
                            .onAppear { model.markRead(id) }
                    case .saved:
                        SavedRecipesView(model: model)
                    case .settings:
                        RecipeSettingsView(model: model)
                    case .tasteQuiz:
                        TasteQuizView(model: model)
                    case .staples:
                        StaplesView(viewModel: pantry)
                    }
                }
        }
        // On the stack, so every pushed result has it.
        .environment(\.recipeShopping, shoppingContext)
        .onAppear {
            // A result arriving while the tab is on screen opens; otherwise it waits, unread.
            model.setTabOnScreen(true)
            model.setSceneActive(scenePhase == .active)
            // A snackbar raised while the tab was away (a recipe saved meanwhile) is said now.
            if let failure = model.failure {
                model.failure = nil
                show(failure)
            }
            model.syncSelection(with: pantry.items)
            model.refreshAvailability()
            model.reloadSaved()
            // The first visit ever opens "How recipes work"; like onboarding, it rules the rating
            // prompt out for the session.
            if model.onTabShown() { ReviewPrompter.shared.onboardingShown() }
        }
        // Prices from the store, and the count for an install that already has a user. Neither
        // creates a user nor calls our server for anyone who never bought.
        .onDisappear { model.setTabOnScreen(false) }
        // The free recipes' offer is asked too (at most every 15 minutes, only where buying can
        // be offered): App Check only, nothing about the person, no account made.
        .task {
            await credits.loadPacksIfNeeded()
            await credits.refreshWelcomeOffer()
            await credits.refreshBalance()
        }
        .onChange(of: pantry.items) { _, items in model.syncSelection(with: items) }
        // Leaving a recipe that was just written (for anything but its own dishes) is one of the
        // rating prompt's moments: asked then, never over the recipe as it appears.
        .onChange(of: model.path) { _, path in
            let showsRecipe = path.contains { destination in
                switch destination {
                case .result, .dish: return true
                case .saved, .settings, .tasteQuiz, .staples: return false
                }
            }
            if !showsRecipe { ReviewPrompter.shared.recipeLeft { requestReview() } }
        }
        // The model can finish downloading, or Apple Intelligence be switched on, meanwhile; a
        // purchase waiting for the server is tried again.
        .onChange(of: scenePhase) { _, phase in
            model.setSceneActive(phase == .active)
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
        .larderSheet(item: $model.welcomeSheet, fitsContent: true) { request in
            WelcomeSignInSheet(model: model, recipes: request.recipes) { model.welcomeSheet = nil }
        }
        // ALREADY: the person signed in for nothing, so it is said plainly, with the way on.
        .larderDialog(
            L10n.welcomeAlreadyTitle,
            // The sign-in sheet shows its own while it is up.
            isPresented: Binding(get: { credits.welcomeAlready && model.welcomeSheet == nil && !model.showsBuySheet }, set: { credits.welcomeAlready = $0 }),
            message: L10n.welcomeAlreadyMessage,
            actions: credits.canBuy
                ? [.cancel(L10n.recipeClose), .init(L10n.recipeIntroPacksAction) { model.welcomeToPacks() }]
                : [.cancel(L10n.recipeClose)]
        )
        .larderSheet(
            isPresented: Binding(get: { model.intro == .firstOpen }, set: { if !$0, model.intro == .firstOpen { model.dismissIntro() } }),
            fitsContent: true
        ) {
            RecipeIntroSheet(model: model)
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
                    if model.agreeToAllergenConsent() { model.writeOnServer(from: pantry.items, staples: pantry.stapleRows) }
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
            // Only once something is saved; it follows the saved list as it changes.
            if model.hasSaved {
                savedButton
            }
            settingsButton
        }
    }

    /// Recipe settings, with a dot while the taste profile has questions not yet been through.
    private var settingsButton: some View {
        let isNew = model.hasNewTasteQuestions
        return LarderIconButton(
            .slidersHorizontal,
            label: isNew ? "\(L10n.recipeSettingsButton), \(L10n.tasteSettingsBadge)" : L10n.recipeSettingsButton
        ) { model.path.append(.settings) }
            .overlay(alignment: .topTrailing) {
                if isNew {
                    // Centred where the Saved recipes count badge's corner is.
                    LarderBadge.dot
                        .padding(.top, 9 - 2)
                        .padding(.trailing, 7 - 2)
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                }
            }
    }

    /// Saved recipes, with a count of those not seen yet (written or recovered while the writing
    /// screen was away), as the pill's Shopping badge.
    private var savedButton: some View {
        let unread = model.unreadCount
        return LarderIconButton(.bookmark, label: L10n.recipeSavedButtonUnread(unread)) { model.path.append(.saved) }
            .overlay(alignment: .topTrailing) {
                if unread > 0 {
                    LarderBadge(count: unread, isCompact: true)
                        .fixedSize()
                        .offset(x: -4, y: 6)
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                }
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
                // The stack keeps the shell's clearance from reaching this scroll view as an
                // inset, so the form leaves room for the pill (and Scan) itself: the last button
                // ends the content gap above them.
                .padding(.bottom, shellClearance + LarderSpace.s4)
            }
            .frame(maxWidth: isExpanded ? 960 : .infinity)
            .frame(maxWidth: .infinity)
        }
        .defaultScrollAnchor(Self.opensAtEnd ? .bottom : .top)
        // The header scrolls away with the form, as on Home; the status bar keeps the page colour.
        .clipsBelowStatusBar()
    }

    /// `-demoRecipeScrollEnd YES` (Debug): the form opens scrolled to its buttons, for layout
    /// checks of the button group.
    private static var opensAtEnd: Bool {
        #if DEBUG
        UserDefaults.standard.bool(forKey: "demoRecipeScrollEnd")
        #else
        false
        #endif
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

    /// Where Buy recipes would be, the line saying why this account can't buy.
    @ViewBuilder
    private var purchasesBlockedLine: some View {
        if !credits.canBuy && credits.showsPurchasesBlocked {
            hint(L10n.recipePurchasesBlocked)
        }
    }

    /// The free recipes' offer, where it shows (`RecipeCredits.welcomeHint`).
    private var welcome: WelcomeHint? { credits.welcomeHint }

    private func writingOnServer(_ kind: RecipeKind) -> some View {
        VStack(spacing: LarderSpace.s3) {
            LarderSpinner()
            Text(model.serverBusyRetrying ? L10n.recipeServerBusyRetryingTitle : L10n.recipeWritingServerTitle(kind, days: model.writingMenuDays))
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
                model.writeOnDevice(from: pantry.items, staples: pantry.stapleRows)
            }
        case .onDeviceRefused:
            showSnackbar(L10n.recipeRefusedOnDevice)
        case .serverFailed(let kind, let sameRequest):
            showSnackbar(L10n.recipeServerFailed(kind), actionTitle: L10n.settingsTryAgain) {
                if sameRequest {
                    model.resumeServerWrite()
                } else {
                    model.mode = kind == .menu ? .menu : .recipe
                    model.writeOnServer(from: pantry.items, staples: pantry.stapleRows)
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
            // Saved unseen: the way to the list it is in, as on Android.
            showSnackbar(L10n.recipeEarlierArrived(kind), actionTitle: L10n.recipeSavedButton) {
                model.path = [.saved]
            }
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
        case .notEnoughItems(let kind, let days):
            // The form stays as it is; the next try is a new request.
            showSnackbar(L10n.recipeNotEnoughItems(kind, days: days))
        case .dietsNeedServerUpdate:
            showSnackbar(L10n.recipeDietsNeedUpdate, actionTitle: L10n.recipeSettingsButton) {
                model.path.append(.settings)
            }
        case .languageNeedsServerUpdate:
            showSnackbar(L10n.recipeLanguageNeedsUpdate, actionTitle: L10n.recipeSettingsButton) {
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
        case .deviceDataDeleted: showSnackbar(L10n.recipeDeviceDataDeleted)
        case .deletionWaitsForPurchase: showSnackbar(L10n.recipeDeleteWaitsForPurchase)
        case .accountActionFailed: showSnackbar(L10n.recipeAccountActionFailed)
        case .accountBusy: showSnackbar(L10n.recipeAccountBusy)
        case .merged(let moved): showSnackbar(L10n.recipeMerged(moved: moved))
        case .welcomeGranted(let count): showSnackbar(L10n.welcomeAdded(count))
        case .welcomeOff(let justSignedIn): showSnackbar(L10n.welcomeOff(justSignedIn: justSignedIn))
        case .welcomeNotReached:
            // Signed in and not claimed: the offer reads "Get 3 free recipes"; a retry is safe.
            showSnackbar(L10n.welcomeNotReached, actionTitle: L10n.settingsTryAgain) {
                Task { await credits.claimWelcome() }
            }
        }
    }

    // MARK: Cook with

    private var ingredients: some View {
        let items = pantry.items
        let shown = RecipeViewModel.shownItems(items, selected: model.selectedIDs)
        let others = RecipeViewModel.otherItems(items, selected: model.selectedIDs)
        return VStack(alignment: .leading, spacing: 0) {
            SectionHeader(L10n.recipeCookWith)
            // Ticked items past their date: said at the top of the list, from the items' own
            // dates, gone as soon as none is ticked. It never stops a recipe being written.
            if let warning = L10n.recipePastDateFormWarning(model.pastDateTicked(from: items)) {
                LarderCallout(.error, icon: .triangleAlert, message: warning)
                    .padding(.bottom, LarderSpace.s3)
            }
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
            // "Send all products" is on: one quiet line, so what goes with it is never a surprise.
            let optional = model.optionalItems(from: items).count
            if optional > 0 {
                Text(L10n.recipeOptionalCount(optional))
                    .larderFont(.bodySm)
                    .foregroundStyle(LarderColor.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, LarderSpace.s2)
                    .padding(.top, LarderSpace.s2)
            }
            // The person's staples go to every route too: one quiet line, which opens them.
            let staples = model.stapleNames(for: items, staples: pantry.stapleRows).count
            if staples > 0 {
                Button {
                    model.path.append(.staples)
                } label: {
                    Text(L10n.recipeStaplesCount(staples))
                        .larderFont(.bodySm)
                        .foregroundStyle(LarderColor.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, minHeight: LarderSize.control, alignment: .leading)
                        .padding(.horizontal, LarderSpace.s2)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityHint(L10n.staplesOpen)
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
                // Days only from a server that writes menu sets; until then the menu is 3 days.
                let dayOptions = model.menuDayOptions
                if dayOptions.count > 1 {
                    RecipeFormField(label: L10n.recipeDaysLabel) {
                        LarderSegmented(
                            selection: Binding(get: { model.menuSet.days }, set: { model.setMenuDays($0) }),
                            options: dayOptions,
                            fill: true
                        ) { L10n.recipeDays($0) }
                    }
                }
                RecipeFormField(label: L10n.recipeCoursesLabel) {
                    LarderSegmented(
                        selection: Binding(get: { model.menuSet.courses }, set: { model.setMenuCourses($0) }),
                        options: model.menuCourseOptions,
                        fill: true
                    ) { L10n.recipeCourses($0) }
                }
            }
            RecipeDifficultyField(selection: model.binding(\.difficulty))
            RecipeServingsField(servings: model.binding(\.servings))
            RecipeTimeField(minMinutes: model.binding(\.minMinutes), maxMinutes: model.binding(\.maxMinutes))
            // A menu's starters and desserts ignore the meal; time and diet hold for every dish.
            if isMenu, !model.config.mealTypes.isEmpty {
                Text(L10n.recipeMenuMealNote(RecipeChoices.orderedMeals(model.config.mealTypes)))
                    .larderFont(.caption)
                    .foregroundStyle(LarderColor.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
            }
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

    /// The form's one button group, at its end and scrolling with it: the primary action first,
    /// filled and full width (with what it takes), a caption under it when it is a free route,
    /// then "Other ways", one card holding every other route as `RecipeActions` says. Nothing
    /// floats over the form and nothing sits loose between the buttons.
    @ViewBuilder
    private var actions: some View {
        VStack(spacing: 0) {
            if isMenu {
                menuActions
            } else {
                if nothingTicked { hint(L10n.recipeNoneSelectedHint).padding(.bottom, LarderSpace.s2) }
                primaryButton
                if let caption = primaryCaption {
                    hint(caption).padding(.top, LarderSpace.s1)
                }
                otherWays
                if model.actions.primary != .server {
                    purchasesBlockedLine.padding(.top, LarderSpace.s2)
                }
            }
        }
        .padding(.top, LarderSpace.s2)
        .frame(maxWidth: isExpanded ? .infinity : 560)
        .frame(maxWidth: .infinity)
    }

    /// Under a free primary only: what it is, in one line.
    private var primaryCaption: String? {
        switch model.actions.primary {
        case .onDevice: return L10n.recipeOnDeviceCaption
        case .share: return L10n.recipeShareCaption
        default: return nil
        }
    }

    /// The other routes, in this order: the free recipes' offer (not beside the server, as
    /// before), the phone's model, Share prompt, Buy recipes. The ones that need ticked items stay,
    /// dimmed, while nothing is ticked. No card without a row.
    @ViewBuilder
    private var otherWays: some View {
        let secondary = model.actions.secondary
        let offer = model.actions.primary == .server ? nil : welcome
        if offer != nil || !secondary.isEmpty {
            RecipeOtherWays { tiles in
                if let offer {
                    WelcomeOfferRow(model: model, hint: offer, showsTile: tiles).welcomeTransition()
                }
                ForEach(secondary, id: \.self) { route in
                    otherWay(route, showsTile: tiles)
                }
            }
            .padding(.top, LarderSpace.s4)
        }
    }

    @ViewBuilder
    private func otherWay(_ route: RecipeAction, showsTile: Bool) -> some View {
        switch route {
        case .onDevice:
            RecipeWayRow(L10n.recipeGenerateOnPhone, icon: .smartphone, free: L10n.recipeCostFree, supporting: L10n.recipeOnDeviceCaption, showsTile: showsTile) {
                model.writeOnDevice(from: pantry.items, staples: pantry.stapleRows)
            }
            .disabled(nothingTicked)
        case .share:
            RecipeWayRow(L10n.recipeSharePrompt, icon: .share2, free: L10n.recipeCostFree, supporting: L10n.recipeShareCaption, showsTile: showsTile, action: sharePrompt)
                .disabled(nothingTicked)
        case .buy:
            RecipeWayRow(L10n.recipeBuy, icon: .ticket, chevron: true, showsTile: showsTile) {
                model.showsBuySheet = true
            }
        case .server:
            EmptyView()
        }
    }

    /// A menu has no free route: with enough recipes its button; with too few a callout in the
    /// button's place and Buy as the primary, and the free recipes' offer under it with one line
    /// on what they cover, never a promise the menu can't keep.
    @ViewBuilder
    private var menuActions: some View {
        if model.serverCanWriteMenu {
            // Few items for many dishes: said, never stopped; live with the ticks and choices.
            if let few = model.menuItemsHint(for: pantry.items) {
                LarderCallout(.info, icon: .shoppingBasket, message: L10n.recipeMenuFewItems(few))
                    .padding(.bottom, LarderSpace.s2)
            }
            if nothingTicked { hint(L10n.recipeNoneSelectedHint).padding(.bottom, LarderSpace.s2) }
            RecipeGoButton(
                title: L10n.recipeGenerateMenu,
                icon: .sparkles,
                cost: L10n.recipeCost(model.menuCost),
                subtitle: model.sendsTaste ? L10n.tasteUsesProfile : nil
            ) {
                model.writeOnServer(from: pantry.items, staples: pantry.stapleRows)
            }
            .disabled(nothingTicked)
        } else if !credits.serverPaused {
            LarderCallout(
                .info,
                title: L10n.recipeMenuShortTitle(model.menuCost),
                message: L10n.recipeMenuShortMessage(credits.balance ?? 0, recipeCost: model.recipeCost)
            )
            .padding(.bottom, LarderSpace.s2)
            if credits.canBuy {
                RecipeGoButton(title: L10n.recipeBuy, icon: .ticket) { model.showsBuySheet = true }
                    .transition(.opacity)
            }
            if let welcome {
                RecipeOtherWays { tiles in
                    WelcomeOfferRow(
                        model: model,
                        hint: welcome,
                        supporting: L10n.welcomeMenuCover(
                            WelcomeRecipes.menuCover(
                                recipes: welcome.recipes,
                                balance: credits.balance ?? 0,
                                menuCost: model.menuCost,
                                costs: credits.costs,
                                takesMenuDays: credits.serverTakesMenuDays
                            )
                        ),
                        showsTile: tiles
                    )
                }
                .padding(.top, credits.canBuy ? LarderSpace.s4 : 0)
                .welcomeTransition()
            }
            purchasesBlockedLine.padding(.top, LarderSpace.s2)
        }
    }

    /// The server while the pack has recipes, else the free route that writes in the app where
    /// the phone can, else the shared prompt. The server's says inside it when the taste profile
    /// goes with the request.
    @ViewBuilder
    private var primaryButton: some View {
        switch model.actions.primary {
        case .server:
            RecipeGoButton(
                title: L10n.recipeGenerateRecipe,
                icon: .sparkles,
                cost: L10n.recipeCost(model.recipeCost),
                // Only when a profile will really be sent with it.
                subtitle: model.sendsTaste ? L10n.tasteUsesProfile : nil
            ) {
                model.writeOnServer(from: pantry.items, staples: pantry.stapleRows)
            }
            .disabled(nothingTicked)
        case .onDevice:
            RecipeGoButton(title: L10n.recipeGenerateOnPhone, icon: .smartphone, cost: L10n.recipeCostFree) {
                model.writeOnDevice(from: pantry.items, staples: pantry.stapleRows)
            }
            .disabled(nothingTicked)
        default:
            // The share sheet has Copy too, so there is no button of its own for it.
            RecipeGoButton(title: L10n.recipeSharePrompt, icon: .share2, action: sharePrompt)
                .disabled(nothingTicked)
        }
    }

    private func hint(_ text: String) -> some View {
        Text(text)
            .larderFont(.bodySm)
            .foregroundStyle(LarderColor.onSurfaceVariant)
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity)
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
        sharedPrompt = SharedText(text: model.sharePrompt(for: pantry.items, staples: pantry.stapleRows))
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
