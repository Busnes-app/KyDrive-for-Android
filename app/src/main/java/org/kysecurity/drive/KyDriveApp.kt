package org.kysecurity.drive

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import org.kysecurity.drive.lock.Lock

class KyDriveApp : Application() {
    private val startedScreens = mutableSetOf<SecureActivity>()

    /** Always delivered by the process, even when the setup activity's coroutine was cancelled. */
    fun enforceLock() {
        mainExecutor.execute {
            if (Lock.app.isLocked()) OpenProvider.forget()
            startedScreens.toList().forEach { it.gate() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(Settings(this).themeMode.night)
        Lock.init(this, { SessionStore(this).wipe() }, ::enforceLock)
        // The lock's grace period runs while no KyDrive screen is started; a rotation is not leaving.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0

            override fun onActivityStarted(activity: Activity) {
                if (activity is SecureActivity) startedScreens.add(activity)
                if (started++ == 0) Lock.app.foregrounded()
            }

            override fun onActivityStopped(activity: Activity) {
                if (activity is SecureActivity) startedScreens.remove(activity)
                if (--started == 0 && !activity.isChangingConfigurations) Lock.app.backgrounded()
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
