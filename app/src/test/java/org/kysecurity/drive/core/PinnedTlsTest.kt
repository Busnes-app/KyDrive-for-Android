package org.kysecurity.drive.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Collections
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * [Api] against a local HTTPS server whose CA only this test trusts. Leaves [A] and [B] are both
 * valid under that CA, as an interceptor's leaf is valid under a CA the phone trusts.
 */
class PinnedTlsTest {
    private val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val origin = "https://localhost:${server.address.port}"
    private val seen: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** The leaf the server presents on its next handshake. */
    @Volatile private var serving = "a"

    /** Runs when the server answers `GET /api/devices/tls-pin`. */
    @Volatile private var onStatement: () -> Unit = {}

    /** What `/api/drive/device` reports as `identity_key`; null omits it. */
    private var identityKey: String? = TlsPinTest.KEY

    /** The signed statement `/api/devices/tls-pin` serves; null is a 404. */
    private var statement: (() -> JSONObject)? = null

    init {
        server.httpsConfigurator = HttpsConfigurator(serverContext())
        server.createContext("/") { ex: HttpExchange ->
            val signing = ex.requestHeaders.keys.filter { it.lowercase().startsWith("kydrive-") }
            seen += "${ex.requestMethod} ${ex.requestURI.path} auth=${ex.requestHeaders.getFirst("Authorization")} signed=${signing.isNotEmpty()}"
            ex.requestBody.readBytes()
            val body = when (ex.requestURI.path) {
                "/api/drive/device" -> JSONObject().put("user_id", "u1").put("user", "ana").apply { identityKey?.let { put("identity_key", it) } }
                "/api/devices/tls-pin" -> statement?.invoke().also { onStatement() }
                else -> null
            }
            val bytes = (body ?: JSONObject().put("error", "not found")).toString().toByteArray()
            ex.responseHeaders.add("Connection", "close")
            ex.sendResponseHeaders(if (body == null) 404 else 200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun api(pins: PinStore) = Api(origin, "kyd_secret", SoftwareKey(), pins, trust)

    private fun now() = System.currentTimeMillis() / 1000

    /** The statement the real server would sign for [leaf], right now. */
    private fun signs(leaf: String) {
        statement = { TlsPinTest.statement(host = "localhost", pin = leaf, at = now()) }
    }

    @Test
    fun aWrongLeafIsRejectedDuringTheHandshakeAndNothingReachesTheServer() {
        serving = "b"
        val pins = MemoryPins(TlsPin(PIN_A))
        val e = assertThrows(IOException::class.java) { api(pins).account() }
        assertTrue(e.toString(), e.isPinMismatch())
        // No identity key: no rollover fetch either, and the pin stands.
        assertEquals(emptyList<String>(), seen.toList())
        assertEquals(TlsPin(PIN_A), pins.current())
    }

    @Test
    fun thePinnedLeafIsServed() {
        val pins = MemoryPins(TlsPin(PIN_A, TlsPinTest.KEY))
        assertEquals(Account("u1", "ana"), api(pins).account())
        assertEquals(listOf("GET /api/drive/device auth=Bearer kyd_secret signed=true"), seen.toList())
    }

    @Test
    fun noPinYetPinsTheFirstLeafAndLearnsTheIdentityKey() {
        val pins = MemoryPins()
        val before = now()
        api(pins).account()
        val pinned = pins.current()!!
        assertEquals(TlsPin(PIN_A, TlsPinTest.KEY, pinned.statementIssuedAt), pinned)
        // The first pin carries a freshness floor: the time it was set.
        assertTrue(pinned.statementIssuedAt in before..now())
        // Pinned now: the other leaf is refused.
        serving = "b"
        identityKey = null
        assertTrue(assertThrows(IOException::class.java) { api(MemoryPins(TlsPin(PIN_A))).account() }.isPinMismatch())
    }

    @Test
    fun anAnswerWithoutAnIdentityKeyKeepsTheStoredOne() {
        identityKey = null
        val pins = MemoryPins(TlsPin(PIN_A, TlsPinTest.KEY, 7))
        api(pins).account()
        assertEquals(TlsPin(PIN_A, TlsPinTest.KEY, 7), pins.current())
    }

    @Test
    fun aStoredIdentityKeyIsNeverReplaced() {
        identityKey = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 3 })
        val pins = MemoryPins(TlsPin(PIN_A, TlsPinTest.KEY, 7))
        api(pins).account()
        assertEquals(TlsPin(PIN_A, TlsPinTest.KEY, 7), pins.current())
    }

    @Test
    fun aStatementOlderThanTheFirstPinCannotRollBack() {
        // Linked over leaf B, which the server serves now.
        serving = "b"
        val pins = MemoryPins()
        val linked = now()
        api(pins).account()
        assertEquals(PIN_B, pins.current()!!.leaf)
        seen.clear()
        // An interceptor holds retired leaf A and the statement the server signed for it an hour
        // before linking: inside the 24 h window, so only the floor refuses it.
        serving = "a"
        statement = { TlsPinTest.statement(host = "localhost", pin = PIN_A, at = linked - 3600) }
        assertTrue(assertThrows(IOException::class.java) { api(pins).account() }.isPinMismatch())
        assertEquals(PIN_B, pins.current()!!.leaf)
        // Only the credential-free statement fetch reached leaf A: no authenticated retry.
        assertEquals(listOf("GET /api/devices/tls-pin auth=null signed=false"), seen.toList())
    }

    @Test
    fun aSignedRenewalRepinsAndRetriesOnce() {
        serving = "b"
        signs(PIN_B)
        val pins = MemoryPins(TlsPin(PIN_A, TlsPinTest.KEY, now() - 100))
        assertEquals(Account("u1", "ana"), api(pins).account())
        val pin = pins.current()!!
        assertEquals(PIN_B, pin.leaf)
        assertEquals(TlsPinTest.KEY, pin.identityKey)
        assertTrue(pin.statementIssuedAt > now() - 100)
        // The fetch carries no credential; only the retry does.
        assertEquals(
            listOf("GET /api/devices/tls-pin auth=null signed=false", "GET /api/drive/device auth=Bearer kyd_secret signed=true"),
            seen.toList(),
        )
    }

    @Test
    fun aRelayedStatementForTheOldLeafDoesNotRepin() {
        serving = "b"
        signs(PIN_A)
        val pins = MemoryPins(TlsPin(PIN_A, TlsPinTest.KEY, now() - 100))
        assertTrue(assertThrows(IOException::class.java) { api(pins).account() }.isPinMismatch())
        assertEquals(PIN_A, pins.current()!!.leaf)
        assertEquals(listOf("GET /api/devices/tls-pin auth=null signed=false"), seen.toList())
    }

    @Test
    fun anUnlinkDuringTheFetchIsNotUndone() {
        serving = "b"
        signs(PIN_B)
        val pins = MemoryPins(TlsPin(PIN_A, TlsPinTest.KEY, now() - 100))
        var unlinked = false
        val store = object : PinStore {
            override fun current() = if (unlinked) throw IOException("the session has ended") else pins.current()
            override fun replace(expected: TlsPin?, new: TlsPin) = !unlinked && pins.replace(expected, new)
        }
        onStatement = { unlinked = true }
        assertTrue(assertThrows(IOException::class.java) { api(store).account() }.isPinMismatch())
        assertThrows(IOException::class.java) { store.current() }
        assertEquals(PIN_A, pins.current()!!.leaf)
        assertEquals(listOf("GET /api/devices/tls-pin auth=null signed=false"), seen.toList())
    }

    @Test
    fun aFlappingServerRollsOverOnlyOnce() {
        serving = "b"
        signs(PIN_B)
        // Right after signing B, the server goes back to A: the retry meets A and fails.
        onStatement = { serving = "a" }
        val pins = MemoryPins(TlsPin(PIN_A, TlsPinTest.KEY, now() - 100))
        assertTrue(assertThrows(IOException::class.java) { api(pins).account() }.isPinMismatch())
        assertEquals(PIN_B, pins.current()!!.leaf)
        assertEquals(listOf("GET /api/devices/tls-pin auth=null signed=false"), seen.toList())
    }

    @Test
    fun aLinkPinIsEnforcedOnTheRedemption() {
        serving = "b"
        val e = assertThrows(IOException::class.java) { Api(origin, pins = MemoryPins(TlsPin(PIN_A)), trust = trust).redeem("c", "n") }
        assertTrue(e.isPinMismatch())
        assertEquals(emptyList<String>(), seen.toList())
    }

    private companion object {
        private var serial = 1L
        private val ca: KeyPair = ec()
        private val caCert: X509Certificate = cert("CN=Test CA", ca, ca.public, isCa = true)
        private val keyA = ec()
        private val keyB = ec()
        private val leafA = cert("CN=localhost", ca, keyA.public, isCa = false)
        private val leafB = cert("CN=localhost", ca, keyB.public, isCa = false)
        val PIN_A = TlsPins.of(leafA)
        val PIN_B = TlsPins.of(leafB)

        /** The platform check, trusting only the test CA. */
        val trust: X509ExtendedTrustManager = TrustManagerFactory.getInstance("PKIX").apply {
            init(KeyStore.getInstance("PKCS12").apply { load(null); setCertificateEntry("ca", caCert) })
        }.trustManagers.filterIsInstance<X509ExtendedTrustManager>().single()

        fun ec(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

        fun cert(subject: String, issuer: KeyPair, key: java.security.PublicKey, isCa: Boolean): X509Certificate {
            val now = System.currentTimeMillis()
            val b = JcaX509v3CertificateBuilder(X500Name("CN=Test CA"), BigInteger.valueOf(serial++), Date(now - 3_600_000), Date(now + 86_400_000), X500Name(subject), key)
            if (isCa) {
                b.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
            } else {
                b.addExtension(Extension.subjectAlternativeName, false, GeneralNames(arrayOf(GeneralName(GeneralName.dNSName, "localhost"), GeneralName(GeneralName.iPAddress, "127.0.0.1"))))
            }
            return JcaX509CertificateConverter().getCertificate(b.build(JcaContentSignerBuilder("SHA256withECDSA").build(issuer.private)))
        }
    }

    /** Presents whichever leaf [serving] names. */
    private fun serverContext(): SSLContext {
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null)
            setKeyEntry("a", keyA.private, PASS, arrayOf(leafA, caCert))
            setKeyEntry("b", keyB.private, PASS, arrayOf(leafB, caCert))
        }
        val base = KeyManagerFactory.getInstance("SunX509").apply { init(store, PASS) }.keyManagers.single() as X509ExtendedKeyManager
        val switching = object : X509ExtendedKeyManager() {
            override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = serving.takeIf { keyType == "EC" }
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = serving.takeIf { keyType == "EC" }
            override fun getCertificateChain(alias: String?): Array<X509Certificate>? = base.getCertificateChain(alias)
            override fun getPrivateKey(alias: String?): PrivateKey? = base.getPrivateKey(alias)
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf("a", "b")
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        }
        return SSLContext.getInstance("TLS").apply { init(arrayOf(switching), null, null) }
    }
}

private val PASS = "test".toCharArray()
