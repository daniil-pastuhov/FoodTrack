package com.foodtracker.recipe

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The picture and report rules of RecipePictures.kt; iOS checks the same cases in RecipeConfigCheck. */
class RecipePicturesTest {

    private val priced = RecipeCosts(recipeImage = 2, recipeImageSteps = 3)

    private fun choice(chosen: PictureLevel, balance: Int, costs: RecipeCosts = priced, takes: Boolean = true, primary: Boolean = true) =
        pictureChoice(chosen, costs, takes, balance, primary)

    @Test
    fun theRowStartsAtNoPicturesAndOffersEachPricedLevel() {
        val row = choice(PictureLevel.NONE, balance = 12)
        assertTrue(row.shown)
        assertEquals(PictureLevel.entries, row.levels)
        assertEquals(PictureLevel.NONE, row.sent)
        assertNull(row.short)
    }

    @Test
    fun aCoveredChoiceIsSentAsChosen() {
        assertEquals(PictureLevel.DISH, choice(PictureLevel.DISH, balance = 2).sent)
        assertEquals(PictureLevel.STEPS, choice(PictureLevel.STEPS, balance = 3).sent)
    }

    @Test
    fun aShortPackSendsTheHighestCoveredLevelAndSaysWhy() {
        val two = choice(PictureLevel.STEPS, balance = 2)
        assertEquals(PictureLevel.DISH, two.sent)
        assertEquals(PictureShort.DISH_ONLY, two.short)
        assertEquals(setOf(PictureLevel.NONE, PictureLevel.DISH), two.covered)
        // The choice is kept, so it comes back with more recipes.
        assertEquals(PictureLevel.STEPS, two.chosen)
        val one = choice(PictureLevel.STEPS, balance = 1)
        assertEquals(PictureLevel.NONE, one.sent)
        assertEquals(PictureShort.NONE_COVERED, one.short)
        assertEquals(PictureShort.NONE_COVERED, choice(PictureLevel.DISH, balance = 1).short)
        // No pictures chosen: nothing to say, however small the pack.
        assertNull(choice(PictureLevel.NONE, balance = 1).short)
    }

    @Test
    fun aLevelTheServerNoLongerPricesIsNotOfferedOrSent() {
        val dishOnly = RecipeCosts(recipeImage = 2)
        val row = choice(PictureLevel.STEPS, balance = 10, costs = dishOnly)
        assertEquals(listOf(PictureLevel.NONE, PictureLevel.DISH), row.levels)
        assertEquals(PictureLevel.DISH, row.sent)
        // Not a matter of the pack: no line.
        assertNull(row.short)
    }

    @Test
    fun theRowHidesWithoutAPriceAFlagOrTheServerAsThePrimary() {
        listOf(
            choice(PictureLevel.DISH, balance = 10, costs = RecipeCosts()),
            choice(PictureLevel.DISH, balance = 10, takes = false),
            choice(PictureLevel.DISH, balance = 10, primary = false)
        ).forEach { row ->
            assertFalse(row.shown)
            assertEquals(PictureLevel.NONE, row.sent)
        }
    }

    @Test
    fun thePictureCostsAreReadOnlyWhenWholeAndInOrder() {
        fun costs(vararg pairs: Pair<String, Any>) = recipeCostsOf(buildJsonObject {
            pairs.forEach { (key, value) -> if (value is Int) put(key, value) else put(key, value.toString()) }
        })!!
        assertEquals(RecipeCosts(recipeImage = 2, recipeImageSteps = 3), costs("recipe" to 1, "recipeImage" to 2, "recipeImageSteps" to 3))
        // Absent while pictures are off; never a made-up price.
        assertNull(costs("recipe" to 1).recipeImage)
        // Steps without the dish, a dish under a recipe, steps under the dish, strings: none.
        assertNull(costs("recipe" to 1, "recipeImageSteps" to 3).recipeImageSteps)
        assertNull(costs("recipe" to 3, "recipeImage" to 2).recipeImage)
        assertNull(costs("recipe" to 1, "recipeImage" to 3, "recipeImageSteps" to 2).recipeImageSteps)
        assertNull(costs("recipe" to 1, "recipeImage" to "2").recipeImage)
        assertNull(costs("recipe" to 1, "recipeImage" to 0).recipeImage)
    }

    @Test
    fun aRecipeWithPicturesCostsItsLevelsPrice() {
        assertEquals(1, priced.of(WireKind.RECIPE, null))
        assertEquals(2, priced.of(WireKind.RECIPE, null, image = PictureLevel.DISH))
        assertEquals(3, priced.of(WireKind.RECIPE, null, image = PictureLevel.STEPS))
        // A menu never has pictures.
        assertEquals(4, priced.of(WireKind.MENU, 2, image = PictureLevel.DISH))
    }

    @Test
    fun theRequestCarriesImageOnlyWhenSet() {
        val base = GenerateRequest("r", WireKind.RECIPE, config = WireConfig(), ingredients = emptyList(), allergens = emptyList(), today = "2026-11-17")
        assertFalse("image" in RecipeJson.encodeToString(GenerateRequest.serializer(), base))
        val dish = RecipeJson.encodeToString(GenerateRequest.serializer(), base.copy(image = PictureLevel.DISH))
        assertTrue("QimageQ:QdishQ".replace('Q', '"') in dish, dish)
        val steps = RecipeJson.encodeToString(GenerateRequest.serializer(), base.copy(image = PictureLevel.STEPS))
        assertTrue("QimageQ:QstepsQ".replace('Q', '"') in steps, steps)
        // Another level is another ask: a new request id.
        assertFalse(base.sameAskAs(base.copy(requestId = "s", image = PictureLevel.DISH)))
    }

    @Test
    fun theNoticeSaysOnlyWhatFailed() {
        assertNull(pictureNoticeOf(null, imageFailed = true, stepImagesFailed = true, dishKept = false))
        assertNull(pictureNoticeOf(PictureLevel.DISH, imageFailed = false, stepImagesFailed = false, dishKept = true))
        assertEquals(PictureNotice(dishFailed = true, stepsFailed = false), pictureNoticeOf(PictureLevel.DISH, imageFailed = true, stepImagesFailed = false, dishKept = false))
        // A picture that came but couldn't be kept counts as not made.
        assertEquals(PictureNotice(dishFailed = true, stepsFailed = false), pictureNoticeOf(PictureLevel.DISH, imageFailed = false, stepImagesFailed = false, dishKept = false))
        assertEquals(PictureNotice(dishFailed = false, stepsFailed = true), pictureNoticeOf(PictureLevel.STEPS, imageFailed = false, stepImagesFailed = true, dishKept = true))
        // A steps recipe whose steps were all plain: nothing to say.
        assertNull(pictureNoticeOf(PictureLevel.STEPS, imageFailed = false, stepImagesFailed = false, dishKept = true))
        // A dish request never talks about steps.
        assertNull(pictureNoticeOf(PictureLevel.DISH, imageFailed = false, stepImagesFailed = true, dishKept = true))
    }

    @Test
    fun stepPicturesKeepOnlyRealStepsOnceInOrder() {
        val bytes = PictureBytes(ByteArray(1), 768, 768)
        val pictures = ReplyPictures(steps = listOf(StepPictureBytes(4, bytes), StepPictureBytes(1, bytes), StepPictureBytes(1, bytes), StepPictureBytes(-1, bytes)))
        assertEquals(listOf(1), pictures.forSteps(stepCount = 3).map { it.step })
        assertFalse(bytes.toString().contains("[B"))
    }

    @Test
    fun oldSavedRecipesReadWithoutTheNewFields() {
        val old = """{"title":"Toast","steps":["Toast."],"source":"server"}"""
        val recipe = RecipeJson.decodeFromString(Recipe.serializer(), old)
        assertNull(recipe.requestId)
        assertNull(recipe.picture)
        assertTrue(recipe.stepPictures.isEmpty())
        val menu = RecipeJson.decodeFromString(Menu.serializer(), """{"days":[]}""")
        assertNull(menu.requestId)
        // And a new one round-trips.
        val kept = recipe.copy(requestId = "req1", picture = RecipePicture(RECIPE_PICTURE_DISH, 768, 768), stepPictures = listOf(RecipeStepPicture(0, RecipePicture(recipeStepPictureFile(0), 768, 576))))
        assertEquals(kept, RecipeJson.decodeFromString(Recipe.serializer(), RecipeJson.encodeToString(Recipe.serializer(), kept)))
    }

    @Test
    fun nothingLocalComesInFromAReplyOrAModel() {
        val sneaky = RecipeJson.decodeFromString(
            Recipe.serializer(),
            """{"title":"x","requestId":"other","picture":{"file":"../../etc","width":1,"height":1}}"""
        ).withoutLocalFields()
        assertNull(sneaky.requestId)
        assertNull(sneaky.picture)
    }

    @Test
    fun onlyTheAppsOwnPictureNamesAreOpened() {
        assertTrue(isRecipePictureFile(RECIPE_PICTURE_DISH))
        assertTrue(isRecipePictureFile(recipeStepPictureFile(3)))
        listOf("../dish.jpg", "dish.png", "step-.jpg", "step-123.jpg", "/etc/passwd", "step-1.jpg/..").forEach { assertFalse(isRecipePictureFile(it), it) }
    }

    @Test
    fun theFlagsAreReadAsTheOtherCapabilities() {
        assertTrue(acceptsRecipeImageOf(JsonPrimitive(true)))
        assertFalse(acceptsRecipeImageOf(JsonPrimitive("true")))
        assertFalse(acceptsRecipeImageOf(null))
        assertTrue(acceptsReportsOf(JsonPrimitive(true)))
        assertFalse(acceptsReportsOf(JsonPrimitive(false)))
    }

    // --- Report -------------------------------------------------------------------------------

    @Test
    fun thePictureReasonOnlyWithPictures() {
        assertEquals(listOf(ReportReason.OFFENSIVE, ReportReason.UNSAFE, ReportReason.OTHER), reportReasons(hasPictures = false))
        assertEquals(ReportReason.entries, reportReasons(hasPictures = true))
    }

    @Test
    fun theNoteIsCutAt500CodePointsNeverInsideOne() {
        val tomato = "🍅"
        val long = "a".repeat(499) + tomato + "b"
        val cut = cutReportNote(long)
        assertEquals(500, reportNoteLength(cut))
        assertTrue(cut.endsWith(tomato))
        assertEquals("note", reportNoteToSend("  note  "))
        assertNull(reportNoteToSend("   "))
        assertEquals("short", cutReportNote("short"))
    }

    @Test
    fun theReportFootFollowsTheRule() {
        val none = emptySet<String>()
        fun foot(source: RecipeSource = RecipeSource.SERVER, id: String? = "req", available: Boolean = true, account: Boolean = true, takes: Boolean = true, reported: Set<String> = none) =
            reportFoot(source, id, available, account, takes, reported)
        assertEquals(ReportFoot.AVAILABLE, foot())
        // Never on a phone-written recipe, nor a row saved before request ids were kept.
        assertEquals(ReportFoot.HIDDEN, foot(source = RecipeSource.DEVICE))
        assertEquals(ReportFoot.HIDDEN, foot(id = null))
        // No account is made for it; no paid route, no report; nor before the server says so.
        assertEquals(ReportFoot.HIDDEN, foot(account = false))
        assertEquals(ReportFoot.HIDDEN, foot(available = false))
        assertEquals(ReportFoot.HIDDEN, foot(takes = false))
        assertEquals(ReportFoot.REPORTED, foot(reported = setOf("req")))
        assertEquals(ReportFoot.HIDDEN, foot(source = RecipeSource.DEVICE, reported = setOf("req")))
    }
}
