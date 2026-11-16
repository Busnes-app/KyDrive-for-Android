package org.kysecurity.drive.lock

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** A stored PIN verifier. Compare only through [AppLock], which is constant time. */
class PinRecord(val salt: ByteArray, val hash: ByteArray)

/** What the lock keeps between launches: SharedPreferences on the phone, a map in tests. */
interface LockState {
    /** Null: the lock is off. */
    var pin: PinRecord?
    var failedAttempts: Int
    var lockoutUntil: Long
    var lockoutDuration: Long
    var graceMillis: Long
    var wipeAfter: Int?
    var biometric: Boolean
}

/** Mixes a non-exportable device secret into the KDF output; throws when that secret is gone. */
fun interface Pepper {
    fun mix(raw: ByteArray): ByteArray
}

sealed interface Attempt {
    data object Success : Attempt
    data class Rejected(val waitMillis: Long) : Attempt

    /** Too many wrong PINs: everything is already erased. */
    data object Wiped : Attempt

    /** The verifier cannot be evaluated (Keystore trouble). Not a wrong PIN, so not counted. */
    data object Unavailable : Attempt
}

/**
 * The app lock. "Unlocked" lives only in this process: every launch starts locked while a PIN
 * is set. Leaving the app starts a grace period on [clock], which must keep running in deep
 * sleep (elapsedRealtime). Every PIN check, including the ones Settings asks for, goes through
 * [attempt], so the delay ladder and the erase apply to all of them.
 */
class AppLock(
    private val state: LockState,
    private val pepper: Pepper,
    private val clock: () -> Long,
    private val wipe: () -> Unit,
    /** Runs each time the app goes from unlocked to locked, on whichever thread noticed. */
    private val onLock: () -> Unit = {},
) {
    // PIN derivation holds this object's monitor; lifecycle transitions use a separate, short lock.
    private val visibility = Any()
    private var foreground = true
    private var visibilityVersion = 0L

    @Volatile private var unlocked = false

    @Volatile private var deadline = 0L

    val enabled: Boolean get() = state.pin != null

    /** Biometric unlock. Callers turn it on only after a PIN check and a fresh enrolment key. */
    var biometric: Boolean
        get() = enabled && state.biometric
        set(v) { state.biometric = v }

    /** The grace period after leaving the app, and wrong PINs before erasing (null: never). */
    var graceMillis: Long
        get() = state.graceMillis
        set(v) { state.graceMillis = v }

    var wipeAfter: Int?
        get() = state.wipeAfter
        set(v) { state.wipeAfter = v }

    private fun updateLockState(change: () -> Unit) {
        val relocked = synchronized(visibility) {
            val was = unlocked
            change()
            was && !unlocked
        }
        // OpenProvider may take its own locks; callbacks run outside the visibility monitor.
        if (relocked) onLock()
    }

    private fun authenticationStarted(): Long = synchronized(visibility) { visibilityVersion }

    private fun authenticated(version: Long) = updateLockState {
        unlocked = foreground && version == visibilityVersion
        deadline = 0
    }

    fun isLocked(): Boolean {
        updateLockState {
            if (unlocked && deadline != 0L && clock() >= deadline) {
                unlocked = false
                deadline = 0
            }
        }
        return enabled && !unlocked
    }

    fun lockNow() = updateLockState {
        visibilityVersion++
        unlocked = false
        deadline = 0
    }

    /** Record leaving even while locked: an outstanding authentication may finish later. */
    fun backgrounded() = updateLockState {
        foreground = false
        visibilityVersion++
        if (enabled && unlocked) {
            val grace = state.graceMillis
            if (grace <= 0) {
                unlocked = false
                deadline = 0
            } else {
                deadline = clock() + grace
            }
        }
    }

    /** A screen came back: an expired grace period locks, an unexpired one is cancelled. */
    fun foregrounded() = updateLockState {
        foreground = true
        if (unlocked && deadline != 0L && clock() >= deadline) unlocked = false
        deadline = 0
    }

    @Synchronized
    fun attempt(pin: CharArray): Attempt {
        val wait = remainingLockout()
        if (wait > 0) return Attempt.Rejected(wait)
        val record = state.pin ?: return Attempt.Success
        val version = authenticationStarted()
        val ok = try {
            matches(pin, record)
        } catch (_: Exception) {
            return Attempt.Unavailable
        }
        if (ok) {
            state.failedAttempts = 0
            authenticated(version)
            return Attempt.Success
        }
        val attempts = state.failedAttempts + 1
        state.failedAttempts = attempts
        if (LockoutPolicy.shouldWipe(attempts, state.wipeAfter)) {
            wipe()
            lockNow()
            return Attempt.Wiped
        }
        val delay = LockoutPolicy.delayFor(attempts)
        if (delay > 0) {
            state.lockoutUntil = clock() + delay
            state.lockoutDuration = delay
        }
        return Attempt.Rejected(delay)
    }

    /** After a strong biometric prompt bound to a Keystore key. Under the same lockout as a PIN. */
    @Synchronized
    fun unlockWithBiometric(): Attempt {
        val wait = remainingLockout()
        if (wait > 0) return Attempt.Rejected(wait)
        state.failedAttempts = 0
        authenticated(authenticationStarted())
        return Attempt.Success
    }

    /** Sets or changes the PIN. Callers check [PinPolicy] and, for a change, the old PIN first. */
    @Synchronized
    fun setPin(pin: CharArray) {
        val version = authenticationStarted()
        val turningOn = state.pin == null
        val salt = ByteArray(16).also { random.nextBytes(it) }
        state.pin = PinRecord(salt, derive(pin, salt))
        state.failedAttempts = 0
        if (turningOn) {
            // The design's defaults each time the lock is turned on; a PIN change keeps choices.
            state.wipeAfter = LockoutPolicy.DEFAULT_WIPE
            state.graceMillis = DEFAULT_GRACE
        }
        authenticated(version)
    }

    /** Turns the lock off. Callers verify the PIN through [attempt] first. */
    @Synchronized
    fun disable() {
        state.pin = null
        state.biometric = false
        state.failedAttempts = 0
        state.lockoutUntil = 0
        state.lockoutDuration = 0
    }

    /**
     * Milliseconds until the next PIN may be tried. elapsedRealtime restarts at zero on reboot,
     * so a deadline further off than the delay ever set is from the previous boot and restarts
     * as one full delay from now, rather than lasting until the new uptime catches up.
     */
    fun remainingLockout(): Long {
        val duration = state.lockoutDuration
        if (duration <= 0) return 0
        val now = clock()
        val left = state.lockoutUntil - now
        if (left > duration) {
            state.lockoutUntil = now + duration
            return duration
        }
        return left.coerceAtLeast(0)
    }

    private fun matches(pin: CharArray, record: PinRecord): Boolean {
        val candidate = derive(pin, record.salt)
        try {
            return MessageDigest.isEqual(candidate, record.hash)
        } finally {
            candidate.fill(0)
        }
    }

    private fun derive(pin: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin, salt, ITERATIONS, 256)
        val raw = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        try {
            return pepper.mix(raw)
        } finally {
            raw.fill(0)
        }
    }

    companion object {
        private val random = SecureRandom()

        /** KyPost's CREDENTIAL_KDF_ITERATIONS; the pepper is what makes guessing on-device only. */
        const val ITERATIONS = 150_000
        const val DEFAULT_GRACE = 30_000L
        val GRACE_CHOICES = listOf(0L, 30_000L, 300_000L)
    }
}
