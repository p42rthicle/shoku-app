package app.shoku.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import app.shoku.ShokuApplication
import app.shoku.ui.today.FoodSearchSheet
import app.shoku.ui.today.ReplacePick
import app.shoku.ui.today.resolvePick
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shoku.data.db.FoodKind
import app.shoku.data.db.RefType
import app.shoku.domain.Library
import app.shoku.domain.matchesAll
import app.shoku.domain.searchKey
import app.shoku.domain.searchText
import app.shoku.ui.components.Hairline
import app.shoku.ui.dayMonth
import app.shoku.ui.formatUnitQty
import app.shoku.ui.theme.ShokuColors
import app.shoku.ui.theme.ShokuShapes
import java.time.LocalDate

/**
 * Choose a food, dish or batch as an ingredient (or for a meal template). Same search as adding food:
 * your library, the food databases and Open Food Facts. A database food or online product is saved to
 * the library first; [onPick] then gets its type and id and a library that already contains it.
 */
@Composable
fun ItemPicker(
    library: Library,
    title: String,
    excludeDishId: String?,
    onPick: (refType: String, refId: String, library: Library) -> Unit,
    onDismiss: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as ShokuApplication
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    FoodSearchSheet(
        title, null, library, app.reference,
        onPick = { pick ->
            if (saving) return@FoodSearchSheet
            when (pick) {
                is ReplacePick.Own -> if (library.has(pick.refType, pick.refId)) onPick(pick.refType, pick.refId, library) else scope.launch {
                    onPick(pick.refType, pick.refId, app.repository.library.first { it.has(pick.refType, pick.refId) })
                }
                else -> {
                    saving = true
                    scope.launch {
                        val (type, id) = app.repository.resolvePick(pick)
                        val fresh = app.repository.library.first { lib -> lib.foods.containsKey(id) || lib.dishes.containsKey(id) }
                        saving = false
                        onPick(type, id, fresh)
                    }
                }
            }
        },
        onDismiss = onDismiss,
        browse = true,
        excludeDishId = excludeDishId,
        hint = "Picking from a database or online saves it to your library too.",
    )
}

private fun Library.has(refType: String, refId: String) = when (refType) {
    app.shoku.data.db.RefType.FOOD -> foods.containsKey(refId)
    app.shoku.data.db.RefType.DISH -> dishes.containsKey(refId)
    else -> batches.containsKey(refId)
}
