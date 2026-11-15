package org.kysecurity.drive

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

enum class ThemeMode(val key: String, val night: Int) {
    SYSTEM("system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO),
    DARK("dark", AppCompatDelegate.MODE_NIGHT_YES),
    ;

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

enum class RailSide(val key: String) {
    LEFT("left"),
    RIGHT("right"),
    ;

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: RIGHT
    }
}

/** Display preferences. Nothing secret lives here. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() = ThemeMode.of(prefs.getString("theme_mode", null))
        set(value) = prefs.edit().putString("theme_mode", value.key).apply()

    var railSide: RailSide
        get() = RailSide.of(prefs.getString("rail_side", null))
        set(value) = prefs.edit().putString("rail_side", value.key).apply()
}
