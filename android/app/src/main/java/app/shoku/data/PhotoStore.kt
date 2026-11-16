package app.shoku.data

import android.content.Context
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

    /** The photo files of an entry that are on this phone. */
    fun of(photos: String?): List<String> = photos.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() && file(it).exists() }
}
