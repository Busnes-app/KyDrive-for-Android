package org.kysecurity.drive.core

import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** A P-256 key that never leaves the device's keystore. */
interface DeviceKey {
    /** The public key as SPKI DER, enrolled when the device links. */
    val spki: ByteArray

    /** ECDSA P-256 over SHA-256 of [message], DER encoded. */
    fun sign(message: ByteArray): ByteArray
}

/**
 * KyDrive's `kydrive-request-v1` request signature (KyDrive-server `internal/api/devicesig.go`,
 * golden vector `testdata/device-signing-v1.json`). A device that enrolled a key signs every
 * request with it, so its token alone authorises nothing.
 */
object RequestSigning {
    /** The request headers the server acts on, lowercase, in signing order. */
    val SIGNED_HEADERS = listOf("content-range", "depth", "destination", "if", "if-match", "if-none-match", "lock-token", "overwrite", "timeout", "x-oc-mtime")

    private val random = SecureRandom()

    /** The signing headers for one request; [headers] are the others it sends. */
    fun headers(key: DeviceKey, origin: String, method: String, uri: String, headers: Map<String, String>, body: ByteArray, off: Int = 0, len: Int = body.size, now: Long = System.currentTimeMillis()): Map<String, String> {
        val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also { random.nextBytes(it) })
        val digest = sha256Hex(body, off, len)
        val date = now.toString()
        val msg = message(canonicalOrigin(origin), method, uri, date, nonce, headerDigest(headers), digest)
        return mapOf(
            "KyDrive-Date" to date,
            "KyDrive-Nonce" to nonce,
            "KyDrive-Content-SHA256" to digest,
            "KyDrive-Signature" to Base64.getEncoder().encodeToString(key.sign(msg.toByteArray())),
        )
    }

    /** The signed text; a field holding `|`, CR or LF is refused, as the server refuses it. */
    fun message(vararg fields: String): String {
        require(fields.none { it.isEmpty() || it.any { c -> c == '|' || c == '\r' || c == '\n' } }) { "unsignable request field" }
        return "kydrive-request-v1|" + fields.joinToString("|")
    }

    /** scheme://host[:port], lowercased, without a default port, as the server canonicalises KY_APP_URL. */
    fun canonicalOrigin(origin: String): String {
        val u = URI(origin)
        val scheme = u.scheme.lowercase()
        val host = u.host.lowercase()
        val port = u.port
        val default = port == -1 || scheme == "https" && port == 443 || scheme == "http" && port == 80
        return "$scheme://$host" + if (default) "" else ":$port"
    }

    fun headerDigest(headers: Map<String, String>): String {
        val lower = headers.mapKeys { it.key.lowercase() }
        val text = SIGNED_HEADERS.mapNotNull { name -> lower[name]?.let { "$name:$it\n" } }.joinToString("")
        return sha256Hex(text.toByteArray())
    }

    fun sha256Hex(b: ByteArray, off: Int = 0, len: Int = b.size): String {
        val d = MessageDigest.getInstance("SHA-256").apply { update(b, off, len) }.digest()
        return d.joinToString("") { "%02x".format(it) }
    }
}
