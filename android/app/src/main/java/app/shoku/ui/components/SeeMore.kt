package app.shoku.ui.components

import app.shoku.ui.theme.pressScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.shoku.ui.theme.ShokuColors

/** How many rows a long list shows before "See all". */
fun <T> List<T>.shown(open: Boolean, limit: Int): List<T> = if (open) this else take(limit)

/** "See all 23" / "Show less" under a list that's longer than it shows; nothing when it all fits. */
@Composable
fun SeeMoreChip(total: Int, limit: Int, open: Boolean, onToggle: () -> Unit, what: String = "") {
    if (total <= limit) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        val press = app.shoku.ui.theme.rememberPress()
        Row(
            Modifier.pressScale(press, 0.94f).clip(CircleShape).background(ShokuColors.Tint)
                .clickable(interactionSource = press, indication = androidx.compose.material3.ripple(), onClick = onToggle).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (open) "Show less" else "See all $total${if (what.isEmpty()) "" else " $what"}", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(4.dp))
            val turn by androidx.compose.animation.core.animateFloatAsState(if (open) 180f else 0f, app.shoku.ui.theme.ShokuMotion.spatial(), label = "chevron")
            Icon(Icons.Rounded.KeyboardArrowDown, null, tint = ShokuColors.Ink, modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = turn })
        }
    }
}
