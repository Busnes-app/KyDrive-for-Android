package org.kysecurity.drive

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.kysecurity.drive.core.DeviceKey
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/**
 * The device's request-signing key: P-256 in the Android Keystore, never exportable. One per
 * link, so linking again cannot break the session it replaces. No user-authentication
 * requirement: background transfers sign while the app is locked, and the app lock gates the
 * UI. TEE, not StrongBox: every request signs, and StrongBox takes a large fraction of a second
 * per signature.
 */
class DeviceSigningKey private constructor(val alias: String) : DeviceKey {
    override val spki: ByteArray get() = keyStore().getCertificate(alias).publicKey.encoded

    override fun sign(message: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(keyStore().getKey(alias, null) as PrivateKey)
            update(message)
            sign()
        }

    companion object {
        // Storage contract: sessions name their key by this prefix plus a random suffix.
        private const val PREFIX = "kydrive-device-signing-v1-"

        /** A fresh key for one link. */
        fun create(): DeviceSigningKey {
            val alias = PREFIX + UUID.randomUUID()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build(),
                )
            }.generateKeyPair()
            return DeviceSigningKey(alias)
        }

        /** The key a session named, or null when it is gone (the session must link again). */
        fun load(alias: String): DeviceSigningKey? = if (keyStore().containsAlias(alias)) DeviceSigningKey(alias) else null

        fun delete(alias: String) {
            runCatching { keyStore().deleteEntry(alias) }
        }

        private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    }
}
