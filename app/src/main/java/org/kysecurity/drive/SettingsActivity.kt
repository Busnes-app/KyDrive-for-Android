package org.kysecurity.drive

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kysecurity.drive.lock.Biometric
import org.kysecurity.drive.lock.Lock
import org.kysecurity.drive.lock.LockoutPolicy

class SettingsActivity : SecureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val settings = Settings(this)
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.version).text = getString(R.string.about_version, BuildConfig.VERSION_NAME)
        showAccount()
        showSecurity()

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

    // Set while the switches are moved to match the lock, so their listeners stay quiet.
    private var syncing = false

    private val graces = mapOf(R.id.grace_now to 0L, R.id.grace_30s to 30_000L, R.id.grace_5m to 300_000L)
    private val erases = mapOf(R.id.erase_10 to 10, R.id.erase_20 to 20, R.id.erase_30 to 30, R.id.erase_never to null)

    /**
     * The app lock. Turning anything off or weakening it asks for the PIN through the same
     * throttled, counted check as the unlock screen; strengthening it does not.
     */
    private fun showSecurity() {
        if (SessionStore(this).load() == null) return
        findViewById<View>(R.id.security).visibility = View.VISIBLE
        val lock = Lock.app
        findViewById<MaterialSwitch>(R.id.lock_switch).setOnCheckedChangeListener { _, on ->
            if (syncing) return@setOnCheckedChangeListener
            if (on) turnLockOn(::syncSecurity) else askPin(R.string.lock_turn_off) { lock.disable(); Biometric.remove(); syncSecurity() }
            syncSecurity()
        }
        findViewById<MaterialSwitch>(R.id.biometric_switch).setOnCheckedChangeListener { _, on ->
            if (syncing) return@setOnCheckedChangeListener
            if (on) askPin(R.string.lock_biometric) { lock.biometric = runCatching { Biometric.enrol() }.isSuccess; syncSecurity() } else {
                lock.biometric = false
                Biometric.remove()
            }
            syncSecurity()
        }
        findViewById<MaterialButtonToggleGroup>(R.id.grace).addOnButtonCheckedListener { _, id, checked ->
            if (syncing || !checked) return@addOnButtonCheckedListener
            val grace = graces.getValue(id)
            if (grace <= lock.graceMillis) lock.graceMillis = grace else askPin(R.string.lock_after) { lock.graceMillis = grace; syncSecurity() }
            syncSecurity()
        }
        findViewById<MaterialButtonToggleGroup>(R.id.erase).addOnButtonCheckedListener { _, id, checked ->
            if (syncing || !checked) return@addOnButtonCheckedListener
            val erase = erases.getValue(id)
            if (!LockoutPolicy.weakens(lock.wipeAfter, erase)) lock.wipeAfter = erase else askPin(R.string.lock_erase) { lock.wipeAfter = erase; syncSecurity() }
            syncSecurity()
        }
        findViewById<View>(R.id.change_pin).setOnClickListener {
            askPin(R.string.lock_change_pin) { turnLockOn(::syncSecurity) }
        }
        syncSecurity()
    }

    /** Moves every control to what the lock holds now; a cancelled dialog snaps them back. */
    private fun syncSecurity() {
        val lock = Lock.app
        syncing = true
        findViewById<MaterialSwitch>(R.id.lock_switch).isChecked = lock.enabled
        findViewById<View>(R.id.lock_options).visibility = if (lock.enabled) View.VISIBLE else View.GONE
        findViewById<MaterialSwitch>(R.id.biometric_switch).apply {
            visibility = if (Biometric.available(this@SettingsActivity)) View.VISIBLE else View.GONE
            isChecked = lock.biometric
        }
        findViewById<MaterialButtonToggleGroup>(R.id.grace).check(graces.entries.minBy { kotlin.math.abs(it.value - lock.graceMillis) }.key)
        findViewById<MaterialButtonToggleGroup>(R.id.erase).check(erases.entries.first { it.value == lock.wipeAfter }.key)
        syncing = false
    }

    /** Revokes on the server when it can, then wipes whatever the server said. */
    private fun unlink(store: SessionStore, s: Session) {
        findViewById<View>(R.id.unlink).isEnabled = false
        lifecycleScope.launch {
            val reached = withContext(Dispatchers.IO) { runCatching { s.api(this@SettingsActivity).unlink() }.isSuccess.also { store.wipe() } }
            val text = if (reached) getString(R.string.unlink_done) else getString(R.string.unlink_offline, s.host)
            Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
