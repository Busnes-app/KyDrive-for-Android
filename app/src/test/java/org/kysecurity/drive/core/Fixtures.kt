package org.kysecurity.drive.core

import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** The throwaway certificates `scripts/tls-fixtures.sh` makes, on the test classpath. */
internal object Fixtures {
    val PASSWORD = CharArray(0)

    fun cert(name: String): X509Certificate =
        read("$name.pem").inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }

    /** A chain from the server's certificate to its anchor. */
    fun chain(vararg names: String): List<X509Certificate> = names.map { cert(it) }

    fun key(name: String): PrivateKey {
        val pem = read("$name.key").toString(Charsets.UTF_8)
        val der = Base64.getMimeDecoder().decode(
            pem.substringAfter("-----BEGIN PRIVATE KEY-----").substringBefore("-----END PRIVATE KEY-----"),
        )
        return KeyFactory.getInstance(cert(name).publicKey.algorithm).generatePrivate(PKCS8EncodedKeySpec(der))
    }

    /** What a test server presents: [leaf]'s key, and [leaf] plus every [issuers] certificate. */
    fun identity(leaf: String, vararg issuers: String): KeyStore =
        KeyStore.getInstance("PKCS12").apply {
            load(null)
            setKeyEntry("tls", key(leaf), PASSWORD, (listOf(cert(leaf)) + issuers.map { cert(it) }).toTypedArray())
        }

    /** A trust manager with exactly [anchors] installed, standing in for the device's own. */
    fun trust(vararg anchors: String): X509TrustManager {
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null)
            anchors.forEachIndexed { i, name -> setCertificateEntry("anchor$i", cert(name)) }
        }
        return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(store) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()
    }

    private fun read(file: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/tls/$file")) { "run scripts/tls-fixtures.sh: /tls/$file is missing" }
            .use { it.readBytes() }
}
