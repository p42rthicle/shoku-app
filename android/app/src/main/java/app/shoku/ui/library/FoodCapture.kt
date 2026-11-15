package app.shoku.ui.library

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.shoku.ShokuApplication
import app.shoku.data.BarcodeResult
import app.shoku.data.FoodDraft
import app.shoku.data.FoodLookup
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
fun NewFoodEditor(draft: FoodDraft, onReadLabel: ((String?) -> Unit)?, onSave: (Food, List<FoodPortion>) -> Unit, onDismiss: () -> Unit) {
    val pending = remember(draft) { mutableStateOf<List<FoodPortion>>(emptyList()) }
    val barcode = draft.prefill.barcode
    FoodEditor(
        food = null,
        usedIn = 0,
        prefill = draft.prefill,
        familyChoices = draft.families,
        title = when {
            draft.fromLabel -> "Read from the label"
            draft.found -> "Found on Open Food Facts"
            barcode != null -> "New product"
            else -> "New food"
        },
        note = when {
            draft.fromLabel -> listOfNotNull("Check the numbers against the label, then save.", draft.note).joinToString(" ")
            draft.found -> "Check the values against the label, then save. Barcode $barcode."
            barcode != null -> draft.note ?: "Barcode $barcode isn't in Open Food Facts. Take a photo of the label, or type the values (per 100 g or 100 ml)."
            else -> "Type the values per 100 g (or 100 ml, 1 pc), or read them from the label."
        },
        extraAction = if (!draft.found && onReadLabel != null) "Read the label with a photo" to { onReadLabel(barcode) } else null,
        // FoodEditor hands over the units first, then the food. The pack's serving is only suggested.
        portions = emptyList(),
        suggested = draft.portions,
        onSavePortions = { pending.value = it },
        onSave = { food, _ -> onSave(food, pending.value) },
        onArchive = null,
        onDismiss = onDismiss,
    )
}

/** Ways to bring in a food that isn't in the library yet; each ends with it saved there. */
class FoodCapture(val scan: () -> Unit, val label: () -> Unit, val newFood: (String) -> Unit)

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
    fun say(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    val photo = rememberPhotoCapture { jpeg ->
        job = scope.launch {
            busy = "Reading the label…" to true
            runCatching { FoodLookup.label(ai, app.repository.library.first(), jpeg, labelBarcode) }
                .onSuccess { d -> if (d == null) say("Couldn't read a nutrition table in that photo. Try closer, with the table flat and in focus.") else draft = d }
                .onFailure { if (it !is CancellationException) say(it.message ?: "AI request failed") }
            busy = null
        }
    }
    fun readLabel(barcode: String?) { labelBarcode = barcode; draft = null; photo() }

    fun onCode(code: String) {
        job = scope.launch {
            busy = "Looking up $code…" to false
            when (val r = FoodLookup.barcode(app.repository, code)) {
                is BarcodeResult.Known -> onFood(r.food)
                is BarcodeResult.New -> draft = r.draft
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
        )
    }

    busy?.let { (msg, isAi) ->
        BusyOverlay(msg, hint = if (isAi) "Usually 5 to 15 seconds" else "Your foods, then Open Food Facts", ai = isAi, onCancel = { job?.cancel(); busy = null })
    }
    draft?.let { d ->
        NewFoodEditor(
            d,
            onReadLabel = ::readLabel,
            onSave = { food, portions ->
                draft = null
                scope.launch { FoodLookup.save(app.repository, food, portions); onFood(food) }
            },
            onDismiss = { draft = null },
        )
    }
    return capture
}
