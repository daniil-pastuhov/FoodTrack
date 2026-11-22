import Foundation

// The taste profile: a short quiz about the food the person likes, sent only with requests to our
// server (recipes and menus from a pack) and only to a server that said `acceptsTaste`. Never in
// the shared prompt, never to the phone's own model: `RecipeRequest`, which those take, has no
// field for it. The quiz itself (steps, options, caps, texts) comes from the server
// (`getTasteQuiz`, functions/CONTRACT.md "Taste profile"); the app holds no list of steps or
// options and renders whatever definition it is sent. Kept on this phone only
// (`RecipeConfigStore.loadTaste`, outside `RecipeConfig`, backups and `WireSettings`), with the
// definition cached per language beside it. Android: `shared/.../recipe/TasteProfile.kt`.

// MARK: The definition

/// The quiz as the server defines it, in one language. Decoded leniently: a step whose `kind` or
/// `tone` isn't known, that can't be read, or that has no option is left out, and unknown fields are
/// ignored, so a newer server can't break an older app.
struct TasteQuiz: Codable, Equatable, Sendable {
    /// Opaque; changes whenever the definition does.
    var version: String
    var language: String
    var steps: [Step]
    /// Pairs that can't both be chosen; choosing one moves it off the other.
    var conflicts: [Conflict]
    /// The steps, in order, whose labels the settings row's summary names.
    var summary: [String]

    struct Step: Codable, Equatable, Sendable, Identifiable {
        enum Kind: String, Codable, Sendable { case multi, single }
        enum Tone: String, Codable, Sendable { case like, avoid }

        var id: String
        var kind: Kind
        var tone: Tone
        /// A Lucide icon name; the app falls back to a neutral one for a name it doesn't bundle.
        var icon: String?
        var question: String
        /// A line under the question, such as "not an allergy setting".
        var note: String?
        /// How many may be chosen: 1 for a `single` step, at least 1 otherwise.
        var max: Int
        var groups: [Group]

        /// Every option, in display order.
        var options: [Option] { groups.flatMap(\.options) }
        func option(_ id: String) -> Option? { options.first { $0.id == id } }

        init(id: String, kind: Kind, tone: Tone = .like, icon: String? = nil, question: String, note: String? = nil, max: Int, groups: [Group]) {
            self.id = id
            self.kind = kind
            self.tone = tone
            self.icon = icon
            self.question = question
            self.note = note
            self.max = kind == .single ? 1 : Swift.max(1, max)
            self.groups = groups
        }

        private enum CodingKeys: String, CodingKey { case id, kind, tone, icon, question, note, max, groups }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            let id = try c.decode(String.self, forKey: .id)
            guard !id.isEmpty else { throw DecodingError.dataCorruptedError(forKey: .id, in: c, debugDescription: "empty id") }
            let kind = try c.decode(Kind.self, forKey: .kind)
            // A tone this version doesn't know skips the step (CONTRACT.md `getTasteQuiz`); none is `like`.
            let tone = try c.decodeIfPresent(Tone.self, forKey: .tone) ?? .like
            // Options without an id, and repeats, are left out; then empty groups.
            var seen = Set<String>()
            let groups = ((try? c.decode([Lenient<Group>].self, forKey: .groups)) ?? [])
                .compactMap(\.value)
                .map { group in Group(title: group.title, options: group.options.filter { seen.insert($0.id).inserted }) }
                .filter { !$0.options.isEmpty }
            let max = (try? c.decodeIfPresent(Int.self, forKey: .max)) ?? nil
            self.init(
                id: id, kind: kind, tone: tone,
                icon: (try? c.decodeIfPresent(String.self, forKey: .icon)) ?? nil,
                question: (try? c.decodeIfPresent(String.self, forKey: .question)) ?? nil ?? "",
                note: ((try? c.decodeIfPresent(String.self, forKey: .note)) ?? nil).flatMap { $0.isEmpty ? nil : $0 },
                // Never more than there are options.
                max: Swift.min(max ?? Int.max, groups.reduce(0) { $0 + $1.options.count }),
                groups: groups
            )
        }
    }

    struct Group: Codable, Equatable, Sendable {
        var title: String?
        var options: [Option]

        init(title: String? = nil, options: [Option]) {
            self.title = title
            self.options = options
        }

        private enum CodingKeys: String, CodingKey { case title, options }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            title = ((try? c.decodeIfPresent(String.self, forKey: .title)) ?? nil).flatMap { $0.isEmpty ? nil : $0 }
            options = ((try? c.decode([Lenient<Option>].self, forKey: .options)) ?? []).compactMap(\.value)
        }
    }

    struct Option: Codable, Equatable, Sendable, Identifiable {
        var id: String
        var label: String
        /// A second line, on a `single` step's card.
        var sub: String?
        /// 1 to 3, drawn as that many flames on a `single` step's card.
        var level: Int?

        init(id: String, label: String, sub: String? = nil, level: Int? = nil) {
            self.id = id
            self.label = label
            self.sub = sub
            self.level = level
        }

        private enum CodingKeys: String, CodingKey { case id, label, sub, level }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            id = try c.decode(String.self, forKey: .id)
            guard !id.isEmpty else { throw DecodingError.dataCorruptedError(forKey: .id, in: c, debugDescription: "empty id") }
            label = (try? c.decodeIfPresent(String.self, forKey: .label)) ?? nil ?? id
            sub = ((try? c.decodeIfPresent(String.self, forKey: .sub)) ?? nil).flatMap { $0.isEmpty ? nil : $0 }
            level = ((try? c.decodeIfPresent(Int.self, forKey: .level)) ?? nil).map { min(Swift.max($0, 1), 3) }
        }
    }

    /// One option of one step: `"<step>:<option>"` on the wire.
    struct Choice: Hashable, Codable, Sendable {
        var step: String
        var option: String

        init(step: String, option: String) {
            self.step = step
            self.option = option
        }

        init?(wire: String) {
            guard let colon = wire.firstIndex(of: ":") else { return nil }
            let step = String(wire[..<colon]), option = String(wire[wire.index(after: colon)...])
            guard !step.isEmpty, !option.isEmpty else { return nil }
            self.init(step: step, option: option)
        }

        var wire: String { "\(step):\(option)" }
    }

    struct Conflict: Equatable, Sendable {
        var first: Choice
        var second: Choice
    }

    init(version: String, language: String, steps: [Step], conflicts: [Conflict] = [], summary: [String] = []) {
        self.version = version
        self.language = language
        self.steps = steps
        self.conflicts = conflicts
        self.summary = summary
    }

    private enum CodingKeys: String, CodingKey { case version, language, steps, conflicts, summary }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        version = (try? c.decodeIfPresent(String.self, forKey: .version)) ?? nil ?? ""
        language = (try? c.decodeIfPresent(String.self, forKey: .language)) ?? nil ?? ""
        var seen = Set<String>()
        steps = try c.decode([Lenient<Step>].self, forKey: .steps)
            .compactMap(\.value)
            .filter { !$0.options.isEmpty && seen.insert($0.id).inserted }
        conflicts = ((try? c.decodeIfPresent([[Lenient<String>]].self, forKey: .conflicts)) ?? nil ?? []).compactMap { pair in
            let choices = pair.compactMap { $0.value.flatMap(Choice.init(wire:)) }
            guard pair.count == 2, choices.count == 2, choices[0] != choices[1] else { return nil }
            return Conflict(first: choices[0], second: choices[1])
        }
        summary = ((try? c.decodeIfPresent([Lenient<String>].self, forKey: .summary)) ?? nil ?? []).compactMap(\.value)
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(version, forKey: .version)
        try c.encode(language, forKey: .language)
        try c.encode(steps, forKey: .steps)
        try c.encode(conflicts.map { [$0.first.wire, $0.second.wire] }, forKey: .conflicts)
        try c.encode(summary, forKey: .summary)
    }

    func step(_ id: String) -> Step? { steps.first { $0.id == id } }

    /// A definition with no step the app can show is treated as one that couldn't be loaded:
    /// the last good cached one stays in use.
    var isUsable: Bool { !steps.isEmpty }

    /// Decodes a `getTasteQuiz` answer, or nil when it can't be read at all or has no usable step.
    static func decoded(from data: Data) -> TasteQuiz? {
        guard let quiz = try? JSONDecoder().decode(TasteQuiz.self, from: data), quiz.isUsable else { return nil }
        return quiz
    }

    /// Which definition the quiz uses after a fetch: the fetched one when usable, else the last
    /// good cached one (nil: the "couldn't load" state).
    static func afterFetch(_ fetched: TasteQuiz?, cached: TasteQuiz?) -> TasteQuiz? {
        if let fetched, fetched.isUsable { return fetched }
        return cached
    }

    /// The choices that can't stand beside `choice`.
    func conflicting(_ choice: Choice) -> [Choice] {
        conflicts.compactMap { conflict in
            if conflict.first == choice { return conflict.second }
            if conflict.second == choice { return conflict.first }
            return nil
        }
    }
}

/// Reads one element of a list, or nothing when it can't be read, so the rest of the list stays.
private struct Lenient<Value: Decodable>: Decodable {
    let value: Value?
    init(from decoder: Decoder) throws { value = try? Value(from: decoder) }
}

/// A list of ids, one bare id read as a list of one, anything else as none.
private struct LenientIDs: Decodable {
    let ids: [String]
    init(from decoder: Decoder) throws {
        if let one = try? decoder.singleValueContainer().decode(String.self) {
            ids = [one]
        } else {
            ids = ((try? [Lenient<String>](from: decoder)) ?? []).compactMap(\.value)
        }
    }
}

// MARK: The answers

/// The person's answers: step id to chosen option ids, and the definition's `version` they were
/// last saved against. Ids only, never text. What is stored is never rewritten because the
/// definition changed: what is shown and sent is the stored answers filtered through the current
/// definition (`filtered(by:)`), so an option hidden for a while and back is ticked again; only
/// the person's next Save stores anew. The steps the person has been through are kept apart, under
/// their own key (`TasteProfile.newSteps(seen:quiz:)` and the rules beside it). Android: `TasteProfile`.
struct TasteProfile: Codable, Equatable, Sendable {
    var answers: [String: [String]]
    var version: String?

    init(answers: [String: [String]] = [:], version: String? = nil) {
        self.answers = answers.filter { !$0.value.isEmpty }
        self.version = version
    }

    private enum CodingKeys: String, CodingKey { case answers, version }

    /// Field by field, anything unreadable as no answer, so a stored profile never fails.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let raw = (try? c.decodeIfPresent([String: LenientIDs].self, forKey: .answers)) ?? nil ?? [:]
        self.init(
            answers: raw.mapValues(\.ids),
            version: (try? c.decodeIfPresent(String.self, forKey: .version)) ?? nil
        )
    }

    /// No answer anywhere: nothing is sent and the row says what it is for.
    var isEmpty: Bool { answers.values.allSatisfy(\.isEmpty) }

    /// What is chosen in `step`.
    func selected(_ step: String) -> [String] { answers[step] ?? [] }

    func isSelected(_ choice: TasteQuiz.Choice) -> Bool { selected(choice.step).contains(choice.option) }

    /// How many answers in all.
    var count: Int { answers.values.reduce(0) { $0 + $1.count } }

    private mutating func set(_ step: String, _ options: [String]) {
        answers[step] = options.isEmpty ? nil : options
    }

    // MARK: Rules on a definition

    /// Whether `option` of `step` can be tapped on: it is chosen (a tap takes it off), the step is
    /// `single` (a tap replaces its one), or the step is under its `max`. A chip that can't is
    /// shown dimmed, and a tap on it changes nothing.
    func canToggle(_ step: TasteQuiz.Step, _ option: String) -> Bool {
        guard step.option(option) != nil else { return false }
        let chosen = selected(step.id)
        return step.kind == .single || chosen.contains(option) || chosen.count < step.max
    }

    /// Where `option` of `step` is already chosen on the other side of a conflict: the chip's small
    /// mark, and what a tap on it takes off. Nil when it isn't. Android: `oppositeOf`.
    func conflictChosen(_ step: TasteQuiz.Step, _ option: String, in quiz: TasteQuiz) -> TasteQuiz.Choice? {
        quiz.conflicting(TasteQuiz.Choice(step: step.id, option: option)).first(where: isSelected)
    }

    /// The answers after a tap on `option` of `step`: a chosen one goes; a `single` step's one is
    /// replaced; otherwise it is added in display order, up to the step's `max` (a tap past it
    /// changes nothing), and every choice it conflicts with is taken off.
    func toggled(_ step: TasteQuiz.Step, _ option: String, in quiz: TasteQuiz) -> TasteProfile {
        guard canToggle(step, option) else { return self }
        var next = self
        var chosen = selected(step.id)
        if let index = chosen.firstIndex(of: option) {
            chosen.remove(at: index)
        } else {
            chosen = step.kind == .single ? [option] : chosen + [option]
            for other in quiz.conflicting(TasteQuiz.Choice(step: step.id, option: option)) {
                next.set(other.step, next.selected(other.step).filter { $0 != other.option })
            }
        }
        next.set(step.id, step.options.map(\.id).filter(chosen.contains))
        return next
    }

    /// The answers as `quiz` reads them: what is shown and sent, never stored for that reason
    /// alone. In the server's order (`cleanTaste` in functions/src/taste/quiz.ts; Android
    /// `normalized()`): steps and options it doesn't have are left out (removed, renamed, hidden
    /// for now), repeats go, display order; the `avoid` steps are cut to their `max`; each conflict
    /// with both sides chosen is settled (a `like` side loses to an `avoid` side; between two of
    /// the same tone the second listed loses); only then are the other steps cut to their `max`
    /// (a step now `single` keeps its first). `version` is kept as it is.
    func filtered(by quiz: TasteQuiz) -> TasteProfile {
        var next = TasteProfile(version: version)
        for step in quiz.steps {
            let chosen = Set(selected(step.id))
            next.set(step.id, step.options.map(\.id).filter(chosen.contains))
        }
        for step in quiz.steps where step.tone == .avoid {
            next.set(step.id, Array(next.selected(step.id).prefix(step.max)))
        }
        for conflict in quiz.conflicts where next.isSelected(conflict.first) && next.isSelected(conflict.second) {
            let firstLikes = quiz.step(conflict.first.step)?.tone == .like
            let secondLikes = quiz.step(conflict.second.step)?.tone == .like
            let drop = !secondLikes && firstLikes ? conflict.first : conflict.second
            next.set(drop.step, next.selected(drop.step).filter { $0 != drop.option })
        }
        for step in quiz.steps where step.tone != .avoid {
            next.set(step.id, Array(next.selected(step.id).prefix(step.max)))
        }
        return next
    }

    /// What the quiz's Save stores: the answers as edited (filtered, as the quiz shows them),
    /// stamped with the definition's version. Android: `savedTaste(edited, quiz)`.
    func saved(from quiz: TasteQuiz) -> TasteProfile {
        TasteProfile(answers: filtered(by: quiz).answers, version: quiz.version)
    }

    /// Undo of a tap that moved a choice off the other side of a conflict: only the inverse of
    /// that move, on the answers as they are now (later choices stay): `option` comes off `step`
    /// and `moved` goes back on its own step, unless that step is full by now. Android: `undoneMove`.
    func undoneMove(quiz: TasteQuiz, step: String, option: String, movedFrom moved: TasteQuiz.Choice) -> TasteProfile {
        let chosen = TasteQuiz.Choice(step: step, option: option)
        var next = self
        next.set(chosen.step, next.selected(chosen.step).filter { $0 != chosen.option })
        if let step = quiz.step(moved.step), !next.isSelected(moved) {
            let current = next.selected(step.id)
            if step.kind == .single || current.count < step.max {
                let wanted = Set(step.kind == .single ? [moved.option] : current + [moved.option])
                next.set(step.id, step.options.map(\.id).filter(wanted.contains))
            }
        }
        return next
    }

    // Wire shape limits (functions/CONTRACT.md "Taste profile (request)").
    static let maxWireSteps = 16
    static let maxWireOptions = 32
    static let maxWireID = 40

    /// The stored answers within the wire's shape, for a request sent without a definition to
    /// filter them through: ids blank or longer than 40 characters left out, repeats gone, at most
    /// 32 per step and 16 steps (in key order). Android: `shapeCleaned()`.
    func shapeCleaned() -> TasteProfile {
        let valid: (String) -> Bool = { !$0.trimmingCharacters(in: .whitespaces).isEmpty && $0.count <= Self.maxWireID }
        var kept: [String: [String]] = [:]
        for step in answers.keys.sorted() where valid(step) {
            var seen = Set<String>()
            let options = Array(selected(step).filter { valid($0) && seen.insert($0).inserted }.prefix(Self.maxWireOptions))
            if !options.isEmpty { kept[step] = options }
            if kept.count == Self.maxWireSteps { break }
        }
        return TasteProfile(answers: kept, version: version)
    }

    // MARK: Steps been through (`seen`, kept apart from the answers)

    /// Steps of `quiz` not among `seen`, in order: where the quiz opens, the row's "· N new
    /// questions" (with a profile) and the dot (`hasNewQuestions`). For someone who never opened
    /// the quiz, every step. Android: `tasteNewSteps(seen, quiz)`.
    static func newSteps(seen: Set<String>, quiz: TasteQuiz) -> [TasteQuiz.Step] {
        quiz.steps.filter { !seen.contains($0.id) }
    }

    /// The dot on Recipe settings: only when the row opens the quiz and the cached definition has
    /// a step not been through. Never for the promo case or where the row is hidden. Android:
    /// `tasteHasNewQuestions(entry, seen, quiz)`.
    static func hasNewQuestions(entry: Entry, seen: Set<String>, quiz: TasteQuiz?) -> Bool {
        guard entry == .quiz, let quiz else { return false }
        return !newSteps(seen: seen, quiz: quiz).isEmpty
    }

    /// Where the quiz opens: the first new step, else the first. Android: `firstTasteStep(seen, quiz)`.
    static func firstStep(seen: Set<String>, quiz: TasteQuiz) -> Int {
        newSteps(seen: seen, quiz: quiz).first.flatMap { new in quiz.steps.firstIndex { $0.id == new.id } } ?? 0
    }

    /// Leaving the quiz, saved or not: the steps shown count as been through. Android:
    /// `tasteSeenAfter(seen, visited)`.
    static func seenAfter(_ seen: Set<String>, visited: Set<String>) -> Set<String> {
        seen.union(visited)
    }

    /// "New question" on a step in the quiz: only for someone who has been through the quiz
    /// before (on a first opening no step is tagged).
    static func isNewStep(_ step: TasteQuiz.Step, seen: Set<String>) -> Bool {
        !seen.isEmpty && !seen.contains(step.id)
    }

    /// Whether a balance reply's `tasteQuizVersion` calls for a quiet background fetch of the
    /// definition: the server named one (a missing value from an old server is unknown, not
    /// changed), it isn't the cached one for this language (compared for equality only; nothing
    /// cached counts as different), the row opens the quiz, a server account exists, and this
    /// version and language weren't fetched already. Never for the promo case; never creates an
    /// account. Android: `shouldPrefetchTasteQuiz`.
    static func shouldPrefetch(serverVersion: String?, cachedVersion: String?, entry: Entry, hasAccount: Bool, alreadyFetched: Bool) -> Bool {
        guard let serverVersion, !serverVersion.isEmpty else { return false }
        return serverVersion != cachedVersion && entry == .quiz && hasAccount && !alreadyFetched
    }

    /// `taste` as a request carries it: nil without the capability or without answers. Against
    /// the cached definition when there is one (what it no longer has is left out); otherwise the
    /// answers as saved within the wire's shape (`shapeCleaned`), which the server cleans the same way.
    static func toSend(_ profile: TasteProfile, quiz: TasteQuiz?, serverTakesTaste: Bool) -> [String: [String]]? {
        guard serverTakesTaste else { return nil }
        let cleaned = quiz.map { profile.filtered(by: $0) } ?? profile.shapeCleaned()
        return cleaned.isEmpty ? nil : cleaned.answers
    }

    /// Whether a refusal of `request` as invalid means the server was rolled back to one from
    /// before taste profiles: it carried `taste`. Then `serverTakesTaste` is cleared until a reply
    /// with a balance says `acceptsTaste` again.
    static func clearsFlag(after error: RecipeServerError, request: GenerateRequest) -> Bool {
        error == .invalidRequest && request.taste != nil
    }

    // MARK: Summary

    /// The settings row's supporting line, in parts the view joins with " · " (two lines at most):
    /// up to `summaryNamed` labels from the first of `quiz.summary`'s steps that has an answer, the
    /// chosen label of the first `single` step, and how many more besides (shown only above 0).
    /// "Italian, Nordic · Medium · 5 more". Nil for no answers: the row says what it is for.
    /// Without a definition in the app's language the row says "N answers" (`count`). When every
    /// stored answer has dropped out of the current definition it is nil too: the row reads as
    /// without a profile, and nothing says why.
    struct Summary: Equatable, Sendable {
        var named: [String]
        var single: String?
        var more: Int
    }

    static let summaryNamed = 2

    func summary(_ quiz: TasteQuiz) -> Summary? {
        let clean = filtered(by: quiz)
        guard !clean.isEmpty else { return nil }
        let namedStep = quiz.summary.compactMap(quiz.step).first { !clean.selected($0.id).isEmpty }
        let named = namedStep.map { step in clean.selected(step.id).prefix(Self.summaryNamed).compactMap { step.option($0)?.label } } ?? []
        // Never the step the labels were named from, or its label would show twice.
        let singleStep = quiz.steps.first { $0.kind == .single && $0.id != namedStep?.id }
        let single = singleStep.flatMap { step in clean.selected(step.id).first.flatMap { step.option($0)?.label } }
        return Summary(named: named, single: single, more: clean.count - named.count - (single == nil ? 0 : 1))
    }

    // MARK: Entry

    /// What the settings row does. `hidden`: no row (no paid route here, a server that doesn't
    /// take taste profiles, or buying blocked with nothing to use the profile on). `quiz`: recipes
    /// in a pack or a saved profile. `promo`: the sheet that says what it does. Neither the row
    /// nor the promo calls anything; only opening the quiz fetches the definition.
    enum Entry: Equatable, Sendable {
        case hidden, quiz, promo
    }

    static func entry(paidRouteAvailable: Bool, serverTakesTaste: Bool, purchasesBlocked: Bool, balance: Int?, hasSavedProfile: Bool) -> Entry {
        guard paidRouteAvailable, serverTakesTaste else { return .hidden }
        if (balance ?? 0) > 0 || hasSavedProfile { return .quiz }
        // The promo's "See spark packs" would lead nowhere.
        return purchasesBlocked ? .hidden : .promo
    }
}
