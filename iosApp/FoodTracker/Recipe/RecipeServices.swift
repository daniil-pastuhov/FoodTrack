import Foundation
import SwiftData

// The seams `RecipeViewModel` is built on, one protocol per thing that reaches outside it, so
// each route can be swapped or faked. Mirrors Android's `RecipeStateHolder` parameters.

// MARK: On-device model

/// Whether the phone's own model can write a recipe in a given language right now.
enum OnDeviceRecipeAvailability: Equatable, Sendable {
    case available
    /// The phone has a model, still downloading. The design's "Your phone is getting ready".
    case preparing
    /// The model doesn't know the language recipes are set to be written in.
    case unsupportedLanguage
    /// No model on this phone: too old, Apple Intelligence off, or iOS before 26.
    case unsupported
}

enum OnDeviceRecipeError: Error, Equatable {
    /// The model became unavailable, or doesn't take the language after all.
    case unavailable
    case unsupportedLanguage
    /// The model's safety rules declined these ingredients or preferences.
    case refused
    case failed
}

/// The free route that writes in the app: Apple's Foundation Models where the phone has them.
protocol OnDeviceRecipeModel: Sendable {
    func availability(for language: RecipeLanguage) -> OnDeviceRecipeAvailability
    /// Throws `OnDeviceRecipeError`, or `CancellationError` when the task is cancelled.
    func write(_ request: RecipeRequest) async throws -> Recipe
}

// MARK: Paid route

/// Our server's five callables (`functions/CONTRACT.md`). `FirebaseRecipeBackend` is the real
/// one; errors are `RecipeServerError`. `RecipeCredits` builds the purchase and account flows on
/// it, and `RecipeViewModel` the generation.
protocol RecipeBackend: Sendable {
    /// Starts a generation, or answers for one already started under the same id. Each call
    /// spends a limited-use App Check token.
    func generate(_ request: GenerateRequest) async throws -> GenerateResponse
    /// An existing generation's state, without starting one or spending a limited-use token.
    func generation(requestId: String) async throws -> GenerateResponse
    func balance() async throws -> BalanceResponse
    func verifyPurchase(signedTransaction: String, consumptionConsent: Bool) async throws -> VerifyPurchaseResponse
    func mergeBalance(sourceIdToken: String) async throws -> MergeBalanceResponse
    func deleteAccount(appleAuthorizationCode: String?) async throws
}

// MARK: Configuration

protocol RecipeConfigStore {
    func load() -> RecipeConfig
    func save(_ config: RecipeConfig)
}

/// The configuration under its own key in the App Group suite, beside `SettingsStore`'s, but
/// outside `AppSettings`: backups and `WireSettings` don't carry it.
struct UserDefaultsRecipeConfigStore: RecipeConfigStore {
    static let storageKey = "com.foodtracker.recipe_config"

    private let defaults: UserDefaults

    init(defaults: UserDefaults = UserDefaults(suiteName: AppGroup.identifier) ?? .standard) {
        self.defaults = defaults
    }

    func load() -> RecipeConfig {
        guard let data = defaults.data(forKey: Self.storageKey),
              let config = try? JSONDecoder().decode(RecipeConfig.self, from: data) else {
            return RecipeConfig()
        }
        return config
    }

    func save(_ config: RecipeConfig) {
        if let data = try? JSONEncoder().encode(config) {
            defaults.set(data, forKey: Self.storageKey)
        }
    }
}

// MARK: Saved recipes

@MainActor
protocol SavedRecipeStore {
    /// Newest first.
    func all() -> [SavedRecipe]
    func save(_ entry: SavedRecipe)
    func delete(id: String)
}

/// Saved recipes in the shared SwiftData store, as `SavedRecipeRecord` rows.
@MainActor
final class SwiftDataSavedRecipeStore: SavedRecipeStore {
    private let context: ModelContext

    init(container: ModelContainer = NativePantryViewModel.sharedModelContainer) {
        context = ModelContext(container)
    }

    func all() -> [SavedRecipe] {
        let descriptor = FetchDescriptor<SavedRecipeRecord>(sortBy: [SortDescriptor(\.createdAt, order: .reverse)])
        return ((try? context.fetch(descriptor)) ?? []).compactMap(Self.entry(from:))
    }

    func save(_ entry: SavedRecipe) {
        guard let record = Self.record(from: entry) else { return }
        let id = entry.id
        let existing = (try? context.fetch(FetchDescriptor<SavedRecipeRecord>(predicate: #Predicate { $0.id == id }))) ?? []
        existing.forEach(context.delete)
        context.insert(record)
        try? context.save()
    }

    func delete(id: String) {
        try? context.delete(model: SavedRecipeRecord.self, where: #Predicate { $0.id == id })
        try? context.save()
    }

    /// A row the app can't read, such as a kind from a later version, is left out rather than
    /// failing the whole list.
    static func entry(from record: SavedRecipeRecord) -> SavedRecipe? {
        let decoder = JSONDecoder()
        let content: SavedRecipe.Content
        switch SavedRecipeKind(rawValue: record.kind) {
        case .recipe:
            guard var recipe = try? decoder.decode(Recipe.self, from: record.payload) else { return nil }
            recipe.source = RecipeSource(rawValue: record.source) ?? recipe.source
            content = .recipe(recipe)
        case .menu:
            guard let menu = try? decoder.decode(RecipeMenu.self, from: record.payload) else { return nil }
            content = .menu(menu)
        case nil:
            return nil
        }
        return SavedRecipe(id: record.id, createdAt: record.createdAt, content: content)
    }

    static func record(from entry: SavedRecipe) -> SavedRecipeRecord? {
        let encoder = JSONEncoder()
        let payload: Data?
        let title: String
        switch entry.content {
        case .recipe(let recipe):
            payload = try? encoder.encode(recipe)
            title = recipe.title
        case .menu(let menu):
            payload = try? encoder.encode(menu)
            title = menu.days.first?.courses.first?.recipe.title ?? ""
        }
        guard let payload else { return nil }
        return SavedRecipeRecord(
            id: entry.id,
            kind: entry.kind.rawValue,
            payload: payload,
            title: title,
            createdAt: entry.createdAt,
            source: entry.source.rawValue
        )
    }
}
