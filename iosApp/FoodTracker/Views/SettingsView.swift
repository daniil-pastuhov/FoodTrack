import SwiftUI
import UniformTypeIdentifiers
import UserNotifications

/// One page of the expanded layout. Categories isn't one: it opens its own screen.
private enum SettingsSection: Hashable {
    case notifications, shops, threshold, locations, allergens, appearance, data

    var title: String {
        switch self {
        case .notifications: return L10n.settingsSectionNotifications
        case .shops: return L10n.settingsSectionStores
        case .threshold: return L10n.settingsSectionExpiringSoon
        case .locations: return L10n.settingsSectionLocations
        case .allergens: return L10n.settingsSectionAllergens
        case .appearance: return L10n.settingsSectionAppearance
        case .data: return L10n.settingsSectionData
        }
    }

    /// The group it sits in, or nil for the two that are a group of their own.
    var group: String? {
        switch self {
        case .notifications, .shops: return L10n.settingsGroupReminders
        case .threshold, .locations, .allergens: return L10n.settingsGroupKitchen
        case .appearance, .data: return nil
        }
    }
}

/// Everything on one page in four groups on phones; a list of sections with their current values,
/// and one section beside it, when expanded. Choices save as they're made.
///
/// A `List` rather than a scroll view of groups, so locations, allergens and shops keep swipe to
/// delete; each section is one or more `.larderGroupRow`s.
public struct SettingsView: View {
    @ObservedObject var viewModel: NativePantryViewModel
    @ObservedObject private var store = SettingsStore.shared
    /// Called after "Clear All Data" is confirmed, so the root can switch back to the Home
    /// tab — mirroring the Android app's return-to-dashboard behavior after a clear.
    var onDataCleared: () -> Void

    @State private var section: SettingsSection = .notifications
    @State private var showCategories = false
    @State private var newLocationText: String = ""
    @State private var newLocationError: String?
    @State private var newAllergenText: String = ""
    @State private var editingLocation: String? = nil
    @State private var editingLocationText: String = ""
    @State private var editingLocationError: String?
    @State private var showClearConfirm: Bool = false
    /// Built when Export is tapped, not on every render.
    @State private var exportFile: BackupFile?
    @State private var showExporter = false
    @State private var showImporter = false
    /// Held between picking a file and choosing how to apply it: the mode question only makes
    /// sense once we know there is a file, and asking it first would strand the answer if the
    /// user then backed out of the picker.
    @State private var pendingImportURL: URL?
    @State private var newStoreText: String = ""
    @State private var isResolvingStoreLocation = false
    /// iOS has notifications for the app switched off (authorization denied).
    @State private var notificationsDenied = false
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.isExpanded) private var isExpanded
    @Environment(\.showSnackbar) private var showSnackbar

    /// The widest a form gets, so fields don't stretch across a tablet.
    private static let formMaxWidth: CGFloat = 640
    private static let sectionListWidth: CGFloat = 300

    public init(viewModel: NativePantryViewModel, onDataCleared: @escaping () -> Void = {}) {
        self.viewModel = viewModel
        self.onDataCleared = onDataCleared
    }

    private var settings: AppSettings { store.settings }

    private func update(_ transform: (inout AppSettings) -> Void) {
        var updated = store.settings
        transform(&updated)
        store.save(updated)
    }

    public var body: some View {
        NavigationStack {
            Group {
                if isExpanded { twoPanes } else { phonePage }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(LarderColor.background)
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(isPresented: $showCategories) {
                CategorySettingsView(viewModel: viewModel)
            }
            .larderDialog(
                L10n.clearDataDialogTitle,
                isPresented: $showClearConfirm,
                message: viewModel.items.isEmpty
                    ? L10n.clearDataDialogMessage
                    : L10n.clearDataDialogCount(viewModel.items.count),
                actions: [
                    .cancel(L10n.clearDataKeep),
                    .destructive(L10n.clearDataConfirm) {
                        viewModel.clearAllData()
                        onDataCleared()
                    }
                ]
            )
            .fileExporter(
                isPresented: $showExporter,
                document: exportFile,
                contentType: .json,
                defaultFilename: BackupSerializer.suggestedFileName(
                    isoDate: BackupDates.string(from: Date())
                )
            ) { result in
                handleExport(result)
            }
            .fileImporter(
                isPresented: $showImporter,
                // Not restricted to .json: plenty of providers hand a .json file back as plain
                // text or raw data, and a strict filter greys out the very backup the user is
                // trying to pick. The parse is what validates it.
                allowedContentTypes: [.json, .plainText, .data]
            ) { result in
                pendingImportURL = try? result.get()
            }
            .confirmationDialog(
                L10n.settingsImportBackupButton,
                isPresented: Binding(
                    get: { pendingImportURL != nil },
                    set: { if !$0 { pendingImportURL = nil } }
                ),
                titleVisibility: .visible
            ) {
                Button(L10n.restoreModeMergeTitle) { applyImport(mode: .merge) }
                Button(L10n.restoreModeReplaceTitle, role: .destructive) { applyImport(mode: .replace) }
                Button(L10n.restoreModeCancel, role: .cancel) { pendingImportURL = nil }
            } message: {
                Text(L10n.restoreModeMessage)
            }
            // Re-checked whenever the app comes back to the foreground: the fix happens in the
            // system Settings app, and the user returns straight here afterwards.
            .task(id: scenePhase) {
                guard scenePhase == .active else { return }
                let status = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
                notificationsDenied = status == .denied
            }
        }
    }

    // MARK: Layouts

    private var phonePage: some View {
        VStack(spacing: 0) {
            LedeHeader(L10n.settingsTitle, brand: L10n.appName)
            List {
                groupTitle(L10n.settingsGroupReminders, first: true)
                notificationsRows
                cardGap
                shopsRows
                groupTitle(L10n.settingsGroupKitchen)
                thresholdRows
                cardGap
                locationsRows
                cardGap
                categoriesRows
                cardGap
                allergensRows
                groupTitle(L10n.settingsSectionAppearance)
                appearanceRows
                groupTitle(L10n.settingsSectionData)
                dataRows
            }
            .larderList()
        }
        .frame(maxWidth: Self.formMaxWidth)
    }

    private var twoPanes: some View {
        HStack(spacing: 0) {
            VStack(spacing: 0) {
                LedeHeader(L10n.settingsTitle, brand: L10n.appName)
                ScrollView { sectionList }
            }
            .frame(width: Self.sectionListWidth)

            Rectangle()
                .fill(LarderColor.outlineVariant)
                .frame(width: 1)
                .ignoresSafeArea(edges: .vertical)

            VStack(spacing: 0) {
                LedeHeader(section.title, brand: section.group)
                List {
                    switch section {
                    case .notifications: notificationsRows
                    case .shops: shopsRows
                    case .threshold: thresholdRows
                    case .locations: locationsRows
                    case .allergens: allergensRows
                    case .appearance: appearanceRows
                    case .data: dataRows
                    }
                }
                .larderList()
                .frame(maxWidth: Self.formMaxWidth, alignment: .leading)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    private var sectionList: some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionHeader(L10n.settingsGroupReminders)
            LarderGroup {
                sectionRow(.notifications, icon: .bell, value: reminderSummary)
                sectionRow(.shops, icon: .store, value: shopsSummary)
            }
            SectionHeader(L10n.settingsGroupKitchen)
                .padding(.top, LarderSpace.s4)
            LarderGroup {
                sectionRow(.threshold, icon: .clock, value: L10n.categorySettingsShelfLifeDaysFormat(settings.expiringSoonDays))
                sectionRow(
                    .locations,
                    icon: .refrigerator,
                    value: settings.storageLocations.map(L10n.storageLocationName).joined(separator: ", ")
                )
                SectionRow(
                    icon: .layers,
                    title: L10n.settingsSectionCategories,
                    value: L10n.settingsManageCategoriesButton,
                    isSelected: false,
                    showsChevron: true
                ) { showCategories = true }
                sectionRow(
                    .allergens,
                    icon: .circleAlert,
                    value: settings.allergens.isEmpty
                        ? L10n.settingsAllergensNone
                        : settings.allergens.sorted().joined(separator: ", ")
                )
            }
            LarderGroup {
                sectionRow(.appearance, icon: .sparkles, value: settings.theme.localizedTitle)
            }
            .padding(.top, LarderSpace.s4)
            LarderGroup {
                sectionRow(.data, icon: .archive, value: L10n.settingsDataSummary)
            }
            .padding(.top, LarderSpace.s4)
        }
        .padding(.horizontal, LarderSpace.s4)
    }

    private func sectionRow(_ target: SettingsSection, icon: LarderIconName, value: String) -> some View {
        SectionRow(icon: icon, title: target.title, value: value, isSelected: section == target) {
            section = target
        }
    }

    private var reminderSummary: String {
        let notifications = settings.notifications
        guard notifications.enabled else { return L10n.settingsOff }
        let lead = L10n.settingsLeadDaysPhrase(notifications.leadDays)
        switch notifications.reminderStyle {
        case .eachItem: return "\(L10n.settingsReminderStyleEachItem) · \(lead)"
        case .summary: return "\(L10n.settingsReminderStyleSummary) · \(Self.digestEveryDaysLabel(notifications.digestEveryDays))"
        case .both: return "\(L10n.settingsReminderStyleBoth) · \(lead)"
        case nil: return L10n.settingsReminderPreviewNone
        }
    }

    private var shopsSummary: String {
        if !settings.storeRemindersEnabled { return L10n.settingsOff }
        if settings.stores.isEmpty { return L10n.settingsAddStore }
        return settings.stores.map(\.label).joined(separator: ", ")
    }

    // MARK: Row building blocks

    /// The heading of one of the phone page's four groups.
    private func groupTitle(_ text: String, first: Bool = false) -> some View {
        Text(text)
            .larderFont(.title)
            .foregroundStyle(LarderColor.onBackground)
            .accessibilityAddTraits(.isHeader)
            .padding(.top, first ? LarderSpace.s1 : LarderSpace.s6)
            .padding(.bottom, LarderSpace.s2)
            .listRowInsets(EdgeInsets(top: 0, leading: LarderSpace.s4, bottom: 0, trailing: LarderSpace.s4))
            .listRowSeparator(.hidden)
            .listRowBackground(Color.clear)
    }

    private var cardGap: some View {
        Color.clear
            .frame(height: LarderSpace.s3)
            .listRowInsets(EdgeInsets())
            .listRowSeparator(.hidden)
            .listRowBackground(Color.clear)
            .accessibilityHidden(true)
    }

    private func cardRow<Content: View>(
        _ position: LarderGroupPosition,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: LarderSpace.s3, content: content)
            .padding(.horizontal, LarderSpace.s4)
            .padding(.vertical, LarderSpace.s3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .larderGroupRow(position)
    }

    /// A section's own heading. The expanded layout titles the pane instead.
    @ViewBuilder
    private func cardHead(_ title: String, icon: LarderIconName) -> some View {
        if !isExpanded {
            HStack(spacing: LarderSpace.s2) {
                LarderIcon(icon).foregroundStyle(LarderColor.primary)
                Text(title)
                    .larderFont(.titleSm)
                    .foregroundStyle(LarderColor.onSurface)
                    .accessibilityAddTraits(.isHeader)
            }
        }
    }

    private func description(_ text: String) -> some View {
        Text(text)
            .larderFont(.bodySm)
            .foregroundStyle(LarderColor.onSurfaceVariant)
            .fixedSize(horizontal: false, vertical: true)
    }

    private func controlLabel(_ text: String) -> some View {
        Text(text).larderFont(.label).foregroundStyle(LarderColor.onSurfaceVariant)
    }

    /// A field with the round add button beside it, lined up with the input below the label.
    private func addRow<Field: View>(
        icon: LarderIconName = .plus,
        label: String,
        topPadding: CGFloat = LarderSpace.s6,
        action: @escaping () -> Void,
        @ViewBuilder field: () -> Field
    ) -> some View {
        HStack(alignment: .top, spacing: LarderSpace.s2) {
            field()
            LarderIconButton(icon, label: label, color: LarderColor.onPrimaryContainer, action: action)
                .background(LarderColor.primaryContainer, in: Circle())
                .padding(.top, topPadding)
        }
    }

    // MARK: Notifications

    private var notificationsRows: some View {
        let notifications = settings.notifications
        return cardRow(.only) {
            cardHead(L10n.settingsSectionNotifications, icon: .bell)
            LarderSwitch(L10n.settingsNotificationsEnabled, isOn: Binding(
                get: { settings.notifications.enabled },
                set: { newValue in
                    update { $0.notifications = $0.notifications.withEnabled(newValue) }
                    rescheduleNotifications()
                }
            ))

            if notifications.enabled {
                if notificationsDenied {
                    LarderCallout(
                        .warning,
                        icon: .bellOff,
                        title: L10n.settingsNotificationsBlockedTitle,
                        message: L10n.settingsNotificationsBlocked,
                        actionTitle: L10n.settingsNotificationsOpenSettings
                    ) {
                        if let url = URL(string: UIApplication.openNotificationSettingsURLString) {
                            UIApplication.shared.open(url)
                        }
                    }
                }

                controlLabel(L10n.settingsReminderStyleLabel)
                LarderSegmented(
                    selection: Binding<ReminderStyle?>(
                        get: { settings.notifications.reminderStyle },
                        set: { newValue in
                            guard let newValue else { return }
                            update { $0.notifications = $0.notifications.withReminderStyle(newValue) }
                            rescheduleNotifications()
                        }
                    ),
                    options: [.eachItem, .summary, .both],
                    fill: true
                ) { style in
                    switch style {
                    case .summary: return L10n.settingsReminderStyleSummary
                    case .both: return L10n.settingsReminderStyleBoth
                    default: return L10n.settingsReminderStyleEachItem
                    }
                }

                if notifications.individualEnabled {
                    controlLabel(L10n.settingsLeadDaysLabel)
                    chipRow {
                        ForEach([1, 2, 3, 5, 7], id: \.self) { days in
                            LarderChip(L10n.settingsLeadDaysPhrase(days), isSelected: notifications.leadDays == days) {
                                notificationBinding(\.leadDays).wrappedValue = days
                            }
                        }
                    }
                }

                if notifications.digestEnabled {
                    controlLabel(L10n.settingsDigestFrequencyLabel)
                    chipRow {
                        ForEach([1, 2, 3, 5, 7], id: \.self) { days in
                            LarderChip(Self.digestEveryDaysLabel(days), isSelected: notifications.digestEveryDays == days) {
                                update { $0.notifications = $0.notifications.withDigestEveryDays(days) }
                                rescheduleNotifications()
                            }
                        }
                    }

                    if notifications.digestEveryDays == 7 {
                        controlLabel(L10n.settingsDigestWeekdayLabel)
                        chipRow {
                            ForEach(1...7, id: \.self) { isoDay in
                                LarderChip(Self.shortWeekdayName(isoDay: isoDay), isSelected: notifications.digestWeekday == isoDay) {
                                    notificationBinding(\.digestWeekday).wrappedValue = isoDay
                                }
                            }
                        }
                    }
                }

                timeRow
                reminderPreviewRow
            }
        }
        // Rows appear and disappear as choices change (style, interval, the blocked warning);
        // animating on the values themselves covers every binding without wrapping each
        // setter in withAnimation.
        .animation(.default, value: settings.notifications)
        .animation(.default, value: notificationsDenied)
    }

    private func chipRow<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        FlowLayout(spacing: LarderSpace.s2, rowSpacing: LarderChip.wrapRowSpacing) { content() }
            .padding(.vertical, LarderSpace.s2)
    }

    private var timeRow: some View {
        HStack(spacing: LarderSpace.s3) {
            LarderIcon(.clock).foregroundStyle(LarderColor.onSurfaceVariant)
            DatePicker(
                L10n.settingsTimeLabel,
                selection: Binding(
                    get: { Self.timeDate(hour: settings.notifications.hour, minute: settings.notifications.minute) },
                    set: { newDate in
                        let comps = Calendar.current.dateComponents([.hour, .minute], from: newDate)
                        update {
                            $0.notifications.hour = comps.hour ?? 9
                            $0.notifications.minute = comps.minute ?? 0
                        }
                        rescheduleNotifications()
                    }
                ),
                displayedComponents: [.hourAndMinute]
            )
            .larderFont(.body)
            .foregroundStyle(LarderColor.onSurface)
        }
        .padding(.horizontal, LarderSpace.s3)
        .padding(.vertical, LarderSpace.s1)
        .frame(minHeight: LarderSize.control)
        .background(LarderColor.surfaceContainer, in: RoundedRectangle(cornerRadius: LarderRadius.md))
    }

    /// The sentence saying what the choices above add up to.
    private var reminderPreviewRow: some View {
        HStack(alignment: .top, spacing: LarderSpace.s2) {
            LarderIcon(.bell, size: .custom(18)).foregroundStyle(LarderColor.primary)
            Text(reminderPreview)
                .larderFont(.bodySm)
                .foregroundStyle(LarderColor.onSurface)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(LarderSpace.s3)
        .overlay {
            RoundedRectangle(cornerRadius: LarderRadius.md).strokeBorder(LarderColor.outlineVariant, lineWidth: 1)
        }
        .accessibilityElement(children: .combine)
    }

    /// One plain sentence saying what the current notification settings will actually do, so
    /// the user doesn't have to work it out from the individual controls.
    private var reminderPreview: String {
        let notifications = settings.notifications
        let time = Self.timeDate(hour: notifications.hour, minute: notifications.minute)
            .formatted(date: .omitted, time: .shortened)
        let eachItem = L10n.settingsReminderPreviewEachItem(leadDays: notifications.leadDays, time: time)
        let summary: String
        switch notifications.digestEveryDays {
        case 1: summary = L10n.settingsReminderPreviewSummaryDaily(time: time)
        case 7: summary = L10n.settingsReminderPreviewSummaryWeekly(weekday: Self.weekdayName(isoDay: notifications.digestWeekday, capitalized: false), time: time)
        case let every: summary = L10n.settingsReminderPreviewSummaryEvery(days: every, time: time)
        }
        switch notifications.reminderStyle {
        case .eachItem: return eachItem
        case .summary: return summary
        case .both: return eachItem + "\n" + summary
        case nil: return L10n.settingsReminderPreviewNone
        }
    }

    /// "Daily" and "Weekly" at the ends, "N days" between.
    private static func digestEveryDaysLabel(_ days: Int) -> String {
        switch days {
        case 1: return L10n.settingsDigestDaily
        case 7: return L10n.settingsDigestWeekly
        default: return L10n.settingsDigestEveryDaysChip(days)
        }
    }

    private func rescheduleNotifications() {
        IOSNotificationManager.shared.rescheduleAll(items: viewModel.items)
        IOSNotificationManager.shared.scheduleDigestNotification(items: viewModel.items)
    }

    /// Binds one notification setting, re-scheduling every reminder and the summary on change -
    /// any of these fields can change what should fire, and when.
    private func notificationBinding<Value>(_ keyPath: WritableKeyPath<NotificationSettings, Value>) -> Binding<Value> {
        Binding(
            get: { settings.notifications[keyPath: keyPath] },
            set: { newValue in
                update { $0.notifications[keyPath: keyPath] = newValue }
                rescheduleNotifications()
            }
        )
    }

    /// Localized weekday name for an ISO day number (1 = Monday ... 7 = Sunday).
    /// `capitalized: false` keeps the locale's own casing, for use mid-sentence ("på mandag").
    private static func weekdayName(isoDay: Int, capitalized: Bool = true) -> String {
        let symbols = Calendar.current.standaloneWeekdaySymbols // index 0 = Sunday
        let name = symbols[isoDay % 7]
        return capitalized ? name.capitalized : name
    }

    private static func shortWeekdayName(isoDay: Int) -> String {
        Calendar.current.shortStandaloneWeekdaySymbols[isoDay % 7].capitalized
    }

    private static func timeDate(hour: Int, minute: Int) -> Date {
        var comps = DateComponents()
        comps.hour = hour
        comps.minute = minute
        return Calendar.current.date(from: comps) ?? Date()
    }

    // MARK: Shops

    @ViewBuilder
    private var shopsRows: some View {
        cardRow(settings.storeRemindersEnabled ? .first : .only) {
            cardHead(L10n.settingsSectionStores, icon: .store)
            LarderSwitch(
                L10n.settingsStoreRemindersEnabled,
                subtitle: L10n.settingsStoreRemindersDesc,
                isOn: Binding(
                    get: { settings.storeRemindersEnabled },
                    set: { enabled in
                        update { $0.storeRemindersEnabled = enabled }
                        StoreGeofenceManager.shared.sync(settings: store.settings)
                    }
                )
            )
        }

        // The shops are only actionable once the reminders are armed, and offering "use my
        // location" before then would ask for location for a feature that's switched off.
        if settings.storeRemindersEnabled {
            ForEach(Array(settings.stores.enumerated()), id: \.element.id) { index, storePlace in
                HStack(spacing: LarderSpace.s2) {
                    LarderIcon(.store).foregroundStyle(LarderColor.onSurfaceVariant)
                    Text(storePlace.label)
                        .larderFont(.body)
                        .foregroundStyle(LarderColor.onSurface)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    LarderIconButton(.x, label: L10n.settingsRemoveFormat(storePlace.label)) { removeStore(at: index) }
                }
                .padding(.leading, LarderSpace.s4)
                .padding(.trailing, LarderSpace.s2)
                .frame(minHeight: LarderSize.touch)
                .larderGroupRow(.middle)
                .larderSwipeActions(trailing: [
                    LarderSwipeAction(L10n.settingsRemoveFormat(storePlace.label), icon: .trash2, tint: .destructive) {
                        removeStore(at: index)
                    }
                ])
            }

            cardRow(.last) {
                LarderTextField(L10n.settingsAddStorePlaceholder, text: $newStoreText)
                    .disabled(isResolvingStoreLocation)
                    .submitLabel(.done)
                LarderButton(
                    L10n.settingsAddStore,
                    icon: .mapPin,
                    variant: .secondary,
                    isLoading: isResolvingStoreLocation,
                    fullWidth: true
                ) {
                    saveCurrentLocationAsStore()
                }
                .disabled(!isResolvingStoreLocation && newStoreText.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
    }

    private func saveCurrentLocationAsStore() {
        let label = newStoreText.trimmingCharacters(in: .whitespaces)
        guard !label.isEmpty, !isResolvingStoreLocation else { return }
        isResolvingStoreLocation = true
        StoreGeofenceManager.shared.resolveCurrentLocation { coordinate in
            isResolvingStoreLocation = false
            guard let coordinate else {
                showSnackbar(L10n.settingsStoreLocationFailed, actionTitle: L10n.settingsTryAgain) {
                    saveCurrentLocationAsStore()
                }
                return
            }
            update {
                $0.stores.append(
                    StorePlace(
                        id: "store_\(UUID().uuidString)",
                        label: label,
                        latitude: coordinate.latitude,
                        longitude: coordinate.longitude
                    )
                )
            }
            newStoreText = ""
            StoreGeofenceManager.shared.sync(settings: store.settings)
            StoreGeofenceManager.shared.requestAlwaysAuthorizationIfNeeded()
        }
    }

    private func removeStore(at index: Int) {
        guard settings.stores.indices.contains(index) else { return }
        let removed = settings.stores[index]
        update { $0.stores.remove(at: index) }
        StoreGeofenceManager.shared.sync(settings: store.settings)
        showSnackbar(L10n.itemRemovedToast(removed.label)) {
            update { $0.stores.insert(removed, at: min(index, $0.stores.count)) }
            StoreGeofenceManager.shared.sync(settings: store.settings)
        }
    }

    // MARK: Threshold

    private var thresholdRows: some View {
        cardRow(.only) {
            cardHead(L10n.settingsSectionExpiringSoon, icon: .clock)
            description(L10n.settingsExpiringSoonDesc)
            LarderSegmented(
                selection: Binding(
                    get: { settings.expiringSoonDays },
                    set: { newValue in update { $0.expiringSoonDays = newValue } }
                ),
                options: [1, 2, 3, 5, 7],
                fill: true
            ) { "\($0)" }
        }
    }

    // MARK: Locations

    @ViewBuilder
    private var locationsRows: some View {
        cardRow(.first) {
            cardHead(L10n.settingsSectionLocations, icon: .refrigerator)
            LarderSwitch(
                L10n.settingsRememberLastLocation,
                subtitle: L10n.settingsRememberLastLocationDesc,
                isOn: Binding(
                    get: { settings.rememberLastStorageLocation },
                    set: { newValue in update { $0.rememberLastStorageLocation = newValue } }
                )
            )
            description(L10n.settingsLocationsDesc)
        }

        ForEach(settings.storageLocations, id: \.self) { location in
            locationRow(location)
                .larderGroupRow(.middle)
                .larderSwipeActions(trailing: settings.storageLocations.count > 1 && editingLocation != location ? [
                    LarderSwipeAction(L10n.settingsDeleteLocationFormat(L10n.storageLocationName(location)), icon: .trash2, tint: .destructive) {
                        removeLocation(location)
                    }
                ] : [])
        }

        cardRow(.last) {
            addRow(label: L10n.settingsAddLocation, action: addLocation) {
                LarderTextField(
                    L10n.settingsAddLocationPlaceholder,
                    text: $newLocationText,
                    message: newLocationError.map { .error($0) }
                )
                .submitLabel(.done)
                .onSubmit(addLocation)
                .onChange(of: newLocationText) { _, _ in newLocationError = nil }
            }
        }
    }

    @ViewBuilder
    private func locationRow(_ location: String) -> some View {
        let name = L10n.storageLocationName(location)
        if editingLocation == location {
            addRow(
                icon: .check,
                label: L10n.settingsRenameLocationFormat(name),
                action: { commitRename(from: location) }
            ) {
                LarderTextField(
                    L10n.settingsRenameLocationFormat(name),
                    text: $editingLocationText,
                    message: editingLocationError.map { .error($0) }
                )
                .submitLabel(.done)
                .onSubmit { commitRename(from: location) }
                .onChange(of: editingLocationText) { _, _ in editingLocationError = nil }
            }
            .padding(.horizontal, LarderSpace.s4)
            .padding(.vertical, LarderSpace.s2)
        } else {
            let isDefault = settings.defaultStorageLocation == location
            HStack(spacing: 0) {
                LarderIconButton(
                    .star,
                    label: L10n.settingsSetDefaultLocationFormat(name),
                    color: isDefault ? LarderColor.primary : LarderColor.onSurfaceVariant
                ) {
                    update { $0.defaultStorageLocation = location }
                }
                .accessibilityAddTraits(isDefault ? .isSelected : [])

                VStack(alignment: .leading, spacing: 0) {
                    Text(name)
                        .larderFont(.body)
                        .foregroundStyle(LarderColor.onSurface)
                        .lineLimit(1)
                    if isDefault {
                        Text(L10n.settingsLocationDefault)
                            .larderFont(.caption)
                            .foregroundStyle(LarderColor.onSurfaceVariant)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                LarderIconButton(.pencil, label: L10n.settingsRenameLocationFormat(name)) {
                    editingLocation = location
                    editingLocationText = name
                    editingLocationError = nil
                }
                LarderIconButton(.trash2, label: L10n.settingsDeleteLocationFormat(name)) {
                    removeLocation(location)
                }
                .disabled(settings.storageLocations.count <= 1)
            }
            .padding(.horizontal, LarderSpace.s2)
            .frame(minHeight: LarderSize.touch)
        }
    }

    private func commitRename(from oldName: String) {
        switch settings.checkLocationRename(oldName, input: editingLocationText) {
        case .noChange:
            editingLocation = nil
        case .taken(let name):
            editingLocationError = L10n.settingsLocationTaken(name)
        case .accepted(let name):
            update { $0.renameLocation(oldName, to: name) }
            editingLocation = nil
        }
    }

    private func addLocation() {
        switch settings.checkNewLocation(newLocationText) {
        case .noChange:
            break
        case .taken(let name):
            newLocationError = L10n.settingsLocationTaken(name)
        case .accepted(let name):
            update { $0.storageLocations.append(name) }
            newLocationText = ""
        }
    }

    private func removeLocation(_ location: String) {
        var updated = store.settings
        guard let removed = updated.removeLocation(location) else { return }
        store.save(updated)
        showSnackbar(L10n.itemRemovedToast(L10n.storageLocationName(location))) {
            update { $0.restoreLocation(removed) }
        }
    }

    // MARK: Categories

    private var categoriesRows: some View {
        cardRow(.only) {
            cardHead(L10n.settingsSectionCategories, icon: .layers)
            description(L10n.settingsCategoriesDesc)
            Button {
                showCategories = true
            } label: {
                HStack(spacing: LarderSpace.s3) {
                    LarderIcon(.layers)
                    Text(L10n.settingsManageCategoriesButton)
                        .larderFont(.body)
                        .foregroundStyle(LarderColor.onSurface)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    LarderIcon(.chevronRight)
                }
                .foregroundStyle(LarderColor.onSurfaceVariant)
                .padding(.horizontal, LarderSpace.s3)
                .frame(minHeight: LarderSize.control)
                .background(LarderColor.surfaceContainer, in: RoundedRectangle(cornerRadius: LarderRadius.md))
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
    }

    // MARK: Allergens

    /// Empty by default: this is a safety feature for the people who need it, not a nag for
    /// everyone.
    @ViewBuilder
    private var allergensRows: some View {
        cardRow(.first) {
            cardHead(L10n.settingsSectionAllergens, icon: .circleAlert)
            description(L10n.settingsAllergensDesc)
        }

        ForEach(settings.allergens.sorted(), id: \.self) { allergen in
            HStack(spacing: 0) {
                Text(allergen)
                    .larderFont(.body)
                    .foregroundStyle(LarderColor.onSurface)
                    .frame(maxWidth: .infinity, alignment: .leading)
                LarderIconButton(.x, label: L10n.settingsRemoveFormat(allergen)) { removeAllergen(allergen) }
            }
            .padding(.leading, LarderSpace.s4)
            .padding(.trailing, LarderSpace.s2)
            .frame(minHeight: LarderSize.touch)
            .larderGroupRow(.middle)
            .larderSwipeActions(trailing: [
                LarderSwipeAction(L10n.settingsRemoveFormat(allergen), icon: .trash2, tint: .destructive) {
                    removeAllergen(allergen)
                }
            ])
        }

        cardRow(.last) {
            addRow(label: L10n.settingsAddAllergen, action: addAllergen) {
                LarderTextField(
                    L10n.settingsAddAllergenLabel,
                    text: $newAllergenText,
                    placeholder: L10n.settingsAddAllergenPlaceholder
                )
                .textInputAutocapitalization(.never)
                .submitLabel(.done)
                .onSubmit(addAllergen)
            }
        }
    }

    private func addAllergen() {
        // Stored lowercased: matching is case-insensitive anyway, and this stops "Milk" and
        // "milk" reading as two different entries in the list.
        let entry = newAllergenText.trimmingCharacters(in: .whitespaces).lowercased()
        newAllergenText = ""
        guard !entry.isEmpty else { return }
        update { $0.allergens.insert(entry) }
    }

    private func removeAllergen(_ allergen: String) {
        update { $0.allergens.remove(allergen) }
        showSnackbar(L10n.itemRemovedToast(allergen)) {
            update { $0.allergens.insert(allergen) }
        }
    }

    // MARK: Appearance

    private var appearanceRows: some View {
        cardRow(.only) {
            LarderSegmented(
                selection: Binding(
                    get: { settings.theme },
                    set: { newValue in update { $0.theme = newValue } }
                ),
                options: Array(AppTheme.allCases),
                fill: true
            ) { $0.localizedTitle }
        }
    }

    // MARK: Data

    private var dataRows: some View {
        cardRow(.only) {
            description(L10n.settingsBackupDesc)
            LarderButton(L10n.settingsExportBackupButton, icon: .upload, variant: .outline, fullWidth: true) {
                startExport()
            }
            LarderButton(L10n.settingsImportBackupButton, icon: .download, variant: .outline, fullWidth: true) {
                showImporter = true
            }
            LarderButton(L10n.settingsClearDataButton, icon: .trash2, variant: .dangerText, fullWidth: true) {
                showClearConfirm = true
            }
        }
    }

    private func startExport() {
        exportFile = BackupFile(text: BackupSerializer.encode(viewModel.exportBackup()))
        showExporter = true
    }

    private func handleExport(_ result: Result<URL, Error>) {
        switch result {
        case .success:
            showSnackbar(L10n.backupExportSuccess)
        case .failure(let error):
            // Backing out of the picker is a choice, not a failure.
            if (error as? CocoaError)?.code == .userCancelled { return }
            showSnackbar(L10n.backupExportFailed, actionTitle: L10n.settingsTryAgain) { startExport() }
        }
    }

    private func applyImport(mode: RestoreMode) {
        guard let url = pendingImportURL else { return }
        pendingImportURL = nil

        // A document-picker URL is security-scoped: without this the read fails with a
        // permission error even though the user just chose the file themselves.
        let needsScope = url.startAccessingSecurityScopedResource()
        defer { if needsScope { url.stopAccessingSecurityScopedResource() } }

        guard let raw = try? String(contentsOf: url, encoding: .utf8) else {
            showSnackbar(L10n.backupImportMalformed)
            return
        }
        let (result, summary) = viewModel.restoreBackup(raw, mode: mode)
        switch result {
        case .malformed:
            showSnackbar(L10n.backupImportMalformed)
        case .unsupportedVersion:
            showSnackbar(L10n.backupImportUnsupported)
        case .success:
            showSnackbar(summary.isEmpty ? L10n.backupImportEmpty : L10n.backupImportSuccess(summary.itemsRestored))
        }
    }
}

/// A row of the section list: icon, name and the current value.
private struct SectionRow: View {
    let icon: LarderIconName
    let title: String
    let value: String
    let isSelected: Bool
    var showsChevron = false
    let action: () -> Void

    var body: some View {
        let valueColor = isSelected ? LarderColor.onPrimaryContainer : LarderColor.onSurfaceVariant
        Button(action: action) {
            HStack(spacing: LarderSpace.s3) {
                LarderIcon(icon).foregroundStyle(valueColor)
                VStack(alignment: .leading, spacing: 0) {
                    Text(title)
                        .larderFont(.body)
                        .foregroundStyle(isSelected ? LarderColor.onPrimaryContainer : LarderColor.onSurface)
                    Text(value)
                        .larderFont(.bodySm)
                        .foregroundStyle(valueColor)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if showsChevron {
                    LarderIcon(.chevronRight).foregroundStyle(valueColor)
                }
            }
            .padding(.horizontal, LarderSpace.s4)
            .padding(.vertical, LarderSpace.s2)
            .frame(minHeight: 64)
            .background(isSelected ? LarderColor.primaryContainer : Color.clear)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

/// The result of pressing Add on a new location, or the tick on a rename.
private enum LocationNameCheck {
    /// Nothing to do: the field is empty, or a rename left the name as it was.
    case noChange
    case taken(String)
    case accepted(String)
}

/// What Undo needs to put a removed location back where it was.
private struct RemovedLocation {
    let name: String
    let index: Int
    let wasDefault: Bool
    let wasLastUsed: Bool
}

/// Hand ports of Android's `SettingsLogic.kt`.
private extension AppSettings {
    /// Whether `name` already names one of `locations`, either exactly as stored or as a
    /// built-in location's translated name. Without the second check, adding "Kjøleskap" next to
    /// the built-in "Fridge" would give two locations that show up under the same name.
    private func isExisting(_ name: String, in locations: [String]) -> Bool {
        locations.contains { $0 == name || L10n.storageLocationName($0) == name }
    }

    func checkNewLocation(_ input: String) -> LocationNameCheck {
        let name = input.trimmingCharacters(in: .whitespaces)
        if name.isEmpty { return .noChange }
        return isExisting(name, in: storageLocations) ? .taken(name) : .accepted(name)
    }

    /// The field starts out showing the translated name, so confirming it unchanged must keep
    /// the built-in key rather than store its translation.
    func checkLocationRename(_ location: String, input: String) -> LocationNameCheck {
        let name = input.trimmingCharacters(in: .whitespaces)
        if name.isEmpty || name == location || name == L10n.storageLocationName(location) { return .noChange }
        return isExisting(name, in: storageLocations.filter { $0 != location }) ? .taken(name) : .accepted(name)
    }

    mutating func renameLocation(_ location: String, to newName: String) {
        storageLocations = storageLocations.map { $0 == location ? newName : $0 }
        if defaultStorageLocation == location { defaultStorageLocation = newName }
        if lastUsedStorageLocation == location { lastUsedStorageLocation = newName }
    }

    /// Takes `location` out, moving the default to the first one left. Nil when it isn't there
    /// or is the last one, which can't be removed.
    mutating func removeLocation(_ location: String) -> RemovedLocation? {
        guard let index = storageLocations.firstIndex(of: location), storageLocations.count > 1 else { return nil }
        let removed = RemovedLocation(
            name: location,
            index: index,
            wasDefault: defaultStorageLocation == location,
            wasLastUsed: lastUsedStorageLocation == location
        )
        storageLocations.remove(at: index)
        if removed.wasDefault { defaultStorageLocation = storageLocations[0] }
        if removed.wasLastUsed { lastUsedStorageLocation = nil }
        return removed
    }

    /// Works on the settings as they are now, so anything changed since the removal stays
    /// changed. If the same name was added again in the meantime, there is nothing to put back.
    mutating func restoreLocation(_ removed: RemovedLocation) {
        guard !storageLocations.contains(removed.name) else { return }
        storageLocations.insert(removed.name, at: min(removed.index, storageLocations.count))
        if removed.wasDefault { defaultStorageLocation = removed.name }
        if removed.wasLastUsed { lastUsedStorageLocation = removed.name }
    }
}

/// Wraps the backup JSON for `fileExporter`, which needs a `FileDocument` rather than a string.
private struct BackupFile: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }

    let text: String

    init(text: String) { self.text = text }

    init(configuration: ReadConfiguration) throws {
        // Export-only: the import side reads the picked URL directly (see applyImport), because
        // it needs to report *why* a file was rejected rather than just failing to open.
        text = ""
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: Data(text.utf8))
    }
}
