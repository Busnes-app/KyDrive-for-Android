package org.kysecurity.drive

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import org.json.JSONObject
import org.kysecurity.drive.core.Api
import org.kysecurity.drive.core.PinStore
import org.kysecurity.drive.core.TlsPin
import org.kysecurity.drive.lock.Lock
import java.io.File
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A linked phone: the server, the device token, the account it belongs to, the alias of its
 * signing key ([DeviceSigningKey]; empty for a phone linked before signing) and its TLS [pin]
 * (null for a phone linked before pinning: its next 2xx answer pins the leaf it came over).
 */
data class Session(val origin: String, val token: String, val userId: String, val user: String, val device: String, val keyAlias: String = "", val pin: TlsPin? = null) {
    val host: String get() = origin.removePrefix("https://")

    /** A key the session names but the Keystore lost signs nothing, and the server refuses the token. */
    fun api(context: Context) = Api(origin, token, keyAlias.takeIf { it.isNotEmpty() }?.let { DeviceSigningKey.load(it) }, SessionStore(context).pins(token))
}

/**
 * Holds the [Session] encrypted under a non-exportable Keystore AES-GCM key, in
 * noBackupFilesDir. The key has no user-authentication requirement: background work needs the
 * token while the app is locked, and the app lock gates the UI instead.
 *
 * [wipe] deletes the key first, which makes the token and anything else under it unreadable at
 * once, then the files. A marker written before anything else makes an interrupted wipe resume
 * on the next [load].
 */
class SessionStore(context: Context) {
    private val app = context.applicationContext
    private val dir = app.noBackupFilesDir
    private val file = File(dir, "session")
    private val marker = File(dir, "wiping")

    fun load(): Session? = synchronized(LOCK) { read() }

    private fun read(): Session? {
        if (marker.exists()) wipe()
        if (!file.exists()) return null
        cached?.let { return it }
        // An unreadable session (key lost, file damaged) cannot be used; the phone holds no
        // unsaved work, so it is wiped and the user links again.
        return runCatching {
            val key = keyStore().getKey(ALIAS, null) as SecretKey
            val bytes = file.readBytes()
            check(bytes[0] == VERSION)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 1, IV))
            val json = JSONObject(cipher.doFinal(bytes, 1 + IV, bytes.size - 1 - IV).toString(Charsets.UTF_8))
            val pin = json.optString("pin").takeIf { it.isNotEmpty() }?.let { TlsPin(it, json.optString("identity_key").ifEmpty { null }, json.optLong("pin_issued_at")) }
            Session(json.getString("origin"), json.getString("token"), json.getString("user_id"), json.getString("user"), json.getString("device"), json.optString("key"), pin)
        }.onSuccess { cached = it }.getOrElse { wipe(); null }
    }

    fun save(s: Session): Unit = synchronized(LOCK) {
        val json = JSONObject().put("origin", s.origin).put("token", s.token).put("user_id", s.userId).put("user", s.user).put("device", s.device).put("key", s.keyAlias)
        s.pin?.let { json.put("pin", it.leaf).put("identity_key", it.identityKey.orEmpty()).put("pin_issued_at", it.statementIssuedAt) }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = byteArrayOf(VERSION) + cipher.iv + cipher.doFinal(json.toString().toByteArray())
        val tmp = File(dir, "session.tmp")
        tmp.writeBytes(sealed)
        check(tmp.renameTo(file)) { "could not store the session" }
        cached = s
    }

    /**
     * The pin of the session holding [token]. A replace lands only while that session is still
     * stored with the expected pin, under the lock [wipe] takes, so an unlink is never undone.
     */
    fun pins(token: String): PinStore = object : PinStore {
        // A wiped or replaced session sends nothing: null would mean "not pinned yet" and send the token unpinned.
        override fun current() = (load()?.takeIf { it.token == token } ?: throw IOException("the session has ended")).pin

        override fun replace(expected: TlsPin?, new: TlsPin): Boolean = synchronized(LOCK) {
            val s = read()?.takeIf { it.token == token && it.pin == expected }
            s?.let { save(it.copy(pin = new)) }
            s != null
        }
    }

    /** Crypto-shreds everything this app holds. Idempotent; safe to call when unlinked. */
    fun wipe(): Unit = synchronized(LOCK) {
        cached = null
        marker.createNewFile()
        OpenProvider.forget()
        Lock.forget(app)
        val ks = keyStore()
        for (alias in ks.aliases().toList()) ks.deleteEntry(alias)
        app.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        app.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        dir.listFiles()?.filter { it != marker }?.forEach { it.deleteRecursively() }
        marker.delete()
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey {
        (keyStore().getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return try {
            generate(strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            generate(strongBox = false)
        }
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }

    private companion object {
        /** Taken by load, save, wipe and pin replacement, process-wide. */
        val LOCK = Any()

        /** The session last read or written, so a request reads its pin without a Keystore decrypt. */
        @Volatile var cached: Session? = null

        const val ALIAS = "session"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
        const val IV = 12
    }
}
