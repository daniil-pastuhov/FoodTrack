package com.foodtracker.android.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foodtracker.android.R
import com.foodtracker.android.ui.theme.LarderSize
import com.foodtracker.android.ui.theme.LocalIsDarkTheme

/**
 * Google's "Sign in with Google" button, drawn to Google's branding guidelines
 * (developers.google.com/identity/branding-guidelines, Android + Web spec): the Light theme on a
 * light app theme and the Dark theme on a dark one, pill shape, 40 dp tall (inside a 48 dp touch
 * target), 12 dp before the logo, 10 dp between logo and text, 12 dp after the text, a 20 dp "G",
 * Google Sans Medium 14/20, and Google's own localised call to action (`recipe_sign_in_google`).
 *
 * The "G" (`google_g_logo_light` / `_dark`) is cut, pixel for pixel, from the icon-only buttons in
 * Google's download bundle (signin-assets.zip, "Android + Web/PNG @1x–@4x", the 20×20 logo area of
 * the 40×40 Square button), not redrawn: the current gradient "G" uses blur filters, which a
 * vector drawable can't express, and the guidelines forbid recreating or recolouring it. Each copy
 * keeps the button fill of its theme behind the logo, so it must only sit on that fill. The font is
 * Google Sans (SIL OFL 1.1, assets/licenses/GoogleSans-OFL.txt), instanced at weight 500 and
 * subset to Latin and Cyrillic.
 */
@Composable
fun GoogleSignInButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    text: String = stringResource(R.string.recipe_sign_in_google),
    accessibilityLabel: String? = null
) {
    val colors = if (LocalIsDarkTheme.current) GoogleButtonColors.Dark else GoogleButtonColors.Light
    val active = enabled && !loading
    Box(
        modifier = modifier
            .heightIn(min = LarderSize.touch)
            .clip(CircleShape)
            .clickable(enabled = active, role = Role.Button, onClick = onClick)
            .then(if (accessibilityLabel != null) Modifier.semantics { contentDescription = accessibilityLabel } else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(GoogleButtonHeight)
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .clip(CircleShape)
                .background(colors.fill, CircleShape)
                .then(if (colors.stroke != null) Modifier.border(colors.stroke, CircleShape) else Modifier)
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(colors.logo),
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Box(Modifier.width(10.dp))
            Text(
                text = text,
                style = GoogleButtonText.copy(color = colors.text),
                // With an explicit label, TalkBack reads only that, not the label and the title.
                modifier = if (accessibilityLabel != null) Modifier.clearAndSetSemantics {} else Modifier,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (loading) {
                Box(Modifier.width(10.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = colors.text,
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

private val GoogleButtonHeight = 40.dp
private const val DISABLED_ALPHA = 0.38f

private val GoogleSansMedium = FontFamily(Font(R.font.google_sans_medium, FontWeight.Medium))

private val GoogleButtonText = TextStyle(
    fontFamily = GoogleSansMedium,
    fontWeight = FontWeight.Medium,
    fontSize = 14.sp,
    lineHeight = 20.sp
)

/** The guidelines' Light and Dark themes (fill, 1 dp inside stroke, text) and their logo copy. */
private enum class GoogleButtonColors(val fill: Color, val stroke: BorderStroke?, val text: Color, val logo: Int) {
    Light(Color(0xFFFFFFFF), BorderStroke(1.dp, Color(0xFF747775)), Color(0xFF1F1F1F), R.drawable.google_g_logo_light),
    Dark(Color(0xFF131314), BorderStroke(1.dp, Color(0xFF8E918F)), Color(0xFFE3E3E3), R.drawable.google_g_logo_dark)
}

// --- Previews ---------------------------------------------------------------------------------

@PreviewLightDark
@Composable
internal fun GoogleSignInButtonStatesPreview() = LarderPreview {
    GoogleSignInButton(onClick = {}, modifier = Modifier.fillMaxWidth())
    GoogleSignInButton(onClick = {}, modifier = Modifier.fillMaxWidth(), loading = true)
    GoogleSignInButton(onClick = {}, modifier = Modifier.fillMaxWidth(), enabled = false)
    GoogleSignInButton(onClick = {})
}

@Preview(name = "ru", locale = "ru")
@Preview(name = "nb", locale = "nb")
@Preview(name = "ru dark", locale = "ru", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "nb dark", locale = "nb", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun GoogleSignInButtonLocalesPreview() = LarderPreview {
    GoogleSignInButton(onClick = {}, modifier = Modifier.fillMaxWidth())
}
