package org.kysecurity.drive

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.kysecurity.drive.core.Api
import org.kysecurity.drive.core.Tls
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The certificate floor on the app's own HTTPS path: [driveApi] against a server on the device,
 * where the platform's trust decision is the real one (network security config, system CAs and
 * the fixture CA that `-PscreenshotCa` adds through `debug-overrides`).
 *
 * The fixture CA signs every certificate below except `untrusted`, so the only difference
 * between a case that links and a case that fails is the certificate's strength. A refused chain
 * must fail inside the handshake: the server records every request line it reads, and for a
 * refusal that list stays empty, which is what "before the request" means for a bearer token.
 *
 * Skipped unless the APK trusts the fixture CA; `ci-tls-floor` builds it that way and fails if
 * these tests skipped.
 */
@RunWith(AndroidJUnit4::class)
class CertFloorDeviceTest {
    private var server: FixtureServer? = null

    @Before
    fun theFixtureCaIsTrusted() {
        // Missing/malformed assets are configuration failures, never a reason to skip.
        val chain = arrayOf(cert("strong"), cert("ca"))
        val trusted = runCatching { platformTrust().checkServerTrusted(chain, "ECDHE_ECDSA") }
        assumeTrue(
            "build with -PscreenshotCa=app/src/test/resources/tls/ca.pem to run the certificate-floor device tests",
            trusted.isSuccess,
        )
    }

    @After
    fun stop() {
        server?.close()
    }

    private fun redeem(leaf: String, vararg issuers: String, enforceFloor: Boolean = true): String {
        val running = FixtureServer(leaf, *issuers).also { server = it }
        // The baseline alone omits the floor. It still uses Android's default system/NSC
        // trust and hostname checks, and proves the negative controls reach our policy.
        val api = if (enforceFloor) driveApi(running.origin) else Api(running.origin, tls = Tls { })
        return api.redeem(CODE, "Ana's Pixel").token
    }

    private fun refused(leaf: String, vararg issuers: String, byFloor: Boolean = true) {
        val failure = assertThrows(IOException::class.java) { redeem(leaf, *issuers) }
        val floorRefusal = generateSequence(failure as Throwable?) { it.cause }
            .any { it.message.orEmpty().contains("KyDrive refuses") }
        assertEquals(failure.stackTraceToString(), byFloor, floorRefusal)
        assertEquals(emptyList<String>(), server!!.requests)
    }

    @Test
    fun aStrongCertificateLinks() {
        assertEquals("kyd_abc", redeem("strong"))
        assertTrue(server!!.requests.single(), server!!.requests.single().startsWith("POST /api/devices/token"))
    }

    @Test
    fun anRsaKeyBelowTheFloorIsRefusedBeforeTheRequest() = refused("rsa2047")

    @Test
    fun thePlatformAcceptsTheFloorNegativeControls() {
        // P-224 is covered by the pure policy test: the native TLS stack may not negotiate
        // it at all. These controls must succeed without our floor, then fail because of it.
        // SHA-1 is absent because Conscrypt rejects its signature before our floor runs.
        for ((leaf, issuers) in listOf("rsa2047" to emptyArray(), "under-weak-int" to arrayOf("weak-int"))) {
            assertEquals(leaf, "kyd_abc", redeem(leaf, *issuers, enforceFloor = false))
            assertTrue(leaf, server!!.requests.isNotEmpty())
            server!!.close()
        }
    }

    @Test
    fun aSha1SignatureStillFails() = refused("sha1", byFloor = false)

    @Test
    fun aWeakIntermediateIsRefusedBeforeTheRequest() = refused("under-weak-int", "weak-int")

    @Test
    fun aCertificateForAnotherNameStillFails() = refused("othername", byFloor = false)

    @Test
    fun anUntrustedAnchorStillFails() = refused("untrusted", byFloor = false)

    /** An HTTPS server on loopback presenting one fixture chain, recording what it is asked. */
    private inner class FixtureServer(leaf: String, vararg issuers: String) : Closeable {
        val requests = CopyOnWriteArrayList<String>()
        private val socket: SSLServerSocket

        init {
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                .apply { init(identity(leaf, *issuers), CharArray(0)) }
            val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
            socket = context.serverSocketFactory.createServerSocket(0, 8, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
            Thread { accept() }.apply { isDaemon = true }.start()
        }

        val origin: String get() = "https://127.0.0.1:${socket.localPort}"

        override fun close() {
            runCatching { socket.close() }
        }

        private fun accept() {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: return
                Thread { serve(client) }.apply { isDaemon = true }.start()
            }
        }

        // A refused chain throws out of the handshake the first read drives, before any line.
        private fun serve(client: Socket) = client.use {
            runCatching {
                val reader = it.getInputStream().bufferedReader()
                val request = reader.readLine() ?: return@runCatching
                requests += request
                var length = 0
                while (true) {
                    val header = reader.readLine().orEmpty()
                    if (header.isEmpty()) break
                    if (header.startsWith("Content-Length:", ignoreCase = true)) {
                        length = header.substringAfter(':').trim().toInt()
                    }
                }
                repeat(length) { reader.read() }
                val body = """{"token":"kyd_abc","user_id":"u1","user":"ana"}"""
                it.getOutputStream().write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body").toByteArray(),
                )
            }
        }
    }

    private companion object {
        // 32 random bytes, base64url: what LinkUri accepts, so this is the real redeem call.
        const val CODE = "Ks8Ue1Fh-7mXqPbR4nTvZ2cLdYwA6gJiOxE0sBvNrKQ"

        fun platformTrust(): X509TrustManager =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(null as KeyStore?) }
                .trustManagers
                .filterIsInstance<X509TrustManager>()
                .first()

        fun asset(name: String): ByteArray =
            InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }

        fun cert(name: String): X509Certificate =
            asset("$name.pem").inputStream().use {
                CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
            }

        fun key(name: String): PrivateKey {
            val pem = asset("$name.key").toString(Charsets.UTF_8)
            val der = Base64.getMimeDecoder().decode(
                pem.substringAfter("-----BEGIN PRIVATE KEY-----").substringBefore("-----END PRIVATE KEY-----"),
            )
            return KeyFactory.getInstance(cert(name).publicKey.algorithm).generatePrivate(PKCS8EncodedKeySpec(der))
        }

        fun identity(leaf: String, vararg issuers: String): KeyStore =
            KeyStore.getInstance("PKCS12").apply {
                load(null)
                setKeyEntry("tls", key(leaf), CharArray(0), (listOf(cert(leaf)) + issuers.map { cert(it) }).toTypedArray())
            }
    }
}
