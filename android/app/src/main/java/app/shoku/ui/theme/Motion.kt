package app.shoku.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Motion in the Material 3 Expressive spirit: springs instead of fixed curves, small and quick.
 * Spatial springs (position, size, scale) bounce a little; effect springs (colour, alpha) don't.
 * Android's "Remove animations" setting scales these to nothing.
 */
object ShokuMotion {
    fun <T> spatial() = spring<T>(dampingRatio = 0.8f, stiffness = 380f)
    fun <T> spatialFast() = spring<T>(dampingRatio = 0.7f, stiffness = 900f)
    fun <T> effects() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1600f)
    fun <T> slow() = spring<T>(dampingRatio = 1f, stiffness = 200f)
}

/** Shrinks a touch while pressed and springs back: the Pixel "squish". Pass the clickable's interaction source. */
fun Modifier.pressScale(source: InteractionSource, pressed: Float = 0.96f): Modifier = composed {
    val isPressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) pressed else 1f, ShokuMotion.spatialFast(), label = "press")
    graphicsLayer { scaleX = scale; scaleY = scale }
}

/** A fresh interaction source to share between a clickable and [pressScale]. */
@Composable
fun rememberPress() = remember { MutableInteractionSource() }

/** Rises and fades in the first time it's shown (screens, overlays). */
fun Modifier.enterRise(distance: Int = 24): Modifier = composed {
    val progress = remember { Animatable(0f) }
    val px = with(LocalDensity.current) { distance.dp.toPx() }
    LaunchedEffect(Unit) { launch { progress.animateTo(1f, ShokuMotion.spatial()) } }
    graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * px
    }
}
