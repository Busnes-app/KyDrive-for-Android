package org.kysecurity.drive.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection

/** A non-2xx answer. [code] is the server's `error` text, never shown verbatim to the user. */
class ApiError(val status: Int, val code: String, val wipe: Boolean) : IOException("KyDrive answered $status") {
    /** The device was revoked on the server: the app must wipe, whatever [wipe] says. */
    val revoked: Boolean get() = status == 401 && code == "device_revoked"
}

/** The token answer from `POST /api/devices/token`. */
class Linked(val token: String, val userId: String, val user: String)

/** The account a device token belongs to, from `GET /api/drive/device`. */
data class Account(val userId: String, val user: String)

/**
 * The KyDrive HTTP API. Blocking; call it off the main thread. [origin] comes from a parsed
 * [LinkUri], so it is a bare HTTPS origin. Redirects are refused rather than followed. With a
 * [key], every request the [token] makes is signed ([RequestSigning]).
 *
 * Every HTTPS connection requires [tls], which checks platform trust, the certificate floor
 * and then the leaf in [pins]. With no pin
 * yet, the leaf of the first 2xx answer is pinned (trust on first use). A wrong leaf triggers one
 * signed rollover ([TlsPins.accept]) and one retry; without the server's identity key it fails.
 */
class Api(
    private val origin: String,
    private val token: String? = null,
    private val key: DeviceKey? = null,
    private val pins: PinStore = MemoryPins(),
    private val tls: Tls? = null,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {

    /** Redeems a scanned phone code, enrolling [key] when there is one. A used or expired code is a 400. */
    fun redeem(code: String, name: String): Linked {
        var form = "grant=qr&code=${enc(code)}&name=${enc(name)}&platform=android"
        key?.let { form += "&publicKey=" + enc(java.util.Base64.getEncoder().encodeToString(it.spki)) }
        val json = learning("POST", "/api/devices/token", "application/x-www-form-urlencoded", form.toByteArray())
        val token = json.optString("token")
        if (!token.startsWith("kyd_")) throw IOException("KyDrive returned no device token")
        return Linked(token, json.str("user_id"), json.str("user"))
    }

    fun account(): Account {
        val json = learning("GET", "/api/drive/device")
        return Account(json.str("user_id"), json.str("user"))
    }

    /** Revokes this device on the server. Already revoked counts as done. */
    fun unlink() {
        try {
            call("DELETE", "/api/devices/self")
        } catch (e: ApiError) {
            if (!e.revoked) throw e
        }
    }

    internal fun call(method: String, path: String, type: String? = null, body: ByteArray? = null): JSONObject =
        open(method, path, type?.let { mapOf("Content-Type" to it) }.orEmpty(), body).answer { if (it.isEmpty()) JSONObject() else parse(it) }

    internal fun call(method: String, path: String, json: JSONObject): JSONObject =
        call(method, path, "application/json", json.toString().toByteArray())

    /** A route that answers a JSON array. */
    internal fun array(method: String, path: String): JSONArray = open(method, path).answer {
        try {
            JSONArray(it)
        } catch (e: JSONException) {
            throw IOException("KyDrive sent malformed JSON", e)
        }
    }

    /** One upload chunk: [len] bytes of [buf] from [off], at [range] ("bytes a-b/total"). */
    internal fun put(path: String, range: String, buf: ByteArray, off: Int, len: Int) {
        open("PUT", path, mapOf("Content-Type" to "application/octet-stream", "Content-Range" to range), buf, off, len).answer { }
    }

    /** Opens [path] with [headers], signed when there is a key, sends [len] bytes of [body] from [off] and reads the status. */
    internal fun open(method: String, path: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null, off: Int = 0, len: Int = body?.size ?: 0): HttpURLConnection {
        val pin = pins.current()
        val conn = try {
            send(method, path, headers, body, off, len, pin?.leaf, credentials = true)
        } catch (e: IOException) {
            if (pin == null || !e.isPinMismatch() || !roll(pin)) throw e
            // The one retry: a server flapping between leaves fails here instead of rolling again.
            send(method, path, headers, body, off, len, pins.current()?.leaf ?: throw e, credentials = true)
        }
        if (pin == null && conn.responseCode in 200..299) conn.leaf()?.let { pins.replace(null, TlsPin.first(it, now())) }
        return conn
    }

    /**
     * A JSON call whose answer may carry `identity_key`. It is learned once, over the pinned leaf,
     * and never replaced: whoever holds a leaf's key (the TLS proxy) must not gain later rollovers.
     */
    private fun learning(method: String, path: String, type: String? = null, body: ByteArray? = null): JSONObject {
        val conn = open(method, path, type?.let { mapOf("Content-Type" to it) }.orEmpty(), body)
        val leaf = conn.leaf()
        val json = conn.answer { if (it.isEmpty()) JSONObject() else parse(it) }
        val identity = TlsPins.identityKeyOrNull(json.optString("identity_key").ifEmpty { null }) ?: return json
        val stored = pins.current() ?: return json
        if (stored.leaf == leaf && stored.identityKey == null) pins.replace(stored, stored.copy(identityKey = identity))
        return json
    }

    /**
     * After [failed] met another leaf: one credential-free fetch of the signed pin over platform
     * trust and the certificate floor. True when the stored pin moved (here or by a concurrent call) and the request is
     * worth one retry. Serialized so one renewal costs one fetch.
     */
    private fun roll(failed: TlsPin): Boolean = synchronized(ROLL) { rollLocked(failed) }

    private fun rollLocked(failed: TlsPin): Boolean {
        val stored = pins.current() ?: return false
        if (stored != failed) return stored.leaf != failed.leaf
        if (stored.identityKey == null) return false
        val conn = send("GET", "/api/devices/tls-pin", emptyMap(), null, 0, 0, null, credentials = false)
        val next = try {
            val observed = conn.leaf()
            if (conn.responseCode != 200 || observed == null) return false
            val statement = parse(conn.inputStream.use { it.readBounded(4096) })
            TlsPins.accept(stored, URI(origin).host.lowercase(), observed, statement, now())
        } finally {
            conn.disconnect()
        }
        // Compare-and-swap: an unlink or re-link during the fetch is never undone.
        return next != null && pins.replace(stored, next)
    }

    /** A connection pinned to [pin] (null: platform trust and strength floor). Without [credentials] it carries no token and no signature. */
    private fun send(method: String, path: String, headers: Map<String, String>, body: ByteArray?, off: Int, len: Int, pin: String?, credentials: Boolean): HttpURLConnection {
        val url = URI(origin + path).toURL()
        val conn = url.openConnection() as HttpURLConnection
        try {
            if (conn is HttpsURLConnection) {
                checkNotNull(tls) { "an HTTPS Api was built without a certificate floor" }.secure(conn, pin)
            }
            conn.requestMethod = method
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Accept", "application/json")
            if (credentials) token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (credentials && token != null && key != null) {
                RequestSigning.headers(key, origin, method, url.file, headers, body ?: ByteArray(0), off, len).forEach { (k, v) -> conn.setRequestProperty(k, v) }
            }
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(len)
                conn.outputStream.use { it.write(body, off, len) }
            }
            conn.responseCode
            return conn
        } catch (e: IOException) {
            conn.disconnect()
            throw e
        }
    }

    /** Reads a 2xx body as text, or throws [ApiError]; always disconnects. */
    internal fun <T> HttpURLConnection.answer(read: (String) -> T): T = try {
        val status = responseCode
        if (status !in 200..299) throw error(status, errorStream)
        read(inputStream.use { it.readBounded(MAX_JSON) })
    } finally {
        disconnect()
    }

    companion object {
        private const val MAX_JSON = 8 shl 20
        private val ROLL = Any()
        private fun HttpURLConnection.leaf(): String? =
            (this as? HttpsURLConnection)?.serverCertificates?.firstOrNull()?.let { TlsPins.of(it as X509Certificate) }

        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8") // the Charset overload needs API 33

        internal fun error(status: Int, body: InputStream?): ApiError {
            val json = body?.use { runCatching { parse(it.readBounded(4096)) }.getOrNull() }
            return ApiError(status, json?.optString("error").orEmpty(), json?.optBoolean("wipe") == true)
        }

        internal fun parse(text: String): JSONObject = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw IOException("KyDrive sent malformed JSON", e)
        }

        private fun JSONObject.str(key: String): String =
            optString(key).ifEmpty { throw IOException("KyDrive's answer lacks $key") }

        private fun InputStream.readBounded(max: Int): String {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > max) throw IOException("KyDrive's answer is too large")
            }
            return out.toString("UTF-8")
        }
    }
}
