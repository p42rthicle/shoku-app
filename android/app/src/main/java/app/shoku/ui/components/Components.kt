package app.shoku.ui.components

import androidx.compose.runtime.getValue
import app.shoku.ui.theme.pressScale
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.shoku.ui.theme.NumberStyle
import app.shoku.ui.theme.ShokuColors
import app.shoku.ui.theme.ShokuShapes
import app.shoku.ui.theme.ShokuSpacing

// ---------- Headers ----------

/** Small muted line above a large title, e.g. "Thursday, 13 Nov" over "Today". */
@Composable
fun ScreenTitle(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            if (eyebrow != null) {
                Text(eyebrow, style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted)
                Spacer(Modifier.height(2.dp))
            }
            Text(title, style = MaterialTheme.typography.headlineLarge, color = ShokuColors.Ink)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = ShokuColors.Muted, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelMedium, color = ShokuColors.Muted)
        }
    }
}

// ---------- Cards ----------

/** Tinted card at the top of a screen: small label, large number. */
@Composable
fun SummaryCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    valueColor: Color = ShokuColors.Ink,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(ShokuShapes.Card)
            .background(ShokuColors.Tint)
            .padding(ShokuSpacing.CardPadding),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.displayLarge, color = valueColor)
            if (trailing != null) {
                Spacer(Modifier.weight(1f))
                Text(
                    trailing,
                    style = MaterialTheme.typography.bodyLarge,
                    color = ShokuColors.Muted,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }
        content()
    }
}

/** White card with a hairline border. `emphasized` draws the darker outline used for active items. */
@Composable
fun OutlinedCard(
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(ShokuSpacing.CardPadding),
    content: @Composable ColumnScope.() -> Unit,
) {
    val border = if (emphasized) BorderStroke(1.5.dp, ShokuColors.Ink) else BorderStroke(1.dp, ShokuColors.Line)
    val press = app.shoku.ui.theme.rememberPress()
    Column(
        modifier
            .then(if (onClick != null) Modifier.pressScale(press, 0.98f) else Modifier)
            .clip(ShokuShapes.Card)
            .background(ShokuColors.Paper)
            .border(border, ShokuShapes.Card)
            .then(if (onClick != null) Modifier.clickable(interactionSource = press, indication = androidx.compose.material3.ripple(), onClick = onClick) else Modifier)
            .animateContentSize(app.shoku.ui.theme.ShokuMotion.spatial())
            .padding(contentPadding),
        content = content,
    )
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, thickness = 1.dp, color = ShokuColors.Line)
}

// ---------- Chips ----------

enum class ChipStyle { Filled, Outlined, Tinted }

@Composable
fun StatusChip(text: String, style: ChipStyle, modifier: Modifier = Modifier) {
    val (bg, fg) = when (style) {
        ChipStyle.Filled -> ShokuColors.Fill to ShokuColors.Paper
        ChipStyle.Outlined -> ShokuColors.Paper to ShokuColors.Ink
        ChipStyle.Tinted -> ShokuColors.Tint to ShokuColors.Muted
    }
    Box(
        modifier
            .clip(CircleShape)
            .background(bg)
            .then(if (style == ChipStyle.Outlined) Modifier.border(1.dp, ShokuColors.Ink, CircleShape) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

// ---------- Progress ----------

/**
 * Thin rounded bar. Solid part is eaten, lighter part after it is planned.
 * Values are fractions of the goal and are clamped to the track.
 */
@Composable
fun MacroBar(
    eatenFraction: Float,
    modifier: Modifier = Modifier,
    plannedFraction: Float = 0f,
    height: Dp = 6.dp,
    // Empty part of the bar: clearly lighter than the summary card in light and dark, so 0 never looks filled.
    track: Color = ShokuColors.Line,
    isLimit: Boolean = false,
) {
    // For limits (calories), going past the goal turns the bar red; planned food that would do so turns light red.
    val over = isLimit && eatenFraction > 1f
    val plannedOver = isLimit && !over && eatenFraction + plannedFraction > 1f
    val eaten by androidx.compose.animation.core.animateFloatAsState(eatenFraction.coerceIn(0f, 1f), app.shoku.ui.theme.ShokuMotion.slow(), label = "eaten")
    val planned by androidx.compose.animation.core.animateFloatAsState(plannedFraction.coerceIn(0f, 1f - eatenFraction.coerceIn(0f, 1f)), app.shoku.ui.theme.ShokuMotion.slow(), label = "planned")
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(track),
    ) {
        Row(Modifier.fillMaxHeight()) {
            if (eaten > 0f) {
                Box(Modifier.fillMaxHeight().weight(eaten).clip(CircleShape).background(if (over) ShokuColors.Over else ShokuColors.Fill))
            }
            if (planned > 0f) {
                Box(Modifier.fillMaxHeight().weight(planned).clip(CircleShape).background(if (plannedOver) ShokuColors.OverPlanned else ShokuColors.Planned))
            }
            val rest = 1f - eaten - planned
            if (rest > 0f) Spacer(Modifier.weight(rest))
        }
    }
}

/** One labelled macro line inside the summary card: "Protein   74 / 90 g" with a bar under it. */
@Composable
fun MacroLine(
    label: String,
    eaten: Float,
    goal: Float?,
    unit: String,
    modifier: Modifier = Modifier,
    planned: Float = 0f,
    isLimit: Boolean = false,
) {
    val over = isLimit && goal != null && goal > 0f && eaten > goal
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = ShokuColors.Ink, modifier = Modifier.weight(1f))
            Text(formatAmount(eaten), style = NumberStyle.Row, color = if (over) ShokuColors.Over else ShokuColors.Ink)
            Text(
                if (goal != null) " / ${formatAmount(goal)} $unit" else " $unit",
                style = MaterialTheme.typography.bodyMedium,
                color = ShokuColors.Muted,
            )
        }
        if (goal != null && goal > 0f) {
            Spacer(Modifier.height(8.dp))
            MacroBar(eatenFraction = eaten / goal, plannedFraction = planned / goal, isLimit = isLimit)
        }
    }
}

// ---------- Small building blocks ----------

@Composable
fun IconTile(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    Box(
        modifier.size(size).clip(ShokuShapes.Tile).background(ShokuColors.Tint),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = ShokuColors.Ink, modifier = Modifier.size(size * 0.5f))
    }
}

/** Outlined square icon button, like the scan button on Bharpai's home screen. */
@Composable
fun SquareIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val press = app.shoku.ui.theme.rememberPress()
    Box(
        modifier
            .pressScale(press, 0.9f)
            .size(44.dp)
            .clip(ShokuShapes.Tile)
            .border(1.dp, ShokuColors.Line, ShokuShapes.Tile)
            .clickable(interactionSource = press, indication = androidx.compose.material3.ripple(), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = ShokuColors.Ink, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun LetterAvatar(letter: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Box(
        modifier.size(size).clip(CircleShape).background(ShokuColors.Fill),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = ShokuColors.Paper)
    }
}

/** Planned = empty circle, eaten = filled circle with a tick. */
@Composable
fun EatenToggle(eaten: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(if (eaten) ShokuColors.Fill else ShokuColors.Paper)
            .then(if (!eaten) Modifier.border(1.5.dp, ShokuColors.Planned, CircleShape) else Modifier)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        if (eaten) {
            Icon(Icons.Rounded.Check, contentDescription = "Eaten", tint = ShokuColors.Paper, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
fun ShokuFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val press = app.shoku.ui.theme.rememberPress()
    Surface(
        onClick = onClick,
        interactionSource = press,
        modifier = modifier.pressScale(press, 0.9f).size(64.dp),
        shape = CircleShape,
        color = ShokuColors.Fill,
        contentColor = ShokuColors.Paper,
        shadowElevation = 6.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Add, contentDescription = "Add food", modifier = Modifier.size(30.dp))
        }
    }
}

// ---------- Buttons ----------

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val press = app.shoku.ui.theme.rememberPress()
    Surface(
        onClick = onClick,
        interactionSource = press,
        modifier = modifier.pressScale(press).fillMaxWidth().height(54.dp),
        shape = ShokuShapes.Button,
        color = ShokuColors.Fill,
        contentColor = ShokuColors.Paper,
    ) {
        ButtonContent(text, icon)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val press = app.shoku.ui.theme.rememberPress()
    Surface(
        onClick = onClick,
        interactionSource = press,
        modifier = modifier.pressScale(press).fillMaxWidth().height(54.dp),
        shape = ShokuShapes.Button,
        color = ShokuColors.Paper,
        contentColor = ShokuColors.Ink,
        border = BorderStroke(1.dp, ShokuColors.Line),
    ) {
        ButtonContent(text, icon)
    }
}

@Composable
private fun ButtonContent(text: String, icon: ImageVector?) {
    Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/** Small grey pill for a brand or variant ("Milbona", "Homemade") next to a food name. */
@Composable
fun TagChip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(CircleShape)
            .background(ShokuColors.Tint)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = ShokuColors.Muted, maxLines = 1)
    }
}

/** A food name with its brand chip after it. */
@Composable
fun NameWithTag(name: String, tag: String?, style: androidx.compose.ui.text.TextStyle, color: Color = ShokuColors.Ink, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (!tag.isNullOrBlank()) {
            Spacer(Modifier.width(6.dp))
            TagChip(tag)
        }
    }
}

// ---------- Log rows ----------

/** One food line in a meal card. */
@Composable
fun EntryRow(
    name: String,
    detail: String,
    kcal: Float,
    proteinG: Float,
    eaten: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    approx: Boolean = false,
    tag: String? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EatenToggle(eaten = eaten, onToggle = onToggle)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            NameWithTag(name, tag, MaterialTheme.typography.titleMedium, if (eaten) ShokuColors.Ink else ShokuColors.Muted)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted, maxLines = 1)
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    (if (approx) "~" else "") + formatAmount(kcal),
                    style = NumberStyle.Row,
                    color = if (eaten) ShokuColors.Ink else ShokuColors.Muted,
                )
                Text(" kcal", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted, modifier = Modifier.padding(bottom = 2.dp))
            }
            Text("${formatAmount(proteinG)} g protein", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
        }
    }
}

// ---------- Formatting ----------

/** 1246.4 -> "1,246", 12.0 -> "12", 4.2 -> "4.2" (one decimal below 10). */
fun formatAmount(value: Float): String {
    val abs = kotlin.math.abs(value)
    return if (abs < 10f && value % 1f != 0f) {
        String.format(java.util.Locale.UK, "%.1f", value)
    } else {
        String.format(java.util.Locale.UK, "%,d", kotlin.math.round(value).toInt())
    }
}
