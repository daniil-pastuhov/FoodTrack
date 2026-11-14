package com.foodtracker.android.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodtracker.android.R
import com.foodtracker.android.ui.design.LarderDivider
import com.foodtracker.android.ui.design.LarderGroup
import com.foodtracker.android.ui.design.LarderIcon
import com.foodtracker.android.ui.design.LarderIcons
import com.foodtracker.android.ui.design.LarderListItem
import com.foodtracker.android.ui.design.LarderSheet
import com.foodtracker.android.ui.design.LarderSwitch
import com.foodtracker.android.ui.design.LarderTopBar
import com.foodtracker.android.ui.theme.LarderRadius
import com.foodtracker.android.ui.theme.LarderSpace
import com.foodtracker.android.ui.theme.LarderTheme
import com.foodtracker.android.util.recipeDietLabel
import com.foodtracker.android.util.recipeMealLabel
import com.foodtracker.recipe.AllergenConsentRecord
import com.foodtracker.recipe.RECIPE_LANGUAGES
import com.foodtracker.recipe.PaidUiState
import com.foodtracker.recipe.allergenConsentGiven
import java.text.DateFormat
import java.util.Date
import com.foodtracker.recipe.RecipeConfig
import com.foodtracker.recipe.RecipeDiet
import com.foodtracker.recipe.RecipeMealType
import com.foodtracker.recipe.RecipeStateHolder
import com.foodtracker.recipe.recipeLanguageEndonym

/**
 * The tab's own settings, separate from app Settings: every field of the configuration, the
 * language, and where bought recipes are kept. Changes save as they are made.
 */
@Composable
fun RecipeSettingsScreen(stateHolder: RecipeStateHolder, onBack: () -> Unit) {
    val state by stateHolder.uiState.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { LarderTopBar(title = stringResource(R.string.recipe_settings_action), onBack = onBack) },
        modifier = Modifier.fillMaxSize()
    ) { padding ->
        RecipeSettingsContent(
            config = state.config,
            appLanguage = state.appLanguage,
            allergens = state.allergens,
            onChange = stateHolder::updateConfig,
            modifier = Modifier.padding(padding),
            paid = state.paid,
            accountActions = AccountActions(
                onSignIn = stateHolder::signIn,
                onBuy = stateHolder::openPacks,
                onSignOut = stateHolder::signOut,
                onDelete = stateHolder::deleteAccount
            ),
            onAllergenConsent = stateHolder::setAllergenConsent
        )
    }
    RecipePaidOverlays(state, stateHolder)
}

@Composable
private fun RecipeSettingsContent(
    config: RecipeConfig,
    appLanguage: String,
    allergens: List<String>,
    onChange: ((RecipeConfig) -> RecipeConfig) -> Unit,
    modifier: Modifier = Modifier,
    paid: PaidUiState = PaidUiState(),
    accountActions: AccountActions = AccountActions(),
    onAllergenConsent: (Boolean) -> Unit = {}
) {
    val resources = LocalContext.current.resources
    var pickingLanguage by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = LarderSpace.s4),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .padding(top = LarderSpace.s2, bottom = LarderSpace.s8),
            verticalArrangement = Arrangement.spacedBy(LarderSpace.s3)
        ) {
            RecipeFormCard(title = stringResource(R.string.recipe_cooking), icon = LarderIcons.ChefHat) {
                SettingsDescription(stringResource(R.string.recipe_cooking_desc))
                DifficultyControl(config.difficulty) { value -> onChange { it.copy(difficulty = value) } }
                DetailControl(config.detail) { value -> onChange { it.copy(detail = value) } }
                ServingsControl(config.servings) { value -> onChange { it.copy(servings = value) } }
                TimeControl(config.maxMinutes) { value -> onChange { it.copy(maxMinutes = value) } }
            }

            RecipeFormCard(title = stringResource(R.string.recipe_meal_and_diet), icon = LarderIcons.Utensils) {
                Column(verticalArrangement = Arrangement.spacedBy(LarderSpace.s2)) {
                    RecipeControlLabel(stringResource(R.string.recipe_meal))
                    ChipRow(RecipeMealType.entries, config.mealType, { value -> onChange { it.copy(mealType = value) } }) {
                        recipeMealLabel(resources, it)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(LarderSpace.s2)) {
                    RecipeControlLabel(stringResource(R.string.recipe_diet))
                    ChipRow(RecipeDiet.entries, config.diet, { value -> onChange { it.copy(diet = value) } }) {
                        recipeDietLabel(resources, it)
                    }
                }
                AllergenNote(allergens)
                // Where the consent to send allergens to our server is seen and withdrawn.
                if (paid.available) {
                    AllergenConsentRow(paid.allergenConsent, onAllergenConsent)
                }
            }

            RecipeFormCard(title = stringResource(R.string.recipe_language), icon = LarderIcons.Languages) {
                LarderListItem(
                    title = stringResource(R.string.recipe_write_in),
                    supporting = recipeLanguageLabel(resources, config.language, appLanguage),
                    leading = { LarderIcon(LarderIcons.Languages, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailing = { LarderIcon(LarderIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { pickingLanguage = true }
                )
            }

            // Builds without the paid route have no account to speak of.
            if (paid.available) AccountCard(paid, accountActions)
        }
    }

    if (pickingLanguage) {
        LanguageSheet(
            selected = config.language,
            appLanguage = appLanguage,
            onSelect = { language ->
                onChange { it.copy(language = language) }
                pickingLanguage = false
            },
            onDismiss = { pickingLanguage = false }
        )
    }
}

@Composable
private fun SettingsDescription(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The allergens from app Settings, which every recipe and prompt leaves out. */
@Composable
private fun AllergenNote(allergens: List<String>) {
    val list = allergens.takeIf { it.isNotEmpty() }?.let(::isolatedList) ?: stringResource(R.string.recipe_allergens_none)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(LarderRadius.md))
            .padding(LarderSpace.s3),
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2)
    ) {
        LarderIcon(LarderIcons.CircleAlert, contentDescription = null, size = 18.dp, tint = MaterialTheme.colorScheme.primary)
        Text(
            text = stringResource(R.string.recipe_allergens_note, list),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** Sending allergens to our server: on once agreed (with the date), off to withdraw. Turning it
 *  on shows the consent step first (RecipePaidOverlays). */
@Composable
private fun AllergenConsentRow(record: AllergenConsentRecord?, onChange: (Boolean) -> Unit) {
    val given = allergenConsentGiven(record)
    val supporting = if (given && record != null) {
        val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(record.atMillis))
        stringResource(R.string.recipe_allergen_consent_setting_on, date)
    } else {
        stringResource(R.string.recipe_allergen_consent_setting_off)
    }
    LarderListItem(
        title = stringResource(R.string.recipe_allergen_consent_setting),
        supporting = supporting,
        trailing = { LarderSwitch(given, onChange) },
        onClick = { onChange(!given) }
    )
}

/** "Same as the app", then each language in its own name. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSheet(
    selected: String?,
    appLanguage: String,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val resources = LocalContext.current.resources
    val options: List<String?> = listOf<String?>(null) + RECIPE_LANGUAGES
    LarderSheet(onDismissRequest = onDismiss, title = stringResource(R.string.recipe_write_in)) {
        LarderGroup(modifier = Modifier.padding(bottom = LarderSpace.s4)) {
            options.forEachIndexed { index, language ->
                if (index > 0) LarderDivider()
                LarderListItem(
                    title = if (language == null) recipeLanguageLabel(resources, null, appLanguage) else recipeLanguageEndonym(language),
                    trailing = {
                        if (language == selected) {
                            LarderIcon(LarderIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    onClick = { onSelect(language) }
                )
            }
        }
    }
}

@PreviewLightDark
@Composable
internal fun RecipeSettingsPreview() = LarderTheme {
    Surface(color = MaterialTheme.colorScheme.background) {
        RecipeSettingsContent(RecipeConfig(), appLanguage = "en", allergens = emptyList(), onChange = {})
    }
}

@PreviewLightDark
@Composable
internal fun RecipeSettingsChosenPreview() = LarderTheme {
    Surface(color = MaterialTheme.colorScheme.background) {
        RecipeSettingsContent(
            RecipeConfig(mealType = RecipeMealType.DINNER, diet = RecipeDiet.VEGETARIAN, language = "de"),
            appLanguage = "en",
            allergens = listOf("celery", "peanuts"),
            onChange = {}
        )
    }
}
