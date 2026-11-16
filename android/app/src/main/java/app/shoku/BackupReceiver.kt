package app.shoku

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File

/**
 * Copies the database to the app's own external files (Android/data/app.shoku/files/backups), so a
 * release build (no adb run-as) can still be backed up from a computer before an update:
 *   adb shell am broadcast -a app.shoku.BACKUP -n app.shoku/.BackupReceiver
 *   adb pull /sdcard/Android/data/app.shoku/files/backups <folder>
 * Only the shell can send it (the DUMP permission in the manifest); other apps can't.
 */
class BackupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val done = goAsync()
        Thread {
            val result = runCatching {
                val db = (context.applicationContext as ShokuApplication).database
                // Fold the write-ahead log into the main file first, so shoku.db alone is complete.
                db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
                val dir = File(context.getExternalFilesDir(null), "backups").apply { mkdirs() }
                listOf("shoku.db", "shoku.db-wal", "shoku.db-shm").forEach { n ->
                    val from = context.getDatabasePath(n)
                    val to = File(dir, n)
                    if (from.exists()) from.copyTo(to, overwrite = true) else to.delete()
                }
                "ok ${File(dir, "shoku.db").length()}"
            }.getOrElse { "error: ${it.message}" }
            done.resultData = result
            done.finish()
        }.start()
    }
}
