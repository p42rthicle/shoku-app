package app.shoku.ui.library

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.shoku.ui.components.ChipStyle
import app.shoku.ui.components.StatusChip
import app.shoku.ui.theme.ShokuColors
import app.shoku.ui.theme.ShokuShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.shoku.ShokuApplication
import app.shoku.ui.add.PackSheet
import app.shoku.domain.Duplicates
import app.shoku.data.db.RefType
import app.shoku.data.BarcodeResult
import app.shoku.data.FoodDraft
import app.shoku.data.FoodLookup
import app.shoku.data.DraftMerge
import androidx.compose.foundation.horizontalScroll
import app.shoku.data.PackSizes
import app.shoku.data.ai.AiClient
import app.shoku.data.db.Food
import app.shoku.data.db.FoodPortion
import app.shoku.ui.components.BusyOverlay
import app.shoku.ui.components.rememberPhotoCapture
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Confirm a new food before it's saved: values from Open Food Facts or a label photo, or empty fields
 * to type. [onReadLabel] (with the scanned barcode, if any) offers reading the values from a photo.
 */
@Composable
fun NewFoodEditor(
    draft: FoodDraft,
    onReadLabel: ((String?) -> Unit)?,
    onSave: (Food, List<FoodPortion>) -> Unit,
    onDismiss: () -> Unit,
    onPackPhoto: ((String?) -> Unit)? = null,
    /** Picked "Use this" on a food the library already has. */
    onUseExisting: ((Food) -> Unit)? = null,
    /** Barcode or photo picked for a field they disagree on: the updated draft. */
    onDraft: ((FoodDraft) -> Unit)? = null,
) {
    val pending = remember(draft) { mutableStateOf<List<FoodPortion>>(emptyList()) }
    // Is it already in the library? (A scan of the wraps you have under another name.)
    val shoku = LocalContext.current.applicationContext as ShokuApplication
    val library by shoku.repository.library.collectAsState(initial = null)
    val logs by shoku.repository.allEntries.collectAsState(initial = emptyList())
    val matches = remember(draft, library, logs.size) {
        library?.let { Duplicates.matches(it, draft.prefill, logs).take(2) }.orEmpty()
    }
    val uses = remember(logs.size) { logs.filter { !it.deleted && it.refType == RefType.FOOD }.groupingBy { it.refId }.eachCount() }
    val barcode = draft.prefill.barcode
    val photo = if (draft.photoKind == "pack") "Pack photo" else "Label"
    // A new value chosen below re-seeds the fields.
    androidx.compose.runtime.key(draft.prefill) {
    FoodEditor(
        food = null,
        usedIn = 0,
        prefill = draft.prefill,
        familyChoices = draft.families,
        title = when {
            draft.merged != null -> "Barcode and ${photo.lowercase()}"
            draft.fromPack -> "From the photos of the pack"
            draft.fromLabel -> "Read from the label"
            draft.found -> "Found on Open Food Facts"
            barcode != null -> "New product"
            else -> "New food"
        },
        note = when {
            draft.merged != null -> draft.note ?: "Name and brand from the barcode, the numbers from the ${photo.lowercase()}. Where they disagree, tap the one to keep."
            draft.fromPack && draft.fromLabel -> listOfNotNull("Name and brand from the pack, values from its label. Check them, then save.", draft.note).joinToString(" ")
            draft.fromPack -> listOfNotNull("Brand and name from the pack; the values are typical for this kind of product. Check them, then save.", draft.note).joinToString(" ")
            draft.fromLabel -> listOfNotNull("Check the numbers against the label, then save.", draft.note).joinToString(" ")
            draft.found -> "Check the values against the label, then save. Barcode $barcode."
            barcode != null -> draft.note ?: "Barcode $barcode isn't in Open Food Facts. Take a photo of the label, or type the values (per 100 g or 100 ml)."
            else -> "Type the values per 100 g (or 100 ml, 1 pc), or read them from the label."
        },
        header = if ((matches.isEmpty() || onUseExisting == null) && draft.choices.isEmpty()) null else { {
            if (draft.choices.isNotEmpty()) Column(
                Modifier.fillMaxWidth().clip(ShokuShapes.Card).background(ShokuColors.Tint).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Barcode or ${photo.lowercase()}?", style = MaterialTheme.typography.titleMedium)
                draft.choices.forEach { c ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(c.label, style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
                        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(false to "Barcode · ${c.barcode}", true to "$photo · ${c.photo}").forEach { (fromPhoto, text) ->
                                Box(Modifier.clip(CircleShape).clickable { onDraft?.invoke(DraftMerge.choose(draft, c.field, fromPhoto)) }) {
                                    StatusChip(text, if (c.fromPhoto == fromPhoto) ChipStyle.Filled else ChipStyle.Tinted)
                                }
                            }
                        }
                    }
                }
            }
            if (matches.isNotEmpty() && onUseExisting != null) Column(
                Modifier.fillMaxWidth().clip(ShokuShapes.Card).background(ShokuColors.Tint).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Already in your library?", style = MaterialTheme.typography.titleMedium)
                matches.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name + (f.brand?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodyLarge)
                            val n = uses[f.id] ?: 0
                            Text(listOfNotNull(if (n > 0) "logged $n×" else "not logged yet", if (!f.barcode.isNullOrBlank() && f.barcode == barcode) "same barcode" else null, if (f.archived) "archived" else null).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.clip(CircleShape).clickable { onUseExisting(f) }) { StatusChip("Use this", ChipStyle.Filled) }
                    }
                }
                Text(if (barcode != null) "Using it adds this barcode to it, so the next scan finds it." else "Or save this as a new food below.",
                    style = MaterialTheme.typography.bodySmall, color = ShokuColors.Muted)
            }
        } },
        // After a scan the label can still fill in or correct values (it's merged with the barcode's, not replacing it).
        extraActions = if (draft.found && barcode == null) emptyList() else listOfNotNull(
            onReadLabel?.let { f -> (if (draft.found) "Add values from the label" else "Read the label with a photo") to { f(barcode) } },
            onPackPhoto?.let { f -> (if (draft.found) "Or a photo of the pack" else "No label? Take a photo of the pack") to { f(barcode) } },
        ),
        // FoodEditor hands over the units first, then the food. What the scan found (serving, pack) is
        // filled in, so it's kept unless removed.
        portions = draft.portions,
        onSavePortions = { pending.value = it },
        onSave = { food, _ -> onSave(food, pending.value) },
        onArchive = null,
        onDismiss = onDismiss,
    )
    }
}

/** Ways to bring in a food that isn't in the library yet; each ends with it saved there. */
class FoodCapture(val scan: () -> Unit, val label: () -> Unit, val newFood: (String) -> Unit, val pack: () -> Unit = {})

/**
 * The scanner (library, then Open Food Facts), a photo of the nutrition label (AI) and a food typed by
 * hand, with their sheets and loaders, for any screen. [onFood] gets the food once the library has it.
 */
@Composable
fun rememberFoodCapture(onFood: (Food) -> Unit): FoodCapture {
    val context = LocalContext.current
    val app = context.applicationContext as ShokuApplication
    val scope = rememberCoroutineScope()
    val ai = remember { AiClient() }
    var draft by remember { mutableStateOf<FoodDraft?>(null) }
    var busy by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // message, is AI
    var job by remember { mutableStateOf<Job?>(null) }
    var labelBarcode by remember { mutableStateOf<String?>(null) }
    var packing by remember { mutableStateOf(false) } // the pack photo sheet is open
    fun say(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    // What the barcode found, kept while a label or pack photo is read, then merged (barcode first).
    var base by remember { mutableStateOf<FoodDraft?>(null) }
    fun combine(d: FoodDraft): FoodDraft = base?.let { DraftMerge.merge(it, d) } ?: d
    val photo = rememberPhotoCapture { jpeg ->
        job = scope.launch {
            busy = "Reading the label…" to true
            // A label photo without a nutrition table still tells what the product is.
            runCatching { FoodLookup.labelOrProduct(ai, app.repository.library.first(), jpeg, labelBarcode) }
                .onSuccess { d -> if (d == null) say("Couldn't tell what that is. Try the front of the pack, with the name in focus.") else draft = combine(d) }
                .onFailure { if (it !is CancellationException) say(it.message ?: "AI request failed") }
            busy = null
        }
    }
    fun keep() { base = draft?.takeIf { DraftMerge.worthKeeping(it) }?.let { it.merged?.let { (a, _) -> it.copy(prefill = a, merged = null, choices = emptyList()) } ?: it } }
    fun readLabel(barcode: String?) { labelBarcode = barcode; keep(); draft = null; photo() }
    fun packPhoto(barcode: String?) { labelBarcode = barcode; keep(); draft = null; packing = true }
    fun identify(photos: List<String>) {
        packing = false
        job = scope.launch {
            busy = "Working out what this is…" to true
            runCatching { FoodLookup.product(ai, app.repository.library.first(), photos, labelBarcode) }
                .onSuccess { d -> if (d == null) say("Couldn't tell what that is. Try the front of the pack, with the name in focus.") else draft = combine(d) }
                .onFailure { if (it !is CancellationException) say(it.message ?: "AI request failed") }
            busy = null
        }
    }

    fun onCode(code: String) {
        job = scope.launch {
            busy = "Looking up $code…" to false
            when (val r = FoodLookup.barcode(app.repository, code)) {
                is BarcodeResult.Known -> onFood(r.food)
                is BarcodeResult.New -> { base = null; draft = r.draft }
                BarcodeResult.Offline -> say("Couldn't reach Open Food Facts. Check the connection and scan again.")
            }
            busy = null
        }
    }
    val capture = remember {
        FoodCapture(
            scan = {
                // Google's code scanner: runs in Play services, needs no camera permission in this app.
                val options = GmsBarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E)
                    .enableAutoZoom().build()
                GmsBarcodeScanning.getClient(context, options).startScan()
                    .addOnSuccessListener { b -> b.rawValue?.let(::onCode) }
                    .addOnFailureListener { e -> say("Scanner unavailable: ${e.message}") }
            },
            label = { readLabel(null) },
            newFood = { name -> scope.launch { draft = FoodLookup.manual(app.repository.library.first(), name) } },
            pack = { packPhoto(null) },
        )
    }

    if (packing) PackSheet(onIdentify = ::identify, onDismiss = { packing = false })
    busy?.let { (msg, isAi) ->
        BusyOverlay(msg, hint = if (isAi) "Usually 5 to 15 seconds" else "Your foods, then Open Food Facts", ai = isAi, onCancel = { job?.cancel(); busy = null })
    }
    draft?.let { d ->
        NewFoodEditor(
            d,
            onReadLabel = ::readLabel,
            onPackPhoto = ::packPhoto,
            onUseExisting = { f -> val d0 = d; draft = null; base = null; scope.launch { onFood(FoodLookup.useExisting(app.repository, f, d0)) } },
            onSave = { food, portions ->
                draft = null; base = null
                d.packQty?.let { PackSizes.remember(food.id, it) }
                scope.launch { FoodLookup.save(app.repository, food, portions); onFood(food) }
            },
            onDismiss = { draft = null; base = null },
            onDraft = { draft = it },
        )
    }
    return capture
}
