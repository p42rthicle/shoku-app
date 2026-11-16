package app.shoku.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.shoku.ShokuApplication
import app.shoku.data.PhotoStore
import app.shoku.data.db.LogEntry
import app.shoku.ui.formatUnitQty
import app.shoku.ui.theme.ShokuColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** One meal photo and what was logged with it. */
data class MealPhoto(val file: String, val date: String, val meal: String, val entries: List<LogEntry>)

/** Every meal photo on this phone, newest first (a photo shared by several entries once). */
fun mealPhotos(entries: List<LogEntry>): List<MealPhoto> = entries.asSequence()
    .filter { !it.deleted && !it.photos.isNullOrBlank() }
    .flatMap { e -> PhotoStore.of(e.photos).map { it to e } }
    .groupBy({ it.first }, { it.second })
    .map { (file, es) -> MealPhoto(file, es.first().date, es.first().meal, es.sortedBy { it.createdAt }) }
    .sortedWith(compareByDescending<MealPhoto> { it.date }.thenByDescending { it.entries.maxOf { e -> e.createdAt } })

/**
 * Meal photos, full screen: swipe left and right through all of them (newest first), starting at [start].
 * Under each: the day, the meal and what was logged with it.
 */
@Composable
fun MealPhotoViewer(start: String?, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as ShokuApplication
    val all by app.repository.allEntries.collectAsState(initial = emptyList())
    val photos = remember(all) { mealPhotos(all) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
            if (photos.isEmpty()) {
                Text("No meal photos yet. Log a plate with a photo and keep \"Save photo with the meal\" on.",
                    color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.align(Alignment.Center).padding(32.dp))
            } else {
                val first = photos.indexOfFirst { it.file == start }.coerceAtLeast(0)
                val pager = rememberPagerState(initialPage = first) { photos.size }
                HorizontalPager(pager, Modifier.fillMaxSize(), key = { photos[it].file }) { page ->
                    val p = photos[page]
                    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            val bitmap by produceState<ImageBitmap?>(null, p.file) {
                                value = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(PhotoStore.file(p.file).path)?.asImageBitmap() }.getOrNull() }
                            }
                            bitmap?.let { Image(it, contentDescription = "Meal photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                        }
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val d = LocalDate.parse(p.date)
                            Text("${d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)} ${d.dayOfMonth} ${d.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)} · ${p.meal}",
                                style = MaterialTheme.typography.titleMedium, color = androidx.compose.ui.graphics.Color.White)
                            Text(p.entries.joinToString(" · ") { "${it.name} ${formatUnitQty(it.qty, it.unit)}" } +
                                " · ${Math.round(p.entries.sumOf { it.kcal ?: 0.0 })} kcal",
                                style = MaterialTheme.typography.bodySmall, color = androidx.compose.ui.graphics.Color(0xFFBBBBBB), maxLines = 3)
                            Spacer(Modifier.height(4.dp))
                            Text("${page + 1} of ${photos.size}", style = MaterialTheme.typography.labelMedium, color = androidx.compose.ui.graphics.Color(0xFF888888))
                        }
                    }
                }
            }
            Row(Modifier.statusBarsPadding().padding(12.dp)) {
                SquareIconButton(Icons.Rounded.Close, "Close", onClick = onDismiss)
            }
        }
    }
}
