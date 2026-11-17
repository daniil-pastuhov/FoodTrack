package com.foodtracker.recipe

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Recipe pictures and "Report this recipe" (owner's decisions 2026-11-17; functions/CONTRACT.md
 * "Capability `acceptsRecipeImage`", "Recipe picture (response)", "Capability `acceptsReports`",
 * `reportRecipe`). Pictures are for a single recipe written by our server only: never the shared
 * prompt, the phone's own model or a menu. iOS mirrors these rules by hand.
 */

/** How many pictures a server recipe comes with. The wire value is the request's `image`; [NONE]
 *  is never sent (the field is left out). */
@Serializable
enum class PictureLevel {
    @SerialName("none") NONE,
    @SerialName("dish") DISH,
    @SerialName("steps") STEPS
}

/** Why the level sent is lower than the one chosen: the pack doesn't cover it. */
enum class PictureShort {
    /** Not even the dish's picture: "You have N left: not enough for pictures." */
    NONE_COVERED,

    /** The dish, not the steps: "...enough for a picture of the dish, not the steps." */
    DISH_ONLY
}

/**
 * The form's Pictures row, worked out from the state ([pictureChoice]).
 *
 * [levels] are the sheet's rows (no pictures first, then each level the server prices); empty when
 * the row is hidden. [chosen] is the remembered choice, [sent] what a request really carries and
 * the primary's pill and subtitle show, [covered] the levels the pack covers now (the others are
 * dimmed in the sheet), and [short] the second line when [sent] is below what was chosen for lack
 * of recipes.
 */
data class PictureChoice(
    val levels: List<PictureLevel> = emptyList(),
    val chosen: PictureLevel = PictureLevel.NONE,
    val sent: PictureLevel = PictureLevel.NONE,
    val covered: Set<PictureLevel> = setOf(PictureLevel.NONE),
    val short: PictureShort? = null
) {
    /** The row is on the form. */
    val shown: Boolean get() = levels.isNotEmpty()
}

/**
 * The Pictures row, one rule on both platforms (iOS: `RecipePictures.choice` in `RecipeWire.swift`).
 *
 * Shown only where the server writes the recipe as the primary ([serverPrimary]: single-recipe
 * mode, recipes in the pack, nothing in the way), the server said `acceptsRecipeImage`
 * ([serverTakes]) and [costs] price at least the dish. Each level is offered only while the costs
 * price it. What is sent is the highest level, not above the choice, that the costs price and the
 * [balance] covers; [PictureChoice.short] says why when that is below the chosen level (or below the
 * highest priced level under it). Never sent with pictures dropped quietly: the button shows what
 * [PictureChoice.sent] is.
 */
fun pictureChoice(chosen: PictureLevel, costs: RecipeCosts, serverTakes: Boolean, balance: Int, serverPrimary: Boolean): PictureChoice {
    if (!serverPrimary || !serverTakes || costs.recipeImage == null) return PictureChoice(chosen = chosen)
    val levels = PictureLevel.entries.filter { costs.picture(it) != null }
    val covered = levels.filterTo(LinkedHashSet()) { level -> (costs.picture(level) ?: Int.MAX_VALUE) <= balance }.apply { add(PictureLevel.NONE) }
    // A choice the server no longer prices falls to the highest one it does, without a word.
    val target = levels.last { it.ordinal <= chosen.ordinal }
    val sent = levels.last { it.ordinal <= target.ordinal && it in covered }
    val short = when {
        sent == target -> null
        sent == PictureLevel.NONE -> PictureShort.NONE_COVERED
        else -> PictureShort.DISH_ONLY
    }
    return PictureChoice(levels, chosen, sent, covered, short)
}

/** A picture as a reply carried it, decoded from base64 by the platform backend off the main
 *  thread. Never printed: [toString] says only its size. */
class PictureBytes(val bytes: ByteArray, val width: Int, val height: Int) {
    override fun toString(): String = "PictureBytes(${width}x$height, ${bytes.size} bytes)"
}

/** A step's picture from a reply: [step] is the 0-based index into the recipe's steps. */
class StepPictureBytes(val step: Int, val picture: PictureBytes) {
    override fun toString(): String = "StepPictureBytes($step, $picture)"
}

/** A reply's `image` and `stepImages`, as the backend could read them. */
class ReplyPictures(val dish: PictureBytes? = null, val steps: List<StepPictureBytes> = emptyList()) {
    val isEmpty: Boolean get() = dish == null && steps.isEmpty()
    override fun toString(): String = "ReplyPictures(dish=$dish, steps=${steps.size})"
}

/** The longest edge a reply's picture may have, and the most bytes one may take. The contract says
 *  4:3 pictures of usually 1200 × 896 (another provider's 1200 × 800), at most 1,280 px on the
 *  longer side and 600,000 bytes, passed through from the provider with its metadata; these leave
 *  room above both, nothing near what could hurt memory (pictures are decoded at the size shown).
 *  Neither side is assumed equal to the other or to any fixed size. */
const val MAX_PICTURE_EDGE = 4096
const val MAX_PICTURE_BYTES = 1_000_000

/** Only the step pictures of real steps, each step once, in step order. */
fun ReplyPictures.forSteps(stepCount: Int): List<StepPictureBytes> =
    steps.filter { it.step in 0 until stepCount }.distinctBy { it.step }.sortedBy { it.step }

/**
 * Where pictures are kept on this device, one folder per saved recipe (Android: `filesDir/recipe_pictures/<savedId>/`,
 * out of Auto Backup and device transfer). Written before the saved row, so a row never points at
 * a picture that isn't there; a folder whose row is gone is removed by [sweep], never on delete,
 * so Undo still finds its pictures.
 */
interface RecipePictureStore {
    /** Keeps [pictures] (only the dish and [steps]) for [savedId] and returns what was kept, as the
     *  saved recipe names it; a picture that couldn't be written is left out. */
    suspend fun save(savedId: String, pictures: ReplyPictures, steps: Int): SavedPictures

    /** Removes the folders of saved recipes that aren't in [keep] (and weren't written in the last
     *  minutes, so one being saved right now stays). */
    suspend fun sweep(keep: Set<String>)
}

/** What [RecipePictureStore.save] kept. */
data class SavedPictures(val dish: RecipePicture? = null, val steps: List<RecipeStepPicture> = emptyList())

/** Builds without pictures (and tests): keeps nothing. */
object NoRecipePictureStore : RecipePictureStore {
    override suspend fun save(savedId: String, pictures: ReplyPictures, steps: Int): SavedPictures = SavedPictures()
    override suspend fun sweep(keep: Set<String>) = Unit
}

/** The quiet lines on a result that has just arrived with fewer pictures than asked for; never on
 *  a result opened later. */
data class PictureNotice(val dishFailed: Boolean, val stepsFailed: Boolean)

/**
 * What a reply to a request with pictures says failed ([request] asked for [PictureLevel.DISH] or
 * [PictureLevel.STEPS]): the dish when the server said `imageFailed` or no dish picture could be
 * kept; the steps when it said `stepImagesFailed` (at least one tried step picture wasn't
 * delivered). A "steps" recipe whose steps were all plain carries neither and says nothing. Null
 * when there is nothing to say.
 */
fun pictureNoticeOf(asked: PictureLevel?, imageFailed: Boolean, stepImagesFailed: Boolean, dishKept: Boolean): PictureNotice? {
    if (asked == null || asked == PictureLevel.NONE) return null
    val dish = imageFailed || !dishKept
    val steps = asked == PictureLevel.STEPS && stepImagesFailed
    return if (dish || steps) PictureNotice(dish, steps) else null
}

// --- Report this recipe ---------------------------------------------------------------------------

/** A report's reason, as `reportRecipe` names it. */
enum class ReportReason(val wire: String) {
    OFFENSIVE("offensive"),
    UNSAFE("unsafe"),

    /** Only offered on a result with pictures ([reportReasons]). */
    PICTURE("picture"),
    OTHER("other")
}

/** The reasons the sheet offers: the picture one only for a result with pictures. */
fun reportReasons(hasPictures: Boolean): List<ReportReason> =
    ReportReason.entries.filter { hasPictures || it != ReportReason.PICTURE }

/** The note's limit, in code points, as the server cuts it. */
const val REPORT_NOTE_MAX = 500

/** [note] as the field keeps it: at most [REPORT_NOTE_MAX] code points, never cut inside one. */
fun cutReportNote(note: String): String {
    val points = codePointsOf(note)
    if (points.size <= REPORT_NOTE_MAX) return note
    // Cut after the 500th code point: count chars, keeping surrogate pairs whole.
    var chars = 0
    var seen = 0
    while (seen < REPORT_NOTE_MAX && chars < note.length) {
        chars += if (note[chars].isHighSurrogate() && chars + 1 < note.length && note[chars + 1].isLowSurrogate()) 2 else 1
        seen++
    }
    return note.substring(0, chars)
}

/** The note's length as its counter shows it, in code points. */
fun reportNoteLength(note: String): Int = codePointsOf(note).size

/** The note as sent: trimmed, null when blank. */
fun reportNoteToSend(note: String): String? = cutReportNote(note).trim().ifEmpty { null }

/** What `reportRecipe` is sent; the platform backend adds the app's platform and version. */
data class RecipeReport(
    val reportId: String,
    val requestId: String,
    val reason: ReportReason,
    val note: String?,
    /** The app's language (`en`, `nb`...). */
    val language: String
)

/** What a result's foot shows for reporting. */
enum class ReportFoot {
    /** Nothing: a phone-written recipe, a row saved before request ids were kept, no account, an
     *  install without the paid route, or a server that hasn't said `acceptsReports`. */
    HIDDEN,
    AVAILABLE,

    /** Sent from this device: "You've reported this" in the button's place. */
    REPORTED
}

/**
 * Whether a saved result offers "Report this recipe / menu", one rule on both platforms (iOS:
 * `RecipeReporting.foot` in `RecipeWire.swift`): only a result the server wrote with its request id
 * kept ([requestId] non-null), only where the paid route is [available], an account exists
 * ([hasAccount]: one is never made for a report) and the server said `acceptsReports`; once sent
 * from here, "You've reported this" ([reported] holds the request ids sent).
 */
fun reportFoot(source: RecipeSource, requestId: String?, available: Boolean, hasAccount: Boolean, serverTakesReports: Boolean, reported: Set<String>): ReportFoot = when {
    source != RecipeSource.SERVER || requestId == null -> ReportFoot.HIDDEN
    requestId in reported -> ReportFoot.REPORTED
    !available || !hasAccount || !serverTakesReports -> ReportFoot.HIDDEN
    else -> ReportFoot.AVAILABLE
}

/** The report sheet's state. */
enum class ReportStatus {
    EDITING,
    SENDING,

    /** Not sent (offline, or our server couldn't take it): Try again sends the same report. */
    FAILED,

    /** REPORT_LIMIT: no more today. */
    LIMIT,

    /** UNKNOWN_REQUEST: the server has no reportable record of this result any more (delivered
     *  before it kept one, and its 7-day generation gone). Nothing was stored; no Try again. */
    UNKNOWN
}

/** The report sheet on screen ([RecipeStateHolder.openReport]). [reportId] is kept for every retry
 *  of this report, so the server stores it once. */
data class ReportSheet(
    val savedId: String,
    val requestId: String,
    val menu: Boolean,
    val hasPictures: Boolean,
    val reportId: String,
    val reason: ReportReason? = null,
    val note: String = "",
    val status: ReportStatus = ReportStatus.EDITING,
    /** Send was tapped without a reason: "Choose a reason." */
    val reasonMissing: Boolean = false
) {
    val reasons: List<ReportReason> get() = reportReasons(hasPictures)
}

/** How many sent reports this device remembers ([RecipeOptions.reportedRequests]); the oldest go first. */
const val REPORTED_REMEMBERED = 200
