package com.foodtracker.recipe

import com.foodtracker.model.AppSettings
import com.foodtracker.model.ExpiryStatus
import com.foodtracker.model.PantryItem
import com.foodtracker.viewmodel.PantryRow
import com.foodtracker.viewmodel.PantryUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeConfigRepository(var stored: RecipeConfig = RecipeConfig()) : RecipeConfigRepository {
    var saves = 0
    override fun get(): RecipeConfig = stored
    override fun save(config: RecipeConfig) {
        saves++
        stored = config
    }
}

private class FakeSavedRecipeRepository : SavedRecipeRepository {
    val rows = MutableStateFlow<List<SavedRecipe>>(emptyList())
    override fun getAllFlow(): Flow<List<SavedRecipe>> = rows.map { list -> list.sortedByDescending { it.createdAtEpochMillis } }
    override suspend fun save(recipe: SavedRecipe) {
        rows.value = rows.value.filterNot { it.id == recipe.id } + recipe
    }
    override suspend fun delete(id: String) {
        rows.value = rows.value.filterNot { it.id == id }
    }
    override suspend fun clearAll() {
        rows.value = emptyList()
    }
}

private class FakeOnDeviceModel(
    var availability: OnDeviceAvailability = OnDeviceAvailability.AVAILABLE,
    var languages: Set<String> = setOf("en")
) : OnDeviceRecipeModel {
    val prompts = mutableListOf<String>()
    val replies = ArrayDeque<() -> String>()
    var gate: CompletableDeferred<Unit>? = null
    /** One gate per call, awaited without cancelling: a model that doesn't stop when asked. */
    val stubbornGates = ArrayDeque<CompletableDeferred<Unit>>()
    var downloads = 0
    var availableAfterDownload = OnDeviceAvailability.AVAILABLE

    override suspend fun availability(): OnDeviceAvailability = availability
    override fun supportsLanguage(languageCode: String): Boolean = languageCode in languages
    override suspend fun download(): Boolean {
        downloads++
        availability = availableAfterDownload
        return availability == OnDeviceAvailability.AVAILABLE
    }
    override suspend fun generate(prompt: String): String {
        prompts += prompt
        gate?.await()
        stubbornGates.removeFirstOrNull()?.let { withContext(NonCancellable) { it.await() } }
        return replies.removeFirst().invoke()
    }
}

private const val GOOD_REPLY =
    """{"title":"Spinach toast","summary":"Quick.","minutes":10,"servings":2,"difficulty":"easy",""" +
        """"ingredients":[{"amount":"2","name":"Bread","fromPantry":true}],"steps":["Toast."]}"""

@OptIn(ExperimentalCoroutinesApi::class)
class RecipeStateHolderTest {

    private val today = LocalDate(2026, 11, 4)

    private fun row(id: String, days: Int): PantryRow {
        val item = PantryItem(
            id = id,
            name = id.replaceFirstChar { it.uppercase() },
            expiryDate = LocalDate.fromEpochDays(today.toEpochDays() + days),
            addedDate = today
        )
        return PantryRow(item, days, ExpiryStatus.compute(days, 3), null, id)
    }

    private val pantryRows = listOf(
        row("salmon", 3), row("bread", -1), row("spinach", 0), row("eggs", 15),
        row("yoghurt", 1), row("milk", 2), row("cheese", 22)
    )

    private val pantry = MutableStateFlow(
        PantryUiState(isLoading = false, allRows = pantryRows, settings = AppSettings(allergens = setOf("peanuts")))
    )
    private val config = FakeConfigRepository()
    private val saved = FakeSavedRecipeRepository()
    private val model = FakeOnDeviceModel()
    private var nextId = 0

    private fun TestScope.holder(
        scope: CoroutineScope = backgroundScope,
        appLanguage: String = "en"
    ) = RecipeStateHolder(
        pantry = pantry,
        onDeviceModel = model,
        savedRecipeRepository = saved,
        configRepository = config,
        appLanguage = { appLanguage },
        coroutineScope = scope,
        nowMillis = { 1_000L + nextId },
        newId = { "r${nextId++}" }
    )

    private fun runUnconfined(block: suspend TestScope.() -> Unit) = runTest(UnconfinedTestDispatcher()) { block() }

    @Test
    fun ticksTheThreeSoonestAndListsFiveUntilTheSelectionIsChanged() = runUnconfined {
        val state = holder().uiState.value

        assertEquals(listOf("bread", "spinach", "yoghurt", "milk", "salmon", "eggs", "cheese"), state.rows.map { it.id })
        assertEquals(setOf("bread", "spinach", "yoghurt"), state.selectedIds)
        assertEquals(listOf("bread", "spinach", "yoghurt", "milk", "salmon"), state.shownRows.map { it.id })
        assertEquals(2, state.otherCount)
        assertEquals(listOf("peanuts"), state.allergens)
    }

    @Test
    fun theDefaultFollowsThePantryButAChosenSelectionStays() = runUnconfined {
        val holder = holder()
        pantry.value = pantry.value.copy(allRows = pantryRows + row("cream", -3))
        assertEquals(setOf("cream", "bread", "spinach"), holder.uiState.value.selectedIds)

        holder.toggleItem("cheese")
        pantry.value = pantry.value.copy(allRows = pantryRows.filterNot { it.id == "bread" })

        val state = holder.uiState.value
        assertEquals(setOf("spinach", "cheese"), state.selectedIds, "a used-up item drops out, the rest stay")
        assertTrue(state.shownRows.any { it.id == "cheese" }, "a ticked item beyond the first five is listed")
    }

    @Test
    fun selectingItemsFromHomeTicksExactlyThoseEvenBeforeThePantryLoads() = runUnconfined {
        pantry.value = PantryUiState(isLoading = true)
        val holder = holder()
        holder.selectItems(listOf("eggs", "gone"))

        pantry.value = PantryUiState(isLoading = false, allRows = pantryRows)

        assertEquals(setOf("eggs"), holder.uiState.value.selectedIds)
        assertEquals(listOf("eggs"), holder.uiState.value.selectedRows.map { it.id })
    }

    @Test
    fun configChangesAreNormalisedAndKept() = runUnconfined {
        val holder = holder()
        holder.updateConfig { it.copy(servings = 99, mealType = RecipeMealType.DINNER) }

        assertEquals(12, holder.uiState.value.config.servings)
        assertEquals(RecipeConfig(servings = 12, mealType = RecipeMealType.DINNER), config.stored)

        holder.updateConfig { it.copy(servings = 12) }
        assertEquals(1, config.saves, "an unchanged config isn't written again")
    }

    @Test
    fun theStoredConfigIsTheFormsStartingPoint() = runUnconfined {
        config.stored = RecipeConfig(difficulty = RecipeDifficulty.MEDIUM, servings = 0)

        assertEquals(RecipeConfig(difficulty = RecipeDifficulty.MEDIUM, servings = 1), holder().uiState.value.config)
    }

    @Test
    fun onDeviceRouteFollowsTheModelAndTheLanguage() = runUnconfined {
        val holder = holder(appLanguage = "ru")
        assertEquals("ru", holder.uiState.value.language)
        assertEquals(OnDeviceRoute.HIDDEN, holder.uiState.value.onDeviceRoute, "the model doesn't know Russian")

        holder.updateConfig { it.copy(language = "en") }
        assertEquals(OnDeviceRoute.READY, holder.uiState.value.onDeviceRoute)

        model.availability = OnDeviceAvailability.UNSUPPORTED
        holder.refresh()
        assertEquals(OnDeviceRoute.HIDDEN, holder.uiState.value.onDeviceRoute)
    }

    @Test
    fun aModelThatIsMissingIsDownloadedAndThenReady() = runUnconfined {
        model.availability = OnDeviceAvailability.DOWNLOADABLE
        val holder = holder()

        assertEquals(1, model.downloads)
        assertEquals(OnDeviceRoute.READY, holder.uiState.value.onDeviceRoute)
    }

    @Test
    fun aDownloadStillRunningShowsPreparing() = runUnconfined {
        model.availability = OnDeviceAvailability.DOWNLOADING
        model.availableAfterDownload = OnDeviceAvailability.DOWNLOADING

        assertEquals(OnDeviceRoute.PREPARING, holder().uiState.value.onDeviceRoute)
        assertEquals(1, model.downloads, "a download already running is waited for")
    }

    @Test
    fun aDownloadAlreadyRunningIsWaitedForUntilReady() = runUnconfined {
        model.availability = OnDeviceAvailability.DOWNLOADING

        assertEquals(OnDeviceRoute.READY, holder().uiState.value.onDeviceRoute)
    }

    @Test
    fun aSecondTapWhileWritingStartsNothing() = runUnconfined {
        model.gate = CompletableDeferred()
        model.replies += { GOOD_REPLY }
        val holder = holder()

        holder.generateOnDevice()
        holder.generateOnDevice()

        assertEquals(1, model.prompts.size)
        model.gate?.complete(Unit)
        assertEquals(1, saved.rows.value.size)
    }

    @Test
    fun aStoppedRunThatFinishesAnywayDoesNotSpeakForTheNextOne() = runUnconfined {
        val first = CompletableDeferred<Unit>()
        model.stubbornGates += first
        model.stubbornGates += CompletableDeferred()
        model.replies += { GOOD_REPLY }
        model.replies += { GOOD_REPLY }
        val holder = holder()

        holder.generateOnDevice()
        holder.stopWriting()
        holder.generateOnDevice()
        first.complete(Unit)

        val state = holder.uiState.value
        assertTrue(state.isWriting, "the second run is still writing")
        assertNull(state.event)
    }

    @Test
    fun writingOnThePhoneSavesTheRecipeAndOpensIt() = runUnconfined {
        model.replies += { GOOD_REPLY }
        val holder = holder()
        assertTrue(holder.uiState.value.savedLoaded)

        holder.generateOnDevice()

        val state = holder.uiState.value
        assertFalse(state.isWriting)
        val event = assertIs<RecipeEvent.Written>(state.event)
        val recipe = saved.rows.value.single()
        assertEquals(event.savedId, recipe.id)
        assertEquals("Spinach toast", recipe.title)
        assertEquals(RecipeSource.DEVICE, recipe.source)
        assertEquals(recipe, holder.savedRecipe(event.savedId))
        val prompt = model.prompts.single()
        assertTrue(prompt.contains("- Bread") && prompt.contains("- Spinach") && prompt.contains("- Yoghurt"), prompt)
        assertFalse(prompt.contains("- Salmon"), "only ticked items: $prompt")
        assertTrue(prompt.contains("peanuts"), "allergens always apply: $prompt")

        holder.consumeEvent()
        assertNull(holder.uiState.value.event)
    }

    @Test
    fun aReplyThatIsNotARecipeIsTriedOnceMore() = runUnconfined {
        model.replies += { "Sure! Here's an idea." }
        model.replies += { GOOD_REPLY }
        val holder = holder()

        holder.generateOnDevice()

        assertIs<RecipeEvent.Written>(holder.uiState.value.event)
        assertEquals(2, model.prompts.size)
    }

    @Test
    fun twoBadRepliesOrAnErrorFailWithoutSaving() = runUnconfined {
        model.replies += { "nope" }
        model.replies += { "still nope" }
        model.replies += { error("AICore busy") }
        val holder = holder()

        holder.generateOnDevice()
        assertEquals(RecipeEvent.Failed, holder.uiState.value.event)
        holder.consumeEvent()
        holder.generateOnDevice()
        assertEquals(RecipeEvent.Failed, holder.uiState.value.event)

        assertTrue(saved.rows.value.isEmpty())
        assertFalse(holder.uiState.value.isWriting)
    }

    @Test
    fun stoppingLeavesNothingBehind() = runUnconfined {
        model.gate = CompletableDeferred()
        model.replies += { GOOD_REPLY }
        val holder = holder()

        holder.generateOnDevice()
        assertTrue(holder.uiState.value.isWriting)
        holder.stopWriting()
        model.gate?.complete(Unit)

        assertFalse(holder.uiState.value.isWriting)
        assertNull(holder.uiState.value.event)
        assertTrue(saved.rows.value.isEmpty())
    }

    @Test
    fun nothingIsWrittenWithNothingTickedOrNoModel() = runUnconfined {
        val holder = holder()
        holder.selectItems(emptyList())
        holder.generateOnDevice()

        holder.selectItems(listOf("milk"))
        model.availability = OnDeviceAvailability.UNSUPPORTED
        holder.refresh()
        holder.generateOnDevice()

        assertTrue(model.prompts.isEmpty())
        assertFalse(holder.uiState.value.isWriting)
    }

    @Test
    fun deletingASavedRecipeCanBeUndone() = runUnconfined {
        val recipe = SavedRecipe.of("a", Recipe(title = "A", steps = listOf("s")), 1L)
        saved.save(recipe)
        saved.save(SavedRecipe.of("b", Recipe(title = "B", steps = listOf("s")), 2L))
        val holder = holder()
        assertEquals(listOf("b", "a"), holder.uiState.value.saved.map { it.id }, "newest first")

        var removed: SavedRecipe? = null
        holder.deleteSaved("a") { removed = it }
        assertEquals(listOf("b"), holder.uiState.value.saved.map { it.id })
        assertEquals(recipe, removed)

        holder.restoreSaved(removed!!)
        assertEquals(listOf("b", "a"), holder.uiState.value.saved.map { it.id })

        holder.clearSaved()
        assertTrue(holder.uiState.value.saved.isEmpty())
    }

    @Test
    fun thePaidRouteIsNotOfferedWhileItIsNotThere() = runUnconfined {
        assertFalse(holder().uiState.value.paidRouteAvailable)
    }
}
