package org.kysecurity.drive

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class KyDriveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(Settings(this).themeMode.night)
    }
}
