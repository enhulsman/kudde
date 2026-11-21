package dev.kudde.android.ui.spaces

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The home screen's view chosen last, Agents or Spaces (spaces spec, "A Spaces
 * view of the home screen": the app opens on the view used last), kept across
 * app restarts in its own app-private preferences file, as the chat view's
 * choice is (ChatViewSetting). Agents on a fresh install; an absent value, or
 * one of another type, reads as Agents. Compose state, so the home redraws on
 * a choice. One instance lives on KuddeApp. Main thread only.
 */
class HomeViewSetting(private val prefs: SharedPreferences) {
    var spaces: Boolean by mutableStateOf(runCatching { prefs.getBoolean(KEY, false) }.getOrDefault(false))
        private set

    fun choose(spaces: Boolean) {
        if (spaces == this.spaces) return
        this.spaces = spaces
        prefs.edit().putBoolean(KEY, spaces).apply()
    }

    companion object {
        const val FILE = "home_view"
        const val KEY = "spaces"

        fun open(context: Context): HomeViewSetting =
            HomeViewSetting(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}
