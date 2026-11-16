package com.foodtracker.recipe

import com.foodtracker.recipe.TasteQuizFixture.AVOID
import com.foodtracker.recipe.TasteQuizFixture.CUISINES
import com.foodtracker.recipe.TasteQuizFixture.FAVOURITES
import com.foodtracker.recipe.TasteQuizFixture.FLAVOURS
import com.foodtracker.recipe.TasteQuizFixture.HEAT
import com.foodtracker.recipe.TasteQuizFixture.METHODS
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TasteProfileTest {

    private val quiz = TasteQuizFixture.quiz()
    private fun step(id: String) = requireNotNull(quiz.step(id))
    private fun TasteProfile.tap(stepId: String, vararg options: String, in_: TasteQuiz = quiz) =
        options.fold(this) { acc, option -> acc.toggled(in_, requireNotNull(in_.step(stepId)), option) }

    // --- Reading the definition -------------------------------------------------------------

    @Test
    fun aDefinitionReadsAsTheServerSentIt() {
        assertEquals("v1", quiz.version)
        assertEquals(listOf("cuisines", "heat", "flavours", "favourites", "avoid", "methods"), quiz.steps.map { it.id })
        val heat = step("heat")
        assertEquals(TasteStepKind.SINGLE, heat.kind)
        assertEquals(1, heat.max)
        assertEquals(TasteOption("medium", "Medium", "A gentle warmth", 2), heat.option("medium"))
        assertEquals(TasteTone.AVOID, step("avoid").tone)
        assertEquals("This is a preference, not an allergy setting.", step("avoid").note)
        assertTrue(step("favourites").grouped)
        assertFalse(step("cuisines").grouped)
        assertEquals(listOf("tomatoes", "mushrooms", "berries"), step("favourites").options.map { it.id })
        assertEquals(TasteChoice("favourites", "mushrooms") to TasteChoice("avoid", "mushrooms"), quiz.conflicts.first())
        assertEquals(listOf("cuisines", "flavours", "favourites", "methods"), quiz.summary)
    }

    @Test
    fun aStepThisVersionDoesntUnderstandIsSkippedNeverACrash() {
        val unknownKind = """{"id":"texture","kind":"slider","question":"How crunchy?","groups":[{"options":[{"id":"crisp","label":"Crisp"}]}]}"""
        val noOptions = """{"id":"empty","kind":"multi","question":"Nothing here?","max":3,"groups":[{"title":null,"options":[]}]}"""
        val brokenOptions = """{"id":"broken","kind":"multi","question":"Broken?","groups":[{"options":[{"id":"a"},{"label":"B"},7]}]}"""
        val seventh = """{"id":"drinks","kind":"multi","tone":"like","icon":"cup-soda","question":"What do you drink?","max":2,"future":{"x":1},
            "groups":[{"title":null,"options":[{"id":"tea","label":"Tea","colour":"green"},{"id":"coffee","label":"Coffee"}]}]}"""
        val unknownIcon = """{"id":"mood","kind":"single","tone":"like","icon":"rocket-ship","question":"Mood?","groups":[{"options":[{"id":"calm","label":"Calm","level":9}]}]}"""
        val read = TasteQuizFixture.quiz(TasteQuizFixture.json(TasteQuizFixture.ALL + listOf(unknownKind, noOptions, brokenOptions, seventh, unknownIcon, "42", CUISINES)))
        assertEquals(listOf("cuisines", "heat", "flavours", "favourites", "avoid", "methods", "drinks", "mood"), read.steps.map { it.id }, "a seventh step and more are fine; the rest skipped; a repeated id once")
        assertEquals("rocket-ship", read.step("mood")?.icon, "the app draws a neutral icon for it")
        assertNull(read.step("mood")?.option("calm")?.level, "a level outside 1 to 3 is no level")
        assertEquals(listOf("tea", "coffee"), read.step("drinks")?.options?.map { it.id })
    }

    @Test
    fun aToneThisVersionDoesntUnderstandSkipsTheStep() {
        val unknownTone = CUISINES.replace("\"tone\":\"like\"", "\"tone\":\"cheerful\"")
        val noTone = HEAT.replace("\"tone\":\"like\",", "")
        val numberTone = METHODS.replace("\"tone\":\"like\"", "\"tone\":3")
        val read = TasteQuizFixture.quiz(TasteQuizFixture.json(listOf(unknownTone, noTone, FLAVOURS, numberTone, AVOID)))
        assertEquals(listOf("flavours", "avoid"), read.steps.map { it.id })
    }

    @Test
    fun aMaxIsKeptWithinTheOptions() {
        val noMax = """{"id":"a","tone":"like","kind":"multi","question":"A?","groups":[{"options":[{"id":"x","label":"X"},{"id":"y","label":"Y"}]}]}"""
        val bigMax = """{"id":"b","tone":"like","kind":"multi","question":"B?","max":40,"groups":[{"options":[{"id":"x","label":"X"}]}]}"""
        val singleMax = """{"id":"c","tone":"like","kind":"single","question":"C?","max":5,"groups":[{"options":[{"id":"x","label":"X"},{"id":"y","label":"Y"}]}]}"""
        val read = TasteQuizFixture.quiz(TasteQuizFixture.json(listOf(noMax, bigMax, singleMax)))
        assertEquals(listOf(2, 1, 1), read.steps.map { it.max })
    }

    @Test
    fun nothingUsableIsNoDefinition() {
        assertNull(tasteQuizOf(null))
        assertNull(tasteQuizOf(RecipeJson.parseToJsonElement("[]")))
        assertNull(tasteQuizOf(RecipeJson.parseToJsonElement("""{"steps":[$CUISINES]}""")), "no version")
        assertNull(tasteQuizOf(TasteQuizFixture.element(TasteQuizFixture.json(steps = emptyList()))), "no step")
        val onlyUnknown = """{"id":"x","kind":"wheel","question":"?","groups":[]}"""
        assertNull(tasteQuizOf(TasteQuizFixture.element(TasteQuizFixture.json(steps = listOf(onlyUnknown)))), "no usable step")
    }

    @Test
    fun brokenConflictsAndSummaryAreIgnored() {
        val read = TasteQuizFixture.quiz(TasteQuizFixture.json(conflicts = """[["a:b"],["x","y:z"],["a:b","a:b"],"q",["favourites:mushrooms","avoid:mushrooms"]]""", summary = """[1,"cuisines",null]"""))
        assertEquals(listOf(TasteChoice("favourites", "mushrooms") to TasteChoice("avoid", "mushrooms")), read.conflicts)
        assertEquals(listOf("cuisines"), read.summary)
    }

    // --- Choosing -----------------------------------------------------------------------------

    @Test
    fun aTapChoosesAndASecondTapUnchooses() {
        val one = TasteProfile().tap("cuisines", "nordic")
        assertEquals(listOf("nordic"), one.selected("cuisines"))
        assertTrue(one.tap("cuisines", "nordic").isEmpty)
    }

    @Test
    fun choicesKeepTheDefinitionsOrder() {
        assertEquals(listOf("italian", "nordic", "thai"), TasteProfile().tap("cuisines", "thai", "italian", "nordic").selected("cuisines"))
    }

    @Test
    fun atTheMaxNothingIsReplacedButUntickingWorks() {
        val full = TasteProfile().tap("cuisines", "italian", "french", "nordic")
        assertSame(full, full.toggled(quiz, step("cuisines"), "thai"))
        assertFalse(full.canPick(step("cuisines"), "thai"))
        assertTrue(full.canPick(step("cuisines"), "italian"))
        assertTrue(full.tap("cuisines", "italian").canPick(step("cuisines"), "thai"))
    }

    @Test
    fun aSingleStepReplacesAndClearsOnASecondTap() {
        val mild = TasteProfile().tap("heat", "mild")
        assertTrue(mild.canPick(step("heat"), "hot"))
        assertEquals(listOf("hot"), mild.tap("heat", "hot").selected("heat"))
        assertTrue(mild.tap("heat", "mild").isEmpty)
    }

    @Test
    fun anOptionTheStepDoesntHaveChangesNothing() {
        val profile = TasteProfile().tap("cuisines", "italian")
        assertSame(profile, profile.toggled(quiz, step("cuisines"), "martian"))
        assertSame(profile, profile.toggled(quiz, step("favourites"), "olives"))
    }

    @Test
    fun aConflictMovesTheChoiceAndMarksIt() {
        val skipped = TasteProfile().tap("avoid", "mushrooms", "olives")
        assertEquals(TasteChoice("avoid", "mushrooms"), skipped.conflictOf(quiz, "favourites", "mushrooms"), "marked on the love step")
        val loved = skipped.tap("favourites", "mushrooms")
        assertEquals(listOf("mushrooms"), loved.selected("favourites"))
        assertEquals(listOf("olives"), loved.selected("avoid"))
        assertEquals(TasteChoice("favourites", "mushrooms"), loved.conflictOf(quiz, "avoid", "mushrooms"))

        val garlic = TasteProfile().tap("avoid", "garlic").tap("flavours", "garlic")
        assertEquals(listOf("garlic"), garlic.selected("flavours"))
        assertTrue(garlic.selected("avoid").isEmpty(), "garlic as a flavour and garlic to skip exclude each other")
        assertNull(TasteProfile().conflictOf(quiz, "favourites", "mushrooms"), "not chosen there: no mark")
    }

    @Test
    fun undoingAMoveKeepsWhatWasChosenSince() {
        val skipped = TasteProfile().tap("avoid", "mushrooms", "olives")
        val other = assertNotNull(skipped.conflictOf(quiz, "favourites", "mushrooms"))
        val moved = skipped.tap("favourites", "mushrooms")
        // Two more choices before Undo: one on this step, one after Next.
        val later = moved.tap("favourites", "berries").tap("methods", "oven")
        val undone = later.undoneMove(quiz, "favourites", "mushrooms", other)
        assertEquals(listOf("berries"), undone.selected("favourites"), "the later choice on this step stays")
        assertEquals(listOf("oven"), undone.selected("methods"), "and the one after Next")
        assertEquals(listOf("mushrooms", "olives"), undone.selected("avoid"), "back on the skip list, in its order")
    }

    @Test
    fun undoingAMoveLeavesItOffAListThatIsFullByThen() {
        val skipped = TasteProfile().tap("flavours", "garlic")
        val other = assertNotNull(skipped.conflictOf(quiz, "avoid", "garlic"))
        val moved = skipped.tap("avoid", "garlic").tap("flavours", "citrus")
        val smallFlavours = TasteQuizFixture.quiz(TasteQuizFixture.json(listOf(CUISINES, HEAT, FLAVOURS.replace("\"max\":6", "\"max\":1"), FAVOURITES, AVOID, METHODS)))
        val undone = moved.undoneMove(smallFlavours, "avoid", "garlic", other)
        assertTrue(undone.selected("avoid").isEmpty())
        assertEquals(listOf("citrus"), undone.selected("flavours"), "full by then: left off, nothing replaced")
    }

    @Test
    fun atTheMaxAConflictingChoiceStaysWhereItIs() {
        val full = TasteProfile().tap("favourites", "tomatoes", "berries").tap("avoid", "mushrooms")
        assertSame(full, full.toggled(quiz, step("favourites"), "mushrooms"))
    }

    // --- Normalizing against a definition (shown and sent) ------------------------------------

    @Test
    fun normalizedKeepsWhatTheDefinitionHasInItsOrderWithinItsMax() {
        val stored = TasteProfile(
            answers = mapOf(
                "cuisines" to listOf("thai", "martian", "italian", "thai", "nordic", "french"),
                "heat" to listOf("medium", "hot"),
                "gone" to listOf("x"),
                "favourites" to listOf("mushrooms", "tomatoes"),
                "avoid" to listOf("mushrooms")
            ),
            version = "v0"
        )
        val shown = stored.normalized(quiz)
        assertEquals(
            mapOf("cuisines" to listOf("italian", "french", "nordic"), "heat" to listOf("medium"), "favourites" to listOf("tomatoes"), "avoid" to listOf("mushrooms")),
            shown.answers
        )
        assertEquals("v1", shown.version)
    }

    @Test
    fun aConflictIsSettledBeforeALikeStepIsCutAsTheServerDoes() {
        // Three favourites over a max of 2, the first also on the skip list: it goes to the skip
        // list, and the other two both stay.
        val stored = TasteProfile(mapOf("favourites" to listOf("tomatoes", "mushrooms", "berries"), "avoid" to listOf("mushrooms")))
        assertEquals(mapOf("favourites" to listOf("tomatoes", "berries"), "avoid" to listOf("mushrooms")), stored.normalized(quiz).answers)
    }

    @Test
    fun aConflictBetweenTwoLikeStepsKeepsTheFirstOfThePair() {
        val both = TasteQuizFixture.quiz(TasteQuizFixture.json(conflicts = """[["cuisines:thai","flavours:smoky"]]"""))
        val stored = TasteProfile(mapOf("cuisines" to listOf("thai"), "flavours" to listOf("smoky")))
        assertEquals(mapOf("cuisines" to listOf("thai")), stored.normalized(both).answers)
    }

    // --- Section 4: the questions change under old answers ------------------------------------

    private val stored = TasteProfile(
        answers = mapOf(
            "cuisines" to listOf("italian", "nordic", "thai"),
            "heat" to listOf("medium"),
            "favourites" to listOf("mushrooms", "berries"),
            "avoid" to listOf("olives")
        ),
        version = "v1"
    )
    private val allSeen = listOf("cuisines", "heat", "flavours", "favourites", "avoid", "methods")

    private fun without(stepJson: String, option: String) = stepJson.replace(Regex("""\{"id":"$option","label":"[^"]*"[^}]*\},?"""), "").replace(",]", "]")

    @Test
    fun anOptionRemovedIsLeftOutQuietly() {
        val v2 = TasteQuizFixture.quiz(TasteQuizFixture.json(listOf(without(CUISINES, "thai"), HEAT, FLAVOURS, FAVOURITES, AVOID, METHODS), version = "v2"))
        assertEquals(listOf("italian", "nordic"), stored.normalized(v2).selected("cuisines"))
        assertEquals(mapOf("cuisines" to listOf("italian", "nordic"), "heat" to listOf("medium"), "favourites" to listOf("mushrooms", "berries"), "avoid" to listOf("olives")), wireTasteFor(stored, v2, true))
    }

    @Test
    fun aStepRemovedIsLeftOutQuietly() {
        val v2 = TasteQuizFixture.quiz(TasteQuizFixture.json(listOf(CUISINES, FLAVOURS, FAVOURITES, AVOID, METHODS), version = "v2"))
        assertNull(stored.normalized(v2).answers["heat"])
        assertEquals(TasteSummary(listOf("Italian", "Nordic"), null, 4), tasteSummary(stored, v2))
    }

    @Test
    fun anOptionHiddenForAWhileIsChosenAgainWhenItComesBack() {
        val hidden = TasteQuizFixture.quiz(TasteQuizFixture.json(listOf(without(CUISINES, "thai"), HEAT, FLAVOURS, FAVOURITES, AVOID, METHODS), version = "v2"))
        assertFalse("thai" in stored.normalized(hidden).selected("cuisines"))
        // Nothing was rewritten: the stored profile is the one it was.
        assertEquals(listOf("italian", "nordic", "thai"), stored.selected("cuisines"))
        val back = TasteQuizFixture.quiz(TasteQuizFixture.json(version = "v3"))
        assertEquals(listOf("italian", "nordic", "thai"), stored.normalized(back).selected("cuisines"))
    }

    @Test
    fun aLowerMaxCutsInTheDefinitionsOrder() {
        val lower = TasteQuizFixture.quiz(TasteQuizFixture.json(listOf(CUISINES.replace("\"max\":3", "\"max\":2"), HEAT, FLAVOURS, FAVOURITES, AVOID, METHODS)))
        assertEquals(listOf("italian", "nordic"), stored.normalized(lower).selected("cuisines"))
    }

    @Test
    fun aStepThatBecameSingleKeepsTheFirstAnswerInItsOrder() {
        val single = TasteQuizFixture.quiz(TasteQuizFixture.json(listOf(CUISINES.replace("\"kind\":\"multi\"", "\"kind\":\"single\""), HEAT, FLAVOURS, FAVOURITES, AVOID, METHODS)))
        assertEquals(listOf("italian"), stored.normalized(single).selected("cuisines"))
    }

    @Test
    fun aConflictAddedBetweenTwoStoredAnswersFavoursTheSkipList() {
        val v2 = TasteQuizFixture.quiz(TasteQuizFixture.json(conflicts = """[["avoid:olives","cuisines:thai"]]""", version = "v2"))
        // Olives on the skip list against Thai: avoid wins whichever side of the pair it is on.
        assertEquals(listOf("italian", "nordic"), stored.normalized(v2).selected("cuisines"))
        assertEquals(listOf("olives"), stored.normalized(v2).selected("avoid"))
    }

    private val drinks = """{"id":"drinks","kind":"multi","tone":"like","icon":"cup-soda","question":"What do you drink?","max":2,"groups":[{"title":null,"options":[{"id":"tea","label":"Tea"}]}]}"""

    @Test
    fun aNewStepIsNotedAndTheQuizOpensAtIt() {
        val v2 = TasteQuizFixture.quiz(TasteQuizFixture.json(listOf(CUISINES, drinks, HEAT, FLAVOURS, FAVOURITES, AVOID, METHODS), version = "v2"))
        assertEquals(listOf("drinks"), tasteNewSteps(allSeen, v2).map { it.id })
        assertEquals(1, firstTasteStep(allSeen, v2))
        assertEquals(0, firstTasteStep(allSeen, quiz), "nothing new: the first step")
        assertEquals(0, firstTasteStep(emptyList(), v2), "never opened: from the start")
        // Leaving after seeing it (saved or not) marks it seen.
        assertTrue(tasteNewSteps(tasteSeenAfter(allSeen, listOf("drinks")), v2).isEmpty())
        assertEquals(allSeen + "drinks", tasteSeenAfter(allSeen, listOf("cuisines", "drinks")), "once each")
    }

    // --- Section 5: the dot ---------------------------------------------------------------------

    @Test
    fun theDotOnlyWhereTheQuizOpens() {
        for (entry in TasteEntry.entries) {
            assertEquals(entry == TasteEntry.QUIZ, tasteHasNewQuestions(entry, emptyList(), quiz), "$entry")
        }
        assertFalse(tasteHasNewQuestions(TasteEntry.QUIZ, emptyList(), null), "no definition: nothing to tell")
    }

    @Test
    fun neverOpenedShowsTheDotUntilOpenedAndLeft() {
        assertTrue(tasteHasNewQuestions(TasteEntry.QUIZ, emptyList(), quiz), "every question is new")
        val leftAtStep2 = tasteSeenAfter(emptyList(), listOf("cuisines", "heat"))
        assertTrue(tasteHasNewQuestions(TasteEntry.QUIZ, leftAtStep2, quiz), "a step never reached stays new")
        val throughAll = tasteSeenAfter(leftAtStep2, quiz.steps.map { it.id })
        assertFalse(tasteHasNewQuestions(TasteEntry.QUIZ, throughAll, quiz))
    }

    @Test
    fun aStepAddedAfterSavingShowsTheDotAgain() {
        val v2 = TasteQuizFixture.quiz(TasteQuizFixture.json(TasteQuizFixture.ALL + drinks, version = "v2"))
        assertFalse(tasteHasNewQuestions(TasteEntry.QUIZ, allSeen, quiz))
        assertTrue(tasteHasNewQuestions(TasteEntry.QUIZ, allSeen, v2))
    }

    @Test
    fun theQuietFetchConditions() {
        val yes = shouldPrefetchTasteQuiz("v2", "v1", TasteEntry.QUIZ, hasAccount = true, alreadyFetched = false)
        assertTrue(yes, "a different version")
        assertTrue(shouldPrefetchTasteQuiz("v2", null, TasteEntry.QUIZ, true, false), "nothing cached")
        assertFalse(shouldPrefetchTasteQuiz("v1", "v1", TasteEntry.QUIZ, true, false), "the same version")
        assertFalse(shouldPrefetchTasteQuiz(null, null, TasteEntry.QUIZ, true, false), "a server that doesn't say")
        assertFalse(shouldPrefetchTasteQuiz("v2", "v1", TasteEntry.QUIZ, hasAccount = false, alreadyFetched = false), "no account: none created")
        assertFalse(shouldPrefetchTasteQuiz("v2", "v1", TasteEntry.PROMO, true, false), "never for the promo")
        assertFalse(shouldPrefetchTasteQuiz("v2", "v1", TasteEntry.HIDDEN, true, false))
        assertFalse(shouldPrefetchTasteQuiz("v2", "v1", TasteEntry.QUIZ, true, alreadyFetched = true), "once per version and language")
    }

    @Test
    fun everyStoredAnswerGoneReadsAsNoProfile() {
        val other = """{"id":"drinks","tone":"like","kind":"multi","question":"What do you drink?","groups":[{"options":[{"id":"tea","label":"Tea"}]}]}"""
        val v2 = TasteQuizFixture.quiz(TasteQuizFixture.json(listOf(other), version = "v2"))
        assertTrue(stored.normalized(v2).isEmpty)
        assertNull(tasteSummary(stored, v2))
        assertNull(wireTasteFor(stored, v2, true), "requests go without taste")
        assertFalse(stored.isEmpty, "but nothing was deleted")
    }

    @Test
    fun saveStoresTheFilteredAnswersAsEdited() {
        val v2 = TasteQuizFixture.quiz(TasteQuizFixture.json(listOf(without(CUISINES, "thai"), HEAT, FLAVOURS, FAVOURITES, AVOID, METHODS), version = "v2"))
        val edited = stored.normalized(v2).tap("methods", "oven", in_ = v2)
        val saved = savedTaste(edited, v2)
        assertEquals(listOf("italian", "nordic"), saved.selected("cuisines"), "what the definition no longer has is gone from then on")
        assertEquals(listOf("oven"), saved.selected("methods"))
        assertEquals("v2", saved.version)
        assertEquals(TasteProfile(), savedTaste(TasteProfile(), v2), "nothing chosen: no profile")
    }

    @Test
    fun theProfileRoundTripsAsJson() {
        val json = RecipeJson.encodeToString(TasteProfile.serializer(), stored)
        assertEquals(stored, RecipeJson.decodeFromString(TasteProfile.serializer(), json))
        assertEquals(TasteProfile(mapOf("a" to listOf("b"))), RecipeJson.decodeFromString(TasteProfile.serializer(), """{"answers":{"a":["b"]},"later":1}"""))
    }

    // --- What is sent -------------------------------------------------------------------------

    @Test
    fun sentOnlyToAServerThatTakesItAndNeverEmpty() {
        val profile = TasteProfile(mapOf("cuisines" to listOf("nordic", "italian")))
        assertEquals(mapOf("cuisines" to listOf("italian", "nordic")), wireTasteFor(profile, quiz, true))
        assertNull(wireTasteFor(profile, quiz, false))
        assertNull(wireTasteFor(TasteProfile(), quiz, true))
        assertNull(wireTasteFor(TasteProfile(mapOf("cuisines" to listOf("martian"))), quiz, true))
    }

    @Test
    fun withoutADefinitionTheAnswersGoAsStoredWithinTheContractsBounds() {
        val long = "x".repeat(41)
        val profile = TasteProfile((1..20).associate { "step$it" to (listOf("a", "a", " ", long) + (1..40).map { n -> "o$n" }) } + (long to listOf("a")))
        val sent = assertNotNull(wireTasteFor(profile, null, true))
        assertEquals(16, sent.size)
        assertTrue(sent.values.all { it.size == 32 && it.first() == "a" && it.distinct() == it && long !in it })
        assertFalse(long in sent)
    }

    @Test
    fun aRequestWithoutTasteHasNoTasteKey() {
        val request = GenerateRequest("r", WireKind.RECIPE, config = WireConfig(), ingredients = emptyList(), allergens = emptyList(), today = "2026-11-15")
        val encoded = RecipeJson.encodeToJsonElement(GenerateRequest.serializer(), request) as JsonObject
        assertFalse("taste" in encoded, "byte-identical to the request from before taste profiles")
        val withTaste = RecipeJson.encodeToJsonElement(GenerateRequest.serializer(), request.copy(taste = mapOf("heat" to listOf("hot")))) as JsonObject
        assertEquals("""{"heat":["hot"]}""", withTaste["taste"].toString())
    }

    // --- Gate and summary ---------------------------------------------------------------------

    @Test
    fun theGate() {
        assertEquals(TasteEntry.HIDDEN, tasteEntry(false, 5, true))
        assertEquals(TasteEntry.HIDDEN, tasteEntry(true, 5, true, serverTakesTaste = false), "the quiz can't be fetched")
        assertEquals(TasteEntry.QUIZ, tasteEntry(true, 1, false))
        assertEquals(TasteEntry.QUIZ, tasteEntry(true, null, true))
        assertEquals(TasteEntry.QUIZ, tasteEntry(true, -3, true))
        assertEquals(TasteEntry.PROMO, tasteEntry(true, null, false))
        assertEquals(TasteEntry.PROMO, tasteEntry(true, 0, false))
        assertEquals(TasteEntry.HIDDEN, tasteEntry(true, 0, false, purchasesBlocked = true), "See recipe packs would lead nowhere")
        assertEquals(TasteEntry.QUIZ, tasteEntry(true, 3, false, purchasesBlocked = true))
        assertEquals(TasteEntry.QUIZ, tasteEntry(true, 0, true, purchasesBlocked = true))
    }

    @Test
    fun theSummaryNamesTwoLabelsTheSingleChoiceAndCountsTheRest() {
        val profile = TasteProfile(mapOf("cuisines" to listOf("nordic", "italian", "thai"), "heat" to listOf("medium"), "flavours" to listOf("garlic"), "methods" to listOf("oven", "pan"), "avoid" to listOf("olives")))
        // "Italian, Nordic · Medium · 5 more": thai, garlic, two methods, olives.
        assertEquals(TasteSummary(listOf("Italian", "Nordic"), "Medium", 5), tasteSummary(profile, quiz))
    }

    @Test
    fun theSummaryTakesTheFirstAnsweredSummaryStep() {
        assertEquals(TasteSummary(listOf("Garlic", "Citrus"), null, 0), tasteSummary(TasteProfile(mapOf("flavours" to listOf("citrus", "garlic"))), quiz))
        assertEquals(TasteSummary(emptyList(), null, 2), tasteSummary(TasteProfile(mapOf("avoid" to listOf("olives", "garlic"))), quiz), "the skip list is only counted")
        assertEquals(TasteSummary(emptyList(), "Hot", 0), tasteSummary(TasteProfile(mapOf("heat" to listOf("hot"))), quiz))
    }

    @Test
    fun withoutADefinitionTheSummaryOnlyCounts() {
        assertEquals(TasteSummary(emptyList(), null, 3), tasteSummary(stored.copy(answers = mapOf("a" to listOf("x", "y"), "b" to listOf("z"))), null))
        assertNull(tasteSummary(TasteProfile(), null))
        assertNull(tasteSummary(TasteProfile(), quiz))
    }
}
