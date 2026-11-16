package org.kysecurity.drive.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLDecoder

/** Api against a local HTTP server. Production origins are HTTPS; LinkUri enforces that. */
class ApiTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }
    private val origin = "http://127.0.0.1:${server.address.port}"
    private val seen = mutableListOf<String>()

    @After
    fun stop() = server.stop(0)

    private fun route(path: String, status: Int, body: String, headers: Map<String, String> = emptyMap()) {
        server.createContext(path) { ex: HttpExchange ->
            val sent = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            seen += "${ex.requestMethod} ${ex.requestURI} auth=${ex.requestHeaders.getFirst("Authorization")} type=${ex.requestHeaders.getFirst("Content-Type")} body=$sent"
            headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
    }

    @Test
    fun redeemSendsTheQrGrantAndReadsTheToken() {
        route("/api/devices/token", 200, """{"token":"kyd_abc","user_id":"u1","user":"ana","devices_url":"x"}""")
        val linked = Api(origin).redeem("C0de", "Ana's Pixel & co")
        assertEquals("kyd_abc", linked.token)
        assertEquals("u1", linked.userId)
        assertEquals("ana", linked.user)
        val req = seen.single()
        assertTrue(req, req.startsWith("POST /api/devices/token auth=null type=application/x-www-form-urlencoded body="))
        val form = req.substringAfter("body=").split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals(mapOf("grant" to "qr", "code" to "C0de", "name" to "Ana's Pixel & co", "platform" to "android"), form)
    }

    @Test
    fun redeemRefusesAnAnswerWithoutADeviceToken() {
        route("/api/devices/token", 200, """{"token":"abc","user_id":"u1","user":"ana"}""")
        assertThrows(IOException::class.java) { Api(origin).redeem("c", "n") }
    }

    @Test
    fun aBurnedCodeIsA400() {
        route("/api/devices/token", 400, """{"error":"Invalid or expired code"}""")
        val e = assertThrows(ApiError::class.java) { Api(origin).redeem("c", "n") }
        assertEquals(400, e.status)
        assertFalse(e.revoked)
    }

    @Test
    fun accountSendsTheBearerToken() {
        route("/api/drive/device", 200, """{"user_id":"u1","user":"ana"}""")
        assertEquals(Account("u1", "ana"), Api(origin, "kyd_t").account())
        assertTrue(seen.single(), seen.single().startsWith("GET /api/drive/device auth=Bearer kyd_t"))
    }

    @Test
    fun revokedIsRecognisedWhateverTheWipeFlag() {
        route("/api/drive/device", 401, """{"error":"device_revoked","wipe":false}""")
        val e = assertThrows(ApiError::class.java) { Api(origin, "kyd_t").account() }
        assertTrue(e.revoked)
        assertFalse(e.wipe)
    }

    @Test
    fun aPlain401IsNotRevoked() {
        route("/api/drive/device", 401, """{"error":"Authentication required"}""")
        assertFalse(assertThrows(ApiError::class.java) { Api(origin, "kyd_t").account() }.revoked)
    }

    @Test
    fun redirectsAreRefusedNotFollowed() {
        route("/api/drive/device", 302, "", mapOf("Location" to "$origin/elsewhere"))
        route("/elsewhere", 200, """{"user_id":"u1","user":"ana"}""")
        assertEquals(302, assertThrows(ApiError::class.java) { Api(origin, "kyd_t").account() }.status)
        assertEquals(1, seen.size)
    }

    @Test
    fun unlinkTreatsAnAlreadyRevokedDeviceAsDone() {
        route("/api/devices/self", 401, """{"error":"device_revoked","wipe":true}""")
        Api(origin, "kyd_t").unlink()
        assertTrue(seen.single(), seen.single().startsWith("DELETE /api/devices/self auth=Bearer kyd_t"))
    }

    @Test
    fun unlinkSurfacesOtherFailures() {
        route("/api/devices/self", 500, """{"error":"Drive operation failed"}""")
        assertThrows(ApiError::class.java) { Api(origin, "kyd_t").unlink() }
    }

    @Test
    fun aKeyedDeviceSignsEveryRequestItsTokenMakes() {
        val key = SoftwareKey()
        val checked = mutableListOf<String>()
        server.createContext("/api/") { ex: HttpExchange ->
            val body = ex.requestBody.readBytes()
            val h = ex.requestHeaders
            val headers = RequestSigning.SIGNED_HEADERS.mapNotNull { n -> h.getFirst(n)?.let { n to it } }.toMap()
            val msg = RequestSigning.message(
                RequestSigning.canonicalOrigin(origin), ex.requestMethod, ex.requestURI.rawPath + (ex.requestURI.rawQuery?.let { "?$it" } ?: ""),
                h.getFirst("KyDrive-Date"), h.getFirst("KyDrive-Nonce"), RequestSigning.headerDigest(headers), RequestSigning.sha256Hex(body),
            )
            val ok = h.getFirst("KyDrive-Content-SHA256") == RequestSigning.sha256Hex(body) &&
                verifies(key.public, msg, java.util.Base64.getDecoder().decode(h.getFirst("KyDrive-Signature")))
            checked += "${ex.requestMethod} ${ex.requestURI.rawPath} $ok"
            val out = """{"user_id":"u1","user":"ana","received":4}""".toByteArray()
            ex.sendResponseHeaders(if (ok) 200 else 401, out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        val api = Api(origin, "kyd_t", key)
        api.account()
        api.call("POST", "/api/drive/workspaces/w/folders", org.json.JSONObject().put("name", "A b"))
        api.put("/api/drive/upload-sessions/s%201", "bytes 2-5/9", "..data..".toByteArray(), 2, 4)
        api.call("GET", "/api/drive/search?q=a%20b")
        assertEquals(listOf("GET /api/drive/device true", "POST /api/drive/workspaces/w/folders true", "PUT /api/drive/upload-sessions/s%201 true", "GET /api/drive/search true"), checked)
    }

    @Test
    fun redeemEnrolsTheKeyAndSignsNothing() {
        val key = SoftwareKey()
        var signature: String? = null
        var form = ""
        server.createContext("/api/devices/token") { ex: HttpExchange ->
            form = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            signature = ex.requestHeaders.getFirst("KyDrive-Signature")
            val out = """{"token":"kyd_abc","user_id":"u1","user":"ana"}""".toByteArray()
            ex.sendResponseHeaders(200, out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        Api(origin, key = key).redeem("c", "n")
        val sent = form.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertTrue(key.spki.contentEquals(java.util.Base64.getDecoder().decode(sent["publicKey"])))
        assertEquals(null, signature)
    }
}
