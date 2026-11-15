package com.foodtracker.android.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.foodtracker.recipe.Menu
import com.foodtracker.recipe.MenuCourse
import com.foodtracker.recipe.MenuCourseKind
import com.foodtracker.recipe.MenuDay
import com.foodtracker.recipe.Recipe
import com.foodtracker.recipe.RecipeDifficulty
import com.foodtracker.recipe.RecipeIngredient
import com.foodtracker.recipe.RecipeSource
import com.foodtracker.recipe.SavedRecipe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SavedRecipeDaoTest {

    private lateinit var db: FoodTrackerDatabase
    private lateinit var repo: RoomSavedRecipeRepository

    private val recipe = Recipe(
        title = "Spinach toast",
        summary = "Quick.",
        minutes = 10,
        servings = 2,
        difficulty = RecipeDifficulty.MEDIUM,
        ingredients = listOf(RecipeIngredient("4 slices", "Bread", fromPantry = true), RecipeIngredient("", "Salt")),
        steps = listOf("Toast.", "Top."),
        tip = "Day-old bread toasts better.",
        source = RecipeSource.DEVICE
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            FoodTrackerDatabase::class.java
        ).allowMainThreadQueries().build()
        repo = RoomSavedRecipeRepository(db.savedRecipeDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun aRecipeRoundTripsWithEveryField() = runTest {
        val saved = SavedRecipe.of("r1", recipe, 1_000L)
        repo.save(saved)

        assertEquals(listOf(saved), repo.getAllFlow().first())
        val row = db.savedRecipeDao().getById("r1")!!
        assertEquals("recipe", row.kind)
        assertEquals("device", row.source)
        assertEquals("Spinach toast", row.title)
    }

    @Test
    fun aMenuIsOneRow() = runTest {
        val menu = Menu(
            listOf(
                MenuDay(
                    date = LocalDate(2026, 11, 4),
                    uses = listOf("Bread"),
                    courses = listOf(
                        MenuCourse(MenuCourseKind.MAIN, recipe.copy(source = RecipeSource.SERVER)),
                        MenuCourse(MenuCourseKind.DESSERT, recipe.copy(title = "Yoghurt pot", source = RecipeSource.SERVER))
                    )
                )
            )
        )
        val saved = SavedRecipe.of("m1", "Menu for three days", menu, 2_000L)
        repo.save(saved)

        val back = repo.getAllFlow().first().single()
        assertEquals(saved, back)
        assertEquals(2, back.dishCount)
        assertEquals("menu", db.savedRecipeDao().getById("m1")!!.kind)
    }

    @Test
    fun newestFirstAndDeleteAndClear() = runTest {
        repo.save(SavedRecipe.of("old", recipe.copy(title = "Old"), 1L))
        repo.save(SavedRecipe.of("new", recipe.copy(title = "New"), 2L))
        assertEquals(listOf("new", "old"), repo.getAllFlow().first().map { it.id })

        repo.delete("new")
        assertEquals(listOf("old"), repo.getAllFlow().first().map { it.id })

        repo.clearAll()
        assertTrue(repo.getAllFlow().first().isEmpty())
    }

    @Test
    fun aRowThatCantBeReadIsSkippedNotFatal() = runTest {
        repo.save(SavedRecipe.of("good", recipe, 1L))
        db.savedRecipeDao().upsert(
            SavedRecipeEntity(id = "bad", kind = "recipe", title = "?", payload = "{broken", createdAtEpochMillis = 2L, source = "device")
        )
        db.savedRecipeDao().upsert(
            SavedRecipeEntity(id = "future", kind = "shoppingPlan", title = "?", payload = "{}", createdAtEpochMillis = 3L, source = "server")
        )

        assertEquals(listOf("good"), repo.getAllFlow().first().map { it.id })
    }

    @Test
    fun anUnknownSourceReadsAsWrittenOnThePhone() = runTest {
        db.savedRecipeDao().upsert(SavedRecipe.of("r", recipe, 1L).toEntity().copy(source = "elsewhere"))

        assertEquals(RecipeSource.DEVICE, repo.getAllFlow().first().single().source)
    }
}
