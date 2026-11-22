package com.foodtracker.recipe

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/*
 * The taste profile: a short quiz about the food the person likes, sent only with requests to our
 * server (recipes and menus from a pack) and only to a server that said `acceptsTaste`
 * (functions/CONTRACT.md "Taste profile"). Never in the shared prompt, never to the phone's own
 * model.
 *
 * The quiz itself (its steps, options, caps, conflicts and every text) comes from the server
 * (`getTasteQuiz`, read with [tasteQuizOf]); the app holds no list of steps or options. What the
 * person chose is kept on this phone under its own key ([RecipeConfigRepository.getTaste]), never
 * inside [RecipeConfig] and never in backups, as a map from step id to option ids.
 *
 * iOS: `TasteProfile` / `TasteQuiz` in `Recipe/TasteProfile.swift`, the same rules.
 */

// --- The definition (server → app) -------------------------------------------------------------

enum class TasteStepKind {
    /** Chips, up to [TasteStep.max]. */
    MULTI,

    /** Cards, one choice; tapping the chosen one clears it. */
    SINGLE
}

enum class TasteTone {
    LIKE,

    /** "Rather skip": a chosen chip carries a ban sign, and wins a conflict ([TasteQuiz.conflicts]). */
    AVOID
}

data class TasteOption(
    val id: String,
    val label: String,
    /** A second line, on a [TasteStepKind.SINGLE] card. */
    val sub: String? = null,
    /** 1 to 3, drawn as that many flames on a [TasteStepKind.SINGLE] card. */
    val level: Int? = null
)

data class TasteGroup(val title: String?, val options: List<TasteOption>)

data class TasteStep(
    val id: String,
    val kind: TasteStepKind,
    val tone: TasteTone,
    /** A Lucide icon name; the app draws a neutral one for a name it doesn't bundle. */
    val icon: String?,
    val question: String,
    /** An optional line under the question, such as "not an allergy setting". */
    val note: String?,
    /** How many can be chosen: always 1 for [TasteStepKind.SINGLE]. */
    val max: Int,
    val groups: List<TasteGroup>
) {
    val options: List<TasteOption> get() = groups.flatMap { it.options }

    fun option(id: String): TasteOption? = options.firstOrNull { it.id == id }

    /** Chips are grouped under headings only when the server made more than one group, or gave
     *  the one group a title. */
    val grouped: Boolean get() = groups.size > 1 || groups.any { it.title != null }
}

/** One option of one step, as `"<step>:<option>"` on the wire. */
data class TasteChoice(val step: String, val option: String)

/**
 * The quiz as the server defines it, in one language. [version] changes whenever the definition
 * does. [conflicts] are pairs that can't both be chosen; [summary] the steps whose labels name the
 * settings row's summary ([tasteSummary]).
 */
data class TasteQuiz(
    val version: String,
    val language: String,
    val steps: List<TasteStep>,
    val conflicts: List<Pair<TasteChoice, TasteChoice>> = emptyList(),
    val summary: List<String> = emptyList()
) {
    fun step(id: String): TasteStep? = steps.firstOrNull { it.id == id }
}

/** Limits that keep a definition from a broken or hostile server from drawing an absurd screen. */
private const val MAX_QUIZ_STEPS = 16
private const val MAX_STEP_OPTIONS = 64
private const val MAX_TEXT = 200

/**
 * Reads `getTasteQuiz`'s reply (or the cached copy of it) leniently: a step whose `kind` or `tone`
 * this version doesn't know (or that has none), that lacks an id or a question, repeats an earlier
 * step's id, or has no usable option is skipped; an option without an id or a label is skipped;
 * unknown fields are ignored. A `multi` step's `max` is cut to its option count
 * (and is its option count when missing or below 1). Null only when nothing usable is left: no
 * version or no step.
 */
fun tasteQuizOf(json: JsonElement?): TasteQuiz? {
    val root = json as? JsonObject ?: return null
    val version = root.string("version") ?: return null
    val language = root.string("language") ?: "en"
    val steps = mutableListOf<TasteStep>()
    for (element in (root["steps"] as? JsonArray).orEmpty()) {
        if (steps.size == MAX_QUIZ_STEPS) break
        val step = tasteStepOf(element) ?: continue
        if (steps.none { it.id == step.id }) steps += step
    }
    if (steps.isEmpty()) return null
    val conflicts = (root["conflicts"] as? JsonArray).orEmpty().mapNotNull { pair ->
        val both = (pair as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.let(::tasteChoiceOf) }
        if (both == null || both.size != 2 || both[0] == both[1]) null else both[0] to both[1]
    }
    val summary = (root["summary"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    return TasteQuiz(version, language, steps, conflicts, summary)
}

private fun tasteStepOf(element: JsonElement): TasteStep? {
    val json = element as? JsonObject ?: return null
    val id = json.string("id") ?: return null
    val kind = when (json.string("kind")) {
        "multi" -> TasteStepKind.MULTI
        "single" -> TasteStepKind.SINGLE
        else -> return null
    }
    val question = json.string("question") ?: return null
    var count = 0
    val groups = (json["groups"] as? JsonArray).orEmpty().mapNotNull { group ->
        val groupJson = group as? JsonObject ?: return@mapNotNull null
        val options = (groupJson["options"] as? JsonArray).orEmpty().mapNotNull { option ->
            val optionJson = option as? JsonObject ?: return@mapNotNull null
            val optionId = optionJson.string("id") ?: return@mapNotNull null
            val label = optionJson.string("label") ?: return@mapNotNull null
            val level = (optionJson["level"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it in 1..3 }
            TasteOption(optionId, label, optionJson.string("sub"), level)
        }.filter { option -> count < MAX_STEP_OPTIONS && count++ >= 0 }
        options.takeIf { it.isNotEmpty() }?.let { TasteGroup(groupJson.string("title"), it) }
    }
    // An option id once per step, in its first group.
    val seen = mutableSetOf<String>()
    val unique = groups.mapNotNull { group ->
        group.copy(options = group.options.filter { seen.add(it.id) }).takeIf { it.options.isNotEmpty() }
    }
    if (unique.isEmpty()) return null
    val optionCount = unique.sumOf { it.options.size }
    val max = when (kind) {
        TasteStepKind.SINGLE -> 1
        TasteStepKind.MULTI -> ((json["max"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: 0)
            .let { if (it < 1) optionCount else it.coerceAtMost(optionCount) }
    }
    // `tone` is required and only `like` or `avoid` (functions/CONTRACT.md `getTasteQuiz`): a step
    // without one this version understands is skipped, like an unknown `kind`.
    val tone = when (json.string("tone")) {
        "like" -> TasteTone.LIKE
        "avoid" -> TasteTone.AVOID
        else -> return null
    }
    return TasteStep(id, kind, tone, json.string("icon"), question, json.string("note"), max, unique)
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_TEXT)

private fun tasteChoiceOf(wire: String): TasteChoice? {
    val step = wire.substringBefore(':', "")
    val option = wire.substringAfter(':', "")
    return if (step.isEmpty() || option.isEmpty()) null else TasteChoice(step, option)
}

// --- The answers (kept on the phone, sent to the server) ---------------------------------------

/**
 * What the person chose: step id to option ids, and the [version] of the definition they were
 * answered against. The steps they have been through are kept apart from it
 * ([RecipeConfigRepository.getTasteSeen]), with or without a profile ([tasteNewSteps]).
 *
 * Kept exactly as saved: a change of definition never rewrites it. What is shown and sent is the
 * stored answers [normalized] against the current definition, so an option hidden for a while and
 * back is ticked again; only the person's next Save writes a new one ([savedTaste]).
 */
@Serializable
data class TasteProfile(
    val answers: Map<String, List<String>> = emptyMap(),
    val version: String? = null
) {
    /** Nothing chosen anywhere: not sent, and the settings row says what it is for. */
    val isEmpty: Boolean get() = answers.values.all { it.isEmpty() }

    /** How many answers are chosen in all. */
    val count: Int get() = answers.values.sumOf { it.size }

    fun selected(step: String): List<String> = answers[step].orEmpty()

    fun isSelected(step: String, option: String): Boolean = option in selected(step)

    /** With [step] set to [options] as given; an empty list removes the step. */
    fun with(step: String, options: List<String>): TasteProfile =
        copy(answers = if (options.isEmpty()) answers - step else answers + (step to options))

    /**
     * Only what [quiz] still has: its steps and options, in its order, once each, each step cut to
     * its `max`, and of two conflicting choices the one on an avoid step kept (the first of the pair
     * when neither or both are), in the server's order, so the app shows what the server will use.
     * Stamped with the definition's version.
     */
    fun normalized(quiz: TasteQuiz): TasteProfile {
        // In the server's order (functions/src/taste/quiz.ts): known options in the definition's
        // order, the avoid steps cut to their max, conflicts settled, then the other steps cut.
        var kept = quiz.steps.fold(TasteProfile(version = quiz.version)) { acc, step ->
            val wanted = selected(step.id).toSet()
            val known = step.options.map { it.id }.filter { it in wanted }
            acc.with(step.id, if (step.tone == TasteTone.AVOID) known.take(step.max) else known)
        }
        for ((a, b) in quiz.conflicts) {
            if (!kept.isSelected(a.step, a.option) || !kept.isSelected(b.step, b.option)) continue
            val loser = if (quiz.step(b.step)?.tone == TasteTone.AVOID && quiz.step(a.step)?.tone != TasteTone.AVOID) a else b
            kept = kept.with(loser.step, kept.selected(loser.step) - loser.option)
        }
        return quiz.steps.filter { it.tone != TasteTone.AVOID }.fold(kept) { acc, step -> acc.with(step.id, acc.selected(step.id).take(step.max)) }
    }

    /** The shape the server accepts (functions/CONTRACT.md), without a definition to check ids
     *  against: blank ids and repeats gone, empty steps gone, and the contract's bounds kept, so
     *  a profile can always be sent; the server drops what it doesn't know. */
    fun shapeCleaned(): TasteProfile {
        val cleaned = answers.entries
            .filter { (step, _) -> step.isNotBlank() && step.length <= MAX_WIRE_ID }
            .take(MAX_WIRE_STEPS)
            .associate { (step, options) -> step to options.filter { it.isNotBlank() && it.length <= MAX_WIRE_ID }.distinct().take(MAX_WIRE_OPTIONS) }
            .filterValues { it.isNotEmpty() }
        return copy(answers = cleaned)
    }
}

/**
 * The steps of [quiz] the person hasn't been through: not among [seen] (kept on the device apart
 * from the answers, so with or without a profile). Every step for someone who never opened the
 * quiz. Empty without a definition.
 */
fun tasteNewSteps(seen: Collection<String>, quiz: TasteQuiz?): List<TasteStep> =
    quiz?.steps.orEmpty().filter { it.id !in seen }

/**
 * The dot on the Recipe tab's settings button (and on the settings row without a profile): only
 * where the quiz opens ([TasteEntry.QUIZ]: recipes in a pack or a profile, the paid route, a server
 * with the quiz) and the definition has steps not yet been through. Never the promo case.
 */
fun tasteHasNewQuestions(entry: TasteEntry, seen: Collection<String>, quiz: TasteQuiz?): Boolean =
    entry == TasteEntry.QUIZ && tasteNewSteps(seen, quiz).isNotEmpty()

/** Where the quiz opens: the first step not yet been through, else the first (the first anyway
 *  for someone who has seen none). */
fun firstTasteStep(seen: Collection<String>, quiz: TasteQuiz): Int {
    if (seen.isEmpty()) return 0
    val first = tasteNewSteps(seen, quiz).firstOrNull() ?: return 0
    return quiz.steps.indexOf(first).coerceAtLeast(0)
}

/** The seen steps after leaving the quiz, saved or not: those shown this time ([visited]) added.
 *  A step never reached stays new. Clearing the profile leaves them as they are. */
fun tasteSeenAfter(seen: Collection<String>, visited: Collection<String>): List<String> = (seen + visited).distinct()

/**
 * What Save stores: the [edited] answers as filtered through [quiz] (what it no longer has is gone
 * from then on), stamped with its version. Empty when nothing is chosen: the caller then clears the
 * profile (or, for a new one, keeps none).
 */
fun savedTaste(edited: TasteProfile, quiz: TasteQuiz): TasteProfile {
    val answers = edited.normalized(quiz)
    return if (answers.isEmpty) TasteProfile() else answers
}

/**
 * Whether to fetch the definition quietly in the background after a reply said
 * `tasteQuizVersion`: only when it differs from the cached definition's version for the app's
 * language (or nothing is cached), the quiz is what the row opens ([TasteEntry.QUIZ], never the
 * promo), an account exists (none is ever created for it), and this version and language haven't
 * been fetched already. A failure isn't recorded, so a later reply tries again.
 */
fun shouldPrefetchTasteQuiz(serverVersion: String?, cachedVersion: String?, entry: TasteEntry, hasAccount: Boolean, alreadyFetched: Boolean): Boolean =
    serverVersion != null && serverVersion != cachedVersion && entry == TasteEntry.QUIZ && hasAccount && !alreadyFetched

/** The contract's bounds on `taste`: at most 16 steps, 32 options each, 40 characters an id. */
private const val MAX_WIRE_STEPS = 16
private const val MAX_WIRE_OPTIONS = 32
private const val MAX_WIRE_ID = 40

/** The other side of a conflict for [option] on [step], whether chosen or not. */
fun TasteQuiz.conflictsOf(step: String, option: String): List<TasteChoice> {
    val choice = TasteChoice(step, option)
    return conflicts.mapNotNull { (a, b) -> if (a == choice) b else if (b == choice) a else null }
}

/** Where [option] on [step] is already chosen on the other side of a conflict: its chip carries
 *  the small mark, and choosing it moves it ([toggled]). Null when it isn't. */
fun TasteProfile.conflictOf(quiz: TasteQuiz, step: String, option: String): TasteChoice? =
    quiz.conflictsOf(step, option).firstOrNull { isSelected(it.step, it.option) }

/** Whether [option] can be chosen on [step] now: already chosen (to untick it), a single step (a
 *  new choice replaces the old), or the step is under its `max`. A chip that can't is dimmed. */
fun TasteProfile.canPick(step: TasteStep, option: String): Boolean =
    step.option(option) != null && (isSelected(step.id, option) || step.kind == TasteStepKind.SINGLE || selected(step.id).size < step.max)

/**
 * A tap on [option] of [step]: unticks it if chosen; otherwise chooses it, unless the step is at
 * its `max` (then nothing changes, never a silent replacement). A single step's new choice
 * replaces the old. Choosing what is chosen on the other side of a conflict takes it off there
 * ([conflictOf]). An option the step doesn't have changes nothing.
 */
fun TasteProfile.toggled(quiz: TasteQuiz, step: TasteStep, option: String): TasteProfile {
    if (step.option(option) == null) return this
    val current = selected(step.id)
    if (option in current) return with(step.id, current - option)
    val chosen = when {
        step.kind == TasteStepKind.SINGLE -> with(step.id, listOf(option))
        current.size >= step.max -> return this
        else -> {
            val wanted = (current + option).toSet()
            with(step.id, step.options.map { it.id }.filter { it in wanted })
        }
    }
    return quiz.conflictsOf(step.id, option).fold(chosen) { acc, other ->
        if (acc.isSelected(other.step, other.option)) acc.with(other.step, acc.selected(other.step) - other.option) else acc
    }
}

/**
 * Undo of one conflict move ([toggled] choosing [option] on [step] took [movedFrom] off its list),
 * applied to the answers as they are now, so anything chosen since stays: [option] comes off
 * [step], and [movedFrom] goes back on its step unless that step is full by now (then it stays off,
 * and nothing is said). Not a snapshot of the answers before the move.
 */
fun TasteProfile.undoneMove(quiz: TasteQuiz, step: String, option: String, movedFrom: TasteChoice): TasteProfile {
    val taken = with(step, selected(step) - option)
    val other = quiz.step(movedFrom.step) ?: return taken
    if (taken.isSelected(other.id, movedFrom.option) || other.option(movedFrom.option) == null) return taken
    val current = taken.selected(other.id)
    if (current.size >= other.max) return taken
    val wanted = (current + movedFrom.option).toSet()
    return taken.with(other.id, other.options.map { it.id }.filter { it in wanted })
}

/**
 * The `taste` to send with a server request: only when the server said `acceptsTaste`
 * ([RecipePaidSnapshot.serverTakesTaste]); against [quiz] when the phone has the definition (so
 * what it no longer has is dropped), else only shape-cleaned (the server drops what it doesn't
 * know). Null, and left out of the request, for an empty profile; no error and nothing blocked.
 */
fun wireTasteFor(profile: TasteProfile, quiz: TasteQuiz?, serverTakesTaste: Boolean): Map<String, List<String>>? {
    if (!serverTakesTaste) return null
    val clean = (if (quiz != null) profile.normalized(quiz) else profile).shapeCleaned()
    return clean.answers.takeUnless { clean.isEmpty }
}

// --- Entry and summary -------------------------------------------------------------------------

/** What tapping the Taste profile row in Recipe settings does. */
enum class TasteEntry {
    /** No row. */
    HIDDEN,

    /** The quiz opens (and asks the server for its definition). */
    QUIZ,

    /** The sheet that says what it does, with See spark packs and Cancel. Calls nothing. */
    PROMO
}

/**
 * The gate on the Taste profile row. No row unless the paid route is available on this install
 * and the server said `acceptsTaste` ([serverTakesTaste]: the quiz can't be fetched otherwise).
 * The quiz opens with recipes in a pack (a known [balance] above zero) or a profile already saved,
 * else the promo sheet; but when the server won't sell to the account ([purchasesBlocked]) the
 * promo's See spark packs would lead nowhere, so then there is no row. Deciding calls nothing.
 */
fun tasteEntry(
    paidRouteAvailable: Boolean,
    balance: Int?,
    hasSavedProfile: Boolean,
    purchasesBlocked: Boolean = false,
    serverTakesTaste: Boolean = true
): TasteEntry = when {
    !paidRouteAvailable || !serverTakesTaste -> TasteEntry.HIDDEN
    (balance ?: 0) > 0 || hasSavedProfile -> TasteEntry.QUIZ
    purchasesBlocked -> TasteEntry.HIDDEN
    else -> TasteEntry.PROMO
}

/**
 * The settings row's supporting line for a saved profile, joined with " · " by the UI:
 * "Italian, Nordic · Medium · 5 more". [named] are up to [TASTE_SUMMARY_NAMED] labels from the
 * first step of the definition's `summary` list that has an answer; [single] is the chosen label of
 * the first single step; [more] counts every other answer (the skip list included), shown only
 * above 0. Without a definition in the app's language [named] and [single] are empty and the row
 * reads "N answers" ([more] is then every answer).
 */
data class TasteSummary(val named: List<String>, val single: String?, val more: Int)

const val TASTE_SUMMARY_NAMED = 2

/** See [TasteSummary]; null for an empty profile (the row says what it is for). */
fun tasteSummary(profile: TasteProfile, quiz: TasteQuiz?): TasteSummary? {
    if (quiz == null) {
        val clean = profile.shapeCleaned()
        return if (clean.isEmpty) null else TasteSummary(emptyList(), null, clean.count)
    }
    val clean = profile.normalized(quiz)
    if (clean.isEmpty) return null
    val namedStep = quiz.summary.mapNotNull(quiz::step).firstOrNull { clean.selected(it.id).isNotEmpty() }
    val named = namedStep?.let { step -> clean.selected(step.id).take(TASTE_SUMMARY_NAMED).mapNotNull { step.option(it)?.label } }.orEmpty()
    val singleStep = quiz.steps.firstOrNull { it.kind == TasteStepKind.SINGLE && it != namedStep }
    val single = singleStep?.let { step -> clean.selected(step.id).firstOrNull()?.let { step.option(it)?.label } }
    return TasteSummary(named, single, clean.count - named.size - (if (single != null) 1 else 0))
}
