import SwiftUI
import UniformTypeIdentifiers

public struct SettingsView: View {
    @ObservedObject var viewModel: NativePantryViewModel
    @ObservedObject private var store = SettingsStore.shared
    /// Called after "Clear All Data" is confirmed, so the root can switch back to the Home
    /// tab — mirroring the Android app's return-to-dashboard behavior after a clear.
    var onDataCleared: () -> Void

    @State private var newLocationText: String = ""
    @State private var newAllergenText: String = ""
    @State private var editingLocation: String? = nil
    @State private var editingLocationText: String = ""
    @State private var showClearConfirm: Bool = false
    @State private var showExporter = false
    @State private var showImporter = false
    /// Held between picking a file and choosing how to apply it: the mode question only makes
    /// sense once we know there is a file, and asking it first would strand the answer if the
    /// user then backed out of the picker.
    @State private var pendingImportURL: URL?
    @State private var backupMessage: String?
    @State private var newStoreText: String = ""
    @State private var isResolvingStoreLocation = false
    @State private var storeLocationFailed = false

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
            Form {
                notificationsSection
                expiringSoonSection
                locationsSection
                allergensSection
                storeRemindersSection
                categoriesSection
                appearanceSection
                dataSection
            }
            .navigationTitle(L10n.settingsTitle)
            .navigationBarTitleDisplayMode(.inline)
            // Clears the floating pill nav bar that overlays this screen (see RootTabView).
            .safeAreaInset(edge: .bottom) { Color.clear.frame(height: 170) }
            .confirmationDialog(
                L10n.clearDataDialogTitle,
                isPresented: $showClearConfirm,
                titleVisibility: .visible
            ) {
                Button(L10n.clearDataConfirm, role: .destructive) {
                    viewModel.clearAllData()
                    onDataCleared()
                }
                Button(L10n.cancelButton, role: .cancel) {}
            } message: {
                Text(L10n.clearDataDialogMessage)
            }
            .fileExporter(
                isPresented: $showExporter,
                document: BackupFile(text: BackupSerializer.encode(viewModel.exportBackup())),
                contentType: .json,
                defaultFilename: BackupSerializer.suggestedFileName(
                    isoDate: BackupDates.string(from: Date())
                )
            ) { result in
                backupMessage = (try? result.get()) != nil
                    ? L10n.backupExportSuccess
                    : L10n.backupExportFailed
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
                Button(L10n.cancelButton, role: .cancel) { pendingImportURL = nil }
            } message: {
                Text(L10n.restoreModeMessage)
            }
            .overlay(alignment: .bottom) {
                if let backupMessage {
                    Text(backupMessage)
                        .font(.footnote.weight(.medium))
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                        .background(.thinMaterial, in: Capsule())
                        .padding(.bottom, 200)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            .animation(.easeInOut(duration: 0.25), value: backupMessage)
            .task(id: backupMessage) {
                guard backupMessage != nil else { return }
                try? await Task.sleep(for: .seconds(3))
                if !Task.isCancelled { backupMessage = nil }
            }
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
            backupMessage = L10n.backupImportMalformed
            return
        }
        let (result, summary) = viewModel.restoreBackup(raw, mode: mode)
        switch result {
        case .malformed:
            backupMessage = L10n.backupImportMalformed
        case .unsupportedVersion:
            backupMessage = L10n.backupImportUnsupported
        case .success:
            backupMessage = summary.isEmpty
                ? L10n.backupImportEmpty
                : L10n.backupImportSuccess(summary.itemsRestored)
        }
    }

    private var notificationsSection: some View {
        Section(header: Text(L10n.settingsSectionNotifications)) {
            Toggle(L10n.settingsNotificationsEnabled, isOn: Binding(
                get: { settings.notifications.enabled },
                set: { newValue in
                    update { $0.notifications.enabled = newValue }
                    IOSNotificationManager.shared.rescheduleAll(items: viewModel.items)
                    IOSNotificationManager.shared.scheduleDigestNotification(items: viewModel.items)
                }
            ))

            if settings.notifications.enabled {
                Picker(L10n.settingsLeadDaysLabel, selection: Binding(
                    get: { settings.notifications.leadDays },
                    set: { newValue in
                        update { $0.notifications.leadDays = newValue }
                        IOSNotificationManager.shared.rescheduleAll(items: viewModel.items)
                        IOSNotificationManager.shared.scheduleDigestNotification(items: viewModel.items)
                    }
                )) {
                    ForEach([1, 2, 3, 5, 7], id: \.self) { days in
                        Text("\(days)").tag(days)
                    }
                }

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
                            IOSNotificationManager.shared.rescheduleAll(items: viewModel.items)
                            IOSNotificationManager.shared.scheduleDigestNotification(items: viewModel.items)
                        }
                    ),
                    displayedComponents: [.hourAndMinute]
                )
            }
        }
    }

    private var expiringSoonSection: some View {
        Section(header: Text(L10n.settingsSectionExpiringSoon), footer: Text(L10n.settingsExpiringSoonDesc)) {
            Picker(L10n.settingsSectionExpiringSoon, selection: Binding(
                get: { settings.expiringSoonDays },
                set: { newValue in update { $0.expiringSoonDays = newValue } }
            )) {
                ForEach([1, 2, 3, 5, 7], id: \.self) { days in
                    Text("\(days)").tag(days)
                }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
        }
    }

    private var locationsSection: some View {
        Section(header: Text(L10n.settingsSectionLocations), footer: Text(L10n.settingsLocationsDesc)) {
            Toggle(isOn: Binding(
                get: { settings.rememberLastStorageLocation },
                set: { newValue in update { $0.rememberLastStorageLocation = newValue } }
            )) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(L10n.settingsRememberLastLocation)
                    Text(L10n.settingsRememberLastLocationDesc)
                        .font(.footnote)
                        .foregroundColor(.secondary)
                }
            }

            ForEach(settings.storageLocations, id: \.self) { location in
                HStack {
                    if editingLocation == location {
                        TextField(location, text: $editingLocationText)
                        Button {
                            commitRename(from: location)
                        } label: {
                            Image(systemName: "checkmark.circle.fill")
                        }
                        .buttonStyle(.plain)
                    } else {
                        Button {
                            update { $0.defaultStorageLocation = location }
                        } label: {
                            Image(systemName: settings.defaultStorageLocation == location ? "star.fill" : "star")
                                .foregroundColor(.accentColor)
                        }
                        .buttonStyle(.plain)

                        Text(location)
                        Spacer()

                        Button {
                            editingLocation = location
                            editingLocationText = location
                        } label: {
                            Image(systemName: "pencil")
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .onDelete(perform: deleteLocations)

            HStack {
                TextField(L10n.settingsAddLocationPlaceholder, text: $newLocationText)
                Button {
                    addLocation()
                } label: {
                    Image(systemName: "plus.circle.fill")
                }
                .buttonStyle(.plain)
                .disabled(newLocationText.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
    }

    private var categoriesSection: some View {
        Section(header: Text(L10n.settingsSectionCategories), footer: Text(L10n.settingsCategoriesDesc)) {
            NavigationLink(L10n.settingsManageCategoriesButton) {
                CategorySettingsView(viewModel: viewModel)
            }
        }
    }

    private var appearanceSection: some View {
        Section(header: Text(L10n.settingsSectionAppearance)) {
            Picker(L10n.settingsSectionAppearance, selection: Binding(
                get: { settings.theme },
                set: { newValue in update { $0.theme = newValue } }
            )) {
                ForEach(AppTheme.allCases) { theme in
                    Text(theme.localizedTitle).tag(theme)
                }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
        }
    }

    /// Allergen warnings. Empty by default — this is a safety feature for the people who need
    /// it, not a nag for everyone.
    private var allergensSection: some View {
        Section {
            ForEach(settings.allergens.sorted(), id: \.self) { allergen in
                Text(allergen)
            }
            .onDelete { offsets in
                let sorted = settings.allergens.sorted()
                let removed = offsets.map { sorted[$0] }
                update { $0.allergens.subtract(removed) }
            }
            HStack {
                TextField(L10n.settingsAddAllergenPlaceholder, text: $newAllergenText)
                    .textInputAutocapitalization(.never)
                Button(L10n.settingsAddAllergen) {
                    // Stored lowercased: matching is case-insensitive anyway, and this stops
                    // "Milk" and "milk" reading as two different entries in the list.
                    let entry = newAllergenText.trimmingCharacters(in: .whitespaces).lowercased()
                    guard !entry.isEmpty else { return }
                    update { $0.allergens.insert(entry) }
                    newAllergenText = ""
                }
                .disabled(newAllergenText.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        } header: {
            Text(L10n.settingsSectionAllergens)
        } footer: {
            Text(L10n.settingsAllergensDesc)
        }
    }

    private var storeRemindersSection: some View {
        Section {
            Toggle(L10n.settingsStoreRemindersEnabled, isOn: Binding(
                get: { settings.storeRemindersEnabled },
                set: { enabled in
                    update { $0.storeRemindersEnabled = enabled }
                    if enabled {
                        // While-in-use only at this point. "Always" is escalated once a shop has
                        // actually been saved, where the dialog has an obvious purpose.
                        StoreGeofenceManager.shared.requestWhenInUseAuthorization()
                    }
                    StoreGeofenceManager.shared.sync(settings: store.settings)
                }
            ))

            // The stores themselves are only actionable once the reminders are armed, and
            // showing "use my location" before then would ask for location permission for a
            // feature the user hasn't switched on.
            if settings.storeRemindersEnabled {
                ForEach(settings.stores, id: \.id) { storePlace in
                    Text(storePlace.label)
                }
                .onDelete { offsets in
                    update { $0.stores.remove(atOffsets: offsets) }
                    StoreGeofenceManager.shared.sync(settings: store.settings)
                }

                if storeLocationFailed {
                    Text(L10n.settingsStoreLocationFailed)
                        .font(.caption)
                        .foregroundStyle(.red)
                }

                TextField(L10n.settingsAddStorePlaceholder, text: $newStoreText)
                    .disabled(isResolvingStoreLocation)
                HStack {
                    Button(L10n.settingsAddStore) { saveCurrentLocationAsStore() }
                        .disabled(isResolvingStoreLocation || newStoreText.trimmingCharacters(in: .whitespaces).isEmpty)
                    if isResolvingStoreLocation {
                        Spacer()
                        ProgressView()
                    }
                }
            }
        } header: {
            Text(L10n.settingsSectionStores)
        } footer: {
            Text(L10n.settingsStoreRemindersDesc)
        }
    }

    private func saveCurrentLocationAsStore() {
        let label = newStoreText.trimmingCharacters(in: .whitespaces)
        guard !label.isEmpty else { return }
        isResolvingStoreLocation = true
        storeLocationFailed = false
        StoreGeofenceManager.shared.resolveCurrentLocation { coordinate in
            isResolvingStoreLocation = false
            guard let coordinate else {
                storeLocationFailed = true
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

    private var dataSection: some View {
        Section {
            Button {
                showExporter = true
            } label: {
                Label(L10n.settingsExportBackupButton, systemImage: "square.and.arrow.up")
            }
            Button {
                showImporter = true
            } label: {
                Label(L10n.settingsImportBackupButton, systemImage: "square.and.arrow.down")
            }
            Button(role: .destructive) {
                showClearConfirm = true
            } label: {
                Text(L10n.settingsClearDataButton)
            }
        } header: {
            Text(L10n.settingsSectionData)
        } footer: {
            Text(L10n.settingsBackupDesc)
        }
    }

    private static func timeDate(hour: Int, minute: Int) -> Date {
        var comps = DateComponents()
        comps.hour = hour
        comps.minute = minute
        return Calendar.current.date(from: comps) ?? Date()
    }

    private func commitRename(from oldName: String) {
        let newName = editingLocationText.trimmingCharacters(in: .whitespaces)
        if !newName.isEmpty && !settings.storageLocations.contains(newName) {
            update { current in
                current.storageLocations = current.storageLocations.map { $0 == oldName ? newName : $0 }
                if current.defaultStorageLocation == oldName {
                    current.defaultStorageLocation = newName
                }
                if current.lastUsedStorageLocation == oldName {
                    current.lastUsedStorageLocation = newName
                }
            }
        }
        editingLocation = nil
    }

    private func addLocation() {
        let newName = newLocationText.trimmingCharacters(in: .whitespaces)
        guard !newName.isEmpty, !settings.storageLocations.contains(newName) else { return }
        update { $0.storageLocations.append(newName) }
        newLocationText = ""
    }

    private func deleteLocations(at offsets: IndexSet) {
        guard settings.storageLocations.count - offsets.count >= 1 else { return }
        let removedDefault = offsets.contains { settings.storageLocations[$0] == settings.defaultStorageLocation }
        let removedLastUsed = offsets.contains { settings.storageLocations[$0] == settings.lastUsedStorageLocation }
        update { current in
            current.storageLocations.remove(atOffsets: offsets)
            if removedDefault {
                current.defaultStorageLocation = current.storageLocations.first ?? current.defaultStorageLocation
            }
            if removedLastUsed {
                current.lastUsedStorageLocation = nil
            }
        }
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
