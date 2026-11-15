package app.shoku.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private fun shokuColorScheme() = lightColorScheme(
    primary = ShokuColors.Fill,
    onPrimary = ShokuColors.Paper,
    primaryContainer = ShokuColors.Tint,
    onPrimaryContainer = ShokuColors.Ink,
    secondary = ShokuColors.Muted,
    onSecondary = ShokuColors.Paper,
    secondaryContainer = ShokuColors.Tint,
    onSecondaryContainer = ShokuColors.Ink,
    tertiary = ShokuColors.Ink,
    onTertiary = ShokuColors.Paper,
    background = ShokuColors.Paper,
    onBackground = ShokuColors.Ink,
    surface = ShokuColors.Paper,
    onSurface = ShokuColors.Ink,
    surfaceVariant = ShokuColors.Tint,
    onSurfaceVariant = ShokuColors.Muted,
    surfaceContainerLowest = ShokuColors.Paper,
    surfaceContainerLow = ShokuColors.Paper,
    surfaceContainer = ShokuColors.Paper,
    surfaceContainerHigh = ShokuColors.Paper,
    surfaceContainerHighest = ShokuColors.Tint,
    surfaceTint = ShokuColors.Paper,
    outline = ShokuColors.Line,
    outlineVariant = ShokuColors.Line,
    inverseSurface = ShokuColors.Fill,
    inverseOnSurface = ShokuColors.Paper,
    scrim = ShokuColors.Ink,
)

object ShokuShapes {
    val Card = RoundedCornerShape(18.dp)
    val Tile = RoundedCornerShape(12.dp)
    val Button = RoundedCornerShape(14.dp)
    val Sheet = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
}

object ShokuSpacing {
    val Screen = 20.dp
    val CardPadding = 18.dp
    val Gap = 12.dp
}

/** No dynamic colour, so the look stays fixed whatever the wallpaper. Light or dark from Settings. */
@Composable
fun ShokuTheme(content: @Composable () -> Unit) {
    val dark = when (Appearance.mode) {
        "dark" -> true
        "light" -> false
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    ShokuColors.dark = dark
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    // The scheme's values are read from ShokuColors, so rebuild it when the theme flips.
    val scheme = androidx.compose.runtime.remember(dark) { shokuColorScheme() }
    MaterialTheme(
        colorScheme = scheme,
        typography = ShokuTypography,
        shapes = Shapes(
            small = ShokuShapes.Tile,
            medium = ShokuShapes.Card,
            large = ShokuShapes.Card,
        ),
        content = {
            // Text without an explicit colour follows the theme (screens without a Surface would otherwise get black).
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides ShokuColors.Ink, content = content)
        },
    )
}
