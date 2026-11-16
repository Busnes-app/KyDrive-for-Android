package org.kysecurity.drive.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AppLockTest {
    private class Memory : LockState {
        override var pin: PinRecord? = null
        override var failedAttempts = 0
        override var lockoutUntil = 0L
        override var lockoutDuration = 0L
        override var graceMillis = 0L
        override var wipeAfter: Int? = null
        override var biometric = false
    }

    private val state = Memory()
    private var now = 1_000_000L
    private var wiped = 0
    private var pepperGone = false
    private val pepper = Pepper { raw -> if (pepperGone) error("key gone") else raw.map { (it.toInt() xor 0x5a).toByte() }.toByteArray() }
    private var locks = 0
    private val lock = AppLock(state, pepper, { now }, { wiped++; state.pin = null }) { locks++ }
    private val good = "73915062".toCharArray()
    private val bad = "73915063".toCharArray()

    @Test
    fun offUntilAPinIsSet() {
        assertFalse(lock.enabled)
        assertFalse(lock.isLocked())
        lock.setPin(good)
        assertTrue(lock.enabled)
        assertFalse(lock.isLocked()) // the user who just set it is in
        assertEquals(LockoutPolicy.DEFAULT_WIPE, state.wipeAfter)
        assertEquals(AppLock.DEFAULT_GRACE, state.graceMillis)
    }

    @Test
    fun aNewProcessStartsLocked() {
        lock.setPin(good)
        val restarted = AppLock(state, pepper, { now }, {})
        assertTrue(restarted.isLocked())
        assertEquals(Attempt.Success, restarted.attempt(good))
        assertFalse(restarted.isLocked())
    }

    @Test
    fun theGracePeriodLocksOnlyOnceItEnds() {
        lock.setPin(good)
        lock.backgrounded()
        now += AppLock.DEFAULT_GRACE - 1
        lock.foregrounded()
        assertFalse(lock.isLocked())
        lock.backgrounded()
        now += AppLock.DEFAULT_GRACE
        assertTrue(lock.isLocked()) // expired, even before foregrounded() runs
    }

    @Test
    fun lockingIsAnnouncedOncePerUnlock() {
        lock.setPin(good)
        lock.backgrounded()
        now += AppLock.DEFAULT_GRACE
        assertTrue(lock.isLocked())
        assertTrue(lock.isLocked())
        lock.lockNow()
        assertEquals(1, locks) // open files were ended once, when it locked
        lock.foregrounded()
        lock.attempt(good)
        lock.lockNow()
        assertEquals(2, locks)
    }

    @Test
    fun noGraceLocksAtOnce() {
        lock.setPin(good)
        state.graceMillis = 0
        lock.backgrounded()
        assertTrue(lock.isLocked())
    }

    @Test
    fun wrongPinsClimbTheLadderAndTheRightOneResetsIt() {
        lock.setPin(good)
        lock.lockNow()
        assertEquals(Attempt.Rejected(0), lock.attempt(bad))
        assertEquals(Attempt.Rejected(0), lock.attempt(bad))
        assertEquals(Attempt.Rejected(30_000), lock.attempt(bad))
        // Locked out: even the right PIN waits, and is not tried.
        assertEquals(Attempt.Rejected(30_000), lock.attempt(good))
        now += 30_000
        assertEquals(Attempt.Success, lock.attempt(good))
        assertEquals(0, state.failedAttempts)
    }

    @Test
    fun biometricUnlockWaitsOutTheLockoutToo() {
        lock.setPin(good)
        lock.lockNow()
        repeat(3) { lock.attempt(bad) }
        assertTrue(lock.unlockWithBiometric() is Attempt.Rejected)
        assertTrue(lock.isLocked())
    }

    @Test
    fun theErasePolicyWipes() {
        lock.setPin(good)
        state.wipeAfter = 10
        lock.lockNow()
        var last: Attempt = Attempt.Success
        repeat(10) {
            now += 3_600_000 // wait out each delay
            last = lock.attempt(bad)
        }
        assertEquals(Attempt.Wiped, last)
        assertEquals(1, wiped)
        assertNull(state.pin)
    }

    @Test
    fun eraseOffNeverWipes() {
        lock.setPin(good)
        state.wipeAfter = null
        lock.lockNow()
        repeat(40) {
            now += 3_600_000
            lock.attempt(bad)
        }
        assertEquals(0, wiped)
    }

    @Test
    fun anUnreadableVerifierIsNotAWrongPin() {
        lock.setPin(good)
        lock.lockNow()
        pepperGone = true
        assertEquals(Attempt.Unavailable, lock.attempt(bad))
        assertEquals(0, state.failedAttempts)
        assertTrue(lock.isLocked())
    }

    @Test
    fun aLockoutFromBeforeARebootLastsOneDelayNotUntilUptimeCatchesUp() {
        lock.setPin(good)
        lock.lockNow()
        repeat(3) { lock.attempt(bad) }
        now = 5_000 // rebooted: the clock restarted
        assertEquals(30_000, lock.remainingLockout())
        now += 30_000
        assertEquals(0, lock.remainingLockout())
    }

    @Test
    fun aPinChangeKeepsTheUsersChoices() {
        lock.setPin(good)
        state.wipeAfter = null
        state.graceMillis = 0
        lock.setPin("80461375".toCharArray())
        assertNull(state.wipeAfter)
        assertEquals(0, state.graceMillis)
        lock.lockNow()
        assertTrue(lock.attempt(good) is Attempt.Rejected)
        assertEquals(Attempt.Success, lock.attempt("80461375".toCharArray()))
    }

    @Test
    fun pinPolicy() {
        fun v(s: String) = PinPolicy.validate(s.toCharArray())
        assertEquals(PinPolicy.Result.VALID, v("73915062"))
        assertEquals(PinPolicy.Result.TOO_SHORT, v("7391506"))
        assertEquals(PinPolicy.Result.TOO_LONG, v("7391506273915"))
        assertEquals(PinPolicy.Result.NOT_DIGITS, v("7391506a"))
        assertEquals(PinPolicy.Result.NOT_DIGITS, v("٠١٢٣٤٥٦٧")) // Arabic-Indic digits
        for (weak in listOf("12345678", "87654321", "11111111", "12121212", "20202020")) assertEquals(weak, PinPolicy.Result.TOO_COMMON, v(weak))
    }

    @Test
    fun weakeningTheEraseIsRecognised() {
        assertTrue(LockoutPolicy.weakens(10, 30))
        assertTrue(LockoutPolicy.weakens(30, null))
        assertFalse(LockoutPolicy.weakens(30, 10))
        assertFalse(LockoutPolicy.weakens(null, 30))
    }
    @Test(timeout = 30_000)
    fun authenticationFinishingAfterBackgroundingCannotUnlock() = backgroundCompletion(setup = false)

    @Test(timeout = 30_000)
    fun pinSetupFinishingAfterBackgroundingCannotUnlock() = backgroundCompletion(setup = true)

    @Test(timeout = 30_000)
    fun returningBeforeVerificationFinishesDoesNotReviveTheOldAttempt() = backgroundCompletion(setup = false, reenter = true)

    private fun backgroundCompletion(setup: Boolean, reenter: Boolean = false) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocking = AtomicBoolean(false)
        val memory = Memory()
        val app = AppLock(memory, Pepper { raw ->
            if (blocking.get()) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            raw.clone()
        }, { now }, {})
        if (!setup) app.setPin(good)
        app.lockNow()
        blocking.set(true)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit {
                if (setup) app.setPin(good) else assertEquals(Attempt.Success, app.attempt(good))
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            app.backgrounded()
            if (reenter) app.foregrounded()
            release.countDown()
            result.get(10, TimeUnit.SECONDS)
            now += AppLock.DEFAULT_GRACE + 1
            app.foregrounded()
            assertTrue("setup=$setup, reenter=$reenter", app.isLocked())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun biometricCompletionInBackgroundCannotUnlock() {
        lock.setPin(good)
        lock.lockNow()
        lock.backgrounded()
        assertEquals(Attempt.Success, lock.unlockWithBiometric())
        now += AppLock.DEFAULT_GRACE + 1
        lock.foregrounded()
        assertTrue(lock.isLocked())
    }

}
