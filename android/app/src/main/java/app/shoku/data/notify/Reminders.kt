package app.shoku.data.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.shoku.MainActivity
import app.shoku.R
import app.shoku.ShokuApplication
import app.shoku.data.MealSettingsStore
import app.shoku.data.ShokuRepository
import app.shoku.data.db.EntryStatus
import app.shoku.data.db.LogEntry
import app.shoku.data.db.Meals
import app.shoku.domain.eaten
import app.shoku.domain.goalFor
import app.shoku.domain.usualMeal
import app.shoku.domain.prepNeeds
import app.shoku.ui.formatUnitQty
import app.shoku.domain.weekShopping
import app.shoku.domain.stockLevels
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/** Reminder times, kept on the phone only. Minutes after midnight; null means off. */
class ReminderSettings(context: Context) {
    private val prefs = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    fun timeFor(meal: String): Int? = prefs.getInt("time_$meal", -1).takeIf { it >= 0 }
    fun setTime(meal: String, minutes: Int?) = prefs.edit().putInt("time_$meal", minutes ?: -1).apply()
    var weekly: Boolean
        get() = prefs.getBoolean("weekly", false)
        set(v) = prefs.edit().putBoolean("weekly", v).apply()
    /** Supplement reminders ride on the meal reminders; on by default, at the default time if a meal's reminder is off. */
    /** Sunday 10:00: next week's shopping list (plan minus stock). */
    var shopping: Boolean
        get() = prefs.getBoolean("shopping", true)
        set(v) = prefs.edit().putBoolean("shopping", v).apply()
    /** Sunday and Wednesday evening: what to marinate, make and cook for the next days of the plan. */
    var prep: Boolean
        get() = prefs.getBoolean("prep", true)
        set(v) = prefs.edit().putBoolean("prep", v).apply()
    var supplements: Boolean
        get() = prefs.getBoolean("supplements", true)
        set(v) = prefs.edit().putBoolean("supplements", v).apply()

    companion object {
        val DEFAULTS = mapOf(Meals.BREAKFAST to 9 * 60 + 30, Meals.LUNCH to 13 * 60 + 30, Meals.SNACKS to 17 * 60, Meals.DINNER to 20 * 60 + 30)
    }
}

object Reminders {
    const val CHANNEL = "reminders"
    const val WEEKLY = "weekly"
    const val PREP = "prep"
    const val SHOPPING = "shopping"

    private fun nextSundayAt(h: Int, m: Int): LocalDateTime {
        val t = LocalDate.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).atTime(h, m)
        return if (t.isAfter(LocalDateTime.now().plusMinutes(1))) t else t.plusWeeks(1)
    }

    /** Opens a shoku:// link in the app (see DeepLinks). */
    fun linkIntent(context: Context, link: String, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context, requestCode,
        Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link)).setClass(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    /** Prep evenings: Sunday covers Mon–Wed, Wednesday covers Thu–Sun. */
    val PREP_DAYS = listOf(DayOfWeek.SUNDAY, DayOfWeek.WEDNESDAY)

    private fun nextPrep(): LocalDateTime = PREP_DAYS.map { d ->
        val t = LocalDate.now().with(TemporalAdjusters.nextOrSame(d)).atTime(18, 0)
        if (t.isAfter(LocalDateTime.now().plusMinutes(1))) t else t.plusWeeks(1)
    }.min()

    /** Days from the day after [today] up to and including the next prep day. */
    fun prepDays(today: LocalDate): Int = (1..7).first { today.plusDays(it.toLong()).dayOfWeek in PREP_DAYS }
    const val ACTION_LOG_USUAL = "app.shoku.LOG_USUAL"
    const val ACTION_TAKE_SUPPLEMENTS = "app.shoku.TAKE_SUPPLEMENTS"
    const val EXTRA_MEAL = "meal"
    const val EXTRA_ONLY = "only"
    const val EXTRA_OPEN = "open"

    fun notificationId(meal: String) = 1000 + (Meals.ALL.indexOf(meal).takeIf { it >= 0 } ?: 9)

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Meal reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A nudge to log a meal you haven't logged yet, and a Sunday summary"
            })
        }
    }

    /** (Re)schedules every reminder from the settings. Call on app start and after changing a setting. */
    fun scheduleAll(context: Context) {
        val s = ReminderSettings(context)
        // The worker decides at fire time what to show (meal, supplements or nothing).
        Meals.ALL.forEach { meal ->
            val time = s.timeFor(meal) ?: if (s.supplements) ReminderSettings.DEFAULTS.getValue(meal) else null
            schedule(context, meal, time?.let { nextAt(it) })
        }
        schedule(context, WEEKLY, if (s.weekly) nextSunday() else null)
        schedule(context, PREP, if (s.prep) nextPrep() else null)
        schedule(context, SHOPPING, if (s.shopping) nextSundayAt(10, 0) else null)
    }

    private fun schedule(context: Context, key: String, at: LocalDateTime?) {
        val wm = WorkManager.getInstance(context)
        if (at == null) { wm.cancelUniqueWork("reminder-$key"); return }
        val delay = Duration.between(LocalDateTime.now(), at).toMillis().coerceAtLeast(0)
        val req = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, java.util.concurrent.TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(EXTRA_MEAL to key))
            .build()
        wm.enqueueUniqueWork("reminder-$key", ExistingWorkPolicy.REPLACE, req)
    }

    private fun nextAt(minutes: Int): LocalDateTime {
        val today = LocalDate.now().atTime(LocalTime.of(minutes / 60, minutes % 60))
        return if (today.isAfter(LocalDateTime.now().plusMinutes(1))) today else today.plusDays(1)
    }

    private fun nextSunday(): LocalDateTime {
        val t = LocalDate.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).atTime(19, 0)
        return if (t.isAfter(LocalDateTime.now().plusMinutes(1))) t else t.plusWeeks(1)
    }

    fun canNotify(context: Context) =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun openAppIntent(context: Context, meal: String?, open: String?, requestCode: Int): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            meal?.let { putExtra(EXTRA_MEAL, it) }
            open?.let { putExtra(EXTRA_OPEN, it) }
        }
        return PendingIntent.getActivity(context, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun post(context: Context, id: Int, n: android.app.Notification) {
        if (canNotify(context)) NotificationManagerCompat.from(context).notify(id, n)
    }
}

/** Posts a meal reminder (only if that meal is still empty today) or the Sunday summary, then schedules the next one. */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val key = inputData.getString(Reminders.EXTRA_MEAL) ?: return Result.success()
        val app = applicationContext as ShokuApplication
        Reminders.ensureChannel(app)
        runCatching {
            when (key) {
                Reminders.WEEKLY -> weekly(app)
                Reminders.PREP -> prep(app)
                Reminders.SHOPPING -> shopping(app)
                else -> meal(app, key, inputData.getString(Reminders.EXTRA_ONLY)?.split(',')?.toSet())
            }
        }
        Reminders.scheduleAll(app)
        return Result.success()
    }

    /** [only]: limit to these supplement ids and skip the meal part (used by the self-test). */
    private suspend fun meal(app: ShokuApplication, meal: String, only: Set<String>? = null) {
        val repo = app.repository
        val today = LocalDate.now()
        val settings = ReminderSettings(app)
        // The meal part: only when its reminder is on, the meal is in use today and still empty.
        val askMeal = only == null && settings.timeFor(meal) != null && !MealSettingsStore.current.value.hiddenOn(meal, today) &&
            repo.day(today).first().none { it.meal == meal }
        // The supplement part: due with this meal and not ticked yet.
        val supps = app.home.supplements.first().let { st -> st.due(meal, today).filter { !st.taken(it, today) && (only == null || it.id in only) } }
            .takeIf { settings.supplements }.orEmpty()
        if (!askMeal && supps.isEmpty()) return
        val usual = if (askMeal) usualMeal(repo.allEntries.first(), meal, today, repo.library.first()) else emptyList()
        val suppText = if (supps.isEmpty()) null else "Take: " + supps.joinToString(", ") { it.name }
        val mealText = when {
            !askMeal -> null
            usual.isNotEmpty() -> "Your usual: " + usual.joinToString(", ") { it.name } + " · ${Math.round(usual.sumOf { it.nutrients?.kcal ?: 0.0 })} kcal"
            else -> "Nothing logged for ${meal.lowercase()} yet"
        }
        val text = listOfNotNull(suppText, mealText).joinToString("\n")
        val b = NotificationCompat.Builder(app, Reminders.CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(if (askMeal) "Log ${meal.lowercase()}?" else "Supplements with ${meal.lowercase()}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(Reminders.openAppIntent(app, meal, if (askMeal) "add" else "today", Reminders.notificationId(meal)))
        if (supps.isNotEmpty()) {
            val take = Intent(app, ReminderActionReceiver::class.java).setAction(Reminders.ACTION_TAKE_SUPPLEMENTS).putExtra(Reminders.EXTRA_MEAL, meal)
                .putExtra(Reminders.EXTRA_ONLY, only?.joinToString(","))
            b.addAction(0, "Taken", PendingIntent.getBroadcast(app, 200 + Reminders.notificationId(meal), take, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        if (!askMeal) {
            Reminders.post(app, Reminders.notificationId(meal), b.build())
            return
        }
        if (usual.isNotEmpty()) {
            val log = Intent(app, ReminderActionReceiver::class.java).setAction(Reminders.ACTION_LOG_USUAL).putExtra(Reminders.EXTRA_MEAL, meal)
            b.addAction(0, "Log usual", PendingIntent.getBroadcast(app, Reminders.notificationId(meal), log, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        b.addAction(0, "Describe", Reminders.openAppIntent(app, meal, "describe", 100 + Reminders.notificationId(meal)))
        Reminders.post(app, Reminders.notificationId(meal), b.build())
    }

    private suspend fun shopping(app: ShokuApplication) {
        val lib = app.repository.library.first()
        val (items, moves) = app.home.stock.first()
        val plan = app.home.plan.first()
        val today = LocalDate.now()
        val levels = lib.stockLevels(items, moves, app.repository.allEntries.first(), app.home.supplements.first().doses, plan, today)
        val nextMonday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        val week = lib.weekShopping(plan, levels, nextMonday)
        if (week.isEmpty()) return
        val text = week.take(8).joinToString(" · ") { "${it.name} ${formatUnitQty(it.buyRounded, it.buyUnit)}" } + if (week.size > 8) " · +${week.size - 8} more" else ""
        val n = NotificationCompat.Builder(app, Reminders.CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Shopping for next week: ${week.size} ${if (week.size == 1) "item" else "items"}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(Reminders.linkIntent(app, "shoku://plan/stock", 5000))
            .build()
        Reminders.post(app, 5000, n)
    }

    private suspend fun prep(app: ShokuApplication) {
        val today = LocalDate.now()
        val days = Reminders.prepDays(today)
        val lib = app.repository.library.first()
        val need = lib.prepNeeds(app.home.plan.first(), today.plusDays(1), days)
        val parts = buildList {
            if (need.chickenG > 0) add("marinate ${Math.round(need.chickenG)} g chicken")
            if (need.paneerG > 0) add("make ${Math.round(need.paneerG)} g paneer (≈${"%.1f".format(java.util.Locale.US, need.milkL)} L milk)")
            if (need.batchServings > 0) add("cook a sabzi or dal batch (${Math.round(need.batchServings).let { if (it == 1L) "1 serving" else "$it servings" }})")
        }
        if (parts.isEmpty()) return
        fun day(n: Long) = today.plusDays(n).dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.UK)
        val range = if (days == 1) day(1) else "${day(1)}–${day(days.toLong())}"
        val text = parts.joinToString(" · ").replaceFirstChar { it.uppercase() }
        val n = NotificationCompat.Builder(app, Reminders.CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Prep for $range")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(Reminders.openAppIntent(app, null, "today", 4000))
            .build()
        Reminders.post(app, 4000, n)
    }

    private fun daysText(n: Int) = if (n == 1) "1 day" else "$n days"

    private suspend fun weekly(app: ShokuApplication) {
        val repo = app.repository
        val end = LocalDate.now()
        val start = end.minusDays(6)
        val all = repo.allEntries.first().filter { it.date >= start.toString() && it.date <= end.toString() }.eaten()
        val byDay = all.groupBy { it.date }
        if (byDay.isEmpty()) return
        val goals = repo.allGoals.first()
        val kcal = byDay.values.map { d -> d.sumOf { it.kcal ?: 0.0 } }
        val protein = byDay.values.map { d -> d.sumOf { it.protein ?: 0.0 } }
        val onTarget = byDay.count { (date, d) -> goalFor(goals, date)?.let { g -> d.sumOf { it.kcal ?: 0.0 } <= g.kcal } == true }
        val proteinMet = byDay.count { (date, d) -> goalFor(goals, date)?.let { g -> d.sumOf { it.protein ?: 0.0 } >= g.protein } == true }
        val text = "Logged ${byDay.size} of 7 days · ${Math.round(kcal.average())} kcal and ${Math.round(protein.average())} g protein a day · " +
            "calories on target ${daysText(onTarget)}, protein met ${daysText(proteinMet)}"
        val n = NotificationCompat.Builder(app, Reminders.CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Your week")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(Reminders.openAppIntent(app, null, "insights", 2000))
            .build()
        Reminders.post(app, 2000, n)
    }
}

/** "Log usual" on a reminder: logs the usual meal as eaten without opening the app. */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val meal = intent.getStringExtra(Reminders.EXTRA_MEAL) ?: return
        val app = context.applicationContext as ShokuApplication
        if (intent.action == Reminders.ACTION_TAKE_SUPPLEMENTS) {
            val pending = goAsync()
            app.appScope.launch {
                try {
                    app.home.takeAll(meal, LocalDate.now(), intent.getStringExtra(Reminders.EXTRA_ONLY)?.split(',')?.toSet())
                    NotificationManagerCompat.from(app).cancel(Reminders.notificationId(meal))
                } finally {
                    pending.finish()
                }
            }
            return
        }
        if (intent.action != Reminders.ACTION_LOG_USUAL) return
        val pending = goAsync()
        app.appScope.launch {
            try {
                val repo = app.repository
                val today = LocalDate.now()
                val items = usualMeal(repo.allEntries.first(), meal, today, repo.library.first())
                val now = System.currentTimeMillis()
                items.forEachIndexed { i, it ->
                    repo.add(
                        LogEntry(
                            id = ShokuRepository.newId(), date = today.toString(), meal = meal, refType = it.refType, refId = it.refId,
                            name = it.name, unit = it.unit, qty = it.qty, kcal = it.nutrients?.kcal, protein = it.nutrients?.protein,
                            fibre = it.nutrients?.fibre, carbs = it.nutrients?.carbs, fat = it.nutrients?.fat, status = EntryStatus.EATEN,
                            basis = it.basis, source = "usual", createdAt = now + i, updatedAt = now + i,
                        ),
                    )
                }
                val n = NotificationCompat.Builder(app, Reminders.CHANNEL)
                    .setSmallIcon(R.drawable.ic_notify)
                    .setContentTitle("Logged your usual ${meal.lowercase()}")
                    .setContentText("${items.size} items · ${Math.round(items.sumOf { it.nutrients?.kcal ?: 0.0 })} kcal. Tap to change.")
                    .setAutoCancel(true)
                    .setTimeoutAfter(60 * 60 * 1000L)
                    .setContentIntent(Reminders.openAppIntent(app, null, "today", 3000))
                    .build()
                Reminders.post(app, Reminders.notificationId(meal), n)
            } finally {
                pending.finish()
            }
        }
    }
}
