package app.shoku

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.io.File

/**
 * "Add bill to stock" in another app's share sheet (the Lidl app's receipt). Invisible: it copies the shared
 * images (the sender's permission ends with this screen), starts reading the bill, and brings the Shoku you
 * already have open to Stock, instead of a second copy of the app on top of the sender.
 */
class BillShare : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        // One bill at a time: while one is being read, its files stay as they are and Stock shows its progress.
        val busy = app.shoku.data.BillImport.state.value is app.shoku.data.BillImport.State.Working
        val dir = File(cacheDir, "shared-bill").apply { if (!busy) { deleteRecursively(); mkdirs() } }
        // A share can always be read while it happens; one that can't is an old share replayed (Shoku 0.9.55-56
        // kept it as a task in recents, and the sender's file or permission is gone): just open Stock.
        var stale = false
        val copies = if (busy) emptyList() else uris.take(8).mapIndexedNotNull { i, uri ->
            runCatching {
                val f = File(dir, "bill$i")
                contentResolver.openInputStream(uri)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
                Uri.fromFile(f)
            }.onFailure { stale = true }.getOrNull()
        }
        if (copies.isNotEmpty()) app.shoku.data.BillImport.start(application as ShokuApplication, copies)
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(app.shoku.data.notify.Reminders.EXTRA_OPEN, "stock")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                // A replayed old share: start that task afresh, so it stops replaying it.
                .apply { if (stale) addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK) },
        )
        finish()
    }
}
