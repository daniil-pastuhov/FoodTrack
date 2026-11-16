package com.foodtracker.recipe

import com.foodtracker.viewmodel.PantryRow
import com.foodtracker.viewmodel.PantryUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** How many of the soonest items the tab ticks by itself, whatever their date (past it included).
 *  iOS: `RecipeViewModel.defaultTickCount`. The one place to change it on Android. */
const val RECIPE_DEFAULT_TICKED = 3

/** How many of the soonest items the tab lists before "Add other items". Ticked items are listed
 *  as well, wherever they fall. */
const val RECIPE_SHOWN_ITEMS = 5

/** Which items to cook with: the soonest few until the person changes it, then exactly what
 *  they chose. */
sealed interface RecipeSelection {
    data object Default : RecipeSelection
    data class Chosen(val ids: Set<String>) : RecipeSelection
}

/** What the phone's own model can do for the tab right now. */
enum class OnDeviceRoute {
    /** Still asking the phone. Shown as no route, so nothing flickers in. */
    CHECKING,

    /** No model on this phone, or none for the chosen language: the prompt is the free route. */
    HIDDEN,

    /** The model is downloading. */
    PREPARING,
    READY
}

sealed interface RecipeEvent {
    /** A recipe or a menu was written and saved; open it. [recipesLeft] is the pack's new count
     *  when the server wrote it. */
    data class Written(
        val savedId: String,
        val recipesLeft: Int? = null,
        /** Items written without, because the server refused their names (as sent). */
        val leftOut: List<String> = emptyList()
    ) : RecipeEvent

    /** The server refused this item's name and it couldn't be left out (it was the only one, or
     *  leaving one out was already tried). Nothing was taken. */
    data class IngredientNotSent(val ingredient: String) : RecipeEvent

    /** The server refused this allergen's name. Nothing was written or taken: a recipe written
     *  without it could be unsafe, so it is never left out. */
    data class AllergenNotSent(val allergen: String) : RecipeEvent

    /** More allergens are set than the server takes in one request ([limit]). Nothing was
     *  written or taken: none is ever left out, since a recipe written without it could be
     *  unsafe. */
    data class TooManyAllergens(val count: Int, val limit: Int) : RecipeEvent

    /** Allergens are set and the person refused, or withdrew, sending them to our server.
     *  Nothing was sent: the free routes still work, and Recipe settings turns it back on. */
    data object AllergenConsentDeclined : RecipeEvent

    /** The server said the ticked items can't make what was asked (NOT_ENOUGH_ITEMS): once the
     *  allergens and diets are applied nothing is left, or for a [menu] some day has nothing it
     *  may use. Nothing was taken; the form stays as it is. */
    data class NotEnoughItems(val menu: Boolean, val days: Int = DEFAULT_MENU_DAYS) : RecipeEvent

    /** A recipe or a menu was written and saved after the person had left the writing screen (the
     *  tab, or the app): kept as unread in Saved recipes rather than opened. [recipesLeft] and
     *  [leftOut] as for [Written]. */
    data class SavedUnread(
        val menu: Boolean,
        val recipesLeft: Int? = null,
        val leftOut: List<String> = emptyList()
    ) : RecipeEvent

    /** The selected diets can't all be sent to the server as it is now (gluten-free with another
     *  diet, before it reads several diets): nothing was sent, since a recipe ignoring one could
     *  contain something the person excluded. One diet, or a free route, still works. */
    data object DietsNeedServerUpdate : RecipeEvent

    /** The recipe would be in Danish and the server hasn't said `acceptsDanish` (one deployed
     *  before it): nothing was sent, since another language in its place isn't what was asked.
     *  Another recipe language, or a free route, still works. */
    data object LanguageNeedsServerUpdate : RecipeEvent

    /** The phone's model failed or returned something that isn't a recipe. */
    data object Failed : RecipeEvent

    /** The server couldn't write it, and nothing was taken. */
    data object ServerFailed : RecipeEvent

    /** The server couldn't write it for a reason that asking again now won't fix (every
     *  instance busy, or a reason this app doesn't know yet). Nothing was taken. */
    data object ServerFailedLater : RecipeEvent

    /** Another recipe of this account was still being written (BUSY) for as long as the app
     *  waited. Nothing was taken. */
    data object ServerBusy : RecipeEvent

    /** Too many requests in a short time (RATE_LIMITED, refused as it was sent). Nothing was
     *  taken. */
    data object ServerRateLimited : RecipeEvent

    /** A daily limit (this account's, or the whole service's) was reached. Nothing was taken;
     *  it works again tomorrow. */
    data object ServerLimitToday : RecipeEvent

    /** The server failed for technical reasons too often today for this account. Nothing was
     *  taken; it works again tomorrow. */
    data object ServerTroubleToday : RecipeEvent

    /** The server declined these items once too often, and [taken] recipes were counted. */
    data class ServerRefused(val taken: Int) : RecipeEvent
}

/** A way to get a recipe from the form, each one its own button: a tap on one never falls back
 *  to another, so a free route never spends a recipe. */
enum class RecipeAction {
    /** Our server, from the pack. */
    SERVER,

    /** The phone's own model, free. */
    ON_DEVICE,

    /** The prompt to the share sheet, free. */
    SHARE,

    /** The prompt to the clipboard, free. Only where the share sheet can't copy
     *  ([RecipeUiState.shareSheetCopies] false: Android below 10); iOS never has it. */
    COPY,

    /** The pack sheet. */
    BUY
}

/**
 * What the form offers, as one group at its foot: [primary] first and filled (null: none),
 * then [secondary], in this order. With recipes in the pack the server is the primary and the free
 * routes stay one tap away; without, the phone's model or the shared prompt is. A menu is
 * the server's only: no free routes. iOS: `RecipeViewModel.actions`.
 */
data class RecipeActions(val primary: RecipeAction?, val secondary: List<RecipeAction>)

/** Where the tab's introduction ("How recipes work") was opened from. */
enum class RecipeIntro {
    /** By itself, the first time the tab was opened: closing it marks it seen. */
    FIRST_OPEN,

    /** From Recipe settings. */
    FROM_SETTINGS
}

/** What the tab writes: one recipe, or a menu set of 1 to 4 days (server only). */
enum class RecipeMode { RECIPE, MENU }

const val DEFAULT_MENU_COURSES = 3

/** The courses a menu falls back to when the ones chosen can't be asked for ([RecipeUiState.menuCourses]). */
const val FALLBACK_MENU_COURSES = 2

/** The menu form's "few items" hint: [items] usable items for [dishes] dishes. */
data class MenuItemsHint(val items: Int, val dishes: Int)

/**
 * Whether a menu set of [days] days and [courses] courses a day, from [ticked] ticked items and
 * [optional] other items that would go with it ("Send all products", as the request would carry
 * them), is mostly dishes made up plus shopping: fewer usable items than half the dishes, rounded
 * up, with at least one item ticked (nothing ticked has its own hint). A warning only: it never
 * blocks the menu. Null when there is nothing to say. One rule on both platforms; iOS:
 * `MenuSet.itemsHint(ticked:optional:)` in `RecipeWire.swift`.
 */
fun menuItemsHint(ticked: Int, optional: Int, days: Int, courses: Int): MenuItemsHint? {
    val dishes = days * courses
    val usable = ticked + optional
    return if (ticked > 0 && usable < (dishes + 1) / 2) MenuItemsHint(usable, dishes) else null
}

data class RecipeUiState(
    val isLoading: Boolean = true,
    /** Every pantry item, soonest date first. */
    val rows: List<PantryRow> = emptyList(),
    val selection: RecipeSelection = RecipeSelection.Default,
    val config: RecipeConfig = RecipeConfig(),
    /** The app's language as a [RECIPE_LANGUAGES] code, for "Same as the app". */
    val appLanguage: String = "en",
    val onDeviceAvailability: OnDeviceAvailability? = null,
    /** Whether the on-device model knows [language]; see [OnDeviceRecipeModel.supportsLanguage]. */
    val onDeviceSupportsLanguage: Boolean = false,
    /** The phone's own model is writing. */
    val isWriting: Boolean = false,
    val mode: RecipeMode = RecipeMode.RECIPE,
    /** Courses a day for a menu as chosen: 1, 2 or 3. What is asked for is [menuCourses]. */
    val courses: Int = DEFAULT_MENU_COURSES,
    /** Days of a menu as chosen: 1 to 4. What is asked for is [menuDays]. */
    val days: Int = DEFAULT_MENU_DAYS,
    /** Newest first. */
    val saved: List<SavedRecipe> = emptyList(),
    /** False until the saved list has been read once, so "nothing saved" doesn't flash. */
    val savedLoaded: Boolean = false,
    /** From app Settings, already cleaned; always applied. */
    val allergens: List<String> = emptyList(),
    /** The server route, the packs and the account behind them. */
    val paid: PaidUiState = PaidUiState(),
    val event: RecipeEvent? = null,
    /** The tab's settings outside the config: "Send all products", the introduction seen. */
    val options: RecipeOptions = RecipeOptions(),
    /** The saved taste profile ([TasteProfile]), exactly as stored; empty until the quiz is saved. */
    val taste: TasteProfile = TasteProfile(),
    /** The last good taste quiz definition in the app's language, from the cache: names the
     *  settings row's summary and filters what is sent. Null until one was fetched in it. */
    val tasteDefinition: TasteQuiz? = null,
    /** The quiz screen's content while it is open; null when it isn't. */
    val tasteQuiz: TasteQuizScreen? = null,
    /** The quiz steps the person has been through, kept apart from the answers. */
    val tasteSeen: List<String> = emptyList(),
    /** The introduction on screen, and where from; null when it isn't. */
    val intro: RecipeIntro? = null,
    /** The system share sheet has a Copy action of its own (Android 10 and up), so the form
     *  offers no Copy button. A platform fact, given to [RecipeStateHolder] by the app. */
    val shareSheetCopies: Boolean = true
) {
    /** The language recipes are written in. */
    val language: String = config.language ?: appLanguage

    /** Whether the server route can be offered on this build. */
    val paidRouteAvailable: Boolean get() = paid.available

    /** A taste profile goes with a server request: one is saved and the server reads it. The
     *  server action's helper then says "Uses your taste profile". */
    val sendsTaste: Boolean get() = wireTasteFor(taste, tasteDefinition, paid.takesTaste) != null

    /** What tapping the Taste profile row does ([tasteEntry]); [TasteEntry.HIDDEN] hides the row. */
    val tasteEntry: TasteEntry get() = tasteEntry(paid.available, paid.balance, !taste.isEmpty, paid.purchasesBlocked, paid.takesTaste)

    /** The row's summary ([tasteSummary]), null without answers to show. */
    val tasteSummary: TasteSummary? get() = tasteSummary(taste, tasteDefinition)

    /** Questions not yet been through, for the row's "· N new questions" once a profile exists
     *  ([tasteNewSteps]); 0 without one (the row then carries the dot, [tasteRowDot]). */
    val tasteNewQuestions: Int get() = if (tasteSummary == null) 0 else tasteNewSteps(tasteSeen, tasteDefinition).size

    /** The dot on the header's settings button ([tasteHasNewQuestions]). */
    val tasteBadge: Boolean get() = tasteHasNewQuestions(tasteEntry, tasteSeen, tasteDefinition)

    /** The same dot on the settings row, before its chevron: only without a profile (with one,
     *  "· N new questions" says it). */
    val tasteRowDot: Boolean get() = tasteBadge && tasteSummary == null

    /** Something is being written, on the phone or on the server. */
    val isBusy: Boolean get() = isWriting || paid.writing != null

    val selectedIds: Set<String> = when (selection) {
        RecipeSelection.Default -> rows.take(RECIPE_DEFAULT_TICKED).mapTo(LinkedHashSet()) { it.id }
        is RecipeSelection.Chosen -> rows.mapNotNullTo(LinkedHashSet()) { row -> row.id.takeIf { it in selection.ids } }
    }

    /** The ticked items, soonest first. */
    val selectedRows: List<PantryRow> = rows.filter { it.id in selectedIds }

    /** The rows the tab lists: the soonest few, plus anything else that is ticked. */
    val shownRows: List<PantryRow> = rows.filterIndexed { index, row -> index < RECIPE_SHOWN_ITEMS || row.id in selectedIds }

    /** Items reachable only through "Add other items". */
    val otherCount: Int = rows.size - shownRows.size

    /** Everything not ticked, soonest first: what "Send all products" offers the recipe as well. */
    val otherRows: List<PantryRow> = rows.filter { it.id !in selectedIds }

    /** The ticked items past their date, soonest first: the form's red warning, the line in the
     *  shared prompt, and what a recipe written on the phone carries ([Recipe.pastDate]). */
    val pastDateTicked: List<PastDateItem> = selectedRows.filter { it.daysRemaining < 0 }.map { PastDateItem(it.item.name, -it.daysRemaining) }

    /** How many items "Send all products" adds to this recipe; 0 with it off. */
    val optionalCount: Int get() = if (options.sendAllItems) otherRows.size else 0

    /** The other items a menu request would carry as optional ingredients: with "Send all
     *  products" on and a server that reads them, the cleaned names [optionalWireIngredients]
     *  keeps beside the ticked ones; 0 otherwise. */
    private val optionalSent: Int
        get() {
            if (!options.sendAllItems || !paid.serverTakesOptional) return 0
            fun wire(rows: List<PantryRow>) = rows.mapNotNull { row -> cleanWireName(row.item.name)?.let { WireIngredient(it, null, null) } }
            return optionalWireIngredients(wire(selectedRows).take(MAX_REQUEST_INGREDIENTS), wire(otherRows)).size
        }

    /** The menu form's "few items for this menu" hint ([menuItemsHint]) for the menu set asked
     *  for; null in single-recipe mode and when there are enough. */
    val menuItemsHint: MenuItemsHint?
        get() = if (mode != RecipeMode.MENU) null else menuItemsHint(selectedRows.size, optionalSent, menuDays, menuCourses)

    /** Something is saved, so the header offers the saved list. Follows the saved recipes as
     *  they change: the first one written shows it, deleting the last one hides it. */
    val hasSaved: Boolean get() = saved.isNotEmpty()

    /** Saved recipes and menus written without being shown ([RecipeOptions.unreadSaved]) that are
     *  still saved: the count on the header's Saved recipes button. */
    val unreadCount: Int get() = if (options.unreadSaved.isEmpty()) 0 else saved.count { it.id in options.unreadSaved }

    fun isUnread(id: String): Boolean = id in options.unreadSaved

    /** Whether a menu of [days] days and [courses] courses a day can be asked for: the server said
     *  it reads menu sets and prices that combination, or it is the 3-day menu of 2 or 3 courses
     *  every server writes. */
    private fun menuOffered(days: Int, courses: Int): Boolean =
        if (paid.menuSets) paid.costs.menuSet(days, courses) != null else days == DEFAULT_MENU_DAYS && courses in LEGACY_MENU_COURSES

    /**
     * The (days, courses) asked for, one rule on both platforms (iOS `RecipeViewModel`): without
     * `acceptsMenuDays`, 3 days with the chosen courses when that is 2 or 3, else 2. With it, the
     * chosen pair when it has a price, else 3 days with the chosen courses when that has one, else
     * 3 days of 2 courses.
     */
    private val menuPair: Pair<Int, Int>
        get() = when {
            !paid.menuSets -> DEFAULT_MENU_DAYS to (if (courses in LEGACY_MENU_COURSES) courses else FALLBACK_MENU_COURSES)
            menuOffered(days, courses) -> days to courses
            menuOffered(DEFAULT_MENU_DAYS, courses) -> DEFAULT_MENU_DAYS to courses
            else -> DEFAULT_MENU_DAYS to FALLBACK_MENU_COURSES
        }

    /** The courses asked for; see [menuPair]. */
    val menuCourses: Int get() = menuPair.second

    /** The days asked for; see [menuPair]. Always 3 before the server says `acceptsMenuDays`. */
    val menuDays: Int get() = menuPair.first

    /** The Days choice: every day count offered with the courses asked for. Empty (and not
     *  shown) until the server says `acceptsMenuDays`, when every menu is a 3-day one. */
    val menuDayOptions: List<Int>
        get() = if (!paid.menuSets) emptyList() else menuCourses.let { courses -> MENU_DAY_OPTIONS.filter { menuOffered(it, courses) } }

    /** The Courses choice for the days asked for: 2 and 3 with any server (for 3 days), 1 too
     *  with one that says `acceptsMenuDays` and prices it. */
    val menuCourseOptions: List<Int> get() = menuDays.let { days -> MENU_COURSE_OPTIONS.filter { menuOffered(days, it) } }

    val onDeviceRoute: OnDeviceRoute = when (onDeviceAvailability) {
        null -> OnDeviceRoute.CHECKING
        OnDeviceAvailability.UNSUPPORTED -> OnDeviceRoute.HIDDEN
        else -> when {
            !onDeviceSupportsLanguage -> OnDeviceRoute.HIDDEN
            onDeviceAvailability == OnDeviceAvailability.AVAILABLE -> OnDeviceRoute.READY
            else -> OnDeviceRoute.PREPARING
        }
    }

    /**
     * The phone has its own model, but not for the recipe language chosen: the reason "Generate
     * on this device" isn't offered, said once under the language in Recipe settings. Follows
     * [language] as it changes. iOS: `RecipeViewModel.onDeviceLanguageUnsupported`.
     */
    val onDeviceLanguageUnsupported: Boolean =
        onDeviceAvailability != null && onDeviceAvailability != OnDeviceAvailability.UNSUPPORTED && !onDeviceSupportsLanguage

    /** What the current mode takes from a pack. */
    val serverCost: Int get() = if (mode == RecipeMode.MENU) paid.costs.menuSet(menuDays, menuCourses) ?: paid.costs.menu(menuCourses) else paid.costs.recipe

    /** Whether the server can be asked now: a pack with enough left, nothing in the way. */
    val canGenerateOnServer: Boolean
        get() = paid.available && paid.issue != ServerIssue.PAUSED && (paid.balance ?: 0) >= serverCost

    /** The form's actions in this state; see [RecipeActions]. */
    val actions: RecipeActions
        get() {
            if (mode == RecipeMode.MENU) {
                // The too-few callout and Buy take the button's place; buying is the form's.
                return if (canGenerateOnServer) RecipeActions(RecipeAction.SERVER, emptyList()) else RecipeActions(null, emptyList())
            }
            val onDevice = onDeviceRoute == OnDeviceRoute.READY
            // Offline or busy, the free routes move up; the count stays in the bar.
            val primary = when {
                canGenerateOnServer && paid.issue == null -> RecipeAction.SERVER
                onDevice -> RecipeAction.ON_DEVICE
                else -> RecipeAction.SHARE
            }
            val secondary = buildList {
                if (onDevice && primary != RecipeAction.ON_DEVICE) add(RecipeAction.ON_DEVICE)
                if (primary != RecipeAction.SHARE) add(RecipeAction.SHARE)
                if (!shareSheetCopies) add(RecipeAction.COPY)
                if (paid.canBuy && primary != RecipeAction.SERVER) add(RecipeAction.BUY)
            }
            return RecipeActions(primary, secondary)
        }
}

/**
 * The Recipe tab: which items to cook with, the form, the three routes (our server, the phone's
 * own model, the shared prompt), and the recipes kept on this phone. Separate from
 * `PantryStateHolder`, which it only reads. The server route, packs and account live in
 * [RecipePaid]; this class hands its calls through.
 *
 * iOS reimplements this in `RecipeViewModel.swift`; a change to its rules is ported there.
 */
@OptIn(ExperimentalUuidApi::class)
class RecipeStateHolder(
    pantry: Flow<PantryUiState>,
    private val onDeviceModel: OnDeviceRecipeModel,
    private val savedRecipeRepository: SavedRecipeRepository,
    private val configRepository: RecipeConfigRepository,
    backend: RecipeBackend = NotAvailableRecipeBackend,
    store: RecipeStore = NotAvailableRecipeStore,
    account: RecipeAccount = NoRecipeAccount,
    paidRepository: RecipePaidRepository = InMemoryRecipePaidRepository(),
    /** The app's current language, such as "en" or "nb-NO". Read again on [refresh], since the
     *  person can change the app's language while it runs. */
    private val appLanguage: () -> String = { "en" },
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val newId: () -> String = { "recipe_${Clock.System.now().toEpochMilliseconds()}_${Random.nextInt().toUInt()}" },
    newRequestId: () -> String = { Uuid.random().toString() },
    today: () -> LocalDate = { Clock.System.todayIn(TimeZone.currentSystemDefault()) },
    /** Waits the given milliseconds before asking the server again: polls of a generation it is
     *  still writing, and the backoff while it is busy. */
    pollDelay: suspend (Long) -> Unit = { delay(it) },
    /** See [RecipeUiState.shareSheetCopies]: false only on Android below 10 (API 29). */
    shareSheetCopies: Boolean = true
) {
    private val _uiState = MutableStateFlow(
        configRepository.get().normalized().let { config ->
            RecipeUiState(
                config = config,
                appLanguage = appRecipeLanguage(appLanguage()),
                options = configRepository.getOptions(),
                taste = configRepository.getTaste(),
                tasteSeen = configRepository.getTasteSeen(),
                tasteDefinition = cachedTasteQuiz(appRecipeLanguage(appLanguage())),
                shareSheetCopies = shareSheetCopies
            )
                .withLanguageSupport()
        }
    )
    val uiState: StateFlow<RecipeUiState> = _uiState.asStateFlow()

    private val tasteBackend = backend
    private val tasteAccount = account

    /** (Version, language) pairs fetched quietly already: at most once each. */
    private val prefetchedTasteQuizzes = mutableSetOf<String>()
    private var prefetchingTasteQuiz = false

    private val paid = RecipePaid(
        state = _uiState,
        backend = backend,
        store = store,
        account = account,
        repository = paidRepository,
        savedRecipeRepository = savedRecipeRepository,
        scope = coroutineScope,
        nowMillis = nowMillis,
        newSavedId = newId,
        newRequestId = newRequestId,
        today = today,
        pollDelay = pollDelay,
        isOnScreen = { onScreen.value > 0 },
        markUnread = ::markUnread,
        onTermsReply = { prefetchTasteQuiz() }
    )

    /** How many copies of the writing screen are on screen and resumed (a transition can briefly
     *  hold two). Written on the main thread and read where a result arrives, off it. */
    private val onScreen = MutableStateFlow(0)

    private var writingJob: Job? = null

    /** A demo or screenshot launch: the introduction never opens by itself in this run. */
    private var introSuppressed = false
    private val downloadLock = Mutex()

    init {
        coroutineScope.launch {
            pantry
                .map { Triple(it.isLoading, it.allRows, it.settings.allergens) }
                .distinctUntilChanged()
                .collect { (loading, rows, allergens) ->
                    val sorted = rows.sortedWith(compareBy<PantryRow> { it.daysRemaining }.thenBy { it.sortName })
                    val cleaned = cleanAllergens(allergens)
                    _uiState.update {
                        it.copy(isLoading = loading, rows = sorted, allergens = cleaned)
                    }
                    // Not while the settings are still loading: an empty list then isn't the person's.
                    if (!loading) paid.onAllergensChanged(cleaned.any { it.isNotBlank() })
                }
        }
        coroutineScope.launch {
            savedRecipeRepository.getAllFlow().collect { saved -> _uiState.update { it.copy(saved = saved, savedLoaded = true) } }
        }
        paid.start()
        refresh()
    }

    private fun RecipeUiState.withLanguageSupport(): RecipeUiState =
        copy(onDeviceSupportsLanguage = onDeviceModel.supportsLanguage(language))

    /**
     * Asks the phone again whether its model is there, and picks up a change of app language.
     * Called when the tab opens. Starts the model's download when it is needed, so it can be
     * ready by the next visit. Also catches up on the paid route (see [onAppResume]).
     */
    fun refresh() {
        if (demoShown) return
        _uiState.update { state ->
            val language = appRecipeLanguage(appLanguage())
            // The cache is per language: another language's summary reads "N answers" until its
            // definition is fetched.
            val definition = if (language == state.appLanguage) state.tasteDefinition else cachedTasteQuiz(language)
            state.copy(appLanguage = language, tasteDefinition = definition).withLanguageSupport()
        }
        prefetchTasteQuiz()
        coroutineScope.launch {
            val availability = runCatching { onDeviceModel.availability() }.getOrDefault(OnDeviceAvailability.UNSUPPORTED)
            // A demo launch that came in meanwhile shows its own.
            if (demoShown) return@launch
            _uiState.update { it.copy(onDeviceAvailability = availability) }
            downloadIfNeeded()
        }
        paid.resume()
        // The tab is shown: the free recipes' offer, from its cache, and asked again when due.
        paid.onTabShown()
    }

    /**
     * Downloads the phone's model when it is missing, but only while the recipe language is one
     * it writes: a model the route would stay hidden for isn't fetched. Called again when the
     * language changes to one it does write.
     */
    private suspend fun downloadIfNeeded() {
        val state = _uiState.value
        val needsDownload = state.onDeviceAvailability == OnDeviceAvailability.DOWNLOADABLE ||
            state.onDeviceAvailability == OnDeviceAvailability.DOWNLOADING
        // tryLock, so a second refresh while the first download runs doesn't start another.
        if (!needsDownload || !state.onDeviceSupportsLanguage || !downloadLock.tryLock()) return
        try {
            runCatching { onDeviceModel.download() }
            val after = runCatching { onDeviceModel.availability() }.getOrDefault(OnDeviceAvailability.UNSUPPORTED)
            _uiState.update { it.copy(onDeviceAvailability = after) }
        } finally {
            downloadLock.unlock()
        }
    }

    /** The app came to the front: resubmits purchases the server hasn't confirmed, asks again
     *  about a generation whose answer was lost, and refreshes the count. Does nothing for
     *  someone who never started a purchase. */
    fun onAppResume() = paid.resume()

    // --- Items -------------------------------------------------------------------------------

    fun toggleItem(id: String) {
        _uiState.update { state ->
            val current = state.selectedIds
            state.copy(selection = RecipeSelection.Chosen(if (id in current) current - id else current + id))
        }
    }

    /** Ticks exactly [ids]: Home's multi-select and the digest's Recipe ideas. Ids that aren't
     *  (or are no longer) in the pantry are ignored. */
    fun selectItems(ids: Collection<String>) {
        _uiState.update { it.copy(selection = RecipeSelection.Chosen(ids.toSet())) }
    }

    // --- Form --------------------------------------------------------------------------------

    /** Applies [transform] to the configuration and keeps the result as the form's default. */
    fun updateConfig(transform: (RecipeConfig) -> RecipeConfig) {
        val updated = transform(_uiState.value.config).normalized()
        if (updated == _uiState.value.config) return
        configRepository.save(updated)
        val supportedBefore = _uiState.value.onDeviceSupportsLanguage
        _uiState.update { it.copy(config = updated).withLanguageSupport() }
        // A language the model writes, chosen while its model is still missing: fetch it now.
        if (!supportedBefore && _uiState.value.onDeviceSupportsLanguage) coroutineScope.launch { downloadIfNeeded() }
    }

    /** Recipe or Menu set. The menu is offered only with the paid route. */
    fun setMode(mode: RecipeMode) {
        if (mode == RecipeMode.MENU && !_uiState.value.paid.available) return
        _uiState.update { it.copy(mode = mode) }
    }

    fun setCourses(courses: Int) {
        if (courses !in _uiState.value.menuCourseOptions) return
        _uiState.update { it.copy(courses = courses) }
    }

    /** A menu set's days, among [RecipeUiState.menuDayOptions]. */
    fun setDays(days: Int) {
        if (days !in _uiState.value.menuDayOptions) return
        _uiState.update { it.copy(days = days) }
    }

    /**
     * The Recipe tab's screen (where a result being written is waited for) came on screen, or
     * went: another screen or tab, or the app in the background. A result arriving while it isn't
     * on screen is saved as unread instead of being opened.
     */
    fun setOnScreen(on: Boolean) {
        onScreen.update { if (on) it + 1 else (it - 1).coerceAtLeast(0) }
    }

    /** "Send all products" in Recipe settings. */
    fun setSendAllItems(on: Boolean) {
        updateOptions { it.copy(sendAllItems = on) }
    }

    /**
     * Called from the main thread (settings, the introduction, opening a result) and from where a
     * result arrives off it (`markUnread`), so the change is applied to whatever the options are
     * at that moment, never to a copy read earlier, and what is saved is re-checked afterwards:
     * of two changes at once neither is lost, in memory or in storage.
     */
    private fun updateOptions(transform: (RecipeOptions) -> RecipeOptions) {
        val before = _uiState.value.options
        _uiState.update { it.copy(options = transform(it.options)) }
        var saved = _uiState.value.options
        if (saved == before) return
        while (true) {
            configRepository.saveOptions(saved)
            val now = _uiState.value.options
            if (now == saved) return
            saved = now
        }
    }

    // --- Introduction ------------------------------------------------------------------------

    /**
     * The tab is on screen: the first time ever, its introduction opens. Never in a demo launch
     * ([suppressIntroForSession]), and never again once closed. Returns whether it opened, so the
     * caller can treat it as onboarding (no rating request in this session).
     */
    fun onTabShown(): Boolean {
        val state = _uiState.value
        if (introSuppressed || state.options.introSeen || state.intro != null) return false
        _uiState.update { it.copy(intro = RecipeIntro.FIRST_OPEN) }
        return true
    }

    /** Recipe settings' "How recipes work". */
    fun showIntro() {
        _uiState.update { it.copy(intro = RecipeIntro.FROM_SETTINGS) }
    }

    /** Closed, whichever way: never opens by itself again. */
    fun dismissIntro() {
        _uiState.update { it.copy(intro = null) }
        updateOptions { it.copy(introSeen = true) }
    }

    /** The introduction's "See recipe packs": closed, then the pack sheet. */
    fun introToPacks() {
        dismissIntro()
        paid.openPacks()
    }

    /** A demo or screenshot launch: no introduction opens by itself in this run. */
    fun suppressIntroForSession() {
        introSuppressed = true
    }

    /**
     * Debug-only demo launches (`demoRecipeCredits` and its companions in `MainActivity`): the
     * form as [demo] describes it, for screenshots of every combination of actions. Nothing is
     * sent; the real paid route and the phone's model stop changing what is shown for this run.
     */
    fun showDemo(demo: RecipeDemo) {
        demoShown = true
        paid.showDemo(demo.paid)
        _uiState.update {
            it.copy(
                onDeviceAvailability = if (demo.onDevice) OnDeviceAvailability.AVAILABLE else OnDeviceAvailability.UNSUPPORTED,
                onDeviceSupportsLanguage = demo.onDevice,
                shareSheetCopies = demo.shareSheetCopies,
                mode = if (demo.paid.available) demo.mode else RecipeMode.RECIPE,
                days = demo.days,
                courses = demo.courses
            )
        }
    }

    private var demoShown = false

    /** Debug-only demo launches that ask for it: shown as on a first open. */
    fun showIntroForDemo() {
        _uiState.update { it.copy(intro = RecipeIntro.FIRST_OPEN) }
    }

    // --- On-device route ---------------------------------------------------------------------

    /** Writes a recipe with the phone's own model from the ticked items. Saved when it arrives,
     *  then [RecipeEvent.Written]; [RecipeEvent.Failed] when the model fails twice over. */
    fun generateOnDevice() {
        val state = _uiState.value
        if (state.isBusy || state.selectedRows.isEmpty() || state.onDeviceRoute != OnDeviceRoute.READY) return
        val items = state.selectedRows.map { RecipePromptItem(it.item.name, it.daysRemaining) }
        val optional = if (state.options.sendAllItems) state.otherRows.map { RecipePromptItem(it.item.name, it.daysRemaining) } else emptyList()
        val prompt = buildOnDeviceRecipePrompt(items, state.config, state.language, state.allergens, optional)
        val names = (items + optional).map { it.name }
        _uiState.update { it.copy(isWriting = true, event = null) }
        // Started lazily so writingJob is set before the body can run and report.
        val started = coroutineScope.launch(start = CoroutineStart.LAZY) {
            val job = coroutineContext.job
            // A stopped run can still be unwinding when the next one starts; only the current
            // run may report.
            fun report(event: RecipeEvent, written: SavedRecipe? = null) {
                if (writingJob !== job) return
                _uiState.update { state ->
                    // The new recipe joins the list now, so its page opens with it rather than
                    // waiting for the database to emit.
                    val saved = if (written == null) state.saved else listOf(written) + state.saved.filterNot { it.id == written.id }
                    state.copy(isWriting = false, event = event, saved = saved)
                }
            }
            try {
                // Small models get the JSON wrong now and then; one more try is cheap and free.
                val recipe = writeOnce(prompt, state.config, names) ?: writeOnce(prompt, state.config, names)
                if (recipe == null) {
                    report(RecipeEvent.Failed)
                    return@launch
                }
                // The warning comes from the ticked items' own dates, never from the model's text.
                val saved = SavedRecipe.of(newId(), recipe.copy(pastDate = state.pastDateTicked), nowMillis())
                savedRecipeRepository.save(saved)
                if (onScreen.value > 0) {
                    report(RecipeEvent.Written(saved.id), saved)
                } else {
                    markUnread(saved.id)
                    report(RecipeEvent.SavedUnread(menu = false), saved)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report(RecipeEvent.Failed)
            }
        }
        writingJob = started
        started.start()
    }

    private suspend fun writeOnce(prompt: String, config: RecipeConfig, names: List<String>): Recipe? =
        parseRecipeReply(onDeviceModel.generate(prompt), RecipeSource.DEVICE, config, names)

    /** Stops the phone's model. Nothing was paid, so nothing is lost. */
    fun stopWriting() {
        writingJob?.cancel()
        writingJob = null
        _uiState.update { it.copy(isWriting = false) }
    }

    fun consumeEvent() {
        _uiState.update { it.copy(event = null) }
    }

    // --- Server route ------------------------------------------------------------------------

    /** Asks the server for a recipe, or a menu in [RecipeMode.MENU], from the ticked items. The
     *  count changes only when the answer arrives. */
    fun generateOnServer() = paid.generate()

    /** The offline callout's Try again: sends the unanswered request again with its own id, or
     *  asks for the count. */
    fun retryServer() = paid.retry()

    // --- Packs -------------------------------------------------------------------------------

    fun openPacks() = paid.openPacks()
    fun selectPack(productId: String) = paid.selectPack(productId)

    /** Continue in the pack sheet: the first time, the choice of where recipes live; after that
     *  straight to the store. */
    fun continuePurchase() = paid.continuePurchase()

    /** The choice's "On this phone, no account". */
    fun buyWithoutAccount() = paid.buyWithoutAccount()

    /** The choice's "In an account". */
    fun buyWithAccount(provider: SignInProvider) = paid.buyWithAccount(provider)
    fun backToPacks() = paid.backToPacks()
    fun dismissPacks() = paid.dismissPacks()

    /** "Try now" on a purchase that hasn't been added yet. */
    fun retryPurchases() = paid.retryPurchases()
    fun dismissRejectedPurchase() = paid.dismissRejected()

    // --- Account -----------------------------------------------------------------------------

    fun signIn(provider: SignInProvider) = paid.signIn(provider)
    fun confirmMerge() = paid.confirmMerge()
    fun cancelMerge() = paid.cancelMerge()
    fun signOut() = paid.signOut()
    fun deleteAccount() = paid.deleteAccount()
    fun consumePaidEvent() = paid.consumeEvent()

    // --- Free recipes for signing in (WelcomeRecipes.kt) ----------------------------------------

    /** The hint was tapped: the sign-in sheet (signed out), or the claim itself (signed in). */
    fun openWelcome() = paid.openWelcome()

    /** The sign-in sheet's sign-in button. */
    fun welcomeSignIn(provider: SignInProvider) = paid.welcomeSignIn(provider)
    fun dismissWelcomeSheet() = paid.dismissWelcomeSheet()

    /** Signed in, not claimed: claims with one tap; also Try again after a claim not reached. */
    fun claimWelcome() = paid.claimWelcome()
    fun dismissWelcomeAlready() = paid.dismissWelcomeAlready()

    /** "How recipes work"'s offer action: closed, then as [openWelcome]. */
    fun introToWelcome() {
        dismissIntro()
        paid.openWelcome()
    }
    fun dismissAppleLinkNotice() = paid.dismissAppleLinkNotice()

    // --- Allergen consent --------------------------------------------------------------------

    fun agreeToAllergenConsent() = paid.agreeToAllergenConsent()
    fun declineAllergenConsent() = paid.declineAllergenConsent()
    fun setAllergenConsent(on: Boolean) = paid.setAllergenConsent(on)

    // --- Saved recipes -----------------------------------------------------------------------

    fun savedRecipe(id: String): SavedRecipe? = _uiState.value.saved.firstOrNull { it.id == id }

    private fun markUnread(id: String) {
        updateOptions { it.copy(unreadSaved = it.unreadSaved + id) }
    }

    /** Debug demo launches only (`demoUnread`): [id] shown as unread. */
    fun markSavedUnreadForDemo(id: String) = markUnread(id)

    /** A saved recipe or menu was opened: no longer unread. */
    fun markSavedRead(id: String) {
        if (id !in _uiState.value.options.unreadSaved) return
        updateOptions { it.copy(unreadSaved = it.unreadSaved - id) }
    }

    /** Deletes a saved recipe and hands it to [onDeleted], so the caller can offer Undo through
     *  [restoreSaved]. */
    fun deleteSaved(id: String, onDeleted: (SavedRecipe) -> Unit = {}) {
        val saved = savedRecipe(id) ?: return
        markSavedRead(id)
        coroutineScope.launch {
            savedRecipeRepository.delete(id)
            onDeleted(saved)
        }
    }

    /** Stores [profile] as given (the quiz passes [savedTaste]; Undo passes what was there). An
     *  empty profile is the same as [clearTaste]. Nothing is sent or called. */
    fun saveTaste(profile: TasteProfile) {
        val kept = if (profile.isEmpty) TasteProfile() else profile
        configRepository.saveTaste(kept)
        _uiState.update { it.copy(taste = kept) }
    }

    /** "Clear taste profile": forgotten on this phone; requests go without it from now on. */
    fun clearTaste() = saveTaste(TasteProfile())

    /**
     * Opens the quiz. With a good cached definition in the app's language it shows at once, and a
     * fresh one is fetched in the background for the next open (never swapped under the person's
     * fingers). Without one it loads, and fails to [TasteQuizScreen.Failed] (Retry, Close). Asks the
     * server only when the gate says [TasteEntry.QUIZ], and never creates an account to do it.
     */
    fun openTasteQuiz() {
        val state = _uiState.value
        if (state.tasteEntry != TasteEntry.QUIZ) return
        val language = state.appLanguage
        val cached = cachedTasteQuiz(language)
        if (cached != null) {
            _uiState.update { it.copy(tasteDefinition = cached, tasteQuiz = TasteQuizScreen.Ready(cached, firstTasteStep(it.tasteSeen, cached))) }
            coroutineScope.launch { fetchTasteQuiz(language) }
        } else {
            loadTasteQuiz(language)
        }
    }

    /** Retry on the quiz's error. */
    fun retryTasteQuiz() {
        if (_uiState.value.tasteQuiz != TasteQuizScreen.Failed) return
        loadTasteQuiz(_uiState.value.appLanguage)
    }

    /** The quiz was left: saved, discarded or closed. [visited] are the steps shown, which count
     *  as seen with or without a profile ([tasteSeenAfter]), so the dot and the note go; a step
     *  never reached stays new. */
    fun closeTasteQuiz(visited: Collection<String> = emptyList()) {
        val state = _uiState.value
        val seen = tasteSeenAfter(state.tasteSeen, visited)
        if (seen != state.tasteSeen) configRepository.saveTasteSeen(seen)
        _uiState.update { it.copy(tasteQuiz = null, tasteSeen = seen) }
    }

    /**
     * After a reply that carries a balance: when its `tasteQuizVersion` isn't the cached
     * definition's ([shouldPrefetchTasteQuiz]), the definition is fetched quietly so the dot can tell
     * new questions. Silent on failure, which a later reply retries; never for the promo; never
     * swaps an open quiz's definition.
     */
    private fun prefetchTasteQuiz() {
        val state = _uiState.value
        val language = state.appLanguage
        val version = state.paid.tasteQuizVersion
        val key = "$version|$language"
        val hasAccount = tasteAccount.state.value.kind != AccountKind.NONE
        if (prefetchingTasteQuiz || !shouldPrefetchTasteQuiz(version, state.tasteDefinition?.version, state.tasteEntry, hasAccount, key in prefetchedTasteQuizzes)) return
        prefetchingTasteQuiz = true
        coroutineScope.launch {
            try {
                if (fetchTasteQuiz(language) != null) prefetchedTasteQuizzes += key
            } finally {
                prefetchingTasteQuiz = false
            }
        }
    }

    private fun loadTasteQuiz(language: String) {
        _uiState.update { it.copy(tasteQuiz = TasteQuizScreen.Loading) }
        coroutineScope.launch {
            val quiz = fetchTasteQuiz(language)
            _uiState.update { state ->
                if (state.tasteQuiz != TasteQuizScreen.Loading) state
                else state.copy(tasteQuiz = quiz?.let { TasteQuizScreen.Ready(it, firstTasteStep(state.tasteSeen, it)) } ?: TasteQuizScreen.Failed)
            }
        }
    }

    /** Asks the server; caches and returns the definition when it can be read, else keeps the
     *  last good one (also for an answer that can't be decoded) and returns null. */
    private suspend fun fetchTasteQuiz(language: String): TasteQuiz? {
        // Only for someone with an account (who has bought), and only from a server that has it.
        if (tasteAccount.state.value.kind == AccountKind.NONE || !_uiState.value.paid.takesTaste) return null
        val result = runCatching { tasteBackend.getTasteQuiz(language) }.getOrNull() as? ServerResult.Ok ?: return null
        val quiz = tasteQuizOf(result.value) ?: return null
        configRepository.saveTasteQuiz(language, result.value.toString())
        // The summary follows at once; an open quiz keeps the definition it opened with.
        _uiState.update { if (it.appLanguage == language) it.copy(tasteDefinition = quiz) else it }
        return quiz
    }

    private fun cachedTasteQuiz(language: String): TasteQuiz? =
        configRepository.getTasteQuiz(language)?.let { raw -> runCatching { tasteQuizOf(RecipeJson.parseToJsonElement(raw)) }.getOrNull() }

    /** Debug demo launches: opens the quiz on [json] (a bundled sample definition) at [step],
     *  with [draft] as unsaved answers, without asking the server. */
    fun showTasteQuizDemo(json: String, step: Int, draft: TasteProfile? = null) {
        val quiz = tasteQuizOf(RecipeJson.parseToJsonElement(json)) ?: return
        _uiState.update { it.copy(tasteDefinition = quiz, tasteQuiz = TasteQuizScreen.Ready(quiz, step.coerceIn(0, quiz.steps.lastIndex), draft)) }
    }

    /** Debug demo launches: the quiz's loading or error state, without asking the server. */
    fun showTasteQuizStateDemo(screen: TasteQuizScreen) {
        _uiState.update { it.copy(tasteQuiz = screen) }
    }

    /** Debug demo launches: the steps counted as seen. */
    fun showTasteSeenDemo(seen: List<String>) {
        configRepository.saveTasteSeen(seen)
        _uiState.update { it.copy(tasteSeen = seen) }
    }

    /** Debug demo launches: the row's summary from [json] without asking the server. */
    fun showTasteDefinitionDemo(json: String) {
        val quiz = tasteQuizOf(RecipeJson.parseToJsonElement(json)) ?: return
        _uiState.update { it.copy(tasteDefinition = quiz) }
    }

    fun restoreSaved(saved: SavedRecipe) {
        coroutineScope.launch { savedRecipeRepository.save(saved) }
    }

    /**
     * Settings' Clear all data: the saved recipes, the form and the taste profile go back to how a new install has
     * them. What was bought is not touched: the count lives on our server, and a purchase still
     * waiting to be confirmed must not be forgotten.
     */
    fun clearAll() {
        configRepository.save(RecipeConfig())
        configRepository.saveOptions(RecipeOptions())
        configRepository.saveTaste(TasteProfile())
        configRepository.saveTasteSeen(emptyList())
        _uiState.update {
            it.copy(config = RecipeConfig(), options = RecipeOptions(), taste = TasteProfile(), tasteQuiz = null, tasteSeen = emptyList(), mode = RecipeMode.RECIPE, courses = DEFAULT_MENU_COURSES, days = DEFAULT_MENU_DAYS, selection = RecipeSelection.Default)
                .withLanguageSupport()
        }
        coroutineScope.launch { savedRecipeRepository.clearAll() }
    }
}

/** The taste quiz screen's content ([RecipeStateHolder.openTasteQuiz]). */
sealed interface TasteQuizScreen {
    data object Loading : TasteQuizScreen

    /** "Couldn't load the questions", with Retry and Close. */
    data object Failed : TasteQuizScreen

    /** [quiz] stays as it opened until the quiz is closed; [startStep] is the first new step.
     *  [draft]: unsaved answers to start from, for debug demo launches only. */
    data class Ready(val quiz: TasteQuiz, val startStep: Int, val draft: TasteProfile? = null) : TasteQuizScreen
}

/** The courses a day a menu can have: 1 (the main course only) only with a server that says
 *  `acceptsMenuDays` ([RecipeUiState.menuCourseOptions]). */
val MENU_COURSE_OPTIONS: List<Int> = listOf(1, 2, 3)

/** What [RecipeStateHolder.showDemo] shows. Debug demo launches only. */
data class RecipeDemo(
    val paid: PaidUiState,
    val onDevice: Boolean = false,
    val shareSheetCopies: Boolean = true,
    val mode: RecipeMode = RecipeMode.RECIPE,
    val days: Int = DEFAULT_MENU_DAYS,
    val courses: Int = DEFAULT_MENU_COURSES
)
