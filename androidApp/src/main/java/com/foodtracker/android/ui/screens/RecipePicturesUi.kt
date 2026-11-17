package com.foodtracker.android.ui.screens

import android.content.res.Resources
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.foodtracker.android.R
import com.foodtracker.android.recipe.RecipePictureFiles
import com.foodtracker.android.ui.design.LarderButton
import com.foodtracker.android.ui.design.LarderButtonStyle
import com.foodtracker.android.ui.design.LarderCallout
import com.foodtracker.android.ui.design.LarderCalloutKind
import com.foodtracker.android.ui.design.LarderDivider
import com.foodtracker.android.ui.design.LarderGroup
import com.foodtracker.android.ui.design.LarderIcon
import com.foodtracker.android.ui.design.LarderIcons
import com.foodtracker.android.ui.design.LarderListItem
import com.foodtracker.android.ui.design.LarderSheet
import com.foodtracker.android.ui.design.LarderTextField
import com.foodtracker.android.ui.navigation.LocalLarderSnackbars
import com.foodtracker.android.ui.navigation.LocalReviewPrompter
import com.foodtracker.android.ui.theme.LarderRadius
import com.foodtracker.android.ui.theme.LarderSpace
import com.foodtracker.android.ui.theme.LarderTheme
import com.foodtracker.recipe.PictureChoice
import com.foodtracker.recipe.PictureLevel
import com.foodtracker.recipe.PictureShort
import com.foodtracker.recipe.REPORT_NOTE_MAX
import com.foodtracker.recipe.Recipe
import com.foodtracker.recipe.RecipeCosts
import com.foodtracker.recipe.RecipeStateHolder
import com.foodtracker.recipe.RecipeUiState
import com.foodtracker.recipe.ReportFoot
import com.foodtracker.recipe.ReportReason
import com.foodtracker.recipe.ReportSheet
import com.foodtracker.recipe.ReportStatus
import com.foodtracker.recipe.pictureChoice
import com.foodtracker.recipe.reportNoteLength

/*
 * Recipe pictures (the form's Pictures row and sheet, the pictures on a result) and "Report this
 * recipe" (its foot and sheet). Design: docs/design/src/pages/pictures.html.
 */

// --- The form's Pictures row ----------------------------------------------------------------------

internal fun pictureLevelLabel(resources: Resources, level: PictureLevel): String = resources.getString(
    when (level) {
        PictureLevel.NONE -> R.string.recipe_image_none
        PictureLevel.DISH -> R.string.recipe_image_dish
        PictureLevel.STEPS -> R.string.recipe_image_steps
    }
)

/** "The dish · 2 recipes"; no cost for no pictures, which the button's pill says anyway. */
internal fun pictureRowValue(resources: Resources, level: PictureLevel, costs: RecipeCosts): String {
    val label = pictureLevelLabel(resources, level)
    val cost = costs.picture(level)
    return if (level == PictureLevel.NONE || cost == null) label else "$label · " + recipeCount(resources, cost)
}

/** The line under the row and in the sheet when the pack holds the level lower than chosen. */
internal fun pictureShortLine(resources: Resources, short: PictureShort?, balance: Int): String? = when (short) {
    null -> null
    PictureShort.NONE_COVERED -> resources.getString(R.string.recipe_image_short, recipeCount(resources, balance))
    PictureShort.DISH_ONLY -> resources.getString(R.string.recipe_image_short_steps, recipeCount(resources, balance))
}

/** The primary's subtitle for the pictures sent, before "Uses your taste profile". */
internal fun picturesSubtitle(resources: Resources, level: PictureLevel): String? = when (level) {
    PictureLevel.NONE -> null
    PictureLevel.DISH -> resources.getString(R.string.recipe_image_with)
    PictureLevel.STEPS -> resources.getString(R.string.recipe_image_with_steps)
}

/** The collapsed Pictures row, first in the group with More options and drawn like it: its value
 *  is the level really sent with its cost, and the reason on a second line when the pack holds it
 *  lower. Opens [PicturesSheet]. */
@Composable
internal fun PicturesRow(choice: PictureChoice, costs: RecipeCosts, balance: Int, onClick: () -> Unit) {
    val resources = LocalContext.current.resources
    val value = pictureRowValue(resources, choice.sent, costs)
    val short = pictureShortLine(resources, choice.short, balance)
    LarderListItem(
        title = stringResource(R.string.recipe_image_row),
        supporting = if (short != null) "$value\n$short" else value,
        leading = { LarderIcon(LarderIcons.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailing = { LarderIcon(LarderIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        onClick = onClick
    )
}

/** "Pictures, made by AI": no pictures and each priced level with its cost, single choice; a level
 *  the pack doesn't cover is dimmed and can't be chosen, with the line saying why. A choice closes
 *  the sheet at once. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PicturesSheet(choice: PictureChoice, costs: RecipeCosts, balance: Int, onSelect: (PictureLevel) -> Unit, onDismiss: () -> Unit) {
    LarderSheet(onDismissRequest = onDismiss, title = stringResource(R.string.recipe_image_title)) {
        PicturesSheetBody(choice, costs, balance, onSelect, onDismiss)
    }
}

@Composable
internal fun PicturesSheetBody(choice: PictureChoice, costs: RecipeCosts, balance: Int, onSelect: (PictureLevel) -> Unit, onDismiss: () -> Unit) {
    val resources = LocalContext.current.resources
    // Large text: the cost goes under the level's words, so they keep the row's width (as on iOS).
    val stacked = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.3f
    LarderGroup(modifier = Modifier.selectableGroup()) {
        choice.levels.forEachIndexed { index, level ->
            if (index > 0) LarderDivider()
            val covered = level in choice.covered
            val selected = level == choice.sent
            val cost = costs.picture(level)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = selected, enabled = covered, role = Role.RadioButton) { onSelect(level) }
                    .heightIn(min = 56.dp)
                    .padding(horizontal = LarderSpace.s4, vertical = LarderSpace.s3)
                    .alpha(if (covered) 1f else 0.45f),
                horizontalArrangement = Arrangement.spacedBy(LarderSpace.s3),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(pictureLevelLabel(resources, level), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    val description = when (level) {
                        PictureLevel.NONE -> null
                        PictureLevel.DISH -> stringResource(R.string.recipe_image_dish_desc)
                        PictureLevel.STEPS -> stringResource(R.string.recipe_image_steps_desc)
                    }
                    description?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (stacked && cost != null) {
                        Text(recipeCount(resources, cost), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (cost != null && !stacked) {
                    Text(recipeCount(resources, cost), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(modifier = Modifier.padding(start = LarderSpace.s1)) {
                    if (selected) LarderIcon(LarderIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    else androidx.compose.foundation.layout.Spacer(Modifier.padding(12.dp))
                }
            }
        }
    }
    pictureShortLine(resources, choice.short, balance)?.let { line ->
        Text(
            text = line,
            modifier = Modifier.padding(top = LarderSpace.s3, start = LarderSpace.s2, end = LarderSpace.s2),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Row(modifier = Modifier.fillMaxWidth().padding(top = LarderSpace.s4, bottom = LarderSpace.s4), horizontalArrangement = Arrangement.End) {
        LarderButton(text = stringResource(R.string.recipe_report_cancel), onClick = onDismiss, style = LarderButtonStyle.Text)
    }
}

// --- Pictures on a result -------------------------------------------------------------------------

/** What a result's pictures are drawn from: files on this device (or painters in previews). */
internal data class RecipeDocumentPictures(val dish: Any? = null, val steps: Map<Int, Any> = emptyMap()) {
    val isEmpty: Boolean get() = dish == null && steps.isEmpty()
}

/** [recipe]'s pictures kept for saved recipe [savedId], those whose file is still there (a
 *  restored backup has the rows without them). */
@Composable
internal fun rememberRecipePictures(savedId: String, recipe: Recipe): RecipeDocumentPictures {
    val context = LocalContext.current
    return remember(savedId, recipe.picture, recipe.stepPictures) {
        RecipeDocumentPictures(
            dish = recipe.picture?.let { RecipePictureFiles.file(context, savedId, it) },
            steps = recipe.stepPictures.mapNotNull { step -> RecipePictureFiles.file(context, savedId, step.picture)?.let { step.step to it } }.toMap()
        )
    }
}

/**
 * A picture at the column's (or the step text's) width in the 4:3 frame, cropped to fill and
 * centred, whatever the picture's own shape (usually 1200 × 896, another provider's 3:2 1200 × 800):
 * no letterboxing, no distortion. The frame is laid out before the file is read, so nothing moves
 * when the picture appears (a cross-fade; none with reduced motion). Coil decodes it off the main
 * thread at the frame's size, never as the whole 1,200 px bitmap. [model] is a file (or a painter
 * in previews). Not tappable in v1.
 */
@Composable
internal fun RecipePictureImage(model: Any, description: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val reducedMotion = rememberReducedMotion()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .clip(RoundedCornerShape(LarderRadius.lg))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .semantics { contentDescription = description }
    ) {
        if (model is Painter) {
            Image(painter = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            val request = remember(model, reducedMotion) {
                // The size comes from the frame's constraints (Coil's default for AsyncImage).
                ImageRequest.Builder(context).data(model).crossfade(!reducedMotion).build()
            }
            AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.Center, modifier = Modifier.fillMaxSize())
        }
    }
}

/** A quiet caption or failure line under a picture or a heading. */
@Composable
internal fun PictureLine(text: String, modifier: Modifier = Modifier, icon: LarderIcons? = null) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(LarderSpace.s1), verticalAlignment = Alignment.CenterVertically) {
        icon?.let { LarderIcon(it, contentDescription = null, size = 14.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// --- Report this recipe ---------------------------------------------------------------------------

/** The very end of a result our server wrote: "Report this recipe / menu", a quiet text button, or
 *  "You've reported this" once sent from here. Nothing at all for [ReportFoot.HIDDEN]. */
@Composable
internal fun ReportFootRow(foot: ReportFoot, menu: Boolean, onReport: () -> Unit, modifier: Modifier = Modifier) {
    when (foot) {
        ReportFoot.HIDDEN -> Unit
        ReportFoot.AVAILABLE -> Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            LarderButton(
                text = stringResource(if (menu) R.string.recipe_report_menu_action else R.string.recipe_report_action),
                onClick = onReport,
                style = LarderButtonStyle.Text,
                leadingIcon = LarderIcons.Flag
            )
        }
        ReportFoot.REPORTED -> Row(
            modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(LarderSpace.s1, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LarderIcon(LarderIcons.Check, contentDescription = null, size = 16.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.recipe_report_done), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal class ReportSheetActions(
    val onReason: (ReportReason) -> Unit = {},
    val onNote: (String) -> Unit = {},
    val onSend: () -> Unit = {},
    val onDismiss: () -> Unit = {}
)

private fun reportReasonLabel(resources: Resources, reason: ReportReason): String = resources.getString(
    when (reason) {
        ReportReason.OFFENSIVE -> R.string.recipe_report_reason_offensive
        ReportReason.UNSAFE -> R.string.recipe_report_reason_unsafe
        ReportReason.PICTURE -> R.string.recipe_report_reason_picture
        ReportReason.OTHER -> R.string.recipe_report_reason_other
    }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReportRecipeSheet(sheet: ReportSheet, actions: ReportSheetActions) {
    val title = stringResource(if (sheet.menu) R.string.recipe_report_menu_action else R.string.recipe_report_action)
    LarderSheet(onDismissRequest = actions.onDismiss, title = title) {
        ReportSheetBody(sheet, actions)
    }
}

/** The report sheet: one reason (the picture one only with pictures), an optional note of up to
 *  500 characters with its counter, what is sent, and Cancel / Send report; then sending, failed
 *  with Try again, or the day's limit. */
@Composable
internal fun ReportSheetBody(sheet: ReportSheet, actions: ReportSheetActions) {
    val resources = LocalContext.current.resources
    val sending = sheet.status == ReportStatus.SENDING
    // Clear of the keyboard while the note is typed, so Send report can be reached.
    Column(modifier = Modifier.imePadding(), verticalArrangement = Arrangement.spacedBy(LarderSpace.s3)) {
        Text(stringResource(R.string.recipe_report_lede), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        LarderGroup(modifier = Modifier.selectableGroup()) {
            sheet.reasons.forEachIndexed { index, reason ->
                if (index > 0) LarderDivider()
                val selected = reason == sheet.reason
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = selected, enabled = !sending, role = Role.RadioButton) { actions.onReason(reason) }
                        .heightIn(min = 52.dp)
                        .padding(horizontal = LarderSpace.s4, vertical = LarderSpace.s2),
                    horizontalArrangement = Arrangement.spacedBy(LarderSpace.s3),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(reportReasonLabel(resources, reason), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    if (selected) LarderIcon(LarderIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (sheet.reasonMissing) {
            Text(stringResource(R.string.recipe_report_reason_required), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        LarderTextField(
            value = sheet.note,
            onValueChange = actions.onNote,
            label = stringResource(R.string.recipe_report_note_label),
            singleLine = false,
            minLines = 3,
            enabled = !sending,
            counter = stringResource(R.string.recipe_report_note_count, reportNoteLength(sheet.note), REPORT_NOTE_MAX)
        )
        Text(
            stringResource(if (sheet.menu) R.string.recipe_report_sends_server_menu else R.string.recipe_report_sends_server),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        when (sheet.status) {
            ReportStatus.FAILED -> LarderCallout(kind = LarderCalloutKind.Warning, icon = LarderIcons.WifiOff, message = stringResource(R.string.recipe_report_failed))
            ReportStatus.LIMIT -> LarderCallout(kind = LarderCalloutKind.Info, icon = LarderIcons.Clock, message = stringResource(R.string.recipe_report_limit))
            ReportStatus.UNKNOWN -> LarderCallout(kind = LarderCalloutKind.Info, icon = LarderIcons.CircleAlert, message = stringResource(R.string.recipe_report_unknown))
            else -> Unit
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = LarderSpace.s1, bottom = LarderSpace.s4),
            horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LarderButton(text = stringResource(R.string.recipe_report_cancel), onClick = actions.onDismiss, style = LarderButtonStyle.Text)
            if (sheet.status != ReportStatus.LIMIT && sheet.status != ReportStatus.UNKNOWN) {
                LarderButton(
                    text = stringResource(
                        when (sheet.status) {
                            ReportStatus.SENDING -> R.string.recipe_report_sending
                            ReportStatus.FAILED -> R.string.recipe_report_try_again
                            else -> R.string.recipe_report_send
                        }
                    ),
                    onClick = actions.onSend,
                    // "Sending…" said in words, as the design has it, rather than a bare spinner.
                    enabled = !sending
                )
            }
        }
    }
}

/**
 * The report sheet for the result [savedId] shows, and the "Thanks" snackbar once our server has
 * it; a sent report rules out the rating prompt for the session. [asDish]: a menu's dish.
 */
@Composable
internal fun ReportHost(stateHolder: RecipeStateHolder, state: RecipeUiState, savedId: String) {
    val snackbars = LocalLarderSnackbars.current
    val reviewPrompter = LocalReviewPrompter.current
    val resources = LocalContext.current.resources
    LaunchedEffect(state.reportSent) {
        if (state.reportSent) {
            stateHolder.consumeReportSent()
            reviewPrompter?.onReportSent()
            snackbars.show(resources.getString(R.string.recipe_report_sent), null, null)
        }
    }
    val sheet = state.report
    if (sheet != null && sheet.savedId == savedId) {
        ReportRecipeSheet(
            sheet,
            ReportSheetActions(
                onReason = stateHolder::chooseReportReason,
                onNote = stateHolder::setReportNote,
                onSend = stateHolder::sendReport,
                onDismiss = stateHolder::dismissReport
            )
        )
    }
}

/** The "couldn't make" lines of a result that just arrived go once it is left. */
@Composable
internal fun PictureNoticeLifetime(stateHolder: RecipeStateHolder, savedId: String) {
    DisposableEffect(stateHolder, savedId) {
        onDispose { stateHolder.pictureNoticeSeen(savedId) }
    }
}

// --- Previews (a drawn stand-in, never a real picture) ---------------------------------------------

/** A plate of food drawn in code, for previews: no picture file is ever bundled or loaded. It has
 *  a real picture's shape ([width] × [height], 1200 × 896 by default; 1200 × 800 for another
 *  provider's 3:2), so previews crop it as they would crop the real one. */
internal class SampleDishPainter(private val step: Int? = null, width: Float = 1200f, height: Float = 896f) : Painter() {
    override val intrinsicSize: Size = Size(width, height)
    override fun DrawScope.onDraw() {
        drawRect(Color(0xFFB9825A))
        val c = Offset(size.width * 0.5f, size.height * 0.52f)
        val r = size.minDimension * 0.42f
        drawCircle(Color(0xFFF4EFE6), r, c)
        drawCircle(Color(0xFFE4DCCD), r * 0.78f, c)
        if (step == null) {
            drawCircle(Color(0xFF4E7D3A), r * 0.34f, Offset(c.x - r * 0.22f, c.y - r * 0.05f))
            drawCircle(Color(0xFFFFFFFF), r * 0.24f, Offset(c.x + r * 0.22f, c.y + r * 0.1f))
            drawCircle(Color(0xFFF2B233), r * 0.11f, Offset(c.x + r * 0.22f, c.y + r * 0.1f))
            drawCircle(Color(0xFFC0392B), r * 0.05f, Offset(c.x - r * 0.1f, c.y + r * 0.3f))
        } else {
            drawCircle(Color(0xFF6B8E4E), r * 0.5f, c)
            drawCircle(Color(0xFF55733D), r * 0.2f, Offset(c.x - r * 0.15f, c.y))
        }
    }
}

private val previewCosts = RecipeCosts(recipeImage = 2, recipeImageSteps = 3)

@Composable
private fun SheetPreview(content: @Composable () -> Unit) = LarderTheme {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(modifier = Modifier.padding(LarderSpace.s4)) { content() }
    }
}

@PreviewLightDark
@Composable
internal fun PicturesRowLevelsPreview() = LarderTheme {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.padding(LarderSpace.s4), verticalArrangement = Arrangement.spacedBy(LarderSpace.s3)) {
            PictureLevel.entries.forEach { level ->
                LarderGroup { PicturesRow(pictureChoice(level, previewCosts, true, 12, true), previewCosts, 12) {} }
            }
            LarderGroup { PicturesRow(pictureChoice(PictureLevel.STEPS, previewCosts, true, 2, true), previewCosts, 2) {} }
            LarderGroup { PicturesRow(pictureChoice(PictureLevel.STEPS, previewCosts, true, 1, true), previewCosts, 1) {} }
        }
    }
}

@PreviewLightDark
@Composable
internal fun PicturesSheetPreview() = SheetPreview {
    PicturesSheetBody(pictureChoice(PictureLevel.STEPS, previewCosts, true, 12, true), previewCosts, 12, {}, {})
}

@Preview
@Composable
internal fun PicturesSheetShortPreview() = SheetPreview {
    PicturesSheetBody(pictureChoice(PictureLevel.STEPS, previewCosts, true, 2, true), previewCosts, 2, {}, {})
}

@Preview(locale = "de", fontScale = 1.5f)
@Composable
internal fun PicturesSheetLargeTextPreview() = SheetPreview {
    PicturesSheetBody(pictureChoice(PictureLevel.STEPS, previewCosts, true, 2, true), previewCosts, 2, {}, {})
}

@Preview(locale = "ru")
@Composable
internal fun PicturesSheetRussianPreview() = SheetPreview {
    PicturesSheetBody(pictureChoice(PictureLevel.DISH, previewCosts, true, 12, true), previewCosts, 12, {}, {})
}

private val previewReport = ReportSheet(savedId = "1", requestId = "req", menu = false, hasPictures = true, reportId = "rep")

@PreviewLightDark
@Composable
internal fun ReportSheetPreview() = SheetPreview { ReportSheetBody(previewReport, ReportSheetActions()) }

@Preview
@Composable
internal fun ReportSheetReasonMissingPreview() = SheetPreview { ReportSheetBody(previewReport.copy(reasonMissing = true), ReportSheetActions()) }

@Preview
@Composable
internal fun ReportSheetSendingPreview() = SheetPreview {
    ReportSheetBody(previewReport.copy(reason = ReportReason.UNSAFE, note = "It says to leave the cooked rice out overnight.", status = ReportStatus.SENDING), ReportSheetActions())
}

@Preview
@Composable
internal fun ReportSheetFailedPreview() = SheetPreview {
    ReportSheetBody(previewReport.copy(reason = ReportReason.UNSAFE, note = "It says to leave the cooked rice out overnight.", status = ReportStatus.FAILED), ReportSheetActions())
}

@Preview
@Composable
internal fun ReportSheetLimitPreview() = SheetPreview {
    ReportSheetBody(previewReport.copy(reason = ReportReason.OTHER, status = ReportStatus.LIMIT), ReportSheetActions())
}

@Preview
@Composable
internal fun ReportSheetUnknownPreview() = SheetPreview {
    ReportSheetBody(previewReport.copy(reason = ReportReason.OTHER, status = ReportStatus.UNKNOWN), ReportSheetActions())
}

@PreviewLightDark
@Composable
internal fun ReportSheetMenuPreview() = SheetPreview { ReportSheetBody(previewReport.copy(menu = true, hasPictures = false), ReportSheetActions()) }

@PreviewLightDark
@Composable
internal fun ReportFootPreview() = LarderTheme {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.padding(LarderSpace.s4)) {
            ReportFootRow(ReportFoot.AVAILABLE, menu = false, onReport = {})
            ReportFootRow(ReportFoot.AVAILABLE, menu = true, onReport = {})
            ReportFootRow(ReportFoot.REPORTED, menu = false, onReport = {})
        }
    }
}
