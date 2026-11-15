package com.foodtracker.android.ui.screens

import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodtracker.android.R
import com.foodtracker.android.ui.design.LarderButton
import com.foodtracker.android.ui.design.LarderButtonStyle
import com.foodtracker.android.ui.design.LarderDialog
import com.foodtracker.android.ui.design.LarderDivider
import com.foodtracker.android.ui.design.LarderGroup
import com.foodtracker.android.ui.design.LarderIcon
import com.foodtracker.android.ui.design.LarderIconButton
import com.foodtracker.android.ui.design.LarderIcons
import com.foodtracker.android.ui.design.LarderSheet
import com.foodtracker.android.ui.design.LarderSpinner
import com.foodtracker.android.ui.design.ScreenError
import com.foodtracker.android.ui.design.focusRing
import com.foodtracker.android.ui.navigation.LocalLarderSnackbars
import com.foodtracker.android.ui.theme.LarderRadius
import com.foodtracker.android.ui.theme.LarderSpace
import com.foodtracker.android.ui.theme.LarderTheme
import com.foodtracker.recipe.RecipeJson
import com.foodtracker.recipe.RecipeStateHolder
import com.foodtracker.recipe.TasteEntry
import com.foodtracker.recipe.TasteProfile
import com.foodtracker.recipe.TasteQuiz
import com.foodtracker.recipe.TasteQuizScreen
import com.foodtracker.recipe.TasteStep
import com.foodtracker.recipe.TasteStepKind
import com.foodtracker.recipe.TasteSummary
import com.foodtracker.recipe.TasteTone
import com.foodtracker.recipe.canPick
import com.foodtracker.recipe.conflictOf
import com.foodtracker.recipe.tasteNewSteps
import com.foodtracker.recipe.savedTaste
import com.foodtracker.recipe.tasteQuizOf
import com.foodtracker.recipe.toggled
import com.foodtracker.recipe.undoneMove

/*
 * The taste profile (docs/design/src/pages/taste.html): the row in Recipe settings, the promo sheet
 * for someone without recipes in a pack, and the quiz, drawn from the server's definition
 * (TasteQuiz). The app bundles no steps, options or their texts; only the chrome below.
 */

// --- Recipe form ------------------------------------------------------------------------------

/** The quiet line under the server's action while a taste profile goes with its requests
 *  ([com.foodtracker.recipe.RecipeUiState.sendsTaste]); never under the free routes. */
@Composable
internal fun TasteProfileHint() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s1, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LarderIcon(LarderIcons.Heart, contentDescription = null, size = 16.dp, tint = MaterialTheme.colorScheme.primary)
        Text(
            text = stringResource(R.string.taste_uses_profile),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// --- Recipe settings row ----------------------------------------------------------------------

/** The row's summary: "Italian, Nordic · Medium · 5 more", or "6 answers" without the labels
 *  of a cached definition in the app's language; null for no profile. */
internal fun tasteSummaryText(resources: Resources, summary: TasteSummary?): String? {
    summary ?: return null
    if (summary.named.isEmpty() && summary.single == null) {
        return resources.getQuantityString(R.plurals.taste_answers, summary.more, summary.more)
    }
    val parts = buildList {
        if (summary.named.isNotEmpty()) add(summary.named.joinToString(", "))
        summary.single?.let(::add)
        if (summary.more > 0) add(resources.getString(R.string.taste_summary_more, summary.more))
    }
    return parts.joinToString(" · ")
}

/**
 * "Taste profile", on its own between Language and the packs card, only where [entry] isn't
 * hidden. Its line is the summary, cut to fit, then "· N new questions" in the accent, which is
 * never cut; or "For recipes from packs" without a profile.
 */
@Composable
internal fun TasteProfileRow(summary: String?, newQuestions: Int, dot: Boolean = false, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    LarderGroup {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .heightIn(min = 56.dp)
                .padding(horizontal = LarderSpace.s4, vertical = LarderSpace.s2),
            horizontalArrangement = Arrangement.spacedBy(LarderSpace.s3),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LarderIcon(LarderIcons.Heart, contentDescription = null, tint = scheme.onSurfaceVariant)
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.taste_title), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                val line = summary ?: stringResource(R.string.taste_settings_empty)
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = if (newQuestions > 0) 1 else 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (newQuestions > 0) {
                    Text(
                        text = "· " + pluralStringResource(R.plurals.taste_new_questions, newQuestions, newQuestions),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.primary
                    )
                }
            }
            // Without a profile, the same dot as on the header's settings button: questions not
            // yet been through. With one, "· N new questions" says it instead.
            if (dot) {
                val badge = stringResource(R.string.taste_settings_badge)
                TasteDot(Modifier.semantics { contentDescription = badge })
            }
            LarderIcon(LarderIcons.ChevronRight, contentDescription = null, tint = scheme.onSurfaceVariant)
        }
    }
}

/**
 * The smallest badge: a 10dp dot in the primary colour with a 2dp ring in the background colour,
 * for new taste profile questions (the header's settings button, the settings row). Said by the
 * label of what it sits on.
 */
@Composable
internal fun TasteDot(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(14.dp)
            .background(MaterialTheme.colorScheme.background, CircleShape)
            .padding(2.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
    )
}

/** The settings row and what tapping it opens: the quiz ([onOpenQuiz]) or the promo sheet. */
@Composable
internal fun TasteProfileEntry(stateHolder: RecipeStateHolder, onOpenQuiz: () -> Unit) {
    val state by stateHolder.uiState.collectAsStateWithLifecycle()
    val resources = LocalContext.current.resources
    var promo by rememberSaveable { mutableStateOf(TasteDemo.consumePromo()) }
    if (state.tasteEntry == TasteEntry.HIDDEN && !promo) return
    if (state.tasteEntry != TasteEntry.HIDDEN) {
        TasteProfileRow(tasteSummaryText(resources, state.tasteSummary), state.tasteNewQuestions, dot = state.tasteRowDot, onClick = {
            when (state.tasteEntry) {
                TasteEntry.QUIZ -> {
                    stateHolder.openTasteQuiz()
                    onOpenQuiz()
                }
                TasteEntry.PROMO -> promo = true
                TasteEntry.HIDDEN -> Unit
            }
        })
    }
    if (promo) {
        TastePromoSheet(
            onPacks = {
                promo = false
                stateHolder.openPacks()
            },
            onDismiss = { promo = false }
        )
    }
}

/** Debug demo launches: opens the promo sheet once, from MainActivity's `demoRoute tastePromo`. */
internal object TasteDemo {
    @Volatile var promo: Boolean = false

    fun consumePromo(): Boolean = promo.also { promo = false }
}

// --- Promo sheet ------------------------------------------------------------------------------

/** For someone with no recipes in a pack and no profile: what it does, See recipe packs (the Buy
 *  sheet) and Cancel. Showing it calls nothing and creates no account. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TastePromoSheet(onPacks: () -> Unit, onDismiss: () -> Unit) {
    LarderSheet(onDismissRequest = onDismiss) {
        TastePromoBody(onPacks, onDismiss)
    }
}

@Composable
internal fun TastePromoBody(onPacks: () -> Unit, onDismiss: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(LarderSpace.s2)) {
        TasteEmblem(LarderIcons.Heart)
        Text(
            text = stringResource(R.string.taste_title),
            modifier = Modifier.padding(top = LarderSpace.s1).semantics { heading() },
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            text = stringResource(R.string.taste_promo_lede),
            modifier = Modifier.padding(bottom = LarderSpace.s2),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LarderGroup {
            OnboardingRow(LarderIcons.ListChecks, stringResource(R.string.taste_promo_questions_title), stringResource(R.string.taste_promo_questions_body))
            LarderDivider()
            OnboardingRow(LarderIcons.Sparkles, stringResource(R.string.taste_promo_taste_title), stringResource(R.string.taste_promo_taste_body))
            LarderDivider()
            OnboardingRow(LarderIcons.Smartphone, stringResource(R.string.taste_promo_device_title), stringResource(R.string.taste_promo_device_body))
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = LarderSpace.s2),
            verticalArrangement = Arrangement.spacedBy(LarderSpace.s1)
        ) {
            LarderButton(text = stringResource(R.string.recipe_intro_packs_action), onClick = onPacks, modifier = Modifier.fillMaxWidth())
            LarderButton(text = stringResource(R.string.recipe_cancel), onClick = onDismiss, modifier = Modifier.fillMaxWidth(), style = LarderButtonStyle.Text)
        }
    }
}

@Composable
private fun TasteEmblem(icon: LarderIcons) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(LarderRadius.lg)),
        contentAlignment = Alignment.Center
    ) {
        LarderIcon(icon, contentDescription = null, size = 28.dp, tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/** A step's icon by its Lucide name ("heart-off"), or the neutral one for a name this build
 *  doesn't bundle. */
internal fun tasteStepIcon(name: String?): LarderIcons {
    val pascal = name.orEmpty().split('-').joinToString("") { part -> part.replaceFirstChar { it.uppercase() } }
    return LarderIcons.entries.firstOrNull { it.name == pascal } ?: LarderIcons.Utensils
}

// --- The quiz ---------------------------------------------------------------------------------

private val TasteProfileSaver: Saver<TasteProfile, String> = Saver(
    save = { RecipeJson.encodeToString(TasteProfile.serializer(), it) },
    restore = { RecipeJson.decodeFromString(TasteProfile.serializer(), it) }
)

/**
 * The quiz, its own full screen: loading the definition, the error with Retry and Close, or the
 * steps. Leaving keeps nothing unless saved; leaving with changes asks first.
 */
@Composable
fun TasteProfileScreen(stateHolder: RecipeStateHolder, onClose: () -> Unit) {
    val state by stateHolder.uiState.collectAsStateWithLifecycle()
    val close = {
        stateHolder.closeTasteQuiz()
        onClose()
    }
    when (val screen = state.tasteQuiz) {
        null, TasteQuizScreen.Loading -> {
            BackHandler(onBack = close)
            TasteQuizFrame(onClose = close) { TasteQuizLoading() }
        }
        TasteQuizScreen.Failed -> {
            BackHandler(onBack = close)
            TasteQuizFrame(onClose = close) { TasteQuizError(onRetry = stateHolder::retryTasteQuiz, onClose = close) }
        }
        is TasteQuizScreen.Ready -> TasteQuizReady(stateHolder, screen, state.taste, state.tasteSeen, onClose)
    }
}

/** The quiz's frame before the steps are known: the close button and the title only. */
@Composable
private fun TasteQuizFrame(onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        TasteTopBar(onClose = onClose, onSave = null)
        content()
    }
}

@Composable
private fun ColumnScope.TasteQuizLoading() {
    Column(
        modifier = Modifier.fillMaxWidth().weight(1f).padding(LarderSpace.s6),
        verticalArrangement = Arrangement.spacedBy(LarderSpace.s3, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LarderSpinner()
        Text(
            text = stringResource(R.string.taste_loading),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ColumnScope.TasteQuizError(onRetry: () -> Unit, onClose: () -> Unit) {
    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        ScreenError(
            icon = LarderIcons.WifiOff,
            title = stringResource(R.string.taste_error_title),
            message = stringResource(R.string.taste_error_body),
            modifier = Modifier.widthIn(max = 400.dp)
        ) {
            LarderButton(text = stringResource(R.string.taste_retry), onClick = onRetry, modifier = Modifier.fillMaxWidth())
            LarderButton(text = stringResource(R.string.taste_close), onClick = onClose, modifier = Modifier.fillMaxWidth(), style = LarderButtonStyle.Text)
        }
    }
}

@Composable
private fun TasteTopBar(onClose: () -> Unit, onSave: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = LarderSpace.s1, end = LarderSpace.s2),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LarderIconButton(LarderIcons.X, stringResource(R.string.taste_close), onClose, tint = MaterialTheme.colorScheme.onSurface)
        Text(
            text = stringResource(R.string.taste_title),
            modifier = Modifier.weight(1f).padding(start = LarderSpace.s1).semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (onSave != null) {
            LarderButton(text = stringResource(R.string.taste_save), onClick = onSave, style = LarderButtonStyle.Text)
        }
    }
}

/** The steps, with what the person has chosen so far kept here until Save. */
@Composable
private fun TasteQuizReady(stateHolder: RecipeStateHolder, screen: TasteQuizScreen.Ready, stored: TasteProfile, seen: List<String>, onClose: () -> Unit) {
    val quiz = screen.quiz
    val snackbars = LocalLarderSnackbars.current
    val resources = LocalContext.current.resources
    // As stored when the quiz opened, and as shown from it: filtered through this definition.
    val saved = remember(quiz) { stored }
    val shown = remember(quiz) { saved.normalized(quiz) }
    // Tagged "New question" only for someone who has been through the quiz before: to someone
    // opening it for the first time every step is new, which is no news.
    val newSteps = remember(quiz) { if (seen.isEmpty()) emptySet() else tasteNewSteps(seen, quiz).map { it.id }.toSet() }
    val editing = !shown.isEmpty
    var draft by rememberSaveable(quiz.version, stateSaver = TasteProfileSaver) { mutableStateOf(screen.draft ?: shown) }
    var index by rememberSaveable(quiz.version) { mutableIntStateOf(screen.startStep.coerceIn(0, quiz.steps.lastIndex)) }
    var visited by rememberSaveable(quiz.version) { mutableStateOf(listOf<String>()) }
    var askLeave by rememberSaveable { mutableStateOf(false) }
    val step = quiz.steps[index]
    LaunchedEffect(step.id) { if (step.id !in visited) visited = visited + step.id }

    val changed = draft.normalized(quiz) != shown
    fun leave() {
        stateHolder.closeTasteQuiz(visited)
        onClose()
    }
    fun requestLeave() = if (changed) askLeave = true else leave()
    fun save() {
        val next = savedTaste(draft, quiz)
        val shownSteps = visited + step.id
        if (next.isEmpty) {
            if (!saved.isEmpty) {
                stateHolder.clearTaste()
                snackbars.show(resources.getString(R.string.taste_cleared_snackbar), resources.getString(R.string.undo_action)) { stateHolder.saveTaste(saved) }
            }
            stateHolder.closeTasteQuiz(shownSteps)
            onClose()
            return
        }
        stateHolder.saveTaste(next)
        stateHolder.closeTasteQuiz(shownSteps)
        snackbars.show(resources.getString(R.string.taste_saved_snackbar), null, null)
        onClose()
    }
    fun clear() {
        stateHolder.clearTaste()
        stateHolder.closeTasteQuiz(visited + step.id)
        snackbars.show(resources.getString(R.string.taste_cleared_snackbar), resources.getString(R.string.undo_action)) { stateHolder.saveTaste(saved) }
        onClose()
    }

    // System back steps back; on the first step it leaves, asking first if something changed.
    BackHandler { if (index > 0) index-- else requestLeave() }

    TasteQuizPage(
        quiz = quiz,
        index = index,
        draft = draft,
        editing = editing || newSteps.isNotEmpty(),
        isNew = step.id in newSteps,
        canClear = !saved.isEmpty,
        onTap = { option ->
            val before = draft
            val other = draft.conflictOf(quiz, step.id, option)
            draft = draft.toggled(quiz, step, option)
            if (other != null && draft != before && !before.isSelected(step.id, option)) {
                val label = step.option(option)?.label.orEmpty()
                val message = if (quiz.step(other.step)?.tone == TasteTone.AVOID) R.string.taste_moved_off_skip else R.string.taste_moved_off_love
                // Only this move is undone, on the answers as they are by then: later choices stay.
                val moved = step.id
                snackbars.show(resources.getString(message, label), resources.getString(R.string.undo_action)) {
                    draft = draft.undoneMove(quiz, moved, option, other)
                }
            }
        },
        onBack = { index-- },
        onNext = { index++ },
        onSave = ::save,
        onClose = ::requestLeave,
        onClear = ::clear
    )

    if (askLeave) {
        LarderDialog(
            title = stringResource(R.string.discard_changes_title),
            text = stringResource(R.string.taste_discard_message),
            confirmLabel = stringResource(R.string.discard_changes_confirm),
            onConfirm = {
                askLeave = false
                leave()
            },
            onDismissRequest = { askLeave = false },
            dismissLabel = stringResource(R.string.discard_changes_keep),
            destructive = true
        )
    }
}

/** One step of the quiz, stateless: the fixed top (close, progress), the question scrolling, and
 *  the fixed foot (Back, then Skip / Next / Save in one slot). Centred at 640dp on tablets. */
@Composable
internal fun TasteQuizPage(
    quiz: TasteQuiz,
    index: Int,
    draft: TasteProfile,
    editing: Boolean,
    isNew: Boolean,
    canClear: Boolean,
    onTap: (String) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    onClear: () -> Unit
) {
    val step = quiz.steps[index]
    val last = index == quiz.steps.lastIndex
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        TasteTopBar(onClose = onClose, onSave = if (editing) onSave else null)
        TasteColumn { TasteProgress(index, quiz.steps.size) }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                TasteColumn {
                    TasteQuestion(step, draft, quiz, index == 0, isNew, onTap)
                    if (index == 0 && canClear) {
                        LarderButton(
                            text = stringResource(R.string.taste_clear),
                            onClick = onClear,
                            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = LarderSpace.s6),
                            style = LarderButtonStyle.DangerText
                        )
                    }
                    Spacer(Modifier.padding(bottom = LarderSpace.s6))
                }
            }
        }
        HorizontalDivider(color = scheme.outlineVariant)
        TasteColumn(Modifier.padding(top = LarderSpace.s3, bottom = LarderSpace.s4)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (index > 0) {
                    LarderButton(text = stringResource(R.string.taste_back), onClick = onBack, style = LarderButtonStyle.Text, leadingIcon = LarderIcons.ArrowLeft)
                }
                Spacer(Modifier.weight(1f))
                val chosen = draft.selected(step.id).isNotEmpty()
                when {
                    last -> LarderButton(text = stringResource(R.string.taste_save), onClick = onSave, modifier = Modifier.widthIn(min = 112.dp), leadingIcon = LarderIcons.Check)
                    chosen -> LarderButton(text = stringResource(R.string.taste_next), onClick = onNext, modifier = Modifier.widthIn(min = 112.dp))
                    else -> LarderButton(text = stringResource(R.string.taste_skip), onClick = onNext, modifier = Modifier.widthIn(min = 112.dp), style = LarderButtonStyle.Outline)
                }
            }
        }
    }
}

/** The quiz's 640dp column, centred on wide windows. */
@Composable
private fun TasteColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(modifier = modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = LarderSpace.s4), content = content)
    }
}

/** "Step N of M" over one segment per step: done and current filled, the current ringed. */
@Composable
private fun TasteProgress(index: Int, count: Int) {
    val scheme = MaterialTheme.colorScheme
    val label = stringResource(R.string.taste_progress, index + 1, count)
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = LarderSpace.s2).semantics(mergeDescendants = true) { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(LarderSpace.s2)
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        // The current segment's 2dp ring sits outside its bar, as the design's box-shadow does.
        Row(horizontalArrangement = Arrangement.spacedBy(LarderSpace.s1), verticalAlignment = Alignment.CenterVertically) {
            repeat(count) { i ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(10.dp)
                        .then(if (i == index) Modifier.background(scheme.primaryContainer, CircleShape) else Modifier)
                        .padding(2.dp)
                        .background(if (i <= index) scheme.primary else scheme.outlineVariant, CircleShape)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.TasteQuestion(step: TasteStep, draft: TasteProfile, quiz: TasteQuiz, first: Boolean, isNew: Boolean, onTap: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.padding(top = LarderSpace.s3, bottom = LarderSpace.s4), verticalArrangement = Arrangement.spacedBy(LarderSpace.s3)) {
        TasteEmblem(tasteStepIcon(step.icon))
        if (isNew) {
            Text(
                text = stringResource(R.string.taste_new_tag),
                modifier = Modifier
                    .background(scheme.primaryContainer, RoundedCornerShape(LarderRadius.sm))
                    .padding(horizontal = LarderSpace.s2, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onPrimaryContainer
            )
        }
        Text(text = step.question, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall, color = scheme.onBackground)
        if (first) {
            Text(text = stringResource(R.string.taste_intro), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
        TasteHelper(step, draft.selected(step.id).size)
    }
    step.note?.let { TasteNote(it) }
    when (step.kind) {
        TasteStepKind.SINGLE -> Column(verticalArrangement = Arrangement.spacedBy(LarderSpace.s2)) {
            step.options.forEach { option ->
                TasteCard(
                    label = option.label,
                    sub = option.sub,
                    level = option.level,
                    selected = draft.isSelected(step.id, option.id),
                    onClick = { onTap(option.id) }
                )
            }
        }
        TasteStepKind.MULTI -> {
            var markTone: TasteTone? = null
            step.groups.forEachIndexed { groupIndex, group ->
                if (step.grouped && group.title != null) {
                    Text(
                        text = group.title.orEmpty(),
                        modifier = Modifier
                            .padding(top = if (groupIndex == 0) 0.dp else LarderSpace.s4, bottom = LarderSpace.s2)
                            .semantics { heading() },
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.onSurfaceVariant
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    group.options.forEach { option ->
                        val selected = draft.isSelected(step.id, option.id)
                        val other = if (selected) null else draft.conflictOf(quiz, step.id, option.id)
                        val otherTone = other?.let { quiz.step(it.step)?.tone ?: TasteTone.LIKE }
                        if (otherTone != null) markTone = otherTone
                        TasteChip(
                            label = option.label,
                            selected = selected,
                            avoid = step.tone == TasteTone.AVOID,
                            mark = otherTone,
                            dimmed = !draft.canPick(step, option.id),
                            onClick = { onTap(option.id) }
                        )
                    }
                }
            }
            markTone?.let { tone ->
                Row(modifier = Modifier.padding(top = LarderSpace.s4), horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2)) {
                    LarderIcon(if (tone == TasteTone.AVOID) LarderIcons.Ban else LarderIcons.Heart, null, size = 16.dp, tint = scheme.onSurfaceVariant, modifier = Modifier.offset(y = 2.dp))
                    Text(
                        text = stringResource(if (tone == TasteTone.AVOID) R.string.taste_on_skip_note else R.string.taste_on_love_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** "Choose up to 6" and the live count beside it; at the cap, what to do. Read as a status. */
@Composable
private fun TasteHelper(step: TasteStep, count: Int) {
    val scheme = MaterialTheme.colorScheme
    val full = step.kind == TasteStepKind.MULTI && count >= step.max && step.options.size > step.max
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s3)
    ) {
        Text(
            text = when {
                step.kind == TasteStepKind.SINGLE -> stringResource(R.string.taste_choose_one)
                full -> stringResource(R.string.taste_cap_reached, step.max)
                else -> stringResource(R.string.taste_choose_up_to, step.max)
            },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            color = if (full) scheme.onSurface else scheme.onSurfaceVariant
        )
        if (step.kind == TasteStepKind.MULTI && count > 0) {
            Text(
                text = if (full) stringResource(R.string.taste_chosen_of, count, step.max) else stringResource(R.string.taste_chosen, count),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurface
            )
        }
    }
}

/** The server's line under a question (the "not an allergy setting" one), in an outlined box. */
@Composable
private fun TasteNote(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = LarderSpace.s4)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(LarderRadius.md))
            .padding(LarderSpace.s3),
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2)
    ) {
        LarderIcon(LarderIcons.CircleAlert, contentDescription = null, size = 18.dp, tint = MaterialTheme.colorScheme.primary)
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * A large choice chip: 40dp to look at, 48dp to tap. Chosen: filled, with a check (a ban sign on
 * an avoid step). Chosen on the opposite list ([mark]): plain, with a small heart or ban and a
 * firmer outline. At the cap ([dimmed]): faded, still focusable, a tap changes nothing.
 */
@Composable
internal fun TasteChip(label: String, selected: Boolean, avoid: Boolean, mark: TasteTone?, dimmed: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val content = when {
        dimmed -> scheme.onSurface.copy(alpha = 0.38f)
        selected -> scheme.onPrimaryContainer
        else -> scheme.onSurface
    }
    val description = when {
        mark == TasteTone.AVOID -> stringResource(R.string.taste_chip_on_skip, label)
        mark == TasteTone.LIKE -> stringResource(R.string.taste_chip_on_love, label)
        dimmed -> stringResource(R.string.taste_chip_at_cap, label)
        else -> label
    }
    val icon = when {
        selected -> if (avoid) LarderIcons.Ban else LarderIcons.Check
        mark == TasteTone.AVOID -> LarderIcons.Ban
        mark == TasteTone.LIKE -> LarderIcons.Heart
        else -> null
    }
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .toggleable(value = selected, interactionSource = source, indication = null, role = Role.Checkbox, onValueChange = { if (!dimmed) onClick() })
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                // The chip's own description says it all; the label isn't read a second time.
                .clearAndSetSemantics {}
                .focusRing(focused, LarderTheme.colors.focusRing, CircleShape)
                .clip(CircleShape)
                .then(
                    when {
                        selected -> Modifier.background(scheme.primaryContainer)
                        dimmed -> Modifier.border(1.dp, scheme.onSurface.copy(alpha = 0.12f), CircleShape)
                        mark != null -> Modifier.border(1.dp, scheme.outline, CircleShape)
                        else -> Modifier.border(1.dp, scheme.outlineVariant, CircleShape)
                    }
                )
                .heightIn(min = 40.dp)
                .padding(start = if (icon != null) LarderSpace.s3 else LarderSpace.s4, end = LarderSpace.s4),
            horizontalArrangement = Arrangement.spacedBy(LarderSpace.s1),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon?.let { LarderIcon(it, null, size = 16.dp, tint = if (selected) content else scheme.onSurfaceVariant) }
            Text(text = label, style = MaterialTheme.typography.bodyLarge, color = content)
        }
    }
}

/** A single step's choice: a radio card with its subtitle and one to three flames; tapping the
 *  chosen one again clears it. */
@Composable
internal fun TasteCard(label: String, sub: String?, level: Int?, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(LarderRadius.md)
    val content = if (selected) scheme.onPrimaryContainer else scheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(shape)
            .background(if (selected) scheme.primaryContainer else scheme.surface, shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else scheme.outlineVariant, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = LarderSpace.s4, vertical = LarderSpace.s3),
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s4),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .then(if (selected) Modifier.background(scheme.primary, CircleShape) else Modifier.border(2.dp, scheme.outline, CircleShape)),
            contentAlignment = Alignment.Center
        ) {
            if (selected) LarderIcon(LarderIcons.Check, null, size = 16.dp, tint = scheme.onPrimary)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.titleMedium, color = content)
            sub?.let { Text(text = it, style = MaterialTheme.typography.bodySmall, color = if (selected) content else scheme.onSurfaceVariant) }
        }
        level?.let { flames ->
            Row(horizontalArrangement = Arrangement.spacedBy((-4).dp)) {
                repeat(flames) { LarderIcon(LarderIcons.Flame, null, size = 20.dp, tint = if (selected) content else scheme.primary) }
            }
        }
    }
}

// --- Previews ---------------------------------------------------------------------------------

private const val PREVIEW_QUIZ = """{"version":"preview","language":"en","steps":[
 {"id":"cuisines","kind":"multi","tone":"like","icon":"globe","question":"Which cuisines do you like?","max":6,"groups":[{"title":null,"options":[
  {"id":"italian","label":"Italian"},{"id":"french","label":"French"},{"id":"spanish","label":"Spanish"},{"id":"greek","label":"Greek"},{"id":"nordic","label":"Nordic"},
  {"id":"eastern_european","label":"Eastern European"},{"id":"georgian","label":"Georgian"},{"id":"indian","label":"Indian"},{"id":"thai","label":"Thai"}]}]},
 {"id":"heat","kind":"single","tone":"like","icon":"flame","question":"How hot do you like it?","max":1,"groups":[{"title":null,"options":[
  {"id":"mild","label":"Mild","sub":"Little or no chilli","level":1},{"id":"medium","label":"Medium","sub":"A gentle warmth","level":2},{"id":"hot","label":"Hot","sub":"Bring on the chilli","level":3}]}]},
 {"id":"favourites","kind":"multi","tone":"like","icon":"heart","question":"What do you love to eat?","max":8,"groups":[
  {"title":"Vegetables and beans","options":[{"id":"tomatoes","label":"Tomatoes"},{"id":"mushrooms","label":"Mushrooms"},{"id":"aubergine","label":"Aubergine"}]},
  {"title":"Fruit","options":[{"id":"berries","label":"Berries"},{"id":"bananas","label":"Bananas"}]}]},
 {"id":"avoid","kind":"multi","tone":"avoid","icon":"heart-off","question":"What would you rather skip?","note":"This is a preference, not an allergy setting. Allergies are set under Allergen warnings in Settings.","max":8,"groups":[{"title":null,"options":[
  {"id":"onion","label":"Onion"},{"id":"mushrooms","label":"Mushrooms"},{"id":"olives","label":"Olives"},{"id":"offal","label":"Offal"},{"id":"aubergine","label":"Aubergine"}]}]},
 {"id":"meals","kind":"multi","tone":"like","icon":"rocket","question":"Which meals do you cook most?","max":3,"groups":[{"title":null,"options":[
  {"id":"breakfast","label":"Breakfast"},{"id":"lunch","label":"Lunch to take along"},{"id":"dinner","label":"Weeknight dinners"}]}]}],
 "conflicts":[["favourites:mushrooms","avoid:mushrooms"],["favourites:aubergine","avoid:aubergine"]],"summary":["cuisines","favourites"]}"""

private val previewQuiz: TasteQuiz = requireNotNull(tasteQuizOf(RecipeJson.parseToJsonElement(PREVIEW_QUIZ)))
private val previewDraft = TasteProfile(
    mapOf(
        "cuisines" to listOf("italian", "nordic"),
        "heat" to listOf("medium"),
        "favourites" to listOf("tomatoes", "aubergine"),
        "avoid" to listOf("mushrooms", "olives")
    )
)

@Composable
private fun QuizPreview(index: Int, draft: TasteProfile = previewDraft, editing: Boolean = false, isNew: Boolean = false) = LarderTheme {
    TasteQuizPage(previewQuiz, index, draft, editing, isNew, canClear = editing, {}, {}, {}, {}, {}, {})
}

@PreviewLightDark
@Composable
internal fun TasteQuizCuisinesPreview() = QuizPreview(0)

@Preview
@Composable
internal fun TasteQuizEmptyPreview() = QuizPreview(0, draft = TasteProfile())

@Preview
@Composable
internal fun TasteQuizEditingPreview() = QuizPreview(0, editing = true)

@PreviewLightDark
@Composable
internal fun TasteQuizHeatPreview() = QuizPreview(1)

@Preview
@Composable
internal fun TasteQuizFavouritesPreview() = QuizPreview(2)

@PreviewLightDark
@Composable
internal fun TasteQuizAvoidPreview() = QuizPreview(3)

@Preview
@Composable
internal fun TasteQuizNewStepPreview() = QuizPreview(4, editing = true, isNew = true)

@Preview(fontScale = 2f)
@Composable
internal fun TasteQuizLargeTextPreview() = QuizPreview(0)

@Preview(widthDp = 1024, heightDp = 768)
@Composable
internal fun TasteQuizTabletPreview() = QuizPreview(2)

@PreviewLightDark
@Composable
internal fun TasteQuizLoadingPreview() = LarderTheme {
    TasteQuizFrame(onClose = {}) { TasteQuizLoading() }
}

@PreviewLightDark
@Composable
internal fun TasteQuizErrorPreview() = LarderTheme {
    TasteQuizFrame(onClose = {}) { TasteQuizError({}, {}) }
}

@PreviewLightDark
@Composable
internal fun TastePromoPreview() = LarderTheme {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(modifier = Modifier.padding(LarderSpace.s4)) { TastePromoBody({}, {}) }
    }
}

@Preview(locale = "de")
@Composable
internal fun TastePromoGermanPreview() = TastePromoPreview()

@PreviewLightDark
@Composable
internal fun TasteProfileRowPreview() = LarderTheme {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.padding(LarderSpace.s4), verticalArrangement = Arrangement.spacedBy(LarderSpace.s3)) {
            TasteProfileRow(summary = null, newQuestions = 0) {}
            TasteProfileRow(summary = "Italian, Nordic · Medium · 5 more", newQuestions = 0) {}
            TasteProfileRow(summary = "Italian, Nordic · Medium · 5 more", newQuestions = 1) {}
            TasteProfileRow(summary = "6 answers", newQuestions = 0) {}
            TasteProfileRow(summary = null, newQuestions = 0, onClick = {}, dot = true)
        }
    }
}
