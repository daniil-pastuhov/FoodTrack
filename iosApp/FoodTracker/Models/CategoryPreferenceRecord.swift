import Foundation
import SwiftData

/// SwiftData persistence model for `CategoryPreference`, mirroring `PantryItemRecord`'s split
/// between a `@Model` reference type and the plain `Codable` struct used by views.
@Model
final class CategoryPreferenceRecord {
    @Attribute(.unique) var originName: String
    var displayName: String
    var sortOrder: Double = 0
    var enabled: Bool = true
    var defaultShelfLifeDays: Int?

    init(from preference: CategoryPreference) {
        originName = preference.originName
        displayName = preference.displayName
        sortOrder = preference.sortOrder
        enabled = preference.enabled
        defaultShelfLifeDays = preference.defaultShelfLifeDays
    }

    func update(from preference: CategoryPreference) {
        displayName = preference.displayName
        sortOrder = preference.sortOrder
        enabled = preference.enabled
        defaultShelfLifeDays = preference.defaultShelfLifeDays
    }

    var asPreference: CategoryPreference {
        CategoryPreference(
            originName: originName,
            displayName: displayName,
            sortOrder: sortOrder,
            enabled: enabled,
            defaultShelfLifeDays: defaultShelfLifeDays
        )
    }
}
