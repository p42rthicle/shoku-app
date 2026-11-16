package app.shoku

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.shoku.data.ai.AiMemory
import app.shoku.data.ai.ResolvedItem
import app.shoku.data.db.LogEntry
import app.shoku.data.db.MIGRATION_1_2
import app.shoku.data.db.MIGRATION_2_3
import app.shoku.data.db.MIGRATION_3_4
import app.shoku.data.db.MIGRATION_4_5
import app.shoku.data.db.MIGRATION_5_6
import app.shoku.data.db.ShokuDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PhotosAndMemoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    /** A version-5 database (the phone's today) with an entry opens as version 6, the entry untouched. */
    @Test fun migration5to6KeepsEntries() = runBlocking {
        val schema = JSONObject(File("schemas/app.shoku.data.db.ShokuDatabase/5.json").readText()).getJSONObject("database")
        val file = context.getDatabasePath("migrate.db").apply { parentFile?.mkdirs(); delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
                val idx = e.optJSONArray("indices")
                if (idx != null) for (j in 0 until idx.length()) db.execSQL(idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO log_entries (id, date, meal, refType, refId, name, unit, qty, kcal, protein, fibre, carbs, fat, status, source, basis, createdAt, updatedAt, deleted, variant, note) " +
                "VALUES ('e1', '2026-11-16', 'Lunch', 'food', 'f1', 'Boiled Rice', 'g', 150.0, 195.0, 4.0, 0.5, 42.0, 0.4, 'eaten', 'manual', 'exact', 1, 2, 0, NULL, 'at home')")
            db.version = 5
        }
        val room = Room.databaseBuilder(context, ShokuDatabase::class.java, "migrate.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).allowMainThreadQueries().build()
        val e = room.logs().allIncludingDeleted().single()
        assertEquals("Boiled Rice", e.name)
        assertEquals(150.0, e.qty, 0.0)
        assertEquals(195.0, e.kcal!!, 0.0)
        assertEquals("at home", e.note)
        assertNull(e.photos)
        room.close()
    }

    @Test fun correctionsAndUsualPortionsBecomeHints() {
        AiMemory.init(context)
        context.getSharedPreferences("ai_memory", 0).edit().clear().commit()
        fun item(name: String, id: String, qty: Double, said: String) = ResolvedItem(name, "food", id, "g", qty, null, "typical", null, "medium", said)
        // The photo said 200 g basmati; it was 150 g of the rice batch. The dal was right.
        val proposed = listOf(item("Basmati rice, cooked", "b1", 200.0, "rice"), item("Mix Dal", "d1", 150.0, "dal"))
        val logged = listOf(item("Boiled Rice", "r1", 150.0, "rice"), item("Mix Dal", "d1", 150.0, "dal"))
        AiMemory.learnFromReview(proposed, logged)
        // Later, an AI entry's amount fixed on Today.
        val old = LogEntry("x", "2026-11-16", "Dinner", "food", "w1", "High-protein wrap", "pc", 1.0, 127.0, 7.4, null, source = "ai")
        AiMemory.learnFromEdit(old, old.copy(qty = 2.0))
        val logs = (1..5).map { LogEntry("l$it", "2026-11-1$it", "Dinner", "food", "w1", "High-protein wrap", "pc", 2.0, 254.0, 14.8, null) }
        val hints = AiMemory.hints(logs, java.time.LocalDate.of(2026, 11, 16))
        assertEquals("High-protein wrap: 2 pc, not 1 pc", hints[0]) // newest first
        assertEquals("\"rice\" is Boiled Rice (150 g), not Basmati rice, cooked (200 g)", hints[1])
        assertTrue(hints.none { "Mix Dal" in it }) // unchanged items teach nothing
        assertTrue(hints.any { it.startsWith("High-protein wrap: usually 2 pc") })
    }
}
