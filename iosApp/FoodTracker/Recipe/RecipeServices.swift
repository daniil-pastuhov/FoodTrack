import Foundation
import SwiftData

// The seams `RecipeViewModel` is built on, one protocol per thing that reaches outside it, so
// each route can be swapped or faked. Mirrors Android's `RecipeStateHolder` parameters.

// MARK: On-device model

// `OnDeviceRecipeAvailability` is in Models/RecipeModels.swift, with its pure rule.

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

/// Our server's callables (`functions/CONTRACT.md`). `FirebaseRecipeBackend` is the real
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
    /// `appleLinkRemoved` in the answer says whether Apple's link went too (absent without Apple).
    func deleteAccount(appleAuthorizationCode: String?) async throws -> DeleteAccountResponse
    /// Hands the server the code of an Apple sign-in or link, so it can remove Apple's link when
    /// the account is deleted from any phone. Callers ignore its failures.
    func registerAppleSignIn(authorizationCode: String) async throws
    /// The taste-profile quiz in `language` (an ordinary App Check token, as `getBalance`). Only
    /// when the person opens the quiz, and only after a reply said `acceptsTaste`.
    func tasteQuiz(language: String) async throws -> TasteQuiz
    /// `getWelcomeOffer`: how many free recipes a sign-in gets now. App Check only (an ordinary
    /// token), no sign-in: it works before any account exists and creates none, and it sends
    /// nothing about the person. Only where the paid route can be available (`WelcomeRecipes.asks`).
    func welcomeOffer() async throws -> WelcomeOfferResponse
    /// `claimWelcome`: the free recipes, once per Apple or Google account. Signed in only, with a
    /// limited-use App Check token. Idempotent: a retry after a lost answer never grants twice.
    func claimWelcome() async throws -> ClaimWelcomeResponse
    /// `reportRecipe`: "Report this recipe" on a result the server wrote, with no recipe text. A
    /// limited-use App Check token; the same `reportId` again is the same report. Only to a server
    /// that said `acceptsReports`, for an account that exists (never one made for it).
    func reportRecipe(_ request: ReportRecipeRequest) async throws
}

// MARK: Configuration

protocol RecipeConfigStore {
    func load() -> RecipeConfig
    func save(_ config: RecipeConfig)
    /// The tab's settings that aren't part of a request's configuration.
    func loadOptions() -> RecipeOptions
    func saveOptions(_ options: RecipeOptions)
    /// The taste profile as saved (never rewritten for a changed definition); empty when none was.
    func loadTaste() -> TasteProfile
    /// Saving one with no answers removes it ("Clear taste profile").
    func saveTaste(_ taste: TasteProfile)
    /// The quiz steps the person has been through, kept apart from the answers (so with no profile
    /// saved, and through clearing it).
    func loadTasteSeen() -> Set<String>
    func saveTasteSeen(_ seen: Set<String>)
    /// The last good quiz definition received in `language`, if any.
    func loadTasteQuiz(language: String) -> TasteQuiz?
    func saveTasteQuiz(_ quiz: TasteQuiz, language: String)
}

/// Recipe settings kept beside `RecipeConfig` but never sent as part of it. Read field by field
/// with defaults, so options saved by an older version (or none) still load. Android:
/// `RecipeOptions` in `RecipeServices.kt`.
struct RecipeOptions: Codable, Equatable, Sendable {
    /// "Send all products": the rest of the pantry goes with every route as items the recipe may
    /// use as well, while the ticked items stay the ones to use up. Off until turned on.
    var sendAllItems = false
    /// The first-open introduction to the tab was shown and closed.
    var introSeen = false
    /// Saved recipes and menus not seen yet (`RecipeUnread`), by saved id: the header's badge.
    /// JSON key `unreadSaved`, a list of ids. Android: `RecipeOptions.unreadSaved`.
    var unreadSaved: Set<String> = []
    /// The Pictures row's choice for a recipe from a pack: no pictures until the person picks a
    /// level, then remembered (what is sent is `RecipePictures.choice`'s, which the pack may hold
    /// lower). JSON key `pictureLevel`. Android: `RecipeOptions.pictureLevel`.
    var pictureLevel: RecipePictureLevel = .none
    /// Results reported from this device, by request id, oldest first and at most
    /// `RecipeReporting.remembered`: their foot reads "You've reported this" across launches.
    /// Android: `RecipeOptions.reportedRequests`.
    var reportedRequests: [String] = []

    init(sendAllItems: Bool = false, introSeen: Bool = false, unreadSaved: Set<String> = [], pictureLevel: RecipePictureLevel = .none, reportedRequests: [String] = []) {
        self.sendAllItems = sendAllItems
        self.introSeen = introSeen
        self.unreadSaved = unreadSaved
        self.pictureLevel = pictureLevel
        self.reportedRequests = reportedRequests
    }

    private enum CodingKeys: String, CodingKey { case sendAllItems, introSeen, unreadSaved, pictureLevel, reportedRequests }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(sendAllItems, forKey: .sendAllItems)
        try container.encode(introSeen, forKey: .introSeen)
        try container.encode(unreadSaved.sorted(), forKey: .unreadSaved)
        try container.encode(pictureLevel, forKey: .pictureLevel)
        try container.encode(reportedRequests, forKey: .reportedRequests)
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        sendAllItems = (try? container.decodeIfPresent(Bool.self, forKey: .sendAllItems)) ?? false
        introSeen = (try? container.decodeIfPresent(Bool.self, forKey: .introSeen)) ?? false
        unreadSaved = Set((try? container.decodeIfPresent([String].self, forKey: .unreadSaved)) ?? nil ?? [])
        // A level a later version adds reads as none.
        pictureLevel = (try? container.decodeIfPresent(RecipePictureLevel.self, forKey: .pictureLevel)) ?? nil ?? RecipePictureLevel.none
        reportedRequests = Array(((try? container.decodeIfPresent([String].self, forKey: .reportedRequests)) ?? nil ?? []).suffix(RecipeReporting.remembered))
    }
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

    /// Under a key of its own beside the configuration, likewise outside backups.
    static let optionsKey = "com.foodtracker.recipe_options"

    func loadOptions() -> RecipeOptions {
        guard let data = defaults.data(forKey: Self.optionsKey),
              let options = try? JSONDecoder().decode(RecipeOptions.self, from: data) else {
            return RecipeOptions()
        }
        return options
    }

    func saveOptions(_ options: RecipeOptions) {
        if let data = try? JSONEncoder().encode(options) {
            defaults.set(data, forKey: Self.optionsKey)
        }
    }

    /// The taste profile, under a key of its own beside the options: on this phone only, never in
    /// `RecipeConfig`, backups or `WireSettings`. Android: `AndroidRecipeConfigRepository`'s taste key.
    static let tasteKey = "com.foodtracker.recipe_taste"
    /// The quiz definitions received, by language: `[language: TasteQuiz]`, likewise outside backups.
    static let tasteQuizKey = "com.foodtracker.recipe_taste_quiz"

    func loadTaste() -> TasteProfile {
        guard let data = defaults.data(forKey: Self.tasteKey),
              let taste = try? JSONDecoder().decode(TasteProfile.self, from: data) else {
            return TasteProfile()
        }
        return taste
    }

    func saveTaste(_ taste: TasteProfile) {
        if taste.isEmpty {
            defaults.removeObject(forKey: Self.tasteKey)
        } else if let data = try? JSONEncoder().encode(taste) {
            defaults.set(data, forKey: Self.tasteKey)
        }
    }

    /// The steps been through, under their own key. Android: `recipe_taste_seen`.
    static let tasteSeenKey = "com.foodtracker.recipe_taste_seen"

    func loadTasteSeen() -> Set<String> {
        Set(defaults.stringArray(forKey: Self.tasteSeenKey) ?? [])
    }

    func saveTasteSeen(_ seen: Set<String>) {
        defaults.set(seen.sorted(), forKey: Self.tasteSeenKey)
    }

    private func tasteQuizzes() -> [String: TasteQuiz] {
        guard let data = defaults.data(forKey: Self.tasteQuizKey),
              let quizzes = try? JSONDecoder().decode([String: TasteQuiz].self, from: data) else { return [:] }
        return quizzes
    }

    func loadTasteQuiz(language: String) -> TasteQuiz? {
        tasteQuizzes()[language].flatMap { $0.isUsable ? $0 : nil }
    }

    func saveTasteQuiz(_ quiz: TasteQuiz, language: String) {
        guard quiz.isUsable else { return }
        var quizzes = tasteQuizzes()
        quizzes[language] = quiz
        if let data = try? JSONEncoder().encode(quizzes) {
            defaults.set(data, forKey: Self.tasteQuizKey)
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
    /// Saved recipes' multi-select Delete: all of `ids` with one save.
    func delete(ids: Set<String>)
    /// Undo of that Delete: `entries` back as they were, ids intact, with one save.
    func save(_ entries: [SavedRecipe])
    /// The pictures kept with `id`, read now (for Undo, before a delete). Nil without any.
    func pictures(id: String) -> SavedRecipePictures?
    /// The same, read off the main actor (opening a saved recipe).
    func loadPictures(id: String) async -> SavedRecipePictures?
    /// Marks `id` a favourite or not, with one save; nothing for an id not saved.
    func setFavorite(id: String, _ isFavorite: Bool)
}

/// Saved recipes in the shared SwiftData store, as `SavedRecipeRecord` rows.
@MainActor
final class SwiftDataSavedRecipeStore: SavedRecipeStore {
    private let context: ModelContext
    private let container: ModelContainer

    init(container: ModelContainer = NativePantryViewModel.sharedModelContainer) {
        self.container = container
        context = ModelContext(container)
    }

    func all() -> [SavedRecipe] {
        let descriptor = FetchDescriptor<SavedRecipeRecord>(sortBy: [SortDescriptor(\.createdAt, order: .reverse)])
        return ((try? context.fetch(descriptor)) ?? []).compactMap(Self.entry(from:))
    }

    func save(_ entry: SavedRecipe) {
        save([entry])
    }

    func save(_ entries: [SavedRecipe]) {
        for entry in entries {
            guard let record = Self.record(from: entry) else { continue }
            let id = entry.id
            let existing = (try? context.fetch(FetchDescriptor<SavedRecipeRecord>(predicate: #Predicate { $0.id == id }))) ?? []
            // An entry saved without its pictures in hand keeps the ones already stored for it.
            if entry.pictures == nil, let old = existing.first {
                record.dishImage = old.dishImage
                record.stepImages = old.stepImages
                record.thumbnail = record.thumbnail ?? old.thumbnail
            }
            // Saved again (not through `setFavorite`): a favourite stays one.
            if let old = existing.first, old.isFavorite { record.isFavorite = true }
            existing.forEach(context.delete)
            context.insert(record)
        }
        try? context.save()
    }

    func delete(id: String) {
        try? context.delete(model: SavedRecipeRecord.self, where: #Predicate { $0.id == id })
        try? context.save()
    }

    func setFavorite(id: String, _ isFavorite: Bool) {
        let records = (try? context.fetch(FetchDescriptor<SavedRecipeRecord>(predicate: #Predicate { $0.id == id }))) ?? []
        guard !records.isEmpty else { return }
        records.forEach { $0.isFavorite = isFavorite }
        try? context.save()
    }

    func delete(ids: Set<String>) {
        guard !ids.isEmpty else { return }
        let list = Array(ids)
        try? context.delete(model: SavedRecipeRecord.self, where: #Predicate { list.contains($0.id) })
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
        return SavedRecipe(id: record.id, createdAt: record.createdAt, content: content, thumbnail: record.thumbnail, isFavorite: record.isFavorite)
    }

    func pictures(id: String) -> SavedRecipePictures? {
        let records = (try? context.fetch(FetchDescriptor<SavedRecipeRecord>(predicate: #Predicate { $0.id == id }))) ?? []
        return records.first.flatMap(Self.pictures(of:))
    }

    func loadPictures(id: String) async -> SavedRecipePictures? {
        let container = container
        return await Task.detached(priority: .userInitiated) {
            let context = ModelContext(container)
            let records = (try? context.fetch(FetchDescriptor<SavedRecipeRecord>(predicate: #Predicate { $0.id == id }))) ?? []
            return records.first.flatMap(Self.pictures(of:))
        }.value
    }

    nonisolated static func pictures(of record: SavedRecipeRecord) -> SavedRecipePictures? {
        let steps = record.stepImages.flatMap { try? PropertyListDecoder().decode([String: Data].self, from: $0) } ?? [:]
        let pictures = SavedRecipePictures(
            dish: record.dishImage,
            steps: Dictionary(uniqueKeysWithValues: steps.compactMap { key, value in Int(key).map { ($0, value) } })
        )
        return pictures.isEmpty ? nil : pictures
    }

    nonisolated static func encodedSteps(_ steps: [Int: Data]) -> Data? {
        guard !steps.isEmpty else { return nil }
        let encoder = PropertyListEncoder()
        encoder.outputFormat = .binary
        return try? encoder.encode(Dictionary(uniqueKeysWithValues: steps.map { (String($0.key), $0.value) }))
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
        let record = SavedRecipeRecord(
            id: entry.id,
            kind: entry.kind.rawValue,
            payload: payload,
            title: title,
            createdAt: entry.createdAt,
            source: entry.source.rawValue
        )
        record.thumbnail = entry.thumbnail
        record.isFavorite = entry.isFavorite
        if let pictures = entry.pictures {
            record.dishImage = pictures.dish
            record.stepImages = encodedSteps(pictures.steps)
        }
        return record
    }
}
