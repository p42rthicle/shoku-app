package app.shoku.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** How long typing has to pause before a search runs. */
const val SEARCH_DEBOUNCE_MS = 250L

/**
 * Search results that keep up with typing: [compute] runs only after typing pauses for [delayMs]
 * (an empty query runs at once) and off the main thread, so the text field never waits on a search.
 * A newer query cancels the older one. [keys] are the data the results depend on (the library, the log).
 */
@Composable
fun <T> rememberSearchResults(query: String, initial: T, vararg keys: Any?, delayMs: Long = SEARCH_DEBOUNCE_MS, compute: (String) -> T): State<T> {
    val result = remember { mutableStateOf(initial) }
    LaunchedEffect(query, *keys) {
        if (query.isNotBlank()) delay(delayMs)
        result.value = withContext(Dispatchers.Default) { compute(query) }
    }
    return result
}
