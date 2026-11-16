package org.kysecurity.drive

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.snackbar.Snackbar
import com.journeyapps.barcodescanner.ScanContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kysecurity.drive.core.Api
import org.kysecurity.drive.core.ApiError
import org.kysecurity.drive.core.LinkUri
import java.io.IOException

class MainActivity : SecureActivity() {
    private lateinit var store: SessionStore
    private var session: Session? = null
    private var signedOut = false
    private var busy = false

    // Registered before STARTED; a null result is a cancelled scan.
    private val scanner = registerForActivityResult(ScanContract()) { result -> result.contents?.let(::scanned) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = SessionStore(this)
        findViewById<View>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.scan).setOnClickListener { scanner.launch(SecureCaptureActivity.options()) }
        val nav = findViewById<NavigationBarView>(R.id.nav)
        nav.setOnItemSelectedListener { show(it.itemId); true }
    }

    override fun onResume() {
        super.onResume()
        placeRail()
        if (busy) return
        session = store.load()
        render()
        session?.let(::verify)
    }

    /** Confirms the token still works: a revoked phone wipes, a plain 401 is signed out. */
    private fun verify(s: Session) {
        lifecycleScope.launch {
            val failure = withContext(Dispatchers.IO) { runCatching { s.api().account() }.exceptionOrNull() }
            if (session != s) return@launch
            when {
                failure is ApiError && failure.revoked -> revoked()
                failure is ApiError && failure.status == 401 -> { signedOut = true; render() }
                failure == null -> { signedOut = false; render() }
            }
        }
    }

    private fun revoked() {
        store.wipe()
        session = null
        signedOut = false
        render()
        snack(getString(R.string.revoked))
    }

    private fun scanned(text: String) {
        val link = LinkUri.parse(text) ?: return snack(getString(R.string.link_not_a_code))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.link_confirm_title)
            .setMessage(getString(R.string.link_confirm_body, link.host))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.link_confirm) { _, _ -> link(link) }
            .show()
    }

    private fun link(link: LinkUri) {
        val existing = session
        busy = true
        render()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val linked = Api(link.origin).redeem(link.code, deviceName(this@MainActivity))
                    val account = Api(link.origin, linked.token).account()
                    if (account.userId != linked.userId) throw IOException("token answered for another account")
                    val next = Session(link.origin, linked.token, linked.userId, linked.user, deviceName(this@MainActivity))
                    // A signed-out phone keeps its cache only for the same account on the same server.
                    if (existing != null && (existing.origin != next.origin || existing.userId != next.userId)) {
                        runCatching { next.api().unlink() }
                        return@runCatching null
                    }
                    store.save(next)
                    next
                }
            }
            busy = false
            result.onSuccess { next ->
                if (next == null) return@onSuccess snack(getString(R.string.link_other_account))
                session = next
                signedOut = false
                snack(getString(R.string.link_done, next.user))
            }.onFailure { snack(failureText(it, link.host)) }
            render()
        }
    }

    private fun failureText(e: Throwable, host: String): String = when {
        e is ApiError && e.status == 400 -> getString(R.string.link_expired)
        e is ApiError -> getString(R.string.link_refused, e.status)
        e is javax.net.ssl.SSLException -> getString(R.string.error_certificate, host)
        else -> getString(R.string.error_unreachable, host)
    }

    private fun render() {
        val s = session
        val linked = s != null && !signedOut
        findViewById<View>(R.id.link).visibility = if (linked) View.GONE else View.VISIBLE
        findViewById<View>(R.id.empty).visibility = if (linked) View.VISIBLE else View.GONE
        findViewById<View>(R.id.nav).visibility = if (linked) View.VISIBLE else View.GONE
        findViewById<View>(R.id.scan).visibility = if (busy) View.GONE else View.VISIBLE
        findViewById<View>(R.id.linking).visibility = if (busy) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.link_title).setText(if (signedOut) R.string.link_signed_out_title else R.string.link_title)
        findViewById<TextView>(R.id.link_body).text =
            if (signedOut && s != null) getString(R.string.link_signed_out_body, s.host) else getString(R.string.link_body)
        show(findViewById<NavigationBarView>(R.id.nav).selectedItemId)
    }

    private fun show(item: Int) {
        val files = item == R.id.nav_files
        findViewById<TextView>(R.id.title).setText(if (files) R.string.nav_files else R.string.nav_shared)
        val s = session ?: return
        findViewById<TextView>(R.id.empty).text =
            if (files) getString(R.string.empty_files, s.host, s.user) else getString(R.string.empty_shared)
    }

    private fun placeRail() {
        // Only the wide layout has a shell; the side may have changed in Settings.
        val shell = findViewById<LinearLayout>(R.id.shell) ?: return
        val rail = findViewById<View>(R.id.nav)
        val index = if (Settings(this).railSide == RailSide.LEFT) 0 else shell.childCount - 1
        if (shell.indexOfChild(rail) != index) {
            shell.removeView(rail)
            shell.addView(rail, index)
        }
    }

    private fun snack(text: String) {
        Snackbar.make(findViewById(R.id.title), text, Snackbar.LENGTH_LONG).show()
    }
}
