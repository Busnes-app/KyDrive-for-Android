package org.kysecurity.drive.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
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
 * [tls] settles the certificate floor and an HTTPS request without one fails before it is sent;
 * only the tests, against a local HTTP server, leave it out.
 */
class Api(
    private val origin: String,
    private val token: String? = null,
    private val key: DeviceKey? = null,
    private val tls: Tls? = null,
) {

    /** Redeems a scanned phone code, enrolling [key] when there is one. A used or expired code is a 400. */
    fun redeem(code: String, name: String): Linked {
        var form = "grant=qr&code=${enc(code)}&name=${enc(name)}&platform=android"
        key?.let { form += "&publicKey=" + enc(java.util.Base64.getEncoder().encodeToString(it.spki)) }
        val json = call("POST", "/api/devices/token", "application/x-www-form-urlencoded", form.toByteArray())
        val token = json.optString("token")
        if (!token.startsWith("kyd_")) throw IOException("KyDrive returned no device token")
        return Linked(token, json.str("user_id"), json.str("user"))
    }

    fun account(): Account {
        val json = call("GET", "/api/drive/device")
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

    /** Opens [path] with [headers], signed when there is a key, and sends [len] bytes of [body] from [off]. */
    internal fun open(method: String, path: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null, off: Int = 0, len: Int = body?.size ?: 0): HttpURLConnection {
        val url = URI(origin + path).toURL()
        val conn = url.openConnection() as HttpURLConnection
        // Before anything else: the floor refuses a weak chain during the handshake, so a
        // request byte is never written to one.
        if (conn is HttpsURLConnection) {
            checkNotNull(tls) { "an HTTPS Api was built without a certificate floor" }.secure(conn)
        }
        conn.requestMethod = method
        conn.instanceFollowRedirects = false
        conn.useCaches = false
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("Accept", "application/json")
        token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (token != null && key != null) {
            RequestSigning.headers(key, origin, method, url.file, headers, body ?: ByteArray(0), off, len).forEach { (k, v) -> conn.setRequestProperty(k, v) }
        }
        if (body != null) {
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(len)
            conn.outputStream.use { it.write(body, off, len) }
        }
        return conn
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
