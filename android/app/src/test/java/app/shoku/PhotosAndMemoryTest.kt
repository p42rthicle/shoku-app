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

    private fun item(name: String, id: String, qty: Double, said: String, unit: String = "g") =
        ResolvedItem(name, "food", id, unit, qty, null, "typical", null, "medium", said)

    private fun fresh() { AiMemory.init(context); context.getSharedPreferences("ai_memory", 0).edit().clear().commit() }

    @Test fun fixesMatchesAndRepeats() {
        fresh()
        // The photo said 200 g basmati; it was 150 g of the rice batch. The dal was right (their own Mix Dal).
        AiMemory.learnFromReview(
            listOf(item("Basmati rice, cooked", "b1", 200.0, "rice"), item("Mix Dal", "d1", 150.0, "dal")),
            listOf(item("Boiled Rice", "r1", 150.0, "rice"), item("Mix Dal", "d1", 150.0, "dal")), "Dinner",
        )
        val m = AiMemory.all().associateBy { it.kind + ":" + it.line }
        assertTrue(m.keys.any { it == "fix:\"rice\" is Boiled Rice (150 g), not Basmati rice, cooked (200 g)" })
        assertTrue(m.keys.any { it == "match:\"dal\" is their Mix Dal (150 g)" })
        // The same fix again strengthens it rather than adding a line; the latest amount wins.
        AiMemory.learnFromReview(listOf(item("Basmati rice, cooked", "b1", 220.0, "rice")), listOf(item("Boiled Rice", "r1", 160.0, "rice")), "Dinner")
        val rice = AiMemory.all().filter { it.kind == AiMemory.FIX && "Boiled Rice" in it.line }
        assertEquals(1, rice.size)
        assertEquals(2, rice.single().count)
        assertTrue("160 g" in rice.single().line)
        // A later fix for "dal" replaces the match.
        AiMemory.learnFromReview(listOf(item("Mix Dal", "d1", 150.0, "dal")), listOf(item("Rajma Tadka", "k1", 200.0, "dal")), "Dinner")
        assertTrue(AiMemory.all().none { it.kind == AiMemory.MATCH && "Mix Dal" in it.line })
    }

    @Test fun hintsPickWhatFitsTheRequest() {
        fresh()
        val day = 86_400_000L
        val now = 100 * day
        AiMemory.learnFromReview(listOf(item("Basmati rice, cooked", "b1", 200.0, "rice")), listOf(item("Boiled Rice", "r1", 150.0, "rice")), "Dinner")
        // Thirty newer memories about other things (the old way, "latest 25", would drop the rice).
        repeat(30) { i -> AiMemory.learnFromReview(listOf(item("Food$i", "x$i", 100.0, "thing$i")), listOf(item("Other$i", "y$i", 50.0, "thing$i")), "Lunch") }
        val hints = AiMemory.hints(emptyList(), query = "2 katori rice and dal", meal = "Dinner", now = now)
        assertTrue(hints.first().contains("Boiled Rice"))
        // Usual amounts at that meal, and the fridge.
        val logs = (1..5).map { LogEntry("l$it", "2026-11-1$it", "Dinner", "food", "w1", "High-protein wrap", "pc", 2.0, 254.0, 14.8, null) } +
            (1..4).map { LogEntry("m$it", "2026-11-1$it", "Breakfast", "food", "o1", "Oats", "g", 40.0, 150.0, 5.0, null) }
        val h2 = AiMemory.hints(logs, query = null, meal = "Dinner", fridge = listOf("Boiled Rice (680 g left)"), today = java.time.LocalDate.of(2026, 11, 16))
        assertTrue(h2.any { it == "At dinner: High-protein wrap, usually 2 pc (5× in 2 months)" })
        assertTrue(h2.none { "Oats" in it }) // a breakfast food isn't a dinner hint
        assertTrue(h2.any { it.startsWith("Cooked and in their fridge now") && "Boiled Rice (680 g left)" in it })
    }

    @Test fun oldCorrectionsCarryOver() {
        fresh()
        context.getSharedPreferences("ai_memory", 0).edit().putString("corrections", """[{"at":5,"line":"High-protein wrap: 2 pc, not 1 pc"}]""").commit()
        val all = AiMemory.all()
        assertEquals(1, all.size)
        assertEquals(AiMemory.FIX, all.single().kind)
        assertTrue("wrap" in all.single().words)
    }

    @Test fun forgetRestoreAndForgetAll() {
        fresh()
        AiMemory.learnFromReview(listOf(item("Basmati rice, cooked", "b1", 200.0, "rice")), listOf(item("Boiled Rice", "r1", 150.0, "rice")), "Dinner")
        AiMemory.learnFromReview(listOf(item("Mix Dal", "d1", 150.0, "dal")), listOf(item("Mix Dal", "d1", 150.0, "dal")), "Dinner")
        val rice = AiMemory.all().first { "Boiled Rice" in it.line }
        AiMemory.forget(rice.key)
        assertTrue(AiMemory.all().none { "Boiled Rice" in it.line })
        AiMemory.restore(rice)
        assertEquals(2, AiMemory.all().size)
        AiMemory.forgetAll()
        assertTrue(AiMemory.all().isEmpty())
        assertTrue(AiMemory.hints(emptyList(), "rice", "Dinner").isEmpty())
    }

    @Test fun aLaterEditReplacesTheMatch() {
        fresh()
        // Kept at 200 g in the review (a match), then changed to 1 serving on Today.
        AiMemory.learnFromReview(listOf(item("Crushed Gobhi Soya Paneer", "g1", 200.0, "bowl of gobhi sabzi")), listOf(item("Crushed Gobhi Soya Paneer", "g1", 200.0, "bowl of gobhi sabzi")), "Dinner")
        assertEquals(1, AiMemory.all().count { it.kind == AiMemory.MATCH })
        val e = LogEntry("e", "2026-11-16", "Dinner", "batch", "g1", "Crushed Gobhi Soya Paneer", "g", 200.0, 386.0, 30.0, null, source = "ai")
        AiMemory.learnFromEdit(e, e.copy(unit = "serving", qty = 1.0))
        assertTrue(AiMemory.all().none { it.kind == AiMemory.MATCH })
        assertEquals("Crushed Gobhi Soya Paneer: 1 serving, not 200 g", AiMemory.all().single().line)
    }

    @Test fun homeDishesAndReferencePhotos() {
        fresh()
        app.shoku.data.PhotoStore.init(context)
        context.getSharedPreferences("photos", 0).edit().clear().commit()
        AiMemory.learnHomeDishes("grey matte plate with dark rim, steel spoon")
        AiMemory.learnHomeDishes("grey matte plate with a dark rim and steel fork") // the same plate: strengthens
        AiMemory.learnHomeDishes("colourful ceramic plate with flowers")
        val dishes = AiMemory.all().filter { it.kind == AiMemory.DISH }
        assertEquals(2, dishes.size)
        assertEquals(2, dishes.maxOf { it.count })
        val hints = AiMemory.hints(emptyList(), query = null, meal = "Dinner")
        assertTrue(hints.first().startsWith("Their home dishes: ") && "colourful ceramic plate" in hints.first())

        // Reference photos: one per kind of dishware, newest wins, at most 3.
        val jpeg = android.util.Base64.encodeToString(ByteArray(10) { it.toByte() }, android.util.Base64.NO_WRAP)
        fun ref(d: String) = app.shoku.data.PhotoStore.addHomeRef(app.shoku.data.PhotoStore.save(jpeg), d)
        ref("grey matte plate with dark rim"); ref("grey matte plate, dark rim, steel spoon"); ref("colourful plate with flowers")
        assertEquals(2, app.shoku.data.PhotoStore.homeRefs().size)
        ref("white ceramic bowl"); ref("black slate board")
        assertEquals(3, app.shoku.data.PhotoStore.homeRefs().size)
        app.shoku.data.PhotoStore.removeHomeRef("black slate board")
        assertTrue(app.shoku.data.PhotoStore.homeRefs().none { it.second == "black slate board" })
    }
}
