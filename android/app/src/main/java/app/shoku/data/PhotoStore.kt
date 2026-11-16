package app.shoku.data

import android.content.Context
import app.shoku.data.ai.AiMemory
import android.util.Base64
import java.io.File
import java.util.UUID

/**
 * Meal photos kept with their entries: the same downsized JPEG the AI saw (longest side 1600 px,
 * quality 85, a few hundred KB), in the app's own files. Entries hold the file names (LogEntry.photos).
 * Stays on this phone: not synced, not in the backup ZIP.
 */
object PhotoStore {
    private lateinit var app: Context
    fun init(context: Context) { app = context.applicationContext }

    private val dir: File get() = File(app.filesDir, "photos").apply { mkdirs() }
    private val prefs get() = app.getSharedPreferences("photos", Context.MODE_PRIVATE)

    /** "Save photo with the meal" in the plate sheet; on unless turned off. */
    var saveWithMeal: Boolean
        get() = prefs.getBoolean("saveWithMeal", true)
        set(v) { prefs.edit().putBoolean("saveWithMeal", v).apply() }

    /** Writes one photo (base64 JPEG) and returns its file name. */
    fun save(base64: String): String {
        val name = "${UUID.randomUUID()}.jpg"
        File(dir, name).writeBytes(Base64.decode(base64, Base64.NO_WRAP))
        return name
    }

    fun file(name: String): File = File(dir, name)

    /**
     * Up to 3 photos of meals at home, one per kind of dishware ("grey matte plate…", "colourful plate…"),
     * sent small with each plate photo so the AI knows their own dishes. Stored as "file|description" lines.
     */
    fun homeRefs(): List<Pair<String, String>> = prefs.getString("homeRefs", "").orEmpty().lines()
        .mapNotNull { l -> l.split('|', limit = 2).takeIf { it.size == 2 && file(it[0]).exists() }?.let { it[0] to it[1] } }

    private fun saveRefs(list: List<Pair<String, String>>) = prefs.edit().putString("homeRefs", list.joinToString("\n") { "${it.first}|${it.second.replace('\n', ' ')}" }).apply()

    /** A meal at home: keep its photo as the reference for this kind of dishware (newest wins), at most 3 kinds. */
    fun addHomeRef(fileName: String, dishware: String) {
        val words = AiMemory.words(dishware).toSet()
        val list = homeRefs().filterNot { (_, d) -> val w = AiMemory.words(d).toSet(); w.isNotEmpty() && (w intersect words).size * 2 >= minOf(w.size, words.size).coerceAtLeast(1) }
        saveRefs((list + (fileName to dishware)).takeLast(3))
    }

    /** Forgetting a kind of home dishware drops its reference photo too. */
    fun removeHomeRef(dishware: String) = saveRefs(homeRefs().filterNot { it.second == dishware })

    /** The reference photos, small (640 px, JPEG 80), for a request. */
    fun homeRefsBase64(): List<String> = homeRefs().mapNotNull { (f, _) ->
        runCatching {
            val bmp = android.graphics.BitmapFactory.decodeFile(file(f).path) ?: return@runCatching null
            val scale = minOf(1f, 640f / maxOf(bmp.width, bmp.height))
            val small = android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
            val out = java.io.ByteArrayOutputStream()
            small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }.getOrNull()
    }

    /** The photo files of an entry that are on this phone. */
    fun of(photos: String?): List<String> = photos.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() && file(it).exists() }
}
