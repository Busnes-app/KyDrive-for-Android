package org.kysecurity.drive.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** A software key standing in for the Keystore one. */
class SoftwareKey : DeviceKey {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val public: PublicKey get() = pair.public
    override val spki: ByteArray get() = pair.public.encoded
    override fun sign(message: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(pair.private)
        update(message)
        sign()
    }
}

fun verifies(key: PublicKey, message: String, sig: ByteArray): Boolean = Signature.getInstance("SHA256withECDSA").run {
    initVerify(key)
    update(message.toByteArray())
    verify(sig)
}

class SigningTest {
    // KyDrive-server internal/api/testdata/device-signing-v1.json, byte for byte.
    private val v = JSONObject(javaClass.getResource("/device-signing-v1.json")!!.readText())

    @Test
    fun theGoldenVectorBuildsAndVerifies() {
        assertEquals(v.getString("origin"), RequestSigning.canonicalOrigin(v.getString("app_url").trimEnd('/')))
        val headers = v.getJSONObject("headers").let { h -> h.keys().asSequence().associateWith { h.getString(it) } }
        val headerDigest = RequestSigning.headerDigest(headers)
        assertEquals(RequestSigning.sha256Hex(v.getString("signed_headers").toByteArray()), headerDigest)
        val msg = RequestSigning.message(
            v.getString("origin"), v.getString("method"), v.getString("request_uri"), v.getString("date"), v.getString("nonce"),
            headerDigest, RequestSigning.sha256Hex(v.getString("body").toByteArray()),
        )
        assertEquals(v.getString("message"), msg)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(v.getString("public_key_spki_b64"))))
        val sig = Base64.getDecoder().decode(v.getString("signature_b64"))
        assertTrue(verifies(key, msg, sig))
        assertFalse(verifies(key, msg.replace("PUT", "GET"), sig))
    }

    @Test
    fun theOriginMatchesTheServersCanonicalForm() {
        assertEquals("https://drive.example", RequestSigning.canonicalOrigin("https://Drive.Example:443"))
        assertEquals("https://drive.example:8443", RequestSigning.canonicalOrigin("https://drive.example:8443"))
    }

    @Test
    fun anAmbiguousFieldIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { RequestSigning.message("https://a", "GET", "/x|y", "1", "n", "h", "b") }
        assertThrows(IllegalArgumentException::class.java) { RequestSigning.message("https://a", "GET", "/x\n", "1", "n", "h", "b") }
    }

    @Test
    fun headersSignTheBodySliceSent() {
        val key = SoftwareKey()
        val buf = "xxhello worldyy".toByteArray()
        val h = RequestSigning.headers(key, "https://drive.example", "PUT", "/p", mapOf("Content-Range" to "bytes 0-10/11"), buf, 2, 11, now = 5)
        assertEquals("5", h["KyDrive-Date"])
        assertEquals(RequestSigning.sha256Hex("hello world".toByteArray()), h["KyDrive-Content-SHA256"])
        assertEquals(22, h.getValue("KyDrive-Nonce").length)
        val msg = RequestSigning.message("https://drive.example", "PUT", "/p", "5", h.getValue("KyDrive-Nonce"), RequestSigning.headerDigest(mapOf("content-range" to "bytes 0-10/11")), h.getValue("KyDrive-Content-SHA256"))
        assertTrue(verifies(key.public, msg, Base64.getDecoder().decode(h["KyDrive-Signature"])))
    }
}
