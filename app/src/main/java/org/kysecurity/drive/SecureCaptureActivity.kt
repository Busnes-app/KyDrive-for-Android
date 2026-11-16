package org.kysecurity.drive

import android.os.Bundle
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.ScanOptions

/**
 * zxing's scanner. It cannot extend [SecureActivity], so it secures its own window: the
 * preview shows a live link code.
 */
class SecureCaptureActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        secureWindow(window)
        super.onCreate(savedInstanceState)
        window.decorView.filterTouchesWhenObscured = true
    }

    companion object {
        fun options(): ScanOptions = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setBeepEnabled(false)
            .setOrientationLocked(false)
            .setPrompt("")
            .setCaptureActivity(SecureCaptureActivity::class.java)
    }
}
