package app.shoku.data

import android.content.Context
import android.content.SharedPreferences
import app.shoku.domain.MealSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

/** Meal times and hidden meals, kept on the phone (SharedPreferences "meals"). Loaded once at app start. */
object MealSettingsStore {
    private val json = Json { ignoreUnknownKeys = true }
    private var prefs: SharedPreferences? = null
    private val _current = MutableStateFlow(MealSettings())
    val current: StateFlow<MealSettings> = _current

    fun init(context: Context) {
        val p = context.getSharedPreferences("meals", Context.MODE_PRIVATE)
        prefs = p
        _current.value = p.getString("settings", null)
            ?.let { runCatching { json.decodeFromString(MealSettings.serializer(), it) }.getOrNull() }
            ?: MealSettings()
    }

    fun update(change: (MealSettings) -> MealSettings) {
        val next = change(_current.value)
        _current.value = next
        prefs?.edit()?.putString("settings", json.encodeToString(MealSettings.serializer(), next))?.apply()
    }
}
