package app.shoku.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Monochrome palette, taken from the Bharpai design language.
 * State is shown with filled vs outlined vs tinted shapes, not with hue.
 *
 * Light and dark: each colour is read from [dark], a Compose state, so every screen (and every
 * Canvas) redraws when the theme changes without passing colours around.
 */
object ShokuColors {
    /** Set by ShokuTheme from the Appearance setting. */
    var dark by mutableStateOf(false)

    private fun pick(light: Long, darkValue: Long) = Color(if (dark) darkValue else light)

    /** Primary text and icons. */
    val Ink get() = pick(0xFF09090B, 0xFFFAFAFA)

    /** Filled chips, progress fill, primary buttons, FAB. */
    val Fill get() = pick(0xFF18181B, 0xFFF4F4F5)

    /** Secondary text. */
    val Muted get() = pick(0xFF6B6B74, 0xFFA1A1AA)

    /** Card borders and dividers. */
    val Line get() = pick(0xFFE4E4E7, 0xFF2E2E33)

    /** Summary cards, progress tracks, icon tiles, tinted chips. */
    val Tint get() = pick(0xFFF4F4F5, 0xFF1C1C20)

    /** Screen background and card fill. */
    val Paper get() = pick(0xFFFFFFFF, 0xFF09090B)

    /** Planned (not yet eaten) portion of a progress bar. */
    val Planned get() = pick(0xFFC9C9CF, 0xFF52525B)

    /** Middle step of the heatmap scale. */
    val Mid get() = pick(0xFFA1A1AA, 0xFF71717A)

    /** The one accent: going over a calorie limit. */
    val Over get() = pick(0xFFB4483E, 0xFFE0675C)

    /** Planned food that would push a day over its limit. */
    val OverPlanned get() = pick(0xFFE6B9B3, 0xFF5C2B27)
}

/** "system", "light" or "dark", from Settings; kept in SharedPreferences "app" under "theme". */
object Appearance {
    var mode by mutableStateOf("system")
}
