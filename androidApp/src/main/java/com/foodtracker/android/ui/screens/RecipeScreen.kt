package com.foodtracker.android.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodtracker.android.R
import com.foodtracker.android.ui.design.EmptyState
import com.foodtracker.android.ui.design.LarderButton
import com.foodtracker.android.ui.design.LarderButtonStyle
import com.foodtracker.android.ui.design.LarderCallout
import com.foodtracker.android.ui.design.LarderCalloutKind
import com.foodtracker.android.ui.design.LarderDivider
import com.foodtracker.android.ui.design.LarderGroup
import com.foodtracker.android.ui.design.LarderIcon
import com.foodtracker.android.ui.design.LarderIconButton
import com.foodtracker.android.ui.design.LarderIcons
import com.foodtracker.android.ui.design.LarderListItem
import com.foodtracker.android.ui.design.LarderSegmented
import com.foodtracker.android.ui.design.LarderSheet
import com.foodtracker.android.ui.design.LarderSpinner
import com.foodtracker.android.ui.design.LedeHeader
import com.foodtracker.android.ui.design.PantryRow as PantryRowView
import com.foodtracker.android.ui.design.PinnedAction
import com.foodtracker.android.ui.design.PinnedActionEndGap
import com.foodtracker.android.ui.design.SectionHeader
import com.foodtracker.android.ui.design.categoryIcon
import com.foodtracker.android.ui.design.expiryStatusLabel
import com.foodtracker.android.ui.design.isExpanded
import com.foodtracker.android.ui.navigation.LarderRoute
import com.foodtracker.android.ui.navigation.LocalLarderNavigator
import com.foodtracker.android.ui.navigation.LocalLarderSnackbars
import com.foodtracker.android.ui.navigation.LocalReviewPrompter
import com.foodtracker.android.ui.theme.LarderSpace
import com.foodtracker.android.ui.theme.LarderTheme
import com.foodtracker.android.util.buildRecipePrompt
import com.foodtracker.android.util.copyRecipePrompt
import com.foodtracker.android.util.shareRecipePrompt
import com.foodtracker.model.ExpiryStatus
import com.foodtracker.model.PantryItem
import com.foodtracker.recipe.AccountKind
import com.foodtracker.recipe.AccountState
import com.foodtracker.recipe.MENU_COURSE_OPTIONS
import com.foodtracker.recipe.OnDeviceAvailability
import com.foodtracker.recipe.OnDeviceRoute
import com.foodtracker.recipe.PaidUiState
import com.foodtracker.recipe.RECIPE_SHOWN_ITEMS
import com.foodtracker.recipe.RecipeConfig
import com.foodtracker.recipe.RecipeAction
import com.foodtracker.recipe.RecipeEvent
import com.foodtracker.recipe.RecipeIntro
import com.foodtracker.recipe.RecipeMode
import com.foodtracker.recipe.RecipeSelection
import com.foodtracker.recipe.RecipeStateHolder
import com.foodtracker.recipe.RecipeUiState
import com.foodtracker.recipe.ServerIssue
import com.foodtracker.viewmodel.PantryRow
import kotlinx.datetime.LocalDate

/** What the tab's pinned action does, worked out from the state. The server leads when there are
 *  recipes left and nothing stands in its way, as its recipes are the most reliable; then the
 *  phone's own model; then the shared prompt. A menu has only the server. */
internal enum class RecipePrimary { Server, OnDevice, Share, None }

internal fun recipePrimary(state: RecipeUiState): RecipePrimary = when (state.actions.primary) {
    RecipeAction.SERVER -> RecipePrimary.Server
    RecipeAction.ON_DEVICE -> RecipePrimary.OnDevice
    RecipeAction.SHARE -> RecipePrimary.Share
    RecipeAction.COPY, RecipeAction.BUY, null -> RecipePrimary.None
}

/**
 * The Recipe tab: the soonest items ticked, a short form, and the three routes. The primary
 * action is pinned where Scan sits on Home; the root measures it through [onPinnedActionSize],
 * places it ([pinnedActionBottom]: above the pill, or level with it on a wide window) and pads
 * content and snackbars to clear it.
 */
@Composable
fun RecipeScreen(
    stateHolder: RecipeStateHolder,
    bottomOverlayClearance: Dp,
    pinnedActionBottom: Dp,
    onPinnedActionSize: (IntSize) -> Unit
) {
    val state by stateHolder.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val navigator = LocalLarderNavigator.current
    val snackbars = LocalLarderSnackbars.current
    val resources = context.resources
    val tryAgain = stringResource(R.string.recipe_try_again)
    val copiedMessage = stringResource(R.string.recipe_prompt_copied)

    // The model can finish downloading, or the app's language change, while the tab is away.
    LifecycleResumeEffect(stateHolder) {
        stateHolder.refresh()
        onPauseOrDispose { }
    }

    val reviewPrompter = LocalReviewPrompter.current
    // The first visit ever opens the introduction; like onboarding, it rules the rating prompt
    // out for the session.
    LaunchedEffect(stateHolder) {
        if (stateHolder.onTabShown()) reviewPrompter?.onOnboardingShown()
    }
    val event = state.event
    LaunchedEffect(event) {
        // A recipe that just arrived arms the rating prompt for when it is left; anything that
        // went wrong rules it out for the session.
        reviewPrompter?.onRecipeEvent(event)
        when (event) {
            is RecipeEvent.Written -> {
                stateHolder.consumeEvent()
                val saved = stateHolder.savedRecipe(event.savedId)
                navigator.navigate(LarderRoute.RecipeResult(event.savedId))
                if (event.leftOut.isNotEmpty()) {
                    // What it was written without matters more than the count, which the
                    // account card shows anyway.
                    snackbars.show(resources.getString(R.string.recipe_left_out, isolatedList(event.leftOut)), null, null)
                } else {
                    // T3, and the moment the count drops.
                    event.recipesLeft?.let { left ->
                        if (saved != null) snackbars.show(resources.getQuantityString(R.plurals.recipe_saved_left, left, left), null, null)
                    }
                }
            }
            is RecipeEvent.IngredientNotSent -> {
                stateHolder.consumeEvent()
                snackbars.show(resources.getString(R.string.recipe_ingredient_not_sent, isolated(event.ingredient)), null, null)
            }
            is RecipeEvent.AllergenNotSent -> {
                stateHolder.consumeEvent()
                // Long, with the way to the allergen list: nothing is written until it is fixed.
                snackbars.show(resources.getString(R.string.recipe_allergen_not_sent, isolated(event.allergen)), resources.getString(R.string.settings_title)) {
                    navigator.navigate(LarderRoute.Settings)
                }
            }
            is RecipeEvent.TooManyAllergens -> {
                stateHolder.consumeEvent()
                snackbars.show(
                    resources.getQuantityString(R.plurals.recipe_allergens_too_many, event.limit, event.limit),
                    resources.getString(R.string.settings_title)
                ) { navigator.navigate(LarderRoute.Settings) }
            }
            RecipeEvent.AllergenConsentDeclined -> {
                stateHolder.consumeEvent()
                // With the way to the switch that turns sending them back on.
                snackbars.show(
                    resources.getString(R.string.recipe_allergen_consent_declined),
                    resources.getString(R.string.recipe_settings_action)
                ) { navigator.navigate(LarderRoute.RecipeSettings) }
            }
            RecipeEvent.Failed -> {
                stateHolder.consumeEvent()
                snackbars.show(resources.getString(R.string.recipe_failed_phone), tryAgain) { stateHolder.generateOnDevice() }
            }
            RecipeEvent.ServerFailed -> {
                stateHolder.consumeEvent()
                val failed = if (state.mode == RecipeMode.MENU) R.string.recipe_failed_server_menu else R.string.recipe_failed_server
                snackbars.show(resources.getString(failed), tryAgain) { stateHolder.generateOnServer() }
            }
            RecipeEvent.ServerFailedLater -> {
                stateHolder.consumeEvent()
                snackbars.show(resources.getString(R.string.recipe_failed_server_later), null, null)
            }
            RecipeEvent.ServerBusy -> {
                stateHolder.consumeEvent()
                snackbars.show(resources.getString(R.string.recipe_failed_server_busy), null, null)
            }
            RecipeEvent.ServerRateLimited -> {
                stateHolder.consumeEvent()
                snackbars.show(resources.getString(R.string.recipe_failed_rate_limited), null, null)
            }
            RecipeEvent.ServerLimitToday -> {
                stateHolder.consumeEvent()
                snackbars.show(resources.getString(R.string.recipe_failed_limit_today), null, null)
            }
            RecipeEvent.ServerTroubleToday -> {
                stateHolder.consumeEvent()
                snackbars.show(resources.getString(R.string.recipe_failed_trouble_today), null, null)
            }
            is RecipeEvent.ServerRefused -> {
                stateHolder.consumeEvent()
                val refused = if (state.mode == RecipeMode.MENU) R.plurals.recipe_refused_menu else R.plurals.recipe_refused
                snackbars.show(resources.getQuantityString(refused, event.taken, event.taken), null, null)
            }
            null -> Unit
        }
    }

    fun prompt(): String = buildRecipePrompt(
        context,
        state.selectedRows.map { it.item },
        state.config,
        state.allergens,
        // "Send all products": the rest of the pantry, as items the recipe may use as well.
        otherItems = if (state.options.sendAllItems) state.otherRows.map { it.item } else emptyList()
    )

    RecipeTabContent(
        state = state,
        bottomOverlayClearance = bottomOverlayClearance,
        pinnedActionBottom = pinnedActionBottom,
        onPinnedActionSize = onPinnedActionSize,
        actions = RecipeTabActions(
            onToggle = stateHolder::toggleItem,
            onConfigChange = stateHolder::updateConfig,
            onMode = stateHolder::setMode,
            onCourses = stateHolder::setCourses,
            onGenerateServer = stateHolder::generateOnServer,
            onRetryServer = stateHolder::retryServer,
            onRetryPurchases = stateHolder::retryPurchases,
            onBuy = stateHolder::openPacks,
            onGenerateOnDevice = stateHolder::generateOnDevice,
            onStop = stateHolder::stopWriting,
            onShare = { shareRecipePrompt(context, prompt()) },
            onCopy = {
                copyRecipePrompt(context, prompt())
                // Android 13 and up confirm a copy themselves.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) snackbars.show(copiedMessage, null, null)
            },
            onOpenSaved = { navigator.navigate(LarderRoute.SavedRecipes) },
            onOpenSettings = { navigator.navigate(LarderRoute.RecipeSettings) },
            onScan = { navigator.navigate(LarderRoute.Scanner()) }
        )
    )

    RecipePaidOverlays(state, stateHolder)

    if (state.intro == RecipeIntro.FIRST_OPEN) {
        RecipeIntroSheet(state, onDismiss = stateHolder::dismissIntro, onPacks = stateHolder::introToPacks)
    }
}

internal class RecipeTabActions(
    val onToggle: (String) -> Unit = {},
    val onConfigChange: ((RecipeConfig) -> RecipeConfig) -> Unit = {},
    val onMode: (RecipeMode) -> Unit = {},
    val onCourses: (Int) -> Unit = {},
    val onGenerateServer: () -> Unit = {},
    val onRetryServer: () -> Unit = {},
    val onRetryPurchases: () -> Unit = {},
    val onBuy: () -> Unit = {},
    val onGenerateOnDevice: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onShare: () -> Unit = {},
    val onCopy: () -> Unit = {},
    val onOpenSaved: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onScan: () -> Unit = {}
)

@Composable
internal fun RecipeTabContent(
    state: RecipeUiState,
    bottomOverlayClearance: Dp,
    pinnedActionBottom: Dp,
    onPinnedActionSize: (IntSize) -> Unit,
    actions: RecipeTabActions
) {
    val expanded = isExpanded()
    var showingOthers by remember { mutableStateOf(false) }
    val showForm = !state.isLoading && state.rows.isNotEmpty() && !state.isBusy
    val paid = state.paid
    val primary = recipePrimary(state)
    val showsAction = showForm && primary != RecipePrimary.None
    // Reported on every composition rather than reset on dispose: an outgoing copy of this
    // screen (a tab or launch transition) disposing after the new one measured used to leave
    // the root with 0, so the end of the form ran under the pill.
    var actionSize by remember { mutableStateOf(IntSize.Zero) }
    SideEffect { onPinnedActionSize(if (showsAction) actionSize else IntSize.Zero) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            LedeHeader(
                lede = stringResource(R.string.recipe_lede),
                actions = {
                    if (paid.available) {
                        RecipeBalanceChip(paid.balance ?: 0, onClick = if (paid.canBuy) actions.onBuy else null)
                    }
                    // Only once something is saved; it follows the saved list as it changes.
                    if (state.hasSaved) {
                        LarderIconButton(
                            icon = LarderIcons.Bookmark,
                            contentDescription = stringResource(R.string.recipe_saved_action),
                            onClick = actions.onOpenSaved
                        )
                    }
                    LarderIconButton(
                        icon = LarderIcons.SlidersHorizontal,
                        contentDescription = stringResource(R.string.recipe_settings_action),
                        onClick = actions.onOpenSettings
                    )
                }
            )
            when {
                state.isLoading -> Unit
                state.rows.isEmpty() -> EmptyState(
                    title = stringResource(R.string.recipe_empty_title),
                    message = stringResource(R.string.recipe_empty_body),
                    modifier = Modifier.padding(bottom = bottomOverlayClearance),
                    action = {
                        LarderButton(
                            text = stringResource(R.string.scan_content_description),
                            onClick = actions.onScan,
                            leadingIcon = LarderIcons.ScanBarcode
                        )
                    }
                )
                state.isWriting -> Writing(
                    title = stringResource(R.string.recipe_writing_phone_title),
                    body = stringResource(R.string.recipe_writing_phone_body),
                    bottom = bottomOverlayClearance,
                    onStop = actions.onStop
                )
                // Every server instance busy: the app asks again by itself. Calm, not an error.
                paid.writing != null && paid.serverBusy -> Writing(
                    title = stringResource(R.string.recipe_writing_busy_title),
                    body = stringResource(R.string.recipe_writing_busy_body),
                    bottom = bottomOverlayClearance
                )
                paid.writing == RecipeMode.MENU -> Writing(
                    title = stringResource(R.string.recipe_writing_menu_title),
                    body = stringResource(R.string.recipe_writing_menu_body),
                    bottom = bottomOverlayClearance
                )
                // No Stop for the server: the server goes on with a request once it has it.
                paid.writing == RecipeMode.RECIPE -> Writing(
                    title = stringResource(R.string.recipe_writing_server_title),
                    body = stringResource(R.string.recipe_writing_server_body),
                    bottom = bottomOverlayClearance
                )
                else -> RecipeForm(
                    state = state,
                    expanded = expanded,
                    bottom = bottomOverlayClearance,
                    actions = actions,
                    onAddOthers = { showingOthers = true }
                )
            }
        }

        if (showsAction) {
            val nothingTicked = state.selectedIds.isEmpty()
            // The shared prompt pinned alone sits as Scan does on Home: a pill as wide as its
            // label at the end, not a bar across the form. Actions with a cost stay wide.
            val compact = primary == RecipePrimary.Share
            PinnedAction(
                text = when (primary) {
                    RecipePrimary.Server -> stringResource(
                        if (state.mode == RecipeMode.MENU) R.string.recipe_generate_menu else R.string.recipe_generate
                    )
                    RecipePrimary.OnDevice -> stringResource(R.string.recipe_generate_on_phone)
                    else -> stringResource(R.string.recipe_share_prompt)
                },
                icon = when (primary) {
                    RecipePrimary.Server -> LarderIcons.Sparkles
                    RecipePrimary.OnDevice -> LarderIcons.Smartphone
                    else -> LarderIcons.Share2
                },
                onClick = when (primary) {
                    RecipePrimary.Server -> actions.onGenerateServer
                    RecipePrimary.OnDevice -> actions.onGenerateOnDevice
                    else -> actions.onShare
                },
                cost = when (primary) {
                    RecipePrimary.Server -> pluralStringResource(R.plurals.recipe_cost_recipes, state.serverCost, state.serverCost)
                    RecipePrimary.OnDevice -> stringResource(R.string.recipe_cost_free)
                    else -> null
                },
                enabled = !nothingTicked,
                compact = compact,
                modifier = Modifier
                    .align(if (expanded || compact) Alignment.BottomEnd else Alignment.BottomCenter)
                    .padding(
                        start = LarderSpace.s4,
                        end = if (expanded) PinnedActionEndGap else LarderSpace.s4,
                        bottom = pinnedActionBottom
                    )
                    .then(
                        when {
                            compact -> Modifier.widthIn(max = 440.dp)
                            expanded -> Modifier.widthIn(max = 440.dp).fillMaxWidth(0.5f)
                            else -> Modifier.fillMaxWidth()
                        }
                    )
                    .onSizeChanged { actionSize = it }
            )
        }
    }

    if (showingOthers) {
        OtherItemsSheet(
            rows = state.rows.drop(RECIPE_SHOWN_ITEMS),
            selectedIds = state.selectedIds,
            onToggle = actions.onToggle,
            onDismiss = { showingOthers = false }
        )
    }
}

@Composable
private fun RecipeForm(
    state: RecipeUiState,
    expanded: Boolean,
    bottom: Dp,
    actions: RecipeTabActions,
    onAddOthers: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = LarderSpace.s4, end = LarderSpace.s4, bottom = bottom),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.widthIn(max = 960.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(LarderSpace.s3)
        ) {
            if (state.paid.available) {
                val resources = LocalContext.current.resources
                LarderSegmented(
                    options = RecipeMode.entries,
                    selected = state.mode,
                    onSelect = actions.onMode,
                    label = {
                        resources.getString(if (it == RecipeMode.MENU) R.string.recipe_mode_menu else R.string.recipe_mode_recipe)
                    },
                    fill = true
                )
            }
            RecipeCallouts(state, actions)
            if (expanded) {
                Row(horizontalArrangement = Arrangement.spacedBy(LarderSpace.s6)) {
                    Column(modifier = Modifier.weight(1f)) {
                        CookWith(state, actions.onToggle, onAddOthers)
                    }
                    // Lined up with the first row, under the "Cook with" label.
                    Column(
                        modifier = Modifier.weight(1f).padding(top = LarderSpace.s8),
                        verticalArrangement = Arrangement.spacedBy(LarderSpace.s4)
                    ) {
                        QuickForm(state, actions)
                        RouteActions(state, actions)
                    }
                }
            } else {
                CookWith(state, actions.onToggle, onAddOthers)
                QuickForm(state, actions)
                RouteActions(state, actions)
            }
        }
    }
}

/** The lasting conditions (T4) at the top of the form, most pressing first. */
@Composable
private fun RecipeCallouts(state: RecipeUiState, actions: RecipeTabActions) {
    val paid = state.paid
    if (paid.available) {
        if (paid.unconfirmedRecipes > 0) {
            LarderCallout(
                kind = LarderCalloutKind.Warning,
                icon = LarderIcons.Clock,
                title = stringResource(R.string.recipe_unconfirmed_title),
                message = pluralStringResource(R.plurals.recipe_unconfirmed_body, paid.unconfirmedRecipes, paid.unconfirmedRecipes),
                actionLabel = if (paid.verifying) null else stringResource(R.string.recipe_try_now),
                onAction = actions.onRetryPurchases
            )
        } else if (paid.paymentPending) {
            LarderCallout(
                kind = LarderCalloutKind.Info,
                icon = LarderIcons.Clock,
                title = stringResource(R.string.recipe_pending_title),
                message = stringResource(R.string.recipe_pending_body)
            )
        }
        if (paid.elsewhereRecipes > 0) {
            LarderCallout(
                kind = LarderCalloutKind.Info,
                icon = LarderIcons.UserRound,
                title = stringResource(R.string.recipe_elsewhere_title),
                message = pluralStringResource(R.plurals.recipe_elsewhere_body, paid.elsewhereRecipes, paid.elsewhereRecipes)
            )
        }
        when (paid.issue) {
            ServerIssue.OFFLINE -> LarderCallout(
                kind = LarderCalloutKind.Warning,
                icon = LarderIcons.WifiOff,
                title = stringResource(R.string.recipe_offline_title),
                message = stringResource(R.string.recipe_offline_body),
                actionLabel = stringResource(R.string.recipe_try_again),
                onAction = actions.onRetryServer
            )
            ServerIssue.BUSY -> LarderCallout(
                kind = LarderCalloutKind.Info,
                icon = LarderIcons.Clock,
                title = stringResource(R.string.recipe_busy_title),
                message = stringResource(R.string.recipe_busy_body),
                actionLabel = stringResource(R.string.recipe_try_again),
                onAction = actions.onRetryServer
            )
            ServerIssue.PAUSED -> {
                val count = paid.balance ?: 0
                LarderCallout(
                    kind = LarderCalloutKind.Warning,
                    icon = LarderIcons.TriangleAlert,
                    title = stringResource(R.string.recipe_paused_title),
                    message = if (count > 0) {
                        pluralStringResource(R.plurals.recipe_paused_body, count, count)
                    } else {
                        stringResource(R.string.recipe_paused_body_none)
                    }
                )
            }
            null -> Unit
        }
    }
    if (state.mode == RecipeMode.RECIPE && state.onDeviceRoute == OnDeviceRoute.PREPARING) {
        LarderCallout(
            kind = LarderCalloutKind.Info,
            icon = LarderIcons.Download,
            title = stringResource(R.string.recipe_preparing_title),
            message = stringResource(R.string.recipe_preparing_body)
        )
    }
}

@Composable
private fun CookWith(state: RecipeUiState, onToggle: (String) -> Unit, onAddOthers: () -> Unit) {
    SectionHeader(text = stringResource(R.string.recipe_cook_with), first = true)
    LarderGroup {
        state.shownRows.forEachIndexed { index, row ->
            if (index > 0) LarderDivider()
            RecipeItemRow(row = row, selected = row.id in state.selectedIds, onToggle = { onToggle(row.id) })
        }
        if (state.otherCount > 0) {
            LarderDivider()
            LarderListItem(
                title = stringResource(R.string.recipe_add_other),
                supporting = pluralStringResource(R.plurals.recipe_add_other_count, state.otherCount, state.otherCount),
                leading = { LarderIcon(LarderIcons.Plus, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                trailing = { LarderIcon(LarderIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                onClick = onAddOthers
            )
        }
    }
    // "Send all products" is on: one quiet line, so it is never a surprise what goes with it.
    val optional = state.optionalCount
    if (optional > 0) {
        Text(
            text = pluralStringResource(R.plurals.recipe_optional_count, optional, optional),
            modifier = Modifier.padding(start = LarderSpace.s2, top = LarderSpace.s2, end = LarderSpace.s2),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** A pantry item with a tick: the whole row toggles it, as in Home's multi-select. */
@Composable
private fun RecipeItemRow(row: PantryRow, selected: Boolean, onToggle: () -> Unit) {
    val item = row.item
    val resources = LocalContext.current.resources
    val locale = LocalConfiguration.current.locales[0]
    // The row carries days left, not the date it was derived on; this is that date.
    val today = remember(item.expiryDate, row.daysRemaining) {
        LocalDate.fromEpochDays(item.expiryDate.toEpochDays() - row.daysRemaining)
    }
    val statusText = remember(resources, locale, row.status, row.daysRemaining, item.expiryDate, today) {
        expiryStatusLabel(resources, locale, row.status, row.daysRemaining, item.expiryDate, today)
    }
    PantryRowView(
        name = item.name,
        status = row.status,
        statusText = statusText,
        place = null,
        quantity = item.quantity,
        onClick = onToggle,
        imageUrl = item.imageUrl?.takeIf { it.isNotBlank() },
        fallbackIcon = categoryIcon(row.categoryDisplayName ?: item.category),
        selecting = true,
        selected = selected
    )
}

@Composable
private fun QuickForm(state: RecipeUiState, actions: RecipeTabActions) {
    val resources = LocalContext.current.resources
    val config = state.config
    RecipeFormCard {
        if (state.mode == RecipeMode.MENU) {
            Column(verticalArrangement = Arrangement.spacedBy(LarderSpace.s2)) {
                RecipeControlLabel(stringResource(R.string.recipe_courses))
                LarderSegmented(
                    options = MENU_COURSE_OPTIONS,
                    selected = state.courses,
                    onSelect = actions.onCourses,
                    label = { resources.getQuantityString(R.plurals.recipe_courses_count, it, it) },
                    fill = true
                )
            }
        }
        DifficultyControl(config.difficulty) { value -> actions.onConfigChange { it.copy(difficulty = value) } }
        ServingsControl(config.servings) { value -> actions.onConfigChange { it.copy(servings = value) } }
        TimeControl(config.maxMinutes) { value -> actions.onConfigChange { it.copy(maxMinutes = value) } }
    }
    LarderGroup {
        LarderListItem(
            title = stringResource(R.string.recipe_more_options),
            supporting = recipeOptionsSummary(resources, config, state.appLanguage),
            leading = { LarderIcon(LarderIcons.SlidersHorizontal, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            trailing = { LarderIcon(LarderIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            onClick = actions.onOpenSettings
        )
    }
}

/** What sits under the form: the routes that aren't the pinned action, or why it is off. */
@Composable
private fun ColumnScope.RouteActions(state: RecipeUiState, actions: RecipeTabActions) {
    val paid = state.paid
    when {
        state.selectedIds.isEmpty() -> RecipeHint(stringResource(R.string.recipe_tick_hint))
        state.mode == RecipeMode.MENU -> MenuActions(state, actions)
        else -> when (recipePrimary(state)) {
            RecipePrimary.Server -> {
                if (state.onDeviceRoute == OnDeviceRoute.READY) {
                    LarderButton(
                        text = stringResource(R.string.recipe_generate_on_phone) + " · " + stringResource(R.string.recipe_cost_free),
                        onClick = actions.onGenerateOnDevice,
                        modifier = Modifier.fillMaxWidth(),
                        style = LarderButtonStyle.Outline,
                        leadingIcon = LarderIcons.Smartphone
                    )
                }
                ShareAndCopy(actions)
            }
            RecipePrimary.OnDevice -> {
                BuyOrBlocked(paid, actions, LarderButtonStyle.Outline)
                ShareAndCopy(actions)
            }
            else -> {
                LarderButton(
                    text = stringResource(R.string.recipe_copy_prompt),
                    onClick = actions.onCopy,
                    modifier = Modifier.fillMaxWidth(),
                    style = LarderButtonStyle.Outline,
                    leadingIcon = LarderIcons.Copy
                )
                RecipeHint(stringResource(R.string.recipe_prompt_hint))
                BuyOrBlocked(paid, actions, LarderButtonStyle.Text)
            }
        }
    }
}

/** A menu has only the server: the cost, or how to get enough for one. */
@Composable
private fun MenuActions(state: RecipeUiState, actions: RecipeTabActions) {
    val paid = state.paid
    val balance = paid.balance ?: 0
    val resources = LocalContext.current.resources
    // For the courses chosen: switching 2 and 3 moves the button and this together.
    val cost = recipeCount(resources, state.serverCost)
    when {
        paid.issue == ServerIssue.PAUSED -> Unit
        balance < state.serverCost -> {
            // T4: lasts until a pack is bought, so it takes the button's place.
            LarderCallout(
                kind = LarderCalloutKind.Info,
                title = stringResource(R.string.recipe_menu_short_title, cost),
                // Whether a single recipe still fits in what is left, as iOS says it.
                message = when {
                    balance <= 0 -> stringResource(R.string.recipe_menu_short_none)
                    balance >= paid.costs.recipe -> pluralStringResource(R.plurals.recipe_menu_short_body, balance, balance)
                    else -> pluralStringResource(R.plurals.recipe_balance_left, balance, balance)
                }
            )
            BuyOrBlocked(paid, actions, LarderButtonStyle.Primary)
        }
        else -> RecipeHint(stringResource(R.string.recipe_menu_hint, cost))
    }
}

/** Buy where buying is offered; for an account the server won't sell to, one calm line in its
 *  place instead (the recipes it holds stay usable). */
@Composable
private fun BuyOrBlocked(paid: PaidUiState, actions: RecipeTabActions, style: LarderButtonStyle) {
    when {
        paid.available && paid.purchasesBlocked -> RecipeHint(stringResource(R.string.recipe_purchases_blocked))
        paid.canBuy -> BuyButton(actions, style)
    }
}

@Composable
private fun BuyButton(actions: RecipeTabActions, style: LarderButtonStyle) {
    LarderButton(
        text = stringResource(R.string.recipe_buy),
        onClick = actions.onBuy,
        modifier = Modifier.fillMaxWidth(),
        style = style,
        leadingIcon = LarderIcons.Ticket
    )
}

/** Side by side, and one under the other when large text leaves no room for both. */
@Composable
private fun ShareAndCopy(actions: RecipeTabActions) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2, Alignment.CenterHorizontally)
    ) {
        LarderButton(
            text = stringResource(R.string.recipe_share_prompt),
            onClick = actions.onShare,
            style = LarderButtonStyle.Text,
            leadingIcon = LarderIcons.Share2
        )
        LarderButton(
            text = stringResource(R.string.recipe_copy_prompt),
            onClick = actions.onCopy,
            style = LarderButtonStyle.Text,
            leadingIcon = LarderIcons.Copy
        )
    }
}

@Composable
private fun RecipeHint(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}

/** The only loading state outside the scanner. [onStop] only for the phone's own model:
 *  nothing was paid, so nothing is lost by stopping. */
@Composable
private fun Writing(title: String, body: String, bottom: Dp, onStop: (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = LarderSpace.s6, end = LarderSpace.s6, bottom = bottom),
        verticalArrangement = Arrangement.spacedBy(LarderSpace.s3, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LarderSpinner()
        Text(
            text = title,
            modifier = Modifier.padding(top = LarderSpace.s2),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Text(
            text = body,
            modifier = Modifier.widthIn(max = 340.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        onStop?.let {
            LarderButton(
                text = stringResource(R.string.recipe_stop),
                onClick = it,
                style = LarderButtonStyle.Outline
            )
        }
    }
}

/** Everything past the first few, to tick or untick. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OtherItemsSheet(
    rows: List<PantryRow>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val toggle by rememberUpdatedState(onToggle)
    LarderSheet(onDismissRequest = onDismiss, title = stringResource(R.string.recipe_add_other)) {
        LarderGroup {
            rows.forEachIndexed { index, row ->
                if (index > 0) LarderDivider()
                RecipeItemRow(row = row, selected = row.id in selectedIds, onToggle = { toggle(row.id) })
            }
        }
        LarderButton(
            text = stringResource(R.string.recipe_done),
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = LarderSpace.s4)
        )
    }
}

// --- Previews ---------------------------------------------------------------------------------

private val previewToday = LocalDate(2026, 11, 4)

private fun previewRow(id: String, name: String, days: Int, category: String, quantity: Int = 1) = PantryRow(
    item = PantryItem(
        id = id,
        name = name,
        expiryDate = LocalDate.fromEpochDays(previewToday.toEpochDays() + days),
        addedDate = previewToday,
        quantity = quantity,
        category = category
    ),
    daysRemaining = days,
    status = ExpiryStatus.compute(days, 3),
    categoryDisplayName = category,
    sortName = name.lowercase()
)

private val previewRows = listOf(
    previewRow("1", "Sourdough bread", -1, "Bakery"),
    previewRow("2", "Baby spinach", 0, "Vegetables"),
    previewRow("3", "Greek yoghurt", 1, "Dairy", 2),
    previewRow("4", "Semi-skimmed milk", 2, "Dairy"),
    previewRow("5", "Salmon fillet", 3, "Meat & fish", 2),
    previewRow("6", "Strawberries", 4, "Fruit"),
    previewRow("7", "Free-range eggs", 15, "Dairy", 6)
)

private fun previewPaid(balance: Int?, issue: ServerIssue? = null, writing: RecipeMode? = null) = PaidUiState(
    available = true,
    balance = balance,
    account = AccountState(if (balance == null) AccountKind.NONE else AccountKind.ANONYMOUS),
    issue = issue,
    writing = writing
)

private fun previewState(
    availability: OnDeviceAvailability = OnDeviceAvailability.UNSUPPORTED,
    selection: RecipeSelection = RecipeSelection.Default,
    rows: List<PantryRow> = previewRows,
    writing: Boolean = false,
    paid: PaidUiState = PaidUiState(),
    mode: RecipeMode = RecipeMode.RECIPE
) = RecipeUiState(
    isLoading = false,
    rows = rows,
    selection = selection,
    onDeviceAvailability = availability,
    onDeviceSupportsLanguage = true,
    isWriting = writing,
    paid = paid,
    mode = mode
)

@Composable
private fun RecipeTabPreview(state: RecipeUiState) = LarderTheme {
    Surface(color = MaterialTheme.colorScheme.background) {
        RecipeTabContent(state, 120.dp, 96.dp, {}, RecipeTabActions())
    }
}

@PreviewLightDark
@Composable
internal fun RecipeTabPromptOnlyPreview() = RecipeTabPreview(previewState())

@PreviewLightDark
@Composable
internal fun RecipeTabPhoneCanWritePreview() = RecipeTabPreview(previewState(OnDeviceAvailability.AVAILABLE))

@PreviewLightDark
@Composable
internal fun RecipeTabWithRecipesPreview() = RecipeTabPreview(previewState(OnDeviceAvailability.AVAILABLE, paid = previewPaid(12)))

@Preview
@Composable
internal fun RecipeTabNoRecipesPreview() = RecipeTabPreview(previewState(paid = previewPaid(0)))

@Preview
@Composable
internal fun RecipeTabMenuPreview() = RecipeTabPreview(previewState(paid = previewPaid(12), mode = RecipeMode.MENU))

@Preview
@Composable
internal fun RecipeTabMenuShortPreview() = RecipeTabPreview(previewState(paid = previewPaid(2), mode = RecipeMode.MENU))

@Preview
@Composable
internal fun RecipeTabOfflinePreview() = RecipeTabPreview(
    previewState(OnDeviceAvailability.AVAILABLE, paid = previewPaid(12, issue = ServerIssue.OFFLINE))
)

@Preview
@Composable
internal fun RecipeTabPausedPreview() = RecipeTabPreview(previewState(paid = previewPaid(12, issue = ServerIssue.PAUSED)))

@Preview
@Composable
internal fun RecipeTabUnconfirmedPreview() = RecipeTabPreview(previewState(paid = previewPaid(0).copy(unconfirmedRecipes = 50)))

@Preview
@Composable
internal fun RecipeTabServerWritingPreview() = RecipeTabPreview(previewState(paid = previewPaid(12, writing = RecipeMode.RECIPE)))

@Preview
@Composable
internal fun RecipeTabMenuWritingPreview() = RecipeTabPreview(previewState(paid = previewPaid(12, writing = RecipeMode.MENU)))

@Preview
@Composable
internal fun RecipeTabPhonePreparingPreview() = RecipeTabPreview(previewState(OnDeviceAvailability.DOWNLOADING))

@Preview
@Composable
internal fun RecipeTabNothingTickedPreview() = RecipeTabPreview(previewState(selection = RecipeSelection.Chosen(emptySet())))

@Preview
@Composable
internal fun RecipeTabWritingPreview() = RecipeTabPreview(previewState(OnDeviceAvailability.AVAILABLE, writing = true))

@Preview
@Composable
internal fun RecipeTabEmptyPreview() = RecipeTabPreview(previewState(rows = emptyList()))

@Preview(locale = "ru")
@Composable
internal fun RecipeTabRussianPreview() = RecipeTabPreview(previewState(OnDeviceAvailability.AVAILABLE, paid = previewPaid(12)))

@Preview(locale = "ru")
@Composable
internal fun RecipeTabRussianMenuPreview() = RecipeTabPreview(previewState(paid = previewPaid(12), mode = RecipeMode.MENU))

@Preview(widthDp = 820, heightDp = 1180)
@Composable
internal fun RecipeTabTabletPreview() = RecipeTabPreview(previewState(OnDeviceAvailability.AVAILABLE, paid = previewPaid(12)))
