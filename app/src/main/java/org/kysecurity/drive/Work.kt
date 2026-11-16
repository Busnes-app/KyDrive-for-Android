package org.kysecurity.drive

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kysecurity.drive.core.ApiError
import org.kysecurity.drive.core.TransferError
import javax.net.ssl.SSLException

/**
 * Uploads and downloads outlive the screen that started them: they run here, for as long as
 * the process does, and report with a toast. [changed] reloads the visible folder.
 */
object Work {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Set by the screen showing a folder while it is resumed; called on the main thread. */
    var changed: (() -> Unit)? = null

    fun run(context: Context, session: Session, done: String, block: () -> Unit) {
        val app = context.applicationContext
        scope.launch {
            val text = runCatching(block).fold({ done }, { failure(app, session, it) })
            withContext(Dispatchers.Main) {
                Toast.makeText(app, text, Toast.LENGTH_LONG).show()
                changed?.invoke()
            }
        }
    }
}

/**
 * What to tell the user about [e]. A revoked phone is wiped here, whichever screen or job met
 * the answer first.
 */
fun failure(context: Context, session: Session, e: Throwable): String = when {
    e is ApiError && e.revoked -> {
        SessionStore(context).wipe()
        context.getString(R.string.revoked)
    }
    e is ApiError && e.status == 401 -> context.getString(R.string.error_signed_out)
    e is ApiError && e.status == 403 -> context.getString(R.string.error_denied)
    e is ApiError && e.status == 404 -> context.getString(R.string.error_gone)
    e is ApiError && e.status == 409 -> context.getString(R.string.error_conflict)
    e is ApiError && e.status == 413 -> context.getString(R.string.error_quota)
    e is ApiError -> context.getString(R.string.error_server, e.status)
    e is SSLException -> context.getString(R.string.error_certificate, session.host)
    e is TransferError -> context.getString(R.string.error_transfer)
    else -> context.getString(R.string.error_unreachable, session.host)
}

/**
 * The only way this app makes a text field: the keyboard is told not to learn or suggest
 * from file names (SourceRulesTest keeps it the only one).
 */
fun privateText(context: Context, value: String): EditText = EditText(context).apply {
    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_DONE
    isSingleLine = true
    setText(value)
}

/** A PIN field: digits, hidden, and kept out of the keyboard's learning like [privateText]. */
fun privatePin(context: Context): EditText = EditText(context).apply {
    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_DONE
    isSingleLine = true
    filters = arrayOf(android.text.InputFilter.LengthFilter(org.kysecurity.drive.lock.PinPolicy.MAX_LENGTH))
}

/** The field's PIN as characters; the field is cleared so the text does not linger in it. */
fun EditText.takePin(): CharArray {
    val e = text
    val pin = CharArray(e.length) { e[it] }
    e.clear()
    return pin
}
