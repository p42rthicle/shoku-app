package app.shoku.data

import android.content.Context
import android.content.SharedPreferences

/**
 * The user's own food rules for AI meal ideas and week plans ("no pork or beef", "low-fat dairy"),
 * one per line. Kept on the phone only; empty until the user writes some.
 */
object DietNotes {
    private var prefs: SharedPreferences? = null

    fun init(context: Context) { prefs = context.getSharedPreferences("ai", Context.MODE_PRIVATE) }

    var text: String
        get() = prefs?.getString("dietNotes", "") ?: ""
        set(v) { prefs?.edit()?.putString("dietNotes", v.trim())?.apply() }

    val lines: List<String> get() = text.lines().map { it.trim().trimStart('-', '•', ' ') }.filter { it.isNotEmpty() }
}
