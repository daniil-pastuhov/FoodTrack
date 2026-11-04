package com.foodtracker.android.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.foodtracker.android.ui.theme.LarderRadius
import com.foodtracker.android.ui.theme.LarderSize
import com.foodtracker.android.ui.theme.LarderSpace

/**
 * A text field with its label above it, always visible. Pass [label] as null only where the
 * screen's context labels it, such as search in a header; [placeholder] then names it for screen
 * readers.
 *
 * [error] is the T1 message: it replaces [helper] and turns the outline red. Set it when the
 * field loses focus, not on each keystroke. [counter] sits at the end of the message line.
 * [trailing] is for a clear button or similar, usually a [LarderIconButton].
 */
@Composable
fun LarderTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String?,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    leadingIcon: LarderIcons? = null,
    trailing: (@Composable () -> Unit)? = null,
    helper: String? = null,
    error: String? = null,
    counter: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    interactionSource: MutableInteractionSource? = null
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scheme = MaterialTheme.colorScheme
    val muted = scheme.onSurface.copy(alpha = 0.38f)
    val shape = RoundedCornerShape(LarderRadius.md)
    val borderWidth = if ((focused || error != null) && enabled) 2.dp else 1.dp
    val borderColor = when {
        !enabled -> scheme.onSurface.copy(alpha = 0.12f)
        error != null -> scheme.error
        focused -> scheme.primary
        else -> scheme.outline
    }
    val textColor = if (enabled) scheme.onSurface else muted
    val spokenName = label ?: placeholder

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(LarderSpace.s1)) {
        label?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) scheme.onSurface else muted
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    spokenName?.let { contentDescription = it }
                    error?.let { error(it) }
                },
            enabled = enabled,
            readOnly = readOnly,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = textColor),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            visualTransformation = visualTransformation,
            interactionSource = source,
            cursorBrush = SolidColor(scheme.primary),
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .heightIn(min = LarderSize.control)
                        .background(scheme.surface, shape)
                        .border(borderWidth, borderColor, shape)
                        .padding(start = LarderSpace.s3, end = if (trailing != null) 0.dp else LarderSpace.s3),
                    horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    leadingIcon?.let {
                        LarderIcon(it, contentDescription = null, tint = scheme.onSurfaceVariant)
                    }
                    Box(modifier = Modifier.weight(1f).padding(vertical = LarderSpace.s3)) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        innerTextField()
                    }
                    trailing?.invoke()
                }
            }
        )
        val message = error ?: helper
        if (message != null || counter != null) {
            val messageColor = if (error != null) scheme.error else scheme.onSurfaceVariant
            Row(horizontalArrangement = Arrangement.spacedBy(LarderSpace.s1)) {
                if (error != null) {
                    LarderIcon(
                        LarderIcons.CircleAlert,
                        contentDescription = null,
                        size = LarderIconSize.Small,
                        tint = messageColor,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
                Text(
                    text = message.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = messageColor,
                    modifier = Modifier.weight(1f)
                )
                counter?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium.merge(LarderTabularNumbers),
                        color = scheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@PreviewLightDark
@Composable
internal fun LarderTextFieldPreview() = LarderPreview {
    Column(verticalArrangement = Arrangement.spacedBy(LarderSpace.s4)) {
        var name by remember { mutableStateOf("") }
        LarderTextField(name, { name = it }, "Product name", placeholder = "e.g. Greek yoghurt")
        LarderTextField("Fage", {}, "Brand", helper = "As printed on the pack")
        LarderTextField("", {}, "Product name", error = "Add a name so you can find this later")
        LarderTextField("24.11.2062", {}, "Best before", error = "This date is over 10 years away. Check the year.")
        LarderTextField("5012345678900", {}, "Barcode", enabled = false)
        LarderTextField(
            value = "yogh",
            onValueChange = {},
            label = "Search",
            leadingIcon = LarderIcons.Search,
            trailing = { LarderIconButton(LarderIcons.X, "Clear", {}) }
        )
        LarderTextField(
            value = "Keep the lid on",
            onValueChange = {},
            label = "Notes",
            singleLine = false,
            minLines = 3,
            counter = "15/200"
        )
    }
}
