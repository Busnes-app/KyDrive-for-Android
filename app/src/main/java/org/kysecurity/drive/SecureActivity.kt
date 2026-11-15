package org.kysecurity.drive

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

/**
 * Base for every activity (SourceRulesTest enforces it). FLAG_SECURE keeps file names and
 * previews out of screenshots and recents; only a debug build with -PallowScreenshots=true
 * skips it. Overlays are hidden and obscured touches dropped against tapjacking.
 */
abstract class SecureActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        if (!BuildConfig.ALLOW_SCREENSHOTS) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        window.setHideOverlayWindows(true)
        super.onCreate(savedInstanceState)
        window.decorView.filterTouchesWhenObscured = true
    }
}
