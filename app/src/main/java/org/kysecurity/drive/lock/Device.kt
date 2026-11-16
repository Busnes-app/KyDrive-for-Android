package org.kysecurity.drive.lock

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import org.kysecurity.drive.OpenProvider
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/** The one lock for the process, made in KyDriveApp.onCreate. */
object Lock {
    lateinit var app: AppLock
        private set

    fun init(context: Context, wipe: () -> Unit) {
        // Locking ends every file already handed to another app, as a wipe does.
        app = AppLock(PrefsLockState(context), KeystorePepper, SystemClock::elapsedRealtime, wipe) { OpenProvider.forget() }
    }

    /** Wiping removes the lock with everything else: there is nothing left behind it. */
    fun forget(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        app.lockNow()
    }

    internal const val PREFS = "lock"
}

private class PrefsLockState(context: Context) : LockState {
    private val prefs = context.getSharedPreferences(Lock.PREFS, Context.MODE_PRIVATE)
    private val b64 = Base64.getEncoder()
    private val unb64 = Base64.getDecoder()

    // commit, not apply: an attempt counter that a crash can roll back is a free retry.
    override var pin: PinRecord?
        get() {
            val salt = prefs.getString("salt", null) ?: return null
            val hash = prefs.getString("hash", null) ?: return null
            return PinRecord(unb64.decode(salt), unb64.decode(hash))
        }
        set(v) {
            prefs.edit().apply {
                if (v == null) remove("salt").remove("hash") else putString("salt", b64.encodeToString(v.salt)).putString("hash", b64.encodeToString(v.hash))
            }.commit()
        }
    override var failedAttempts: Int
        get() = prefs.getInt("failed", 0)
        set(v) { prefs.edit().putInt("failed", v).commit() }
    override var lockoutUntil: Long
        get() = prefs.getLong("lockout_until", 0)
        set(v) { prefs.edit().putLong("lockout_until", v).commit() }
    override var lockoutDuration: Long
        get() = prefs.getLong("lockout_duration", 0)
        set(v) { prefs.edit().putLong("lockout_duration", v).commit() }
    override var graceMillis: Long
        get() = prefs.getLong("grace", AppLock.DEFAULT_GRACE)
        set(v) { prefs.edit().putLong("grace", v).commit() }
    override var wipeAfter: Int?
        get() = prefs.getInt("wipe_after", LockoutPolicy.DEFAULT_WIPE).takeIf { it > 0 }
        set(v) { prefs.edit().putInt("wipe_after", v ?: 0).commit() }
    override var biometric: Boolean
        get() = prefs.getBoolean("biometric", false)
        set(v) { prefs.edit().putBoolean("biometric", v).commit() }
}

/**
 * HMAC under a non-exportable Keystore key: the stored verifier cannot be brute-forced off the
 * phone. Minted with the first PIN; a missing key makes [mix] throw, which [AppLock] treats as
 * an unreadable verifier, not a wrong PIN.
 */
private object KeystorePepper : Pepper {
    private const val ALIAS = "pin-pepper"

    override fun mix(raw: ByteArray): ByteArray {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = ks.getKey(ALIAS, null) as SecretKey? ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
            .apply { init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN).build()) }
            .generateKey()
        return Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(raw)
    }
}

/**
 * Unlock with a strong biometric. The prompt unlocks a Keystore key that a newly enrolled
 * fingerprint or face invalidates, so adding one switches biometric unlock off until the PIN
 * turns it back on.
 */
object Biometric {
    private const val ALIAS = "biometric"

    fun available(context: Context): Boolean =
        context.getSystemService(android.hardware.biometrics.BiometricManager::class.java)
            .canAuthenticate(BIOMETRIC_STRONG) == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS

    /** Called after the PIN was checked: makes a fresh key for the current enrolment. */
    fun enrol() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.deleteEntry(ALIAS)
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                    .setInvalidatedByBiometricEnrollment(true)
                    .build(),
            )
        }.generateKey()
    }

    fun remove() {
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }

    /**
     * Shows the prompt. [done] gets true only when the key the prompt unlocked actually
     * encrypted; a changed enrolment turns biometric unlock off and gets false.
     */
    fun prompt(activity: Activity, title: String, usePin: String, done: (Boolean) -> Unit) {
        val cipher = try {
            val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(ALIAS, null) as SecretKey
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        } catch (_: KeyPermanentlyInvalidatedException) {
            // A fingerprint or face was added since: off until the PIN turns it on again.
            Lock.app.biometric = false
            remove()
            return done(false)
        } catch (_: Exception) {
            return done(false)
        }
        val executor = activity.mainExecutor
        BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setNegativeButton(usePin, executor) { _, _ -> done(false) }
            .build()
            .authenticate(
                BiometricPrompt.CryptoObject(cipher),
                CancellationSignal(),
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        done(runCatching { result.cryptoObject.cipher!!.doFinal(byteArrayOf(1)) }.isSuccess)
                    }

                    override fun onAuthenticationError(code: Int, message: CharSequence) = done(false)
                },
            )
    }
}
