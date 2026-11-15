package app.shoku.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import app.shoku.ui.components.ChipStyle
import app.shoku.ui.components.EatenToggle
import app.shoku.ui.components.IconTile
import app.shoku.ui.components.LetterAvatar
import app.shoku.ui.components.MacroBar
import app.shoku.ui.components.OutlinedCard
import app.shoku.ui.components.PrimaryButton
import app.shoku.ui.components.ScreenTitle
import app.shoku.ui.components.SecondaryButton
import app.shoku.ui.components.SectionLabel
import app.shoku.ui.components.StatusChip
import app.shoku.ui.components.SummaryCard
import app.shoku.ui.theme.NumberStyle
import app.shoku.ui.theme.ShokuColors
import app.shoku.ui.theme.ShokuShapes
import app.shoku.ui.theme.ShokuSpacing

/** Every design-system piece on one screen, for checking the look on the phone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GalleryScreen(contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = ShokuSpacing.Screen,
            end = ShokuSpacing.Screen,
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { ScreenTitle("Design", eyebrow = "Components check") }

        item {
            SectionLabel("Colours")
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Swatch("Ink", ShokuColors.Ink, "#09090B")
                Swatch("Fill", ShokuColors.Fill, "#18181B")
                Swatch("Muted", ShokuColors.Muted, "#6B6B74")
                Swatch("Planned", ShokuColors.Planned, "#C9C9CF")
                Swatch("Line", ShokuColors.Line, "#E4E4E7")
                Swatch("Tint", ShokuColors.Tint, "#F4F4F5")
                Swatch("Over", ShokuColors.Over, "#B4483E")
                Swatch("Over, planned", ShokuColors.OverPlanned, "#E6B9B3")
            }
        }

        item {
            SectionLabel("Type")
            Spacer(Modifier.height(10.dp))
            val t = MaterialTheme.typography
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TypeSample("1,246", t.displayLarge)
                TypeSample("Today", t.headlineLarge)
                TypeSample("Rajma Tadka batch", t.titleLarge)
                TypeSample("312 kcal", NumberStyle.Stat)
                TypeSample("Grafschafter Weizen Toast", t.titleMedium)
                TypeSample("2 pc · 142 kcal · 4.2 g protein", t.bodyMedium, ShokuColors.Muted)
                TypeSample("Space Grotesk for titles and numbers, Inter for text", t.bodySmall, ShokuColors.Muted)
            }
        }

        item {
            SectionLabel("Chips and ticks")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusChip("Eaten", ChipStyle.Filled)
                StatusChip("Planned", ChipStyle.Outlined)
                StatusChip("2/4 eaten", ChipStyle.Tinted)
                Spacer(Modifier.width(8.dp))
                var ticked by remember { mutableStateOf(true) }
                EatenToggle(ticked, onToggle = { ticked = !ticked })
                var unticked by remember { mutableStateOf(false) }
                EatenToggle(unticked, onToggle = { unticked = !unticked })
            }
        }

        item {
            SectionLabel("Progress")
            Spacer(Modifier.height(10.dp))
            OutlinedCard(Modifier.fillMaxWidth()) {
                Text("Eaten only", style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted)
                Spacer(Modifier.height(8.dp))
                MacroBar(0.62f, track = ShokuColors.Tint)
                Spacer(Modifier.height(14.dp))
                Text("Eaten plus planned", style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted)
                Spacer(Modifier.height(8.dp))
                MacroBar(0.55f, plannedFraction = 0.3f, track = ShokuColors.Tint)
                Spacer(Modifier.height(14.dp))
                Text("Planned would go over", style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted)
                Spacer(Modifier.height(8.dp))
                MacroBar(0.8f, plannedFraction = 0.35f, track = ShokuColors.Tint, isLimit = true)
                Spacer(Modifier.height(14.dp))
                Text("Over goal", style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted)
                Spacer(Modifier.height(8.dp))
                MacroBar(1.2f, track = ShokuColors.Tint, isLimit = true)
            }
        }

        item {
            SectionLabel("Cards")
            Spacer(Modifier.height(10.dp))
            SummaryCard(label = "Protein left", value = "16 g", trailing = "of 90 g")
            Spacer(Modifier.height(10.dp))
            OutlinedCard(Modifier.fillMaxWidth(), emphasized = true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LetterAvatar("R")
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Rajma Tadka", style = MaterialTheme.typography.titleMedium)
                        Text("Cooked 3 May · 416 g", style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted)
                    }
                    Text("171", style = NumberStyle.Row)
                }
            }
        }

        item {
            SectionLabel("Buttons and tiles")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconTile(Icons.Outlined.QrCodeScanner)
                IconTile(Icons.Outlined.CameraAlt)
                IconTile(Icons.Outlined.ContentCopy)
            }
            Spacer(Modifier.height(12.dp))
            PrimaryButton("Log 3 items", onClick = {})
            Spacer(Modifier.height(10.dp))
            SecondaryButton("Copy yesterday's breakfast", onClick = {}, icon = Icons.Outlined.ContentCopy)
        }
    }
}

@Composable
private fun Swatch(name: String, color: Color, hex: String) {
    Column(Modifier.width(96.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(ShokuShapes.Tile)
                .background(color)
                .border(1.dp, ShokuColors.Line, ShokuShapes.Tile),
        )
        Spacer(Modifier.height(6.dp))
        Text(name, style = MaterialTheme.typography.labelLarge)
        Text(hex, style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
    }
}

@Composable
private fun TypeSample(text: String, style: TextStyle, color: Color = ShokuColors.Ink) {
    Text(text, style = style, color = color)
}
