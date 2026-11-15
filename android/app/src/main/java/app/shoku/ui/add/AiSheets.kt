package app.shoku.ui.add

import app.shoku.ui.today.resolvePick
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.RestaurantMenu
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.rounded.Close
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shoku.data.ai.ResolvedItem
import app.shoku.data.db.Basis
import app.shoku.data.db.RefType
import app.shoku.domain.Library
import app.shoku.ui.components.AmountSheet
import app.shoku.ui.components.Hairline
import app.shoku.ui.components.PrimaryButton
import app.shoku.ui.components.rememberSpeechInput
import app.shoku.ui.formatQty
import app.shoku.ui.formatUnitQty
import app.shoku.ui.theme.NumberStyle
import app.shoku.ui.theme.ShokuColors
import app.shoku.ui.theme.ShokuShapes

/** Three entry points for AI logging, shown above the usual items. */
@Composable
fun AiTiles(onDescribe: () -> Unit, onLabel: () -> Unit, onPlate: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile("Describe", Icons.Outlined.TextFields, onDescribe, Modifier.weight(1f))
        Tile("Label", Icons.Outlined.ReceiptLong, onLabel, Modifier.weight(1f))
        Tile("Plate", Icons.Outlined.RestaurantMenu, onPlate, Modifier.weight(1f))
    }
}

@Composable
private fun Tile(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    Column(
        modifier
            .clip(ShokuShapes.Card)
            .border(1.dp, ShokuColors.Line, ShokuShapes.Card)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = ShokuColors.Ink, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/** Type or dictate what you ate. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DescribeSheet(
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = "What did you eat?",
    hint: String = "Amounts help: “2 rotis, a katori of rajma, 150 g skyr”.",
    placeholder: String = "e.g. two eggs, toast with butter and a coffee",
    button: String = "Work it out",
) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val listen = rememberSpeechInput { heard -> text = if (text.isBlank()) heard else "$text, $heard" }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ShokuColors.Paper,
        shape = ShokuShapes.Sheet,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().imePadding().padding(bottom = 16.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted)
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
                    .clip(ShokuShapes.Card)
                    .background(ShokuColors.Tint)
                    .padding(16.dp),
            ) {
                if (text.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = ShokuColors.Planned)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = ShokuColors.Ink),
                    cursorBrush = SolidColor(ShokuColors.Ink),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(54.dp).clip(CircleShape).background(ShokuColors.Tint).clickable(onClick = listen),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Mic, "Dictate", tint = ShokuColors.Ink) }
                Spacer(Modifier.width(10.dp))
                PrimaryButton(button, onClick = { if (text.isNotBlank()) onSubmit(text.trim()) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** AI items to check: untick what's wrong, tap to fix an amount, then log. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiReviewSheet(
    title: String,
    initial: List<ResolvedItem>,
    library: Library?,
    onLog: (List<ResolvedItem>) -> Unit,
    onDismiss: () -> Unit,
    onDraft: ((List<ResolvedItem>) -> Unit)? = null,
    /** Logging a future day saves as planned: the button says so. */
    asPlanned: Boolean = false,
) {
    val items = remember(initial) { mutableStateListOf<ResolvedItem>().apply { addAll(initial) } }
    val included = remember(initial) { mutableStateListOf<Boolean>().apply { initial.forEach { add(true) } } }
    var editing by remember { mutableStateOf<Int?>(null) }
    var replacing by remember { mutableStateOf<Int?>(null) }

    // Only "Discard" closes it: a swipe or Back would throw away a paid AI answer.
    ModalBottomSheet(
        onDismissRequest = {},
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != androidx.compose.material3.SheetValue.Hidden }),
        containerColor = ShokuColors.Paper,
        shape = ShokuShapes.Sheet,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(
                if (items.isEmpty()) "Nothing recognisable came back. Try describing it differently."
                else "Tap an item to change the amount. ~ means estimated.",
                style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted,
            )
            Spacer(Modifier.height(8.dp))
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                items.forEachIndexed { i, it ->
                    if (i > 0) Hairline()
                    Row(Modifier.fillMaxWidth().clickable { editing = i }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(if (included[i]) ShokuColors.Fill else ShokuColors.Paper)
                                .then(if (!included[i]) Modifier.border(1.5.dp, ShokuColors.Planned, CircleShape) else Modifier)
                                .clickable { included[i] = !included[i] },
                            contentAlignment = Alignment.Center,
                        ) { if (included[i]) Icon(Icons.Rounded.Check, "Included", tint = ShokuColors.Paper, modifier = Modifier.size(16.dp)) }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(it.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (included[i]) ShokuColors.Ink else ShokuColors.Muted)
                            val source = when {
                                it.reference != null -> if (it.reference.source == "indb") "Indian dish database" else "German food database"
                                it.refType == RefType.QUICK -> "not in your library, estimated"
                                it.basis == Basis.ESTIMATE -> "from your library, amount estimated"
                                else -> "from your library"
                            }
                            Text("${formatUnitQty(it.qty, it.unit)} · $source", style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted, maxLines = 1)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            (if (it.basis != Basis.EXACT) "~" else "") + (it.nutrients?.kcal?.let { k -> formatQty(Math.round(k).toDouble()) + " kcal" } ?: "–"),
                            style = NumberStyle.Row,
                        )
                    }
                }
            }
            val chosen = items.filterIndexed { i, _ -> included[i] }
            Spacer(Modifier.height(10.dp))
            Hairline()
            Spacer(Modifier.height(10.dp))
            Text(
                "${formatQty(Math.round(chosen.sumOf { it.nutrients?.kcal ?: 0.0 }).toDouble())} kcal · " +
                    "${formatQty(Math.round(chosen.sumOf { it.nutrients?.protein ?: 0.0 } * 10) / 10.0)} g protein",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(12.dp))
            PrimaryButton(if (chosen.isEmpty()) "Nothing to log" else "Log ${chosen.size} as ${if (asPlanned) "planned" else "eaten"}", onClick = { if (chosen.isNotEmpty()) onLog(chosen) })
            if (onDraft != null) {
                Spacer(Modifier.height(10.dp))
                app.shoku.ui.components.SecondaryButton("Save as draft", onClick = { if (chosen.isNotEmpty()) onDraft(chosen) })
                Text(
                    "Drafts show on Today as planned with ~. Fix the amounts there, then tick them when they're right.",
                    style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted, modifier = Modifier.padding(top = 6.dp),
                )
            }
            Text(
                "Discard", style = MaterialTheme.typography.labelLarge, color = ShokuColors.Muted,
                modifier = Modifier.fillMaxWidth().clip(ShokuShapes.Button).clickable(onClick = onDismiss).padding(vertical = 12.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }

    editing?.let { i ->
        val it = items[i]
        val units = when {
            it.reference != null -> it.reference.units
            it.refId != null && library != null -> library.unitsFor(it.refType, it.refId).ifEmpty { listOf(it.unit) }
            else -> listOf(it.unit)
        }
        fun nutrientsFor(u: String, q: Double) = when {
            it.reference != null -> it.reference.nutrients(u, q)
            it.refId != null && library != null -> library.entryNutrients(it.refType, it.refId, u, q)
            else -> it.per100?.times(q / 100.0)
        }
        AmountSheet(
            title = it.name,
            units = units,
            initialUnit = it.unit,
            initialQty = it.qty,
            nutrientsFor = ::nutrientsFor,
            onSave = { u, q -> items[i] = it.copy(unit = u, qty = q, nutrients = nutrientsFor(u, q)); editing = null },
            onRemove = { included[i] = false; editing = null },
            onReplace = if (library != null) ({ editing = null; replacing = i }) else null,
            onDismiss = { editing = null },
        )
    }
    replacing?.let { i ->
        val lib = library ?: return@let
        val shokuApp = androidx.compose.ui.platform.LocalContext.current.applicationContext as app.shoku.ShokuApplication
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        app.shoku.ui.today.FoodSearchSheet(
            "Replace", "AI heard: ${items[i].said.ifBlank { items[i].name }}", lib, shokuApp.reference,
            onPick = { pick ->
                replacing = null
                scope.launch {
                    val (type, id) = shokuApp.repository.resolvePick(pick)
                    val fresh = shokuApp.repository.library.first { l -> l.nameOf(type, id) != null }
                    val unit = fresh.defaultUnit(type, id) ?: return@launch
                    val qty = if (type == app.shoku.data.db.RefType.FOOD) fresh.foods[id]?.let { f -> if (unit == f.unit) f.baseQty else 1.0 } ?: 1.0 else 1.0
                    items[i] = items[i].copy(
                        name = fresh.nameOf(type, id) ?: items[i].name, refType = type, refId = id, reference = null, per100 = null,
                        unit = unit, qty = qty, nutrients = fresh.entryNutrients(type, id, unit, qty), basis = app.shoku.data.db.Basis.EXACT,
                    )
                    editing = i // confirm the amount
                }
            },
            onDismiss = { replacing = null },
            initialQuery = items[i].said.ifBlank { items[i].name },
        )
    }
}

/** A chosen photo: what gets sent, plus a small preview. */
private class PlatePhoto(val base64: String, val preview: androidx.compose.ui.graphics.ImageBitmap?)

private fun preview(base64: String): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
    val bytes = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
}.getOrNull()

/**
 * Up to four photos of the same meal, from the camera or the gallery, plus an optional note.
 * More angles (top, side, with a fork for scale) give the AI a better sense of portion size.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlateSheet(onEstimate: (photos: List<String>, note: String) -> Unit, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val photos = remember { mutableStateListOf<PlatePhoto>() }
    var note by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val takePhoto = app.shoku.ui.components.rememberPhotoCapture { b64 -> if (photos.size < 4) photos.add(PlatePhoto(b64, preview(b64))) }
    val pickPhotos = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(4),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        loading = true
        scope.launch {
            val added = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                uris.take(4 - photos.size).mapNotNull { uri ->
                    runCatching { app.shoku.ui.components.compressToBase64(context, uri, 1600) }.getOrNull()?.let { PlatePhoto(it, preview(it)) }
                }
            }
            photos.addAll(added)
            loading = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ShokuColors.Paper,
        shape = ShokuShapes.Sheet,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().imePadding().padding(bottom = 16.dp)) {
            Text("Photo of your food", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Add up to 4 photos of the same plate. A top view plus a side view, with a fork or glass in the frame, gives the best portion estimate.",
                style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                photos.forEachIndexed { i, p ->
                    Box(Modifier.size(72.dp).clip(ShokuShapes.Tile).background(ShokuColors.Tint)) {
                        p.preview?.let {
                            androidx.compose.foundation.Image(
                                it, contentDescription = "Photo ${i + 1}",
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.matchParentSize(),
                            )
                        }
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clip(CircleShape).background(ShokuColors.Fill).clickable { photos.removeAt(i) },
                            contentAlignment = Alignment.Center,
                        ) { Icon(androidx.compose.material.icons.Icons.Rounded.Close, "Remove", tint = ShokuColors.Paper, modifier = Modifier.size(14.dp)) }
                    }
                }
                if (photos.isEmpty()) Text(if (loading) "Loading photos…" else "No photos yet", style = MaterialTheme.typography.bodyMedium, color = ShokuColors.Muted, modifier = Modifier.padding(vertical = 26.dp))
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                app.shoku.ui.components.SecondaryButton("Camera", onClick = { if (photos.size < 4) takePhoto() }, modifier = Modifier.weight(1f), icon = androidx.compose.material.icons.Icons.Outlined.PhotoCamera)
                app.shoku.ui.components.SecondaryButton(
                    "Gallery",
                    onClick = {
                        if (photos.size < 4) pickPhotos.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    modifier = Modifier.weight(1f), icon = androidx.compose.material.icons.Icons.Outlined.PhotoLibrary,
                )
            }
            Spacer(Modifier.height(12.dp))
            app.shoku.ui.components.LabeledField("Note (optional)", note, { note = it }, Modifier.fillMaxWidth(), placeholder = "e.g. shared plate, I ate half")
            Spacer(Modifier.height(14.dp))
            PrimaryButton(
                if (photos.isEmpty()) "Add a photo first" else "Estimate from ${photos.size} ${if (photos.size == 1) "photo" else "photos"}",
                onClick = { if (photos.isNotEmpty()) onEstimate(photos.map { it.base64 }, note) },
            )
        }
    }
}
