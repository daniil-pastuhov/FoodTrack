package com.foodtracker.recipe

import com.foodtracker.viewmodel.PantryRow
import com.foodtracker.viewmodel.PantryUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import kotlin.random.Random
import kotlin.time.Clock

/** How many of the soonest items the tab ticks by itself. */
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
    /** A recipe was written and saved; open it. */
    data class Written(val savedId: String) : RecipeEvent

    /** The phone's model failed or returned something that isn't a recipe. */
    data object Failed : RecipeEvent
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
    val isWriting: Boolean = false,
    /** Newest first. */
    val saved: List<SavedRecipe> = emptyList(),
    /** False until the saved list has been read once, so "nothing saved" doesn't flash. */
    val savedLoaded: Boolean = false,
    /** From app Settings, already cleaned; always applied. */
    val allergens: List<String> = emptyList(),
    /** Whether the server route can be offered. False until the paid step plugs it in. */
    val paidRouteAvailable: Boolean = false,
    val event: RecipeEvent? = null
) {
    /** The language recipes are written in. */
    val language: String = config.language ?: appLanguage

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

    val onDeviceRoute: OnDeviceRoute = when (onDeviceAvailability) {
        null -> OnDeviceRoute.CHECKING
        OnDeviceAvailability.UNSUPPORTED -> OnDeviceRoute.HIDDEN
        else -> when {
            !onDeviceSupportsLanguage -> OnDeviceRoute.HIDDEN
            onDeviceAvailability == OnDeviceAvailability.AVAILABLE -> OnDeviceRoute.READY
            else -> OnDeviceRoute.PREPARING
        }
    }
}

/**
 * The Recipe tab: which items to cook with, the form, the free routes (the phone's own model and
 * the shared prompt), and the recipes kept on this phone. Separate from `PantryStateHolder`,
 * which it only reads.
 *
 * The server route is a seam for a later step: [backend] and [credits] are taken now so it can
 * plug in, but with [NotAvailableRecipeBackend] the tab never offers it.
 *
 * iOS reimplements this in `RecipeViewModel.swift`; a change to its rules is ported there.
 */
class RecipeStateHolder(
    pantry: Flow<PantryUiState>,
    private val onDeviceModel: OnDeviceRecipeModel,
    private val savedRecipeRepository: SavedRecipeRepository,
    private val configRepository: RecipeConfigRepository,
    private val backend: RecipeBackend = NotAvailableRecipeBackend,
    private val credits: CreditsGateway = NotAvailableCreditsGateway,
    /** The app's current language, such as "en" or "nb-NO". Read again on [refresh], since the
     *  person can change the app's language while it runs. */
    private val appLanguage: () -> String = { "en" },
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val newId: () -> String = { "recipe_${Clock.System.now().toEpochMilliseconds()}_${Random.nextInt().toUInt()}" }
) {
    private val _uiState = MutableStateFlow(
        configRepository.get().normalized().let { config ->
            RecipeUiState(
                config = config,
                appLanguage = appRecipeLanguage(appLanguage()),
                paidRouteAvailable = backend.isAvailable && credits.canBuy
            ).withLanguageSupport()
        }
    )
    val uiState: StateFlow<RecipeUiState> = _uiState.asStateFlow()

    private var writingJob: Job? = null
    private val downloadLock = Mutex()

    init {
        coroutineScope.launch {
            pantry
                .map { Triple(it.isLoading, it.allRows, it.settings.allergens) }
                .distinctUntilChanged()
                .collect { (loading, rows, allergens) ->
                    val sorted = rows.sortedWith(compareBy<PantryRow> { it.daysRemaining }.thenBy { it.sortName })
                    _uiState.update {
                        it.copy(isLoading = loading, rows = sorted, allergens = cleanAllergens(allergens))
                    }
                }
        }
        coroutineScope.launch {
            savedRecipeRepository.getAllFlow().collect { saved -> _uiState.update { it.copy(saved = saved, savedLoaded = true) } }
        }
        refresh()
    }

    private fun RecipeUiState.withLanguageSupport(): RecipeUiState =
        copy(onDeviceSupportsLanguage = onDeviceModel.supportsLanguage(language))

    /**
     * Asks the phone again whether its model is there, and picks up a change of app language.
     * Called when the tab opens. Starts the model's download when it is needed, so it can be
     * ready by the next visit.
     */
    fun refresh() {
        _uiState.update { it.copy(appLanguage = appRecipeLanguage(appLanguage())).withLanguageSupport() }
        coroutineScope.launch {
            val availability = runCatching { onDeviceModel.availability() }.getOrDefault(OnDeviceAvailability.UNSUPPORTED)
            _uiState.update { it.copy(onDeviceAvailability = availability) }
            val needsDownload = availability == OnDeviceAvailability.DOWNLOADABLE ||
                availability == OnDeviceAvailability.DOWNLOADING
            // tryLock, so a second refresh while the first download runs doesn't start another.
            if (needsDownload && downloadLock.tryLock()) {
                try {
                    runCatching { onDeviceModel.download() }
                    val after = runCatching { onDeviceModel.availability() }.getOrDefault(OnDeviceAvailability.UNSUPPORTED)
                    _uiState.update { it.copy(onDeviceAvailability = after) }
                } finally {
                    downloadLock.unlock()
                }
            }
        }
    }

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
        _uiState.update { it.copy(config = updated).withLanguageSupport() }
    }

    // --- On-device route ---------------------------------------------------------------------

    /** Writes a recipe with the phone's own model from the ticked items. Saved when it arrives,
     *  then [RecipeEvent.Written]; [RecipeEvent.Failed] when the model fails twice over. */
    fun generateOnDevice() {
        val state = _uiState.value
        if (state.isWriting || state.selectedRows.isEmpty() || state.onDeviceRoute != OnDeviceRoute.READY) return
        val items = state.selectedRows.map { RecipePromptItem(it.item.name, it.daysRemaining) }
        val prompt = buildOnDeviceRecipePrompt(items, state.config, state.language, state.allergens)
        val names = items.map { it.name }
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
                val saved = SavedRecipe.of(newId(), recipe, nowMillis())
                savedRecipeRepository.save(saved)
                report(RecipeEvent.Written(saved.id), saved)
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

    // --- Saved recipes -----------------------------------------------------------------------

    fun savedRecipe(id: String): SavedRecipe? = _uiState.value.saved.firstOrNull { it.id == id }

    /** Deletes a saved recipe and hands it to [onDeleted], so the caller can offer Undo through
     *  [restoreSaved]. */
    fun deleteSaved(id: String, onDeleted: (SavedRecipe) -> Unit = {}) {
        val saved = savedRecipe(id) ?: return
        coroutineScope.launch {
            savedRecipeRepository.delete(id)
            onDeleted(saved)
        }
    }

    fun restoreSaved(saved: SavedRecipe) {
        coroutineScope.launch { savedRecipeRepository.save(saved) }
    }

    /** Settings' Clear all data. */
    fun clearSaved() {
        coroutineScope.launch { savedRecipeRepository.clearAll() }
    }
}
