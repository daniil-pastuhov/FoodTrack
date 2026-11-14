package com.foodtracker.android.ui.screens

import android.content.Intent
import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodtracker.android.R
import com.foodtracker.android.ui.design.EmptyState
import com.foodtracker.android.ui.design.LarderCallout
import com.foodtracker.android.ui.design.LarderCalloutKind
import com.foodtracker.android.ui.design.LarderDivider
import com.foodtracker.android.ui.design.LarderGroup
import com.foodtracker.android.ui.design.LarderIcon
import com.foodtracker.android.ui.design.LarderIconButton
import com.foodtracker.android.ui.design.LarderIcons
import com.foodtracker.android.ui.design.LarderTopBar
import com.foodtracker.android.ui.design.SectionHeader
import com.foodtracker.android.ui.theme.LarderRadius
import com.foodtracker.android.ui.theme.LarderSpace
import com.foodtracker.android.ui.theme.LarderTheme
import com.foodtracker.recipe.Recipe
import com.foodtracker.recipe.RecipeDifficulty
import com.foodtracker.recipe.RecipeIngredient
import com.foodtracker.recipe.RecipeSource
import com.foodtracker.recipe.RecipeStateHolder
import com.foodtracker.recipe.Menu
import com.foodtracker.recipe.MenuCourseKind
import com.foodtracker.recipe.MenuCourse
import com.foodtracker.recipe.PastDateItem
import com.foodtracker.recipe.MenuDay
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import com.foodtracker.util.DateUtils
import android.text.format.DateFormat
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toJavaLocalDate
import java.time.format.DateTimeFormatter

/** "Hamburgare (145 days over)": each item with the status chip's own wording, its first letter
 *  lowered where the wording starts a sentence ("Просрочено…", "Utløpt…"). */
internal fun pastDateList(resources: Resources, items: List<PastDateItem>): String {
    val locale = resources.configuration.locales[0]
    return items.joinToString(", ") { item ->
        val over = resources.getQuantityString(R.plurals.status_days_over, item.daysOver, item.daysOver)
        val lowered = if (locale.language == "en") over else over.replaceFirstChar { it.lowercase(locale) }
        resources.getString(R.string.recipe_past_date_item, isolated(item.name), lowered)
    }
}

/** The red line above a written recipe's ingredients and in its shared text, or null with none
 *  past its date. */
internal fun pastDateWarning(resources: Resources, items: List<PastDateItem>): String? = when {
    items.isEmpty() -> null
    items.size == 1 -> resources.getString(R.string.recipe_past_date_result_one, pastDateList(resources, items))
    else -> resources.getString(R.string.recipe_past_date_result_many, pastDateList(resources, items))
}

/** The form's red callout over "Cook with": how many ticked items are past their date and the
 *  oldest, or null when none is. */
internal fun pastDateFormWarning(resources: Resources, items: List<PastDateItem>): String? {
    if (items.isEmpty()) return null
    val oldest = items.maxOf { it.daysOver }
    val days = resources.getQuantityString(R.plurals.recipe_past_days, oldest, oldest)
    return if (items.size == 1) {
        resources.getString(R.string.recipe_past_date_form_single, days)
    } else {
        resources.getQuantityString(R.plurals.recipe_past_date_form, items.size, items.size, days)
    }
}

/** A recipe as plain text, for Share: what another app or a message can hold. */
fun recipeShareText(resources: Resources, recipe: Recipe): String = buildString {
    append(recipe.title)
    pastDateWarning(resources, recipe.pastDate)?.let { append("\n\n").append("⚠ ").append(it) }
    if (recipe.summary.isNotBlank()) append("\n\n").append(recipe.summary)
    val facts = listOfNotNull(
        recipe.minutes.takeIf { it > 0 }?.let { recipeDurationLabel(resources, it) },
        resources.getQuantityString(R.plurals.recipe_servings_count, recipe.servings, recipe.servings),
        recipeDifficultyLabel(resources, recipe.difficulty)
    )
    append("\n").append(facts.joinToString(" · "))
    append("\n\n").append(resources.getString(R.string.recipe_ingredients)).append("\n")
    append(recipe.ingredients.joinToString("\n") { "- " + listOf(it.amount, it.name).filter(String::isNotBlank).joinToString(" ") })
    append("\n\n").append(resources.getString(R.string.recipe_method)).append("\n")
    append(recipe.steps.mapIndexed { index, step -> "${index + 1}. $step" }.joinToString("\n"))
    recipe.tip?.let { append("\n\n").append(resources.getString(R.string.recipe_tip)).append(": ").append(it) }
}

/** A menu's day as its section says it: "Today, Wed 4 Nov", then "Thu 5 Nov". With no [today],
 *  never "Today". */
internal fun menuDayLabel(resources: Resources, date: LocalDate, today: LocalDate?): String {
    val locale = resources.configuration.locales[0]
    val formatter = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEdMMM"), locale)
    val day = date.toJavaLocalDate().format(formatter)
    return if (date == today) resources.getString(R.string.recipe_menu_today, day) else day
}

internal fun menuCourseLabel(resources: Resources, course: MenuCourseKind): String = resources.getString(
    when (course) {
        MenuCourseKind.STARTER -> R.string.recipe_course_starter
        MenuCourseKind.MAIN -> R.string.recipe_course_main
        MenuCourseKind.DESSERT -> R.string.recipe_course_dessert
    }
)

/** A menu as plain text, for Share: each day with its dishes, then every recipe in full. */
fun menuShareText(resources: Resources, menu: Menu, today: LocalDate): String = buildString {
    append(resources.getString(R.string.recipe_menu_title))
    pastDateWarning(resources, menu.pastDate)?.let { append("\n\n").append("⚠ ").append(it) }
    menu.days.forEach { day ->
        append("\n\n").append(menuDayLabel(resources, day.date, today))
        day.courses.forEach { append("\n- ").append(menuCourseLabel(resources, it.course)).append(": ").append(it.recipe.title) }
    }
    menu.days.forEach { day ->
        day.courses.forEach { course ->
            append("\n\n---\n").append(menuDayLabel(resources, day.date, today)).append(" · ").append(menuCourseLabel(resources, course.course))
            append("\n").append(recipeShareText(resources, course.recipe))
        }
    }
}

private fun share(context: android.content.Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, null))
}

/** A saved recipe or menu, read on a counter at arm's length: large type, numbered steps. Saved
 *  already, so there is no Save button; Delete is here as the visible twin of the saved list's
 *  swipe. A menu lists its days and dishes; each dish opens on its own. */
@Composable
fun RecipeResultScreen(
    stateHolder: RecipeStateHolder,
    savedId: String,
    onBack: () -> Unit,
    onDelete: (String) -> Unit,
    onOpenDish: (day: Int, course: Int) -> Unit = { _, _ -> }
) {
    val state by stateHolder.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val saved = state.saved.firstOrNull { it.id == savedId }
    val recipe = saved?.recipe
    val menu = saved?.menu
    val today = DateUtils.today()
    Scaffold(
        topBar = {
            LarderTopBar(
                title = stringResource(if (menu != null) R.string.recipe_menu_bar_title else R.string.recipe_result_title),
                onBack = onBack,
                actions = {
                    if (saved != null) {
                        LarderIconButton(
                            icon = LarderIcons.Trash2,
                            contentDescription = stringResource(R.string.delete),
                            onClick = { onDelete(savedId) }
                        )
                        LarderIconButton(
                            icon = LarderIcons.Share2,
                            contentDescription = stringResource(R.string.recipe_share_recipe),
                            onClick = {
                                val text = when {
                                    recipe != null -> recipeShareText(context.resources, recipe)
                                    menu != null -> menuShareText(context.resources, menu, today)
                                    else -> return@LarderIconButton
                                }
                                share(context, text)
                            }
                        )
                    }
                }
            )
        },
        modifier = Modifier.fillMaxSize()
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when {
                recipe != null -> RecipeDocument(recipe)
                menu != null -> MenuDocument(menu, today, onOpenDish)
                // Still loading, or deleted from another screen.
                state.savedLoaded -> EmptyState(title = stringResource(R.string.recipe_not_found), art = null)
            }
        }
    }
}

/** One dish of a saved menu, in the recipe's own layout; its day and course in the bar. */
@Composable
fun MenuDishScreen(
    stateHolder: RecipeStateHolder,
    savedId: String,
    day: Int,
    course: Int,
    onBack: () -> Unit
) {
    val state by stateHolder.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = context.resources
    val menuDay = state.saved.firstOrNull { it.id == savedId }?.menu?.days?.getOrNull(day)
    val dish = menuDay?.courses?.getOrNull(course)
    Scaffold(
        topBar = {
            LarderTopBar(
                title = if (menuDay != null && dish != null) {
                    menuDayLabel(resources, menuDay.date, today = null) + " · " + menuCourseLabel(resources, dish.course)
                } else {
                    stringResource(R.string.recipe_result_title)
                },
                onBack = onBack,
                actions = {
                    if (dish != null) {
                        LarderIconButton(
                            icon = LarderIcons.Share2,
                            contentDescription = stringResource(R.string.recipe_share_recipe),
                            onClick = { share(context, recipeShareText(resources, dish.recipe)) }
                        )
                    }
                }
            )
        },
        modifier = Modifier.fillMaxSize()
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when {
                dish != null -> RecipeDocument(dish.recipe, partOfMenu = true)
                state.savedLoaded -> EmptyState(title = stringResource(R.string.recipe_not_found), art = null)
            }
        }
    }
}

/** Three day sections of course rows, each day saying which dated items it uses. */
@Composable
internal fun MenuDocument(menu: Menu, today: LocalDate, onOpenDish: (day: Int, course: Int) -> Unit) {
    val resources = LocalContext.current.resources
    val courses = menu.days.maxOfOrNull { it.courses.size } ?: 0
    val servings = menu.days.firstOrNull()?.courses?.firstOrNull()?.recipe?.servings ?: 0
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = LarderSpace.s4),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(top = LarderSpace.s2, bottom = LarderSpace.s8)) {
            Text(
                text = stringResource(R.string.recipe_menu_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (courses > 0 && servings > 0) {
                Text(
                    text = stringResource(R.string.recipe_menu_summary, courses, servings),
                    modifier = Modifier.padding(top = LarderSpace.s2),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            PastDateCallout(menu.pastDate, Modifier.padding(top = LarderSpace.s3))
            menu.days.forEachIndexed { dayIndex, day ->
                SectionHeader(menuDayLabel(resources, day.date, today))
                if (day.uses.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.recipe_menu_uses, isolatedList(day.uses)),
                        modifier = Modifier.padding(bottom = LarderSpace.s2),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // The day's own share of the menu's past-date items, as each of its dishes says.
                PastDateCallout(day.courses.firstOrNull()?.recipe?.pastDate.orEmpty(), Modifier.padding(bottom = LarderSpace.s2))
                LarderGroup {
                    day.courses.forEachIndexed { courseIndex, course ->
                        if (courseIndex > 0) LarderDivider()
                        DishRow(
                            course = menuCourseLabel(resources, course.course),
                            title = course.recipe.title,
                            minutes = course.recipe.minutes.takeIf { it > 0 }?.let { recipeDurationLabel(resources, it) },
                            onClick = { onOpenDish(dayIndex, courseIndex) }
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.padding(top = LarderSpace.s6),
                horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LarderIcon(LarderIcons.Cloud, contentDescription = null, size = 16.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = stringResource(R.string.recipe_source_server),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun RecipeDocument(recipe: Recipe, partOfMenu: Boolean = false) {
    val resources = LocalContext.current.resources
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = LarderSpace.s4),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(top = LarderSpace.s2, bottom = LarderSpace.s8)) {
            Text(
                text = recipe.title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (recipe.summary.isNotBlank()) {
                Text(
                    text = recipe.summary,
                    modifier = Modifier.padding(top = LarderSpace.s2),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FlowRow(
                modifier = Modifier.padding(top = LarderSpace.s3),
                horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2),
                verticalArrangement = Arrangement.spacedBy(LarderSpace.s2)
            ) {
                if (recipe.minutes > 0) RecipeFact(LarderIcons.Timer, recipeDurationLabel(resources, recipe.minutes))
                RecipeFact(
                    LarderIcons.Users,
                    pluralStringResource(R.plurals.recipe_servings_count, recipe.servings, recipe.servings)
                )
                RecipeFact(LarderIcons.Gauge, recipeDifficultyLabel(resources, recipe.difficulty))
            }
            // Above the ingredients, never in a footer: seen as the recipe opens.
            PastDateCallout(recipe.pastDate, Modifier.padding(top = LarderSpace.s4))

            SectionHeader(stringResource(R.string.recipe_ingredients))
            LarderGroup {
                recipe.ingredients.forEachIndexed { index, ingredient ->
                    if (index > 0) LarderDivider()
                    IngredientRow(ingredient)
                }
            }

            SectionHeader(stringResource(R.string.recipe_method))
            Column(verticalArrangement = Arrangement.spacedBy(LarderSpace.s4)) {
                recipe.steps.forEachIndexed { index, step ->
                    Row(horizontalArrangement = Arrangement.spacedBy(LarderSpace.s3)) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = (index + 1).toString(),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Text(
                            text = step,
                            modifier = Modifier.weight(1f).padding(top = 2.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }

            recipe.tip?.let { tip ->
                LarderCallout(
                    kind = LarderCalloutKind.Info,
                    icon = LarderIcons.Lightbulb,
                    title = stringResource(R.string.recipe_tip),
                    message = tip,
                    modifier = Modifier.padding(top = LarderSpace.s4)
                )
            }

            Row(
                modifier = Modifier.padding(top = LarderSpace.s6),
                horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val fromPhone = recipe.source == RecipeSource.DEVICE
                LarderIcon(
                    icon = if (partOfMenu) LarderIcons.Utensils else if (fromPhone) LarderIcons.Smartphone else LarderIcons.Cloud,
                    contentDescription = null,
                    size = 16.dp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(
                        when {
                            partOfMenu -> R.string.recipe_part_of_menu
                            fromPhone -> R.string.recipe_source_phone
                            else -> R.string.recipe_source_server
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** A dish in a menu: its course over its name, the time at the end. */
/** The red past-date warning of a written recipe, menu or menu day; nothing when none is. */
@Composable
internal fun PastDateCallout(items: List<PastDateItem>, modifier: Modifier = Modifier) {
    val text = pastDateWarning(LocalContext.current.resources, items) ?: return
    LarderCallout(
        kind = LarderCalloutKind.Error,
        icon = LarderIcons.TriangleAlert,
        message = text,
        modifier = modifier
    )
}

@Composable
private fun DishRow(course: String, title: String, minutes: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = LarderSpace.s4, vertical = LarderSpace.s2),
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s3),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = course, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        minutes?.let {
            Text(text = it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LarderIcon(LarderIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One of the facts under the title: time, servings, difficulty. */
@Composable
private fun RecipeFact(icon: LarderIcons, text: String) {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(LarderRadius.sm))
            .padding(horizontal = LarderSpace.s2, vertical = LarderSpace.s1),
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s1),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LarderIcon(icon, contentDescription = null, size = 16.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Amount in a fixed column so the names line up; items from the pantry are marked. */
@Composable
private fun IngredientRow(ingredient: RecipeIngredient) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LarderSpace.s4, vertical = LarderSpace.s3),
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s3),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = ingredient.amount,
            modifier = Modifier.width(72.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = ingredient.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (ingredient.fromPantry) {
            val colors = LarderTheme.colors
            Row(
                modifier = Modifier
                    .background(colors.successContainer, RoundedCornerShape(LarderRadius.sm))
                    .padding(start = LarderSpace.s1, end = LarderSpace.s2, top = 1.dp, bottom = 1.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LarderIcon(LarderIcons.Check, contentDescription = null, size = 14.dp, tint = colors.onSuccessContainer)
                Text(
                    text = stringResource(R.string.recipe_in_pantry),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSuccessContainer,
                    textAlign = TextAlign.Start
                )
            }
        }
    }
}

private val previewRecipe = Recipe(
    title = "Spinach and yoghurt eggs on sourdough toast",
    summary = "A warm, quick plate that uses the bread, the spinach and one of the yoghurts today.",
    minutes = 15,
    servings = 2,
    difficulty = RecipeDifficulty.EASY,
    ingredients = listOf(
        RecipeIngredient("4 slices", "Sourdough bread", fromPantry = true),
        RecipeIngredient("100 g", "Baby spinach", fromPantry = true),
        RecipeIngredient("1 clove", "Garlic"),
        RecipeIngredient("", "Salt, pepper, chilli flakes")
    ),
    steps = listOf(
        "Stir the grated garlic and a pinch of salt into the yoghurt.",
        "Toast the bread. Day-old sourdough toasts better than fresh.",
        "Spread the yoghurt on the toast, pile on the spinach and top with the eggs."
    ),
    tip = "Check the bread before using it: one day past a best-before date is usually fine, but not if there is any mould.",
    source = RecipeSource.DEVICE
)

@PreviewLightDark
@Composable
internal fun RecipeDocumentPreview() = LarderTheme {
    Surface(color = MaterialTheme.colorScheme.background) { RecipeDocument(previewRecipe) }
}

@Preview(fontScale = 1.5f)
@Composable
internal fun RecipeDocumentLongPreview() = LarderTheme {
    Surface(color = MaterialTheme.colorScheme.background) {
        RecipeDocument(
            previewRecipe.copy(
                title = "Slow-roasted salmon with spinach, yoghurt and a sourdough crumb",
                minutes = 70,
                difficulty = RecipeDifficulty.AMBITIOUS,
                tip = null
            )
        )
    }
}

@PreviewLightDark
@Composable
internal fun MenuDocumentPreview() = LarderTheme {
    val today = LocalDate(2026, 11, 4)
    val menu = Menu(
        (0 until 3).map { offset ->
            MenuDay(
                date = LocalDate.fromEpochDays(today.toEpochDays() + offset),
                uses = listOf("bread", "spinach", "yoghurt").take(3 - offset),
                courses = MenuCourseKind.entries.map { kind ->
                    MenuCourse(kind, previewRecipe.copy(title = "${kind.name.lowercase()} on day ${offset + 1}", source = RecipeSource.SERVER))
                }
            )
        }
    )
    Surface(color = MaterialTheme.colorScheme.background) { MenuDocument(menu, today) { _, _ -> } }
}

@Preview
@Composable
internal fun MenuDishPreview() = LarderTheme {
    Surface(color = MaterialTheme.colorScheme.background) { RecipeDocument(previewRecipe.copy(source = RecipeSource.SERVER), partOfMenu = true) }
}
