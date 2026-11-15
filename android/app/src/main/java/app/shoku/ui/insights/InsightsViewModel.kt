package app.shoku.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shoku.data.ShokuRepository
import app.shoku.data.db.Goal
import app.shoku.data.db.LogEntry
import app.shoku.domain.GoalState
import app.shoku.domain.calorieState
import app.shoku.domain.eaten
import app.shoku.domain.goalFor
import app.shoku.domain.proteinState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

data class DayStat(
    val date: LocalDate,
    val kcal: Double,
    val protein: Double,
    val fibre: Double,
    val logged: Boolean,
    val kcalGoal: Double?,
    val proteinGoal: Double?,
) {
    val calories: GoalState get() = if (logged) calorieState(kcal, kcalGoal) else GoalState.None
    val proteinState: GoalState get() = if (logged) proteinState(protein, proteinGoal) else GoalState.None
}

data class InsightsState(
    val month: YearMonth,
    val days: List<DayStat> = emptyList(),
    val daysElapsed: Int = 0,
    val lastLoggedMonth: YearMonth? = null,
) {
    val logged get() = days.filter { it.logged }
    /** Averages and targets leave today out until it's over (a half-logged day drags them down). */
    val complete get() = logged.filter { it.date < LocalDate.now() }.ifEmpty { logged }
    val todayLeftOut get() = complete.size < logged.size
    val avgKcal get() = complete.takeIf { it.isNotEmpty() }?.map { it.kcal }?.average()
    val avgProtein get() = complete.takeIf { it.isNotEmpty() }?.map { it.protein }?.average()
    val avgFibre get() = complete.takeIf { it.isNotEmpty() }?.map { it.fibre }?.average()
    /** On target means at or under the goal, with the Python app's 4% tolerance. */
    val caloriesOnTarget get() = complete.count { it.calories == GoalState.Good || it.calories == GoalState.Close }
    val proteinMet get() = complete.count { it.proteinState == GoalState.Good }
    val kcalGoal get() = days.lastOrNull { it.kcalGoal != null }?.kcalGoal
    val proteinGoal get() = days.lastOrNull { it.proteinGoal != null }?.proteinGoal
}

/** The supplements card: one month of days, the streak, and per-supplement counts. */
data class SupplementInsights(
    val supplements: List<app.shoku.data.db.Supplement> = emptyList(),
    val selected: String? = null,
    val days: Map<LocalDate, app.shoku.domain.SupplementDay> = emptyMap(),
    val streak: Int = 0,
    /** Per supplement this month: taken of due. */
    val perSupplement: List<Triple<String, Int, Int>> = emptyList(),
)

class InsightsViewModel(private val repo: ShokuRepository, private val home: app.shoku.data.HomeRepository? = null) : ViewModel() {
    val month = MutableStateFlow(YearMonth.now())
    val suppFilter = MutableStateFlow<String?>(null)

    val supplements: StateFlow<SupplementInsights> = combine(month, home?.supplements ?: kotlinx.coroutines.flow.emptyFlow(), suppFilter) { m, st, only ->
        val today = LocalDate.now()
        val days = (1..m.lengthOfMonth()).map { m.atDay(it) }.filter { !it.isAfter(today) }
        val used = st.supplements.filter { s -> s.active || st.doses.any { it.supplementId == s.id } }
        SupplementInsights(
            supplements = used,
            selected = only,
            days = app.shoku.domain.supplementDays(used, st.doses, days, only),
            streak = app.shoku.domain.supplementStreak(used, st.doses, today, only),
            perSupplement = used.map { s ->
                val d = app.shoku.domain.supplementDays(listOf(s), st.doses, days)
                Triple(s.name, d.values.sumOf { it.taken }, d.values.sumOf { it.due })
            }.filter { it.third > 0 },
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SupplementInsights())


    val weights: StateFlow<List<app.shoku.data.db.WeightEntry>> = repo.weights.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun logWeight(date: LocalDate, kg: Double) = viewModelScope.launch { repo.setWeight(date, kg) }
    fun deleteWeight(w: app.shoku.data.db.WeightEntry) = viewModelScope.launch { repo.deleteWeight(w) }


    val state: StateFlow<InsightsState> = combine(month, repo.allEntries, repo.allGoals) { m, entries, goals ->
        build(m, entries, goals)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsState(YearMonth.now()))

    private fun build(m: YearMonth, entries: List<LogEntry>, goals: List<Goal>): InsightsState {
        val eaten = entries.eaten()
        val byDate = eaten.filter { it.date.startsWith(m.toString()) }.groupBy { it.date }
        val days = (1..m.lengthOfMonth()).map { d ->
            val date = m.atDay(d)
            val list = byDate[date.toString()].orEmpty()
            val goal = goalFor(goals, date.toString())
            DayStat(
                date = date,
                kcal = list.sumOf { it.kcal ?: 0.0 },
                protein = list.sumOf { it.protein ?: 0.0 },
                fibre = list.sumOf { it.fibre ?: 0.0 },
                logged = list.isNotEmpty(),
                kcalGoal = goal?.kcal,
                proteinGoal = goal?.protein,
            )
        }
        val today = LocalDate.now()
        val elapsed = when {
            m.isBefore(YearMonth.from(today)) -> m.lengthOfMonth()
            m == YearMonth.from(today) -> today.dayOfMonth
            else -> 0
        }
        val last = eaten.maxOfOrNull { it.date }?.let { YearMonth.from(LocalDate.parse(it)) }
        return InsightsState(m, days, elapsed, last)
    }

    fun shift(months: Long) { month.value = month.value.plusMonths(months) }
    fun show(m: YearMonth) { month.value = m }
}
