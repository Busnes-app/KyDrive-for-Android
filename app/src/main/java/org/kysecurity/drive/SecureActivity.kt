package org.kysecurity.drive

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import org.kysecurity.drive.lock.Lock

/**
 * Base for every activity (SourceRulesTest enforces it). FLAG_SECURE keeps file names and
 * previews out of screenshots and recents; only a debug build with -PallowScreenshots=true
 * skips it. Overlays are hidden and obscured touches dropped against tapjacking. A locked app
 * shows no screen but the PIN prompt.
 */
abstract class SecureActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        secureWindow(window)
        super.onCreate(savedInstanceState)
        window.decorView.filterTouchesWhenObscured = true
    }

    /**
     * The app lock's gate, on every start: a locked app shows nothing and asks for the PIN.
     * Hidden rather than finished, so the screen is back as it was once unlocked.
     */
    override fun onStart() {
        super.onStart()
        gate()
    }

    /**
     * Hides this screen and asks for the PIN if the app is locked. Runs on every start, and
     * again wherever the app can become locked while a screen is already showing.
     */
    fun gate() {
        val locked = this !is UnlockActivity && Lock.app.isLocked()
        window.decorView.visibility = if (locked) View.INVISIBLE else View.VISIBLE
        if (locked) startActivity(Intent(this, UnlockActivity::class.java))
    }
}

/** For the one activity that cannot extend [SecureActivity]; call before super.onCreate. */
fun secureWindow(window: Window) {
    if (!BuildConfig.ALLOW_SCREENSHOTS) {
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    }
    window.setHideOverlayWindows(true)
}
