package org.kysecurity.drive

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.button.MaterialButtonToggleGroup

class SettingsActivity : SecureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val settings = Settings(this)
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.version).text = getString(R.string.about_version, BuildConfig.VERSION_NAME)

        val modes = mapOf(
            R.id.mode_system to ThemeMode.SYSTEM,
            R.id.mode_light to ThemeMode.LIGHT,
            R.id.mode_dark to ThemeMode.DARK,
        )
        findViewById<MaterialButtonToggleGroup>(R.id.mode).apply {
            check(modes.entries.first { it.value == settings.themeMode }.key)
            addOnButtonCheckedListener { _, id, checked ->
                val mode = modes.getValue(id)
                if (checked && mode != settings.themeMode) {
                    settings.themeMode = mode
                    AppCompatDelegate.setDefaultNightMode(mode.night)
                }
            }
        }

        val sides = mapOf(R.id.rail_left to RailSide.LEFT, R.id.rail_right to RailSide.RIGHT)
        findViewById<MaterialButtonToggleGroup>(R.id.rail).apply {
            check(sides.entries.first { it.value == settings.railSide }.key)
            addOnButtonCheckedListener { _, id, checked ->
                if (checked) settings.railSide = sides.getValue(id)
            }
        }
    }
}
