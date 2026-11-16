package org.kysecurity.drive

import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.kysecurity.drive.lock.AppLock
import org.kysecurity.drive.lock.Lock
import org.kysecurity.drive.lock.LockState
import org.kysecurity.drive.lock.Pepper
import org.kysecurity.drive.lock.PinRecord
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LockRecreationTest {
    @Test
    fun staleSetupGatesTheReplacementAfterItsOriginalScopeIsCancelled() {
        val app = ApplicationProvider.getApplicationContext<KyDriveApp>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deriving = CountDownLatch(1)
        val release = CountDownLatch(1)
        val state = object : LockState {
            override var pin: PinRecord? = null
            override var failedAttempts = 0
            override var lockoutUntil = 0L
            override var lockoutDuration = 0L
            override var graceMillis = 0L
            override var wipeAfter: Int? = null
            override var biometric = false
        }
        val fake = AppLock(state, Pepper { raw ->
            deriving.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            raw
        }, { 1000L }, {}, app::enforceLock)
        // Test-only injection; the production singleton remains write-protected.
        val field = Lock::class.java.getDeclaredField("app").apply { isAccessible = true }
        val previous = Lock.app
        field.set(null, fake)
        val monitor = instrumentation.addMonitor(UnlockActivity::class.java.name, null, false)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val entered = fake.entered()
                activity.lifecycleScope.launch {
                    withContext(Dispatchers.Default) { fake.setPin("59372648".toCharArray(), entered) }
                    activity.gate() // deliberately cancelled when the original activity is destroyed
                }
            }
            assertTrue(deriving.await(5, TimeUnit.SECONDS))
            fake.backgrounded()
            fake.foregrounded()
            scenario.recreate()
            lateinit var replacement: MainActivity
            scenario.onActivity {
                replacement = it
                assertEquals(View.VISIBLE, it.window.decorView.visibility)
            }
            release.countDown()
            val unlock = instrumentation.waitForMonitorWithTimeout(monitor, 5000)
            assertNotNull("setup must present unlock after recreating", unlock)
            assertTrue(fake.isLocked())
            instrumentation.runOnMainSync {
                assertEquals(View.INVISIBLE, replacement.window.decorView.visibility)
                fake.disable()
                unlock?.finish()
            }
        } finally {
            release.countDown()
            instrumentation.runOnMainSync { fake.disable() }
            scenario.close()
            instrumentation.removeMonitor(monitor)
            field.set(null, previous)
        }
    }
}
