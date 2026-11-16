package org.kysecurity.drive.core

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Settles how one HTTPS connection is trusted, before anything is sent over it. */
fun interface Tls {
    fun secure(conn: HttpsURLConnection)
}

/**
 * The strength KyDrive requires of a server's certificate chain: RSA keys of at least 2048 bits,
 * elliptic-curve keys of at least 256, and a SHA-2 or stronger signature on every certificate
 * whose signature is verified. A key or a signature algorithm the floor cannot measure — DSA, an
 * unnamed curve, an OID with no digest in it — is refused rather than assumed strong.
 *
 * A self-signed certificate's own signature proves nothing and no validator checks it, so only
 * its key is measured; that is how a trust anchor is treated, including the system roots that
 * still self-sign with SHA-1.
 */
object CertFloor {
    const val RSA_BITS = 2048
    const val EC_BITS = 256

    private val SHA2_OR_BETTER = Regex("""SHA-?(224|256|384|512|3-\d+)""")

    /**
     * Throws [CertificateException] unless every certificate of an already validated [chain]
     * meets the floor. [chain] runs from the server's certificate to the trust anchor.
     */
    fun check(chain: List<X509Certificate>) {
        if (chain.isEmpty()) throw CertificateException("KyDrive got no certificate chain")
        for ((index, cert) in chain.withIndex()) {
            key(cert)
            // Only the final trust anchor gets the self-signature exception. A self-issued
            // leaf/intermediate can have the same subject and issuer without being an anchor.
            if (index != chain.lastIndex || cert.subjectX500Principal != cert.issuerX500Principal) digest(cert)
        }
    }

    private fun key(cert: X509Certificate) {
        when (val key = cert.publicKey) {
            is RSAPublicKey -> {
                val bits = key.modulus.bitLength()
                if (bits < RSA_BITS) refuse(cert, "an RSA key of $bits bits")
            }
            is ECPublicKey -> {
                val params = key.params ?: refuse(cert, "an elliptic-curve key without parameters")
                val bits = minOf(params.order.bitLength(), params.curve.field.fieldSize)
                if (bits < EC_BITS) refuse(cert, "an elliptic-curve key of $bits bits")
            }
            else -> refuse(cert, "a ${key.algorithm} key the certificate floor does not measure")
        }
    }

    private fun digest(cert: X509Certificate) {
        if (!SHA2_OR_BETTER.containsMatchIn(cert.sigAlgName.uppercase())) {
            refuse(cert, "a ${cert.sigAlgName} signature")
        }
    }

    private fun refuse(cert: X509Certificate, what: String): Nothing =
        throw CertificateException("KyDrive refuses ${cert.subjectX500Principal.name}: $what")
}

/**
 * A [Tls] that applies [CertFloor] to the chain [validate] returns. [validate] is the platform's
 * own trust decision — system CAs, the network security config, expiry, basic constraints — so
 * the floor only narrows what is accepted, and it runs inside the handshake, before the first
 * request byte. Hostname verification is the connection's own and is untouched.
 */
fun floorTls(base: X509TrustManager, validate: (Array<X509Certificate>, String) -> List<X509Certificate>): Tls {
    val context = SSLContext.getInstance("TLS")
    context.init(null, arrayOf(FloorTrustManager(base, validate)), null)
    val factory = context.socketFactory
    return Tls { it.sslSocketFactory = factory }
}

private class FloorTrustManager(
    private val base: X509TrustManager,
    private val validate: (Array<X509Certificate>, String) -> List<X509Certificate>,
) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
        CertFloor.check(validate(chain, authType))

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String): Unit =
        throw CertificateException("KyDrive presents no client certificate")

    override fun getAcceptedIssuers(): Array<X509Certificate> = base.acceptedIssuers
}
