package org.kysecurity.drive.core

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import kotlin.math.abs

/**
 * A session's TLS pin: the [leaf] it trusts, the server's Ed25519 [identityKey] that may sign a
 * successor, and the freshness floor a successor's statement must be newer than: the `issued_at`
 * of the statement that installed [leaf], or the time [leaf] was first pinned.
 */
data class TlsPin(val leaf: String, val identityKey: String? = null, val statementIssuedAt: Long = 0) {
    companion object {
        /**
         * A pin set by linking or trust on first use, at [now] (Unix seconds). The floor stops a
         * statement signed before it, for a since-retired leaf, from rolling the session back.
         */
        fun first(leaf: String, now: Long) = TlsPin(leaf, statementIssuedAt = now)
    }
}

/** Where a session keeps its pin. [replace] is compare-and-swap: it writes only if [expected] is still stored. */
interface PinStore {
    /** Null only when nothing is pinned yet; throws when the session it belongs to has ended. */
    fun current(): TlsPin?
    fun replace(expected: TlsPin?, new: TlsPin): Boolean
}

/** A pin held in memory, for linking: the session that stores it does not exist yet. */
class MemoryPins(private var pin: TlsPin? = null) : PinStore {
    @Synchronized override fun current() = pin

    @Synchronized override fun replace(expected: TlsPin?, new: TlsPin): Boolean {
        if (pin != expected) return false
        pin = new
        return true
    }
}

/** The leaf did not match the pin; thrown inside the handshake, before anything is sent. */
class PinMismatch : CertificateException("TLS leaf does not match the pin")

/** True when [this] is, or was caused by, a [PinMismatch]. */
fun Throwable.isPinMismatch(): Boolean = generateSequence(this) { it.cause }.take(10).any { it is PinMismatch }

/**
 * `kydrive-tls-pin/1` (KyDrive-server `internal/api/tls_pin.go`, golden vector
 * `testdata/tls-pin-v1.json`).
 */
object TlsPins {
    /** How far a statement's `issued_at` may sit from this device's clock. */
    const val MAX_SKEW_SECONDS = 24 * 60 * 60L

    private val PIN = Regex("sha256/[A-Za-z0-9+/]{43}=")

    fun valid(pin: String) = PIN.matches(pin)

    /** `sha256/` + std base64 of SHA-256 of the leaf's SubjectPublicKeyInfo DER. */
    fun of(leaf: X509Certificate): String =
        "sha256/" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(leaf.publicKey.encoded))

    /** A std-base64 raw Ed25519 public key, or null unless it is exactly 32 bytes. */
    fun identityKeyOrNull(raw: String?): String? =
        raw?.takeIf { runCatching { Base64.getDecoder().decode(it).size == 32 }.getOrDefault(false) }

    fun statement(host: String, pin: String, issuedAt: Long): ByteArray =
        "kydrive-tls-pin/1\n$host\n$pin\n$issuedAt".toByteArray(Charsets.UTF_8)

    /** BouncyCastle, not JCA: Android has no EdDSA before API 33 and minSdk is 31. */
    fun verify(identityKey: String, host: String, pin: String, issuedAt: Long, signature: String): Boolean = runCatching {
        val message = statement(host, pin, issuedAt)
        Ed25519Signer().run {
            init(false, Ed25519PublicKeyParameters(Base64.getDecoder().decode(identityKey), 0))
            update(message, 0, message.size)
            verifySignature(Base64.getDecoder().decode(signature))
        }
    }.getOrDefault(false)

    /**
     * The pin a signed [statement] installs in place of [stored], or null. All must hold: it
     * names the [observed] leaf (a relayed statement for another leaf fails), for [host], within
     * [MAX_SKEW_SECONDS] of [now], strictly newer than the statement behind [stored] (a replay of
     * a retired leaf's statement fails), for a leaf not already pinned, signed by the stored key.
     */
    fun accept(stored: TlsPin, host: String, observed: String, statement: JSONObject, now: Long): TlsPin? {
        val key = stored.identityKey ?: return null
        val pin = statement.optString("pin")
        val at = when (val v = statement.opt("issued_at")) {
            is Int -> v.toLong()
            is Long -> v
            else -> return null
        }
        val ok = pin == observed && statement.optString("host") == host && abs(now - at) <= MAX_SKEW_SECONDS &&
            at > stored.statementIssuedAt && observed != stored.leaf && verify(key, host, pin, at, statement.optString("signature"))
        return if (ok) TlsPin(observed, key, at) else null
    }

    /** The platform's trust manager: system CAs, as the network security config allows. */
    fun systemTrust(): X509ExtendedTrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
            .trustManagers.filterIsInstance<X509ExtendedTrustManager>().single()
}

/**
 * The platform check first, then the leaf's SPKI against [expected] (null: system trust only).
 * A wrong leaf fails the handshake, so no header or token is ever written to it.
 */
// Lint's CustomX509TrustManager flags any implementation; this one only narrows the platform's.
@Suppress("CustomX509TrustManager")
internal class PinningTrustManager(private val platform: X509ExtendedTrustManager, private val expected: String?) : X509ExtendedTrustManager() {
    private fun pin(chain: Array<X509Certificate>) {
        if (expected != null && TlsPins.of(chain[0]) != expected) throw PinMismatch()
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) {
        platform.checkServerTrusted(chain, authType, socket)
        pin(chain)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) {
        platform.checkServerTrusted(chain, authType, engine)
        pin(chain)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        platform.checkServerTrusted(chain, authType)
        pin(chain)
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException("not a server")
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) = throw CertificateException("not a server")
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) = throw CertificateException("not a server")
    override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers
}
