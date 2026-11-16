package org.kysecurity.drive.core

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * The floor on a real HTTPS connection: the same [Api] call against the same server, where the
 * only thing that changes is the certificate it presents. A refused chain must fail inside the
 * handshake, so the server never sees a request line — and never sees the bearer token.
 *
 * The platform trust decision here is a trust manager holding the fixture CA; on a device it is
 * the network security config's (`DeviceTls`), which the certificate-floor device test covers.
 */
class TlsFloorTest {
    private val seen = mutableListOf<String>()
    private var server: HttpsServer? = null

    @After
    fun stop() {
        server?.stop(0)
    }

    /** A server presenting [leaf] with [issuers] below it, answering the token route. */
    private fun start(leaf: String, vararg issuers: String): Int {
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(Fixtures.identity(leaf, *issuers), Fixtures.PASSWORD) }
        val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
        val https = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        https.httpsConfigurator = HttpsConfigurator(context)
        https.createContext("/api/devices/token") { exchange ->
            seen += "${exchange.requestMethod} ${exchange.requestURI} auth=${exchange.requestHeaders.getFirst("Authorization")}"
            exchange.requestBody.readBytes()
            val body = """{"token":"kyd_abc","user_id":"u1","user":"ana"}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        https.start()
        server = https
        return https.address.port
    }

    /** The floor over a trust manager holding [anchors], the way the device does over its own. */
    private fun floor(vararg anchors: String): Tls {
        val base = Fixtures.trust(*anchors)
        return floorTls(base) { chain, authType -> base.checkServerTrusted(chain, authType); chain.toList() }
    }

    private fun redeem(port: Int, tls: Tls?, pins: PinStore = MemoryPins()) =
        Api("https://localhost:$port", pins = pins, tls = tls).redeem("C0de", "Ana's Pixel")

    private fun Throwable.blamesTheFloor(): Boolean =
        generateSequence(this as Throwable?) { it.cause }.any { it.message.orEmpty().contains("KyDrive refuses") }

    @Test
    fun aStrongCertificateIsAnswered() {
        val linked = redeem(start("strong"), floor("ca"))
        assertEquals("kyd_abc", linked.token)
        assertTrue(seen.single(), seen.single().startsWith("POST /api/devices/token"))
    }

    @Test
    fun aMatchingPinCannotAuthorizeAWeakCertificate() {
        val pins = MemoryPins(TlsPin(TlsPins.of(Fixtures.cert("rsa2047")), TlsPinTest.KEY))
        val e = assertThrows(IOException::class.java) { redeem(start("rsa2047"), floor("ca"), pins) }
        assertTrue(e.stackTraceToString(), e.blamesTheFloor())
        assertFalse(e.isPinMismatch())
        assertEquals(emptyList<String>(), seen)
    }

    @Test
    fun aStrongCertificateStillNeedsThePinnedLeaf() {
        val pins = MemoryPins(TlsPin(TlsPins.of(Fixtures.cert("rsa2048"))))
        val e = assertThrows(IOException::class.java) { redeem(start("strong"), floor("ca"), pins) }
        assertTrue(e.stackTraceToString(), e.isPinMismatch())
        assertEquals(emptyList<String>(), seen)
    }

    @Test
    fun anRsaKeyBelowTheFloorIsRefusedBeforeTheRequest() {
        val e = assertThrows(IOException::class.java) { redeem(start("rsa2047"), floor("ca")) }
        assertTrue(e.stackTraceToString(), e.blamesTheFloor())
        assertEquals(emptyList<String>(), seen)
    }

    @Test
    fun aSha1SignatureIsRefusedBeforeTheRequest() {
        val e = assertThrows(IOException::class.java) { redeem(start("sha1"), floor("ca")) }
        assertTrue(e.stackTraceToString(), e.blamesTheFloor())
        assertEquals(emptyList<String>(), seen)
    }

    @Test
    fun aWeakIntermediateIsRefusedBeforeTheRequest() {
        val e = assertThrows(IOException::class.java) { redeem(start("under-weak-int", "weak-int"), floor("ca")) }
        assertTrue(e.stackTraceToString(), e.blamesTheFloor())
        assertEquals(emptyList<String>(), seen)
    }

    @Test
    fun aCertificateForAnotherNameStillFails() {
        // Hostname verification is the connection's own and the floor does not touch it.
        val e = assertThrows(IOException::class.java) { redeem(start("othername"), floor("ca")) }
        assertFalse(e.stackTraceToString(), e.blamesTheFloor())
        assertEquals(emptyList<String>(), seen)
    }

    @Test
    fun anUntrustedAnchorStillFails() {
        // Strong, and signed by a CA that is not installed: the floor narrows trust, never widens it.
        val e = assertThrows(IOException::class.java) { redeem(start("untrusted"), floor("ca")) }
        assertFalse(e.stackTraceToString(), e.blamesTheFloor())
        assertEquals(emptyList<String>(), seen)
    }

    @Test
    fun httpsWithoutAFloorSendsNothing() {
        assertThrows(IllegalStateException::class.java) { redeem(start("strong"), null) }
        assertEquals(emptyList<String>(), seen)
    }
}
