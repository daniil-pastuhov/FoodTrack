package com.foodtracker.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodtracker.android.R
import com.foodtracker.android.ui.design.LarderButton
import com.foodtracker.android.ui.design.LarderButtonStyle
import com.foodtracker.android.ui.design.LarderDivider
import com.foodtracker.android.ui.design.LarderGroup
import com.foodtracker.android.ui.design.LarderIcon
import com.foodtracker.android.ui.design.LarderIconButton
import com.foodtracker.android.ui.design.LarderIconSize
import com.foodtracker.android.ui.design.LarderIcons
import com.foodtracker.android.ui.design.LarderTextField
import com.foodtracker.android.ui.design.LarderTick
import com.foodtracker.android.ui.design.LarderTopBar
import com.foodtracker.android.ui.design.SectionHeader
import com.foodtracker.android.ui.navigation.LocalLarderSnackbars
import com.foodtracker.android.ui.theme.LarderSize
import com.foodtracker.android.ui.theme.LarderSpace
import com.foodtracker.android.ui.theme.LarderTheme
import com.foodtracker.model.Staple
import com.foodtracker.recipe.appRecipeLanguage
import com.foodtracker.staples.MAX_STAPLES
import com.foodtracker.staples.cutStapleName
import com.foodtracker.staples.StapleAddCheck
import com.foodtracker.staples.StapleRow
import com.foodtracker.staples.StaplePreset
import com.foodtracker.staples.StaplePresets
import com.foodtracker.staples.addCheck
import com.foodtracker.staples.stapleLanguage
import com.foodtracker.staples.stapleRows
import com.foodtracker.viewmodel.PantryStateHolder
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Keeps the column readable on a tablet, as Settings does. */
private val StaplesMaxWidth = 640.dp

/** What the Staples screen can do; the defaults do nothing, for previews. */
internal data class StaplesActions(
    val onTogglePreset: (preset: StaplePreset, ticked: Boolean) -> Unit = { _, _ -> },
    val onRanOut: (StapleRow) -> Unit = {},
    val onRemove: (StapleRow) -> Unit = {},
    val onTypedChange: (String) -> Unit = {},
    val onAdd: () -> Unit = {}
)

/**
 * Settings › Staples: the preset staples to tick, the person's own with an add field, "Ran out"
 * on each (onto the shopping list, which is what makes a staple out: `isOut`), and the note on
 * what is sent. Opened from Settings and from the Recipe form's staples line.
 */
@Composable
fun StaplesScreen(pantry: PantryStateHolder, onBack: () -> Unit) {
    val staples by remember(pantry) {
        pantry.uiState.map { it.staples }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(pantry.uiState.value.staples)
    val shoppingNames by remember(pantry) {
        pantry.uiState.map { state -> state.shoppingList.map { it.productName } }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(pantry.uiState.value.shoppingList.map { it.productName })
    val language = stapleLanguage(appRecipeLanguage(LocalConfiguration.current.locales[0].toLanguageTag()))
    val rows = remember(staples, shoppingNames, language) { stapleRows(staples, shoppingNames, language) }
    val resources = LocalContext.current.resources
    val snackbars = LocalLarderSnackbars.current
    val undo = stringResource(R.string.undo_action)
    var typed by rememberSaveable { mutableStateOf("") }
    var duplicateOf by rememberSaveable { mutableStateOf<String?>(null) }

    fun remove(row: StapleRow) {
        pantry.removeStaple(row.staple.id) { removed ->
            snackbars.show(resources.getString(R.string.staples_removed, row.name), undo) { pantry.restoreStaple(removed) }
        }
    }

    StaplesContent(
        rows = rows,
        presets = StaplePresets.all,
        language = language,
        full = staples.size >= MAX_STAPLES,
        error = duplicateOf?.let { stringResource(R.string.staples_already, it) },
        typed = typed,
        onBack = onBack,
        actions = StaplesActions(
            onTogglePreset = { preset, ticked ->
                // Ticking never adds a second row of a preset (addCheck), whatever Undo did before.
                if (ticked) {
                    if (addCheck(staples, preset.name(language), language) == StapleAddCheck.Preset(preset.id)) {
                        pantry.addStaple(preset.name(language), preset.id)
                    }
                } else {
                    rows.firstOrNull { it.staple.presetId == preset.id }?.let(::remove)
                }
            },
            onRanOut = { row ->
                pantry.stapleRanOut(row.name)
                snackbars.show(resources.getString(R.string.staples_added_to_list, row.name), undo) { pantry.undoStapleRanOut(row.name) }
            },
            onRemove = ::remove,
            onTypedChange = {
                typed = cutStapleName(it)
                duplicateOf = null
            },
            onAdd = {
                when (val check = addCheck(staples, typed, language)) {
                    StapleAddCheck.Empty, StapleAddCheck.Full -> Unit
                    is StapleAddCheck.Duplicate -> duplicateOf = check.existingDisplayName
                    is StapleAddCheck.Preset -> {
                        StaplePresets.byId(check.presetId)?.let { pantry.addStaple(it.name(language), it.id) }
                        typed = ""
                    }
                    is StapleAddCheck.Own -> {
                        pantry.addStaple(check.name)
                        typed = ""
                    }
                }
            }
        )
    )
}

/** The screen without its state: [rows] in order (`stapleRows`), [presets] in table order. */
@Composable
internal fun StaplesContent(
    rows: List<StapleRow>,
    presets: List<StaplePreset>,
    language: String,
    full: Boolean,
    error: String?,
    typed: String = "",
    onBack: () -> Unit = {},
    actions: StaplesActions = StaplesActions(),
    modifier: Modifier = Modifier
) {
    val byPreset = rows.filter { it.staple.presetId != null }.associateBy { it.staple.presetId }
    // Own entries, and presets this version doesn't know (shown by name, as own entries).
    val own = rows.filter { row -> row.staple.presetId?.let { StaplePresets.byId(it) } == null }
    Scaffold(
        topBar = { LarderTopBar(title = stringResource(R.string.staples_title), onBack = onBack) },
        modifier = modifier.fillMaxSize()
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LarderSpace.s4),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = StaplesMaxWidth)
                    .fillMaxWidth()
                    .padding(top = LarderSpace.s2, bottom = LarderSpace.s8)
            ) {
                Text(
                    text = stringResource(R.string.staples_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SectionHeader(stringResource(R.string.staples_common_header))
                LarderGroup {
                    presets.forEachIndexed { index, preset ->
                        if (index > 0) LarderDivider()
                        val row = byPreset[preset.id]
                        PresetRow(
                            name = preset.name(language),
                            ticked = row != null,
                            enabled = row != null || !full,
                            onToggle = { ticked -> actions.onTogglePreset(preset, ticked) },
                            trailing = row?.let { { RanOutAction(it, { actions.onRanOut(it) }, Modifier.padding(end = LarderSpace.s2)) } }
                        )
                    }
                }
                SectionHeader(stringResource(R.string.staples_own_header))
                LarderGroup {
                    own.forEach { row ->
                        OwnRow(row, onRanOut = { actions.onRanOut(row) }, onRemove = { actions.onRemove(row) })
                        LarderDivider()
                    }
                    AddRow(typed = typed, enabled = !full, error = error, onTypedChange = actions.onTypedChange, onAdd = actions.onAdd)
                }
                if (full) {
                    Text(
                        text = stringResource(R.string.staples_full, MAX_STAPLES),
                        modifier = Modifier.padding(start = LarderSpace.s2, top = LarderSpace.s2, end = LarderSpace.s2),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    modifier = Modifier.padding(top = LarderSpace.s6),
                    horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2)
                ) {
                    LarderIcon(
                        LarderIcons.Info,
                        contentDescription = null,
                        size = LarderIconSize.Small,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Text(
                        text = stringResource(R.string.staples_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** A preset: the whole row ticks it (a checkbox); [trailing] is the Staples screen's Ran out or
 *  "On your shopping list". Onboarding's staples step uses the same row without it. */
@Composable
internal fun PresetRow(name: String, ticked: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .toggleable(value = ticked, enabled = enabled, role = Role.Checkbox, onValueChange = onToggle)
                .padding(start = LarderSpace.s2, end = LarderSpace.s2),
            horizontalArrangement = Arrangement.spacedBy(LarderSpace.s1),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LarderTick(
                checked = ticked,
                onCheckedChange = onToggle,
                contentDescription = name,
                enabled = enabled,
                modifier = Modifier.clearAndSetSemantics {}
            )
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
        }
        trailing?.invoke()
    }
}

/** One of the person's own staples: its name, Ran out or "On your shopping list", and Remove. */
@Composable
private fun OwnRow(row: StapleRow, onRanOut: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = LarderSpace.s4, end = LarderSpace.s1),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = row.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        RanOutAction(row, onRanOut)
        LarderIconButton(LarderIcons.X, contentDescription = stringResource(R.string.staples_remove, row.name), onClick = onRemove)
    }
}

/** "Ran out" while the staple is at home; once it is on the shopping list, a quiet note instead. */
@Composable
private fun RanOutAction(row: StapleRow, onRanOut: () -> Unit, modifier: Modifier = Modifier) {
    if (row.out) {
        Row(
            modifier = modifier.heightIn(min = LarderSize.touch).padding(horizontal = LarderSpace.s2),
            horizontalArrangement = Arrangement.spacedBy(LarderSpace.s1),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LarderIcon(LarderIcons.ShoppingBasket, contentDescription = null, size = LarderIconSize.Small, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = stringResource(R.string.staples_on_list),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        val description = stringResource(R.string.staples_ran_out_desc, row.name)
        LarderButton(
            text = stringResource(R.string.staples_ran_out),
            onClick = onRanOut,
            modifier = modifier.semantics { contentDescription = description },
            style = LarderButtonStyle.Text,
            maxLines = 1
        )
    }
}

/** The add field, last in "Your own": Done or Add adds what is typed (`addCheck`). */
@Composable
private fun AddRow(typed: String, enabled: Boolean, error: String?, onTypedChange: (String) -> Unit, onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(LarderSpace.s3),
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2),
        verticalAlignment = Alignment.Top
    ) {
        LarderTextField(
            value = typed,
            onValueChange = onTypedChange,
            label = null,
            placeholder = stringResource(R.string.staples_add_placeholder),
            modifier = Modifier.weight(1f),
            helper = error,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onAdd() })
        )
        LarderButton(
            text = stringResource(R.string.staples_add),
            onClick = onAdd,
            style = LarderButtonStyle.Secondary,
            enabled = enabled && typed.isNotBlank()
        )
    }
}

// --- Previews ---------------------------------------------------------------------------------

private fun previewStaple(index: Int, name: String, presetId: String? = null) =
    Staple(id = "staple_$index", name = name, presetId = presetId, addedAtEpochMillis = 1_762_000_000_000L + index)

private fun presetStaple(index: Int, id: String) = previewStaple(index, StaplePresets.byId(id)!!.name("en"), id)

@Composable
private fun StaplesPreview(staples: List<Staple>, shopping: List<String> = emptyList(), error: String? = null) = LarderTheme {
    Surface(color = MaterialTheme.colorScheme.background) {
        StaplesContent(
            rows = stapleRows(staples, shopping, "en"),
            presets = StaplePresets.all,
            language = "en",
            full = staples.size >= MAX_STAPLES,
            error = error
        )
    }
}

/** Salt, olive oil and eggs ticked, eggs on the shopping list; two of the person's own. */
@PreviewLightDark
@Composable
internal fun StaplesContentPreview() = StaplesPreview(
    staples = listOf(
        presetStaple(0, "salt"),
        presetStaple(1, "olive_oil"),
        presetStaple(2, "eggs"),
        previewStaple(3, "Smoked paprika"),
        previewStaple(4, "Soy sauce")
    ),
    shopping = listOf("Eggs")
)

@PreviewLightDark
@Composable
internal fun StaplesContentEmptyPreview() = StaplesPreview(staples = emptyList())

/** Every preset and six of the person's own: 20, so the field is off and the limit is said. */
@PreviewLightDark
@Composable
internal fun StaplesContentFullPreview() = StaplesPreview(
    staples = StaplePresets.all.mapIndexed { index, preset -> presetStaple(index, preset.id) } +
        listOf("Smoked paprika", "Soy sauce", "Honey", "Mustard", "Oats", "Tomato paste").mapIndexed { index, name -> previewStaple(100 + index, name) }
)
