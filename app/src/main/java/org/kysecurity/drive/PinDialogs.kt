package org.kysecurity.drive

import android.content.DialogInterface
import android.content.Intent
import android.text.format.DateUtils
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kysecurity.drive.lock.Attempt
import org.kysecurity.drive.lock.Lock
import org.kysecurity.drive.lock.PinPolicy

/** What to tell the user about a PIN that did not unlock, or null for a success. */
fun AppCompatActivity.attemptText(a: Attempt): String? = when (a) {
    Attempt.Success, Attempt.Stale -> null
    Attempt.Unavailable -> getString(R.string.lock_unavailable)
    Attempt.Wiped -> getString(R.string.lock_wiped)
    is Attempt.Rejected ->
        if (a.waitMillis > 0) getString(R.string.lock_wait, DateUtils.formatElapsedTime(a.waitMillis / 1000)) else getString(R.string.lock_wrong)
}

/** After an erase there is nothing to return to: start again at the link screen. */
fun AppCompatActivity.restartAfterWipe() {
    Toast.makeText(applicationContext, R.string.lock_wiped, Toast.LENGTH_LONG).show()
    finishAffinity()
    startActivity(Intent(this, MainActivity::class.java))
}

/**
 * Checks [pin] through the lock (throttled, counted, erasing at the limit) off the main
 * thread, then runs [then] on success. Every PIN check outside the unlock screen comes here.
 */
fun AppCompatActivity.checkPin(pin: CharArray, then: () -> Unit) {
    val entered = Lock.app.entered()
    lifecycleScope.launch {
        val result = withContext(Dispatchers.Default) { Lock.app.attempt(pin, entered).also { pin.fill('0') } }
        when {
            result == Attempt.Success -> then()
            result == Attempt.Wiped -> restartAfterWipe()
            // The app locked or left meanwhile: this screen may already be past its gate.
            result == Attempt.Stale -> (this@checkPin as? SecureActivity)?.gate()
            else -> Toast.makeText(this@checkPin, attemptText(result), Toast.LENGTH_LONG).show()
        }
    }
}

private fun AppCompatActivity.fields(vararg hints: Int): Pair<LinearLayout, List<EditText>> {
    val fields = hints.map { hint -> privatePin(this).apply { setHint(hint) } }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 16, 48, 0)
        fields.forEach { addView(it, LinearLayout.LayoutParams(-1, -2)) }
    }
    return box to fields
}

/** Asks for the current PIN, checks it, then runs [then]. */
fun AppCompatActivity.askPin(title: Int, then: () -> Unit) {
    val (box, f) = fields(R.string.lock_pin)
    MaterialAlertDialogBuilder(this)
        .setTitle(title)
        .setView(box)
        .setNegativeButton(R.string.cancel, null)
        .setPositiveButton(R.string.ok) { _, _ -> checkPin(f[0].takePin(), then) }
        .show()
}

/** Asks for a new PIN twice and hands over one that passes [PinPolicy]. */
fun AppCompatActivity.askNewPin(then: (CharArray) -> Unit) {
    val (box, f) = fields(R.string.lock_new_pin, R.string.lock_new_pin_again)
    val dialog = MaterialAlertDialogBuilder(this)
        .setTitle(R.string.lock_choose_pin)
        .setMessage(R.string.lock_choose_pin_body)
        .setView(box)
        .setNegativeButton(R.string.cancel, null)
        .setPositiveButton(R.string.ok, null)
        .show()
    dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
        val pin = f[0].takePin()
        val again = f[1].takePin()
        val error = when {
            !pin.contentEquals(again) -> R.string.lock_mismatch
            else -> when (PinPolicy.validate(pin)) {
                PinPolicy.Result.VALID -> null
                PinPolicy.Result.TOO_SHORT, PinPolicy.Result.TOO_LONG, PinPolicy.Result.NOT_DIGITS -> R.string.lock_rules
                PinPolicy.Result.TOO_COMMON -> R.string.lock_too_common
            }
        }
        again.fill('0')
        if (error != null) {
            pin.fill('0')
            f[0].error = getString(error)
            return@setOnClickListener
        }
        dialog.dismiss()
        then(pin)
    }
}

/** Turns the lock on with a new PIN: the PBKDF2 runs off the main thread. */
fun AppCompatActivity.turnLockOn(done: () -> Unit) {
    askNewPin { pin ->
        val entered = Lock.app.entered()
        lifecycleScope.launch {
            val unlocked = withContext(Dispatchers.Default) { Lock.app.setPin(pin, entered).also { pin.fill('0') } }
            // Set while the app locked or left: the PIN stands, and this screen must ask for it.
            if (!unlocked) (this@turnLockOn as? SecureActivity)?.gate()
            done()
        }
    }
}
