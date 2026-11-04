package com.foodtracker.android.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.foodtracker.android.R

/** The Larder display face, for display, headline and title text. Everything else is the system
 *  font, so body text stays Roboto. Static weights, because variable fonts need API 26. */
val VollkornFamily = FontFamily(
    Font(R.font.vollkorn_medium, FontWeight.Medium),
    Font(R.font.vollkorn_semibold, FontWeight.SemiBold)
)

/**
 * The tokens.json type scale as Material 3 styles. Sizes are in sp, so all of it follows the
 * system font scale.
 *
 * | Token    | Style                                          |
 * |----------|------------------------------------------------|
 * | display  | displayLarge, displayMedium, displaySmall      |
 * | headline | headlineLarge, headlineMedium, headlineSmall   |
 * | title    | titleLarge                                     |
 * | title-sm | titleMedium                                    |
 * | body     | bodyLarge                                      |
 * | body-sm  | bodyMedium                                     |
 * | label    | labelLarge, titleSmall                         |
 * | caption  | labelMedium, labelSmall, bodySmall             |
 */
private val LarderTypography: Typography = run {
    val display = TextStyle(
        fontFamily = VollkornFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.3).sp
    )
    val headline = TextStyle(
        fontFamily = VollkornFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp
    )
    val title = TextStyle(
        fontFamily = VollkornFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 26.sp
    )
    val titleSm = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp)
    val body = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp)
    val bodySm = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp)
    val label = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    )
    val caption = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp
    )
    Typography(
        displayLarge = display,
        displayMedium = display,
        displaySmall = display,
        headlineLarge = headline,
        headlineMedium = headline,
        headlineSmall = headline,
        titleLarge = title,
        titleMedium = titleSm,
        titleSmall = label,
        bodyLarge = body,
        bodyMedium = bodySm,
        bodySmall = caption,
        labelLarge = label,
        labelMedium = caption,
        labelSmall = caption
    )
}

private val LarderShapes = Shapes(
    extraSmall = RoundedCornerShape(LarderRadius.sm),
    small = RoundedCornerShape(LarderRadius.sm),
    medium = RoundedCornerShape(LarderRadius.md),
    large = RoundedCornerShape(LarderRadius.lg),
    extraLarge = RoundedCornerShape(LarderRadius.xl)
)

/**
 * Fills every Material slot, so a stock component can't reach for a baseline purple. Slots with
 * no Larder role take the nearest one: secondary is primary, and tertiary is the soon role.
 * surfaceTint is the plain surface, because tonal elevation would tint the oat paper; only
 * floating things get depth, from [LarderTheme.shadows].
 */
private fun LarderColorRoles.toColorScheme(dark: Boolean): ColorScheme = lightColorScheme(
    primary = primary, onPrimary = onPrimary,
    primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
    inversePrimary = inversePrimary,
    secondary = primary, onSecondary = onPrimary,
    secondaryContainer = primaryContainer, onSecondaryContainer = onPrimaryContainer,
    tertiary = onSoonContainer, onTertiary = soonContainer,
    tertiaryContainer = soonContainer, onTertiaryContainer = onSoonContainer,
    background = background, onBackground = onBackground,
    surface = surface, onSurface = onSurface,
    surfaceVariant = surfaceContainer, onSurfaceVariant = onSurfaceVariant,
    surfaceTint = surface,
    inverseSurface = inverseSurface, inverseOnSurface = onInverseSurface,
    error = error, onError = onError,
    errorContainer = errorContainer, onErrorContainer = onErrorContainer,
    outline = outline, outlineVariant = outlineVariant, scrim = scrim,
    surfaceBright = if (dark) surfaceContainerHigh else surface,
    surfaceDim = if (dark) background else surfaceContainer,
    surfaceContainerLowest = if (dark) background else surface,
    surfaceContainerLow = surface,
    surfaceContainer = surfaceContainer,
    surfaceContainerHigh = surfaceContainerHigh,
    surfaceContainerHighest = surfaceContainerHigh
)

private val LarderLightScheme = LarderLightRoles.toColorScheme(dark = false)
private val LarderDarkScheme = LarderDarkRoles.toColorScheme(dark = true)

/** tokens.json's elevation levels. Only floating things get one: Scan, the pill, snackbars. */
class LarderShadows internal constructor(
    val level1: Shadow,
    val level2: Shadow,
    val level3: Shadow
)

private val LarderLightShadows = LarderShadows(
    level1 = Shadow(radius = 2.dp, offset = DpOffset(0.dp, 1.dp), color = Color(0x142F2B22)),
    level2 = Shadow(radius = 12.dp, offset = DpOffset(0.dp, 4.dp), color = Color(0x1A2F2B22)),
    level3 = Shadow(radius = 28.dp, offset = DpOffset(0.dp, 10.dp), color = Color(0x292F2B22))
)

private val LarderDarkShadows = LarderShadows(
    level1 = Shadow(radius = 2.dp, offset = DpOffset(0.dp, 1.dp), color = Color(0x73000000)),
    level2 = Shadow(radius = 12.dp, offset = DpOffset(0.dp, 4.dp), color = Color(0x80000000)),
    level3 = Shadow(radius = 28.dp, offset = DpOffset(0.dp, 10.dp), color = Color(0x8C000000))
)

/** Casts one of the [LarderTheme.shadows] levels from [shape]. */
fun Modifier.larderShadow(shadow: Shadow, shape: Shape): Modifier = dropShadow(shape, shadow)

private val LocalLarderColors = staticCompositionLocalOf { LarderLightRoles }
private val LocalLarderShadows = staticCompositionLocalOf { LarderLightShadows }

/** Whether the resolved theme (system or user-forced) is dark. */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

/** Larder's tokens that Material has no slot for. Reach for [MaterialTheme] first. */
object LarderTheme {
    /** Every colour role, including expired, today, soon, warning, success, info and nutri. */
    val colors: LarderColorRoles
        @Composable @ReadOnlyComposable get() = LocalLarderColors.current

    val shadows: LarderShadows
        @Composable @ReadOnlyComposable get() = LocalLarderShadows.current
}

@Composable
fun LarderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(
        LocalIsDarkTheme provides darkTheme,
        LocalLarderColors provides if (darkTheme) LarderDarkRoles else LarderLightRoles,
        LocalLarderShadows provides if (darkTheme) LarderDarkShadows else LarderLightShadows
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) LarderDarkScheme else LarderLightScheme,
            shapes = LarderShapes,
            typography = LarderTypography,
            content = content
        )
    }
}
