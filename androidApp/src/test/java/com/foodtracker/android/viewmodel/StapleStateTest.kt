package com.foodtracker.android.viewmodel

import com.foodtracker.android.perf.FakeCategoryPreferenceRepository
import com.foodtracker.android.perf.FakeLocalProductRepository
import com.foodtracker.android.perf.FakePantryRepository
import com.foodtracker.android.perf.FakeProductNameRepository
import com.foodtracker.android.perf.FakeSettingsRepository
import com.foodtracker.android.perf.FakeShoppingListRepository
import com.foodtracker.android.perf.FakeShoppingSectionRepository
import com.foodtracker.android.perf.FakeStapleRepository
import com.foodtracker.model.Staple
import com.foodtracker.staples.MAX_STAPLES
import com.foodtracker.viewmodel.PantryStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

/** PantryStateHolder's staples: Undo never makes a second row of one staple, nor a 21st. */
class StapleStateTest {

    private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
    private val staples = FakeStapleRepository()
    private val holder = PantryStateHolder(
        repository = FakePantryRepository(),
        settingsRepository = FakeSettingsRepository(),
        shoppingListRepository = FakeShoppingListRepository(),
        productNameRepository = FakeProductNameRepository(),
        localProductRepository = FakeLocalProductRepository(),
        categoryPreferenceRepository = FakeCategoryPreferenceRepository(),
        shoppingSectionRepository = FakeShoppingSectionRepository(),
        stapleRepository = staples,
        uncategorizedLabel = "Other",
        coroutineScope = scope,
        computationContext = EmptyCoroutineContext
    )

    @After
    fun tearDown() = scope.cancel()

    private fun removed(id: String): Staple {
        var out: Staple? = null
        holder.removeStaple(id) { out = it }
        return requireNotNull(out)
    }

    @Test
    fun undoAfterTheSamePresetWasTickedAgainDoesNothing() {
        holder.addStaple("Salt", "salt")
        val salt = removed(holder.uiState.value.staples.single().id)
        holder.addStaple("Salt", "salt")
        holder.restoreStaple(salt)
        assertEquals(1, holder.uiState.value.staples.size)
        assertEquals(1, staples.staplesFlow.value.size)
    }

    @Test
    fun undoAtTwentyDoesNothing() {
        repeat(MAX_STAPLES) { holder.addStaple("Own $it") }
        val first = removed(holder.uiState.value.staples.first().id)
        holder.addStaple("Another")
        holder.restoreStaple(first)
        assertEquals(MAX_STAPLES, holder.uiState.value.staples.size)
        assertEquals(MAX_STAPLES, staples.staplesFlow.value.size)
    }

    @Test
    fun undoPutsAStapleBackWhenThereIsRoomAndNoTwin() {
        holder.addStaple("Smoked paprika")
        val paprika = removed(holder.uiState.value.staples.single().id)
        holder.restoreStaple(paprika)
        assertEquals(listOf(paprika), holder.uiState.value.staples)
    }

    @Test
    fun anOwnNameIsCutAtFortyCodePoints() {
        holder.addStaple("𠮷".repeat(45))
        assertEquals("𠮷".repeat(40), holder.uiState.value.staples.single().name)
    }
}
