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
import androidx.compose.runtime.LaunchedEffect
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
import com.foodtracker.android.ui.design.LarderFavouriteButton
import com.foodtracker.android.ui.design.SectionHeader
import com.foodtracker.android.ui.theme.LarderRadius
import com.foodtracker.android.ui.theme.LarderSpace
import com.foodtracker.android.ui.theme.LarderTheme
import com.foodtracker.recipe.Recipe
import com.foodtracker.recipe.RecipeDifficulty
import com.foodtracker.recipe.RecipeIngredient
import com.foodtracker.recipe.RecipeSource
import com.foodtracker.recipe.RecipeStateHolder
import com.foodtracker.recipe.DEFAULT_MENU_DAYS
import com.foodtracker.recipe.Menu
import com.foodtracker.recipe.MenuCourseKind
import com.foodtracker.recipe.MenuCourse
import com.foodtracker.recipe.PastDateItem
import com.foodtracker.recipe.MenuDay
import com.foodtracker.recipe.PictureNotice
import com.foodtracker.recipe.ReportFoot
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
    append(menuTitle(resources, menu))
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
    onOpenDish: (day: Int, course: Int) -> Unit = { _, _ -> },
    shopping: RecipeShoppingActions = RecipeShoppingActions()
) {
    val state by stateHolder.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val saved = state.saved.firstOrNull { it.id == savedId }
    val recipe = saved?.recipe
    val menu = saved?.menu
    val today = DateUtils.today()
    // Opened, so no longer new in Saved recipes (also once it arrives, if it was still loading).
    LaunchedEffect(savedId, saved != null) {
        if (saved != null) stateHolder.markSavedRead(savedId)
    }
    Scaffold(
        topBar = {
            LarderTopBar(
                title = stringResource(if (menu != null) R.string.recipe_menu_bar_title else R.string.recipe_result_title),
                onBack = onBack,
                actions = {
                    if (saved != null) {
                        LarderFavouriteButton(
                            favourite = saved.favorite,
                            onToggle = { stateHolder.setSavedFavorite(savedId, it) }
                        )
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
                recipe != null -> {
                    PictureNoticeLifetime(stateHolder, savedId)
                    RecipeDocument(
                        recipe,
                        shopping = shopping,
                        pictures = rememberRecipePictures(savedId, recipe),
                        notice = state.pictureNotices[savedId],
                        report = saved?.let(state::reportFoot) ?: ReportFoot.HIDDEN,
                        onReport = { stateHolder.openReport(savedId) }
                    )
                }
                menu != null -> MenuDocument(menu, today, onOpenDish, shopping, report = saved?.let(state::reportFoot) ?: ReportFoot.HIDDEN, onReport = { stateHolder.openReport(savedId) })
                // Still loading, or deleted from another screen.
                state.savedLoaded -> EmptyState(title = stringResource(R.string.recipe_not_found), art = null)
            }
        }
    }
    ReportHost(stateHolder, state, savedId)
}

/** One dish of a saved menu, in the recipe's own layout; its day and course in the bar. */
@Composable
fun MenuDishScreen(
    stateHolder: RecipeStateHolder,
    savedId: String,
    day: Int,
    course: Int,
    onBack: () -> Unit,
    shopping: RecipeShoppingActions = RecipeShoppingActions()
) {
    val state by stateHolder.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = context.resources
    val savedMenu = state.saved.firstOrNull { it.id == savedId }
    val menuDays = savedMenu?.menu?.days
    val menuDay = menuDays?.getOrNull(day)
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
                dish != null -> RecipeDocument(
                    dish.recipe,
                    partOfMenu = true,
                    menuDays = menuDays?.size ?: DEFAULT_MENU_DAYS,
                    shopping = shopping,
                    // A dish reports its menu's generation, in the recipe's own words.
                    report = savedMenu?.let(state::reportFoot) ?: ReportFoot.HIDDEN,
                    onReport = { stateHolder.openReport(savedId, asDish = true) }
                )
                state.savedLoaded -> EmptyState(title = stringResource(R.string.recipe_not_found), art = null)
            }
        }
    }
    ReportHost(stateHolder, state, savedId)
}

/** One section of course rows per day (1 to 4, as many as the menu has), each day saying which
 *  dated items it uses. A day of one course is a section with one row. */
@Composable
internal fun MenuDocument(
    menu: Menu,
    today: LocalDate,
    onOpenDish: (day: Int, course: Int) -> Unit,
    shopping: RecipeShoppingActions = RecipeShoppingActions(),
    report: ReportFoot = ReportFoot.HIDDEN,
    onReport: () -> Unit = {}
) {
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
                text = menuTitle(resources, menu),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (courses > 0 && servings > 0) {
                Text(
                    text = pluralStringResource(R.plurals.recipe_menu_summary, courses, courses, servings),
                    modifier = Modifier.padding(top = LarderSpace.s2),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            PastDateCallout(menu.pastDate, Modifier.padding(top = LarderSpace.s3))
            // One list for every dish of every day; each dish also offers its own.
            AddToShoppingButton(
                menu.days.flatMap { day -> day.courses.map { it.recipe } },
                shopping,
                Modifier.padding(top = LarderSpace.s3).fillMaxWidth()
            )
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
            ReportFootRow(report, menu = true, onReport = onReport, modifier = Modifier.padding(top = LarderSpace.s6))
        }
    }
}

@Composable
internal fun RecipeDocument(
    recipe: Recipe,
    partOfMenu: Boolean = false,
    /** The days of the menu it is part of, for "Part of Menu for N days". */
    menuDays: Int = DEFAULT_MENU_DAYS,
    shopping: RecipeShoppingActions = RecipeShoppingActions(),
    /** Its pictures, from the files kept with the saved recipe. */
    pictures: RecipeDocumentPictures = RecipeDocumentPictures(),
    /** Pictures asked for and not made: only on the result that has just arrived. */
    notice: PictureNotice? = null,
    report: ReportFoot = ReportFoot.HIDDEN,
    onReport: () -> Unit = {}
) {
    val resources = LocalContext.current.resources
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = LarderSpace.s4),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(top = LarderSpace.s2, bottom = LarderSpace.s8)) {
            // The dish's picture above the title, with what it is said under it, always.
            pictures.dish?.let { dish ->
                RecipePictureImage(dish, stringResource(R.string.recipe_image_desc, recipe.title))
                PictureLine(
                    stringResource(R.string.recipe_image_caption),
                    Modifier.padding(top = LarderSpace.s2, bottom = LarderSpace.s3),
                    icon = LarderIcons.Sparkles
                )
            }
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
                // Never mistaken for one from our server: the phone's model is the weaker writer.
                if (recipe.source == RecipeSource.DEVICE && !partOfMenu) {
                    RecipeFact(LarderIcons.Smartphone, stringResource(R.string.recipe_saved_from_phone))
                }
            }
            if (notice?.dishFailed == true) {
                PictureLine(stringResource(R.string.recipe_image_failed), Modifier.padding(top = LarderSpace.s3), icon = LarderIcons.ImageOff)
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
            NotFromPantryHint(recipe, Modifier.padding(top = LarderSpace.s2))
            AddToShoppingButton(listOf(recipe), shopping, Modifier.padding(top = LarderSpace.s3).fillMaxWidth())

            SectionHeader(stringResource(R.string.recipe_method))
            if (pictures.steps.isNotEmpty()) {
                PictureLine(stringResource(R.string.recipe_image_steps_caption), Modifier.padding(bottom = LarderSpace.s3), icon = LarderIcons.Sparkles)
            }
            if (notice?.stepsFailed == true) {
                PictureLine(stringResource(R.string.recipe_image_steps_failed), Modifier.padding(bottom = LarderSpace.s3), icon = LarderIcons.ImageOff)
            }
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
                        Column(modifier = Modifier.weight(1f).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(LarderSpace.s3)) {
                            Text(
                                text = step,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            // Inside its step, at the text's width.
                            pictures.steps[index]?.let { picture ->
                                RecipePictureImage(picture, stringResource(R.string.recipe_image_step_desc, index + 1))
                            }
                        }
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

            // A dish says which menu it is part of; a recipe from the phone's model, under its chip,
            // that nothing was sent. A server-written recipe has no source line.
            val footer = when {
                partOfMenu -> pluralStringResource(R.plurals.recipe_part_of_menu, menuDays, menuDays)
                recipe.source == RecipeSource.DEVICE -> stringResource(R.string.recipe_source_phone)
                else -> null
            }
            if (footer != null) {
                Row(
                    modifier = Modifier.padding(top = LarderSpace.s6),
                    horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LarderIcon(
                        icon = if (partOfMenu) LarderIcons.Utensils else LarderIcons.Smartphone,
                        contentDescription = null,
                        size = 16.dp,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = footer,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // At the very end of every result our server wrote.
            ReportFootRow(report, menu = false, onReport = onReport, modifier = Modifier.padding(top = LarderSpace.s6))
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
        } else if (ingredient.stapleName != null) {
            // One of the person's staples: the pantry chip's shape in neutral tones.
            val scheme = MaterialTheme.colorScheme
            Row(
                modifier = Modifier
                    .background(scheme.surfaceContainerHigh, RoundedCornerShape(LarderRadius.sm))
                    .padding(start = LarderSpace.s1, end = LarderSpace.s2, top = 1.dp, bottom = 1.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LarderIcon(LarderIcons.Wheat, contentDescription = null, size = 14.dp, tint = scheme.onSurfaceVariant)
                Text(
                    text = stringResource(R.string.recipe_your_staple),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
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

/** An ingredient that is one of the person's staples, beside one from the pantry and one to buy. */
@PreviewLightDark
@Composable
internal fun RecipeIngredientStaplePreview() = LarderTheme {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.padding(LarderSpace.s4)) {
            LarderGroup {
                IngredientRow(RecipeIngredient("2", "Free-range eggs", fromPantry = true))
                LarderDivider()
                IngredientRow(RecipeIngredient("1 tbsp", "Olive oil", stapleIndex = 0, stapleName = "Olive oil"))
                LarderDivider()
                IngredientRow(RecipeIngredient("1 clove", "Garlic"))
            }
        }
    }
}

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
    Surface(color = MaterialTheme.colorScheme.background) { MenuDocument(menu, today, onOpenDish = { _, _ -> }) }
}

/** A menu set of one day with only the main course. */
@Preview
@Composable
internal fun MenuDocumentOneDayPreview() = LarderTheme {
    val today = LocalDate(2026, 11, 4)
    val menu = Menu(
        listOf(
            MenuDay(
                date = today,
                uses = listOf("bread", "spinach"),
                courses = listOf(MenuCourse(MenuCourseKind.MAIN, previewRecipe.copy(source = RecipeSource.SERVER)))
            )
        )
    )
    Surface(color = MaterialTheme.colorScheme.background) { MenuDocument(menu, today, onOpenDish = { _, _ -> }) }
}

@Preview
@Composable
internal fun MenuDishPreview() = LarderTheme {
    Surface(color = MaterialTheme.colorScheme.background) { RecipeDocument(previewRecipe.copy(source = RecipeSource.SERVER), partOfMenu = true) }
}

/** A server recipe with the dish's picture and two step pictures, as it arrived (captions), and its report foot. */
@PreviewLightDark
@Composable
internal fun RecipeDocumentPicturesPreview() = LarderTheme {
    Surface(color = MaterialTheme.colorScheme.background) {
        RecipeDocument(
            previewRecipe.copy(source = RecipeSource.SERVER),
            pictures = RecipeDocumentPictures(SampleDishPainter(), mapOf(0 to SampleDishPainter(0), 2 to SampleDishPainter(2, height = 800f))),
            report = ReportFoot.AVAILABLE
        )
    }
}

/** The dish's picture only. */
@Preview
@Composable
internal fun RecipeDocumentDishPicturePreview() = LarderTheme {
    Surface(color = MaterialTheme.colorScheme.background) {
        RecipeDocument(previewRecipe.copy(source = RecipeSource.SERVER), pictures = RecipeDocumentPictures(SampleDishPainter()), report = ReportFoot.REPORTED)
    }
}

/** Just arrived without the dish's picture: the quiet line under the facts. */
@Preview
@Composable
internal fun RecipeDocumentPictureFailedPreview() = LarderTheme {
    Surface(color = MaterialTheme.colorScheme.background) {
        RecipeDocument(previewRecipe.copy(source = RecipeSource.SERVER), notice = PictureNotice(dishFailed = true, stepsFailed = false), report = ReportFoot.AVAILABLE)
    }
}

/** Just arrived with the dish but not every step picture: the line under Method. */
@Preview
@Composable
internal fun RecipeDocumentStepsFailedPreview() = LarderTheme {
    Surface(color = MaterialTheme.colorScheme.background) {
        RecipeDocument(
            previewRecipe.copy(source = RecipeSource.SERVER),
            pictures = RecipeDocumentPictures(SampleDishPainter(), mapOf(1 to SampleDishPainter(1))),
            notice = PictureNotice(dishFailed = false, stepsFailed = true)
        )
    }
}

/** A menu's foot: Report this menu. */
@Preview
@Composable
internal fun MenuDocumentReportPreview() = LarderTheme {
    val today = LocalDate(2026, 11, 4)
    val menu = Menu(
        listOf(MenuDay(date = today, uses = listOf("bread"), courses = listOf(MenuCourse(MenuCourseKind.MAIN, previewRecipe.copy(source = RecipeSource.SERVER)))))
    )
    Surface(color = MaterialTheme.colorScheme.background) { MenuDocument(menu, today, onOpenDish = { _, _ -> }, report = ReportFoot.AVAILABLE) }
}
