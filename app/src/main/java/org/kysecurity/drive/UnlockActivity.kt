package org.kysecurity.drive

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kysecurity.drive.lock.Attempt
import org.kysecurity.drive.lock.Biometric
import org.kysecurity.drive.lock.Lock

/** The PIN prompt every locked screen hands over to; it finishes back onto that screen. */
class UnlockActivity : SecureActivity() {
    private val field by lazy { privatePin(this).apply { setHint(R.string.lock_pin) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_unlock)
        findViewById<FrameLayout>(R.id.field).addView(field)
        field.setOnEditorActionListener { _, action, _ -> (action == EditorInfo.IME_ACTION_DONE).also { if (it) unlock() } }
        findViewById<View>(R.id.unlock).setOnClickListener { unlock() }
        findViewById<View>(R.id.biometric).setOnClickListener { biometric() }
        // Back leaves the app rather than reaching the locked screen beneath.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })
        if (savedInstanceState == null && Lock.app.biometric) biometric()
    }

    override fun onResume() {
        super.onResume()
        if (!Lock.app.isLocked()) return finish()
        findViewById<View>(R.id.biometric).visibility = if (Lock.app.biometric && Biometric.available(this)) View.VISIBLE else View.GONE
    }

    private fun unlock() {
        val pin = field.takePin()
        if (pin.isEmpty()) return
        findViewById<View>(R.id.unlock).isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) { Lock.app.attempt(pin).also { pin.fill('0') } }
            findViewById<View>(R.id.unlock).isEnabled = true
            done(result)
        }
    }

    private fun biometric() {
        Biometric.prompt(this, getString(R.string.lock_locked), getString(R.string.lock_use_pin)) { ok ->
            if (ok) done(Lock.app.unlockWithBiometric())
        }
    }

    private fun done(result: Attempt) {
        when (result) {
            Attempt.Success -> finish()
            Attempt.Wiped -> restartAfterWipe()
            else -> findViewById<TextView>(R.id.message).text = attemptText(result)
        }
    }
}
