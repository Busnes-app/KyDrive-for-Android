package org.kysecurity.drive.core

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class TlsPinTest {
    // KyDrive-server internal/api/testdata/tls-pin-v1.json, byte for byte.
    private val v = JSONObject(javaClass.getResource("/tls-pin-v1.json")!!.readText())

    @Test
    fun theGoldenVectorBuildsAndVerifies() {
        val host = v.getString("host")
        val pin = v.getString("pin")
        val at = v.getLong("issued_at")
        assertEquals(v.getString("statement"), TlsPins.statement(host, pin, at).toString(Charsets.UTF_8))
        assertTrue(TlsPins.verify(v.getString("identity_key"), host, pin, at, v.getString("signature")))
        // Ed25519 is deterministic: the seed signs to the exact bytes, and derives the key.
        val seed = Ed25519PrivateKeyParameters(hex(v.getString("seed_hex")), 0)
        assertEquals(v.getString("identity_key"), Base64.getEncoder().encodeToString(seed.generatePublicKey().encoded))
        assertEquals(v.getString("signature"), sign(seed, host, pin, at))
    }

    @Test
    fun anyChangedFieldFailsTheVector() {
        val key = v.getString("identity_key")
        val (host, pin, at, sig) = listOf(v.getString("host"), v.getString("pin"), v.getLong("issued_at").toString(), v.getString("signature"))
        assertFalse(TlsPins.verify(key, "evil.example.com", pin, at.toLong(), sig))
        assertFalse(TlsPins.verify(key, host, OTHER, at.toLong(), sig))
        assertFalse(TlsPins.verify(key, host, pin, at.toLong() + 1, sig))
        assertFalse(TlsPins.verify(key, host, pin, at.toLong(), "not base64!"))
        assertFalse(TlsPins.verify(Base64.getEncoder().encodeToString(ByteArray(32)), host, pin, at.toLong(), sig))
    }

    @Test
    fun onlyA32ByteIdentityKeyIsAccepted() {
        assertEquals(v.getString("identity_key"), TlsPins.identityKeyOrNull(v.getString("identity_key")))
        assertNull(TlsPins.identityKeyOrNull(Base64.getEncoder().encodeToString(ByteArray(31))))
        assertNull(TlsPins.identityKeyOrNull("%%%"))
        assertNull(TlsPins.identityKeyOrNull(null))
    }

    @Test
    fun aSignedStatementForTheObservedLeafIsAccepted() {
        assertEquals(TlsPin(NEW, KEY, NOW), TlsPins.accept(STORED, HOST, NEW, statement(), NOW))
    }

    @Test
    fun aRelayedStatementForAnotherLeafIsRefused() {
        // An interceptor shows its own leaf (observed) while relaying the server's real statement.
        assertNull(TlsPins.accept(STORED, HOST, NEW, statement(pin = OTHER), NOW))
    }

    @Test
    fun aStaleStatementIsRefused() {
        assertNull(TlsPins.accept(STORED, HOST, NEW, statement(at = NOW - TlsPins.MAX_SKEW_SECONDS - 1), NOW))
        assertNull(TlsPins.accept(STORED, HOST, NEW, statement(at = NOW + TlsPins.MAX_SKEW_SECONDS + 1), NOW))
    }

    @Test
    fun aReplayedStatementIsRefused() {
        // A statement for a retired leaf is never newer than the rollover that retired it.
        assertNull(TlsPins.accept(STORED.copy(statementIssuedAt = NOW), HOST, NEW, statement(), NOW))
    }

    @Test
    fun anotherHostIsRefused() {
        assertNull(TlsPins.accept(STORED, HOST, NEW, statement(host = "other.example.com"), NOW))
        assertNull(TlsPins.accept(STORED, "other.example.com", NEW, statement(), NOW))
    }

    @Test
    fun anotherKeyIsRefused() {
        assertNull(TlsPins.accept(STORED, HOST, NEW, statement(seed = ByteArray(32) { 2 }), NOW))
    }

    @Test
    fun theLeafAlreadyPinnedIsNotARollover() {
        assertNull(TlsPins.accept(STORED, HOST, STORED.leaf, statement(pin = STORED.leaf), NOW))
    }

    @Test
    fun withoutAnIdentityKeyNothingIsAccepted() {
        assertNull(TlsPins.accept(STORED.copy(identityKey = null), HOST, NEW, statement(), NOW))
    }

    @Test
    fun aNonIntegerIssuedAtIsRefused() {
        assertNull(TlsPins.accept(STORED, HOST, NEW, statement().put("issued_at", NOW.toString()), NOW))
    }

    @Test
    fun aPinMismatchIsFoundThroughItsCauses() {
        assertTrue(javax.net.ssl.SSLHandshakeException("x").apply { initCause(PinMismatch()) }.isPinMismatch())
        assertFalse(javax.net.ssl.SSLHandshakeException("x").isPinMismatch())
    }

    companion object {
        const val HOST = "drive.example.com"
        const val NOW = 1_794_844_800L
        const val NEW = "sha256/Y7NDZ8IpLOXohWKxDNkLJh8TVXxPrhajRfRkYgJvV5U="
        const val OTHER = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        val SEED = Ed25519PrivateKeyParameters(ByteArray(32) { 1 }, 0)
        val KEY: String = Base64.getEncoder().encodeToString(SEED.generatePublicKey().encoded)
        val STORED = TlsPin("sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=", KEY, NOW - 100)

        fun sign(seed: Ed25519PrivateKeyParameters, host: String, pin: String, at: Long): String {
            val message = TlsPins.statement(host, pin, at)
            return Ed25519Signer().run {
                init(true, seed)
                update(message, 0, message.size)
                Base64.getEncoder().encodeToString(generateSignature())
            }
        }

        fun statement(host: String = HOST, pin: String = NEW, at: Long = NOW, seed: ByteArray? = null): JSONObject {
            val key = seed?.let { Ed25519PrivateKeyParameters(it, 0) } ?: SEED
            return JSONObject().put("host", host).put("pin", pin).put("issued_at", at).put("signature", sign(key, host, pin, at))
        }

        private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
