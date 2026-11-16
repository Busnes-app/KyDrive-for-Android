package org.kysecurity.drive

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : SecureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val settings = Settings(this)
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.version).text = getString(R.string.about_version, BuildConfig.VERSION_NAME)
        showAccount()

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

    private fun showAccount() {
        val store = SessionStore(this)
        val s = store.load() ?: return
        findViewById<View>(R.id.account).visibility = View.VISIBLE
        findViewById<TextView>(R.id.server).text = s.host
        findViewById<TextView>(R.id.user).text = s.user
        findViewById<TextView>(R.id.device).text = s.device
        findViewById<View>(R.id.unlink).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.unlink)
                .setMessage(getString(R.string.unlink_body, s.host))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.unlink) { _, _ -> unlink(store, s) }
                .show()
        }
    }

    /** Revokes on the server when it can, then wipes whatever the server said. */
    private fun unlink(store: SessionStore, s: Session) {
        findViewById<View>(R.id.unlink).isEnabled = false
        lifecycleScope.launch {
            val reached = withContext(Dispatchers.IO) { runCatching { s.api().unlink() }.isSuccess.also { store.wipe() } }
            val text = if (reached) getString(R.string.unlink_done) else getString(R.string.unlink_offline, s.host)
            Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
