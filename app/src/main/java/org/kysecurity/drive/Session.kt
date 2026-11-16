package org.kysecurity.drive

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import org.json.JSONObject
import org.kysecurity.drive.core.Api
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A linked phone: the server, the device token and the account it belongs to. */
data class Session(val origin: String, val token: String, val userId: String, val user: String, val device: String) {
    val host: String get() = origin.removePrefix("https://")
    fun api() = Api(origin, token)
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

    fun load(): Session? {
        if (marker.exists()) wipe()
        if (!file.exists()) return null
        // An unreadable session (key lost, file damaged) cannot be used; the phone holds no
        // unsaved work, so it is wiped and the user links again.
        return runCatching {
            val key = keyStore().getKey(ALIAS, null) as SecretKey
            val bytes = file.readBytes()
            check(bytes[0] == VERSION)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 1, IV))
            val json = JSONObject(cipher.doFinal(bytes, 1 + IV, bytes.size - 1 - IV).toString(Charsets.UTF_8))
            Session(json.getString("origin"), json.getString("token"), json.getString("user_id"), json.getString("user"), json.getString("device"))
        }.getOrElse { wipe(); null }
    }

    fun save(s: Session) {
        val json = JSONObject().put("origin", s.origin).put("token", s.token).put("user_id", s.userId).put("user", s.user).put("device", s.device)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = byteArrayOf(VERSION) + cipher.iv + cipher.doFinal(json.toString().toByteArray())
        val tmp = File(dir, "session.tmp")
        tmp.writeBytes(sealed)
        check(tmp.renameTo(file)) { "could not store the session" }
    }

    /** Crypto-shreds everything this app holds. Idempotent; safe to call when unlinked. */
    fun wipe() {
        marker.createNewFile()
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
        const val ALIAS = "session"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
        const val IV = 12
    }
}
