import Foundation
import SwiftData

/// SwiftData row for one staple (`Staple`). Every stored property has a default so the store
/// gains its table in place. Compiled into the widget extension too, because
/// `NativePantryViewModel.sharedModelContainer` lists it in the shared schema; the widget never
/// reads it. Android: `StapleEntity`, table `staples`.
@Model
final class StapleRecord {
    @Attribute(.unique) var id: String = UUID().uuidString
    var name: String = ""
    var presetId: String? = nil
    var addedAtEpochMillis: Int64 = 0

    init(from staple: Staple) {
        id = staple.id
        name = staple.name
        presetId = staple.presetId
        addedAtEpochMillis = staple.addedAtEpochMillis
    }

    var asStaple: Staple {
        Staple(id: id, name: name, presetId: presetId, addedAtEpochMillis: addedAtEpochMillis)
    }
}
