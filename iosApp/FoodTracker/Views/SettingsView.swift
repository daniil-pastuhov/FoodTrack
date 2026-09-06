import SwiftUI

public struct SettingsView: View {
    @ObservedObject var viewModel: NativePantryViewModel
    @ObservedObject private var store = SettingsStore.shared
    /// Called after "Clear All Data" is confirmed, so the root can switch back to the Home
    /// tab — mirroring the Android app's return-to-dashboard behavior after a clear.
    var onDataCleared: () -> Void

    @State private var newLocationText: String = ""
    @State private var editingLocation: String? = nil
    @State private var editingLocationText: String = ""
    @State private var showClearConfirm: Bool = false

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

    private var dataSection: some View {
        Section(header: Text(L10n.settingsSectionData)) {
            Button(role: .destructive) {
                showClearConfirm = true
            } label: {
                Text(L10n.settingsClearDataButton)
            }
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
        update { current in
            current.storageLocations.remove(atOffsets: offsets)
            if removedDefault {
                current.defaultStorageLocation = current.storageLocations.first ?? current.defaultStorageLocation
            }
        }
    }
}
