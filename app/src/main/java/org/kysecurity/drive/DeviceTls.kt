package org.kysecurity.drive

import android.net.http.X509TrustManagerExtensions
import org.kysecurity.drive.core.Api
import org.kysecurity.drive.core.CertFloor
import org.kysecurity.drive.core.DeviceKey
import org.kysecurity.drive.core.Tls
import org.kysecurity.drive.core.floorTls
import java.net.URI
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The platform's trust decision with [CertFloor] over the chain it validated.
 *
 * The delegate is the trust manager the network security config installs, so system CAs stay the
 * only anchors a shipped build trusts (and `debug-overrides` keeps working for the screenshot
 * CA). [X509TrustManagerExtensions] returns the chain that was actually validated, up to and
 * including the anchor, which is the only way to measure an anchor the server never sent.
 */
object DeviceTls {
    private val byHost = ConcurrentHashMap<String, Tls>()

    /** The floor for [origin]'s host. Cached: a new SSLContext would drop session resumption. */
    fun of(origin: String): Tls = byHost.computeIfAbsent(URI(origin).host) { host ->
        val platform = platformTrust()
        val extensions = X509TrustManagerExtensions(platform)
        floorTls(platform) { chain, authType -> extensions.checkServerTrusted(chain, authType, host) }
    }

    private fun platformTrust(): X509TrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()
}

/**
 * The only place the app builds an [Api]: every connection it makes carries the certificate
 * floor. `SourceRulesTest` keeps it that way.
 */
fun driveApi(origin: String, token: String? = null, key: DeviceKey? = null): Api =
    Api(origin, token, key, DeviceTls.of(origin))
