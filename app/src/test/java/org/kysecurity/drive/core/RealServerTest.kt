package org.kysecurity.drive.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.CookieManager
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration

/**
 * The client against a real KyDrive server, the way kydrive-desktop's real-server job does it.
 * Skipped unless KYDRIVE_SERVER_BIN names a built `kydrive-server`. The server runs in
 * development mode on plain HTTP, so the tests call [Api] with its origin directly; [LinkUri]
 * (HTTPS only) is checked against the server's link with the scheme swapped.
 */
class RealServerTest {
    private val bin: String? = System.getenv("KYDRIVE_SERVER_BIN")?.takeIf { it.isNotEmpty() }
    private lateinit var dir: File
    private lateinit var process: Process
    private lateinit var base: String
    private val cookies = CookieManager()
    private val web = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(10)).build()
    private lateinit var adminId: String

    @Before
    fun start() {
        assumeTrue("KYDRIVE_SERVER_BIN is not set", bin != null)
        val port = ServerSocket(0).use { it.localPort }
        base = "http://127.0.0.1:$port"
        // Short: the server's setup socket path has a length limit.
        dir = Files.createTempDirectory("kyd-").toFile()
        process = ProcessBuilder(bin)
            .redirectErrorStream(true)
            .redirectOutput(File(dir, "server.log"))
            .apply {
                environment() += mapOf(
                    "KY_ENV" to "development", "KY_CAPTCHA_PROVIDER" to "none", "KY_DB_DRIVER" to "sqlite",
                    "KY_DATA_DIR" to "$dir/data", "KY_BACKUP_DIR" to "$dir/backups",
                    "KY_HOST" to "127.0.0.1", "KY_PORT" to "$port", "KY_APP_URL" to base,
                    "KY_ADMIN_PASSWORD" to "E2eBootstrapPass123!",
                    "PUSH_RELAY_URL" to "", "APNS_RELAY_URL" to "",
                )
            }
            .start()
        repeat(100) {
            if (runCatching { web.send(get("/healthz"), HttpResponse.BodyHandlers.discarding()).statusCode() }.getOrNull() == 200) {
                return@repeat signIn()
            }
            Thread.sleep(100)
        }
        assertTrue("server did not start:\n${File(dir, "server.log").readText()}", ::adminId.isInitialized)
    }

    private fun signIn() {
        if (::adminId.isInitialized) return
        // The bootstrap password must be replaced before the session is useful.
        web("POST", "/api/auth/login", JSONObject().put("username", "admin").put("password", "E2eBootstrapPass123!"))
        web("POST", "/api/auth/change-password", JSONObject().put("current_password", "E2eBootstrapPass123!").put("new_password", "E2eReplacementPass456!"))
        web("POST", "/api/auth/login", JSONObject().put("username", "admin").put("password", "E2eReplacementPass456!"))
        adminId = web("GET", "/api/auth/me").getJSONObject("user").getString("id")
    }

    @After
    fun stop() {
        if (bin == null) return
        process.destroyForcibly().waitFor()
        dir.deleteRecursively()
    }

    private fun get(path: String) = HttpRequest.newBuilder(URI(base + path)).build()

    /** The admin's browser session, with its CSRF token. */
    private fun web(method: String, path: String, body: JSONObject? = null): JSONObject =
        webText(method, path, body).let { if (it.isBlank()) JSONObject() else JSONObject(it) }

    private fun webText(method: String, path: String, body: JSONObject? = null): String {
        val csrf = cookies.cookieStore.cookies.firstOrNull { it.name == "ky_csrf" }?.value.orEmpty()
        val req = HttpRequest.newBuilder(URI(base + path))
            .header("Content-Type", "application/json")
            .header("X-CSRF-Token", csrf)
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it.toString()) } ?: HttpRequest.BodyPublishers.noBody())
            .build()
        val res = web.send(req, HttpResponse.BodyHandlers.ofString())
        assertTrue("$method $path: ${res.statusCode()} ${res.body()}", res.statusCode() < 300)
        return res.body()
    }

    /** What Settings, Phones & apps, Link a phone does: a step-up session mints a code. */
    private fun phoneLink(): String = web("POST", "/api/devices/phone-code").getString("link")

    @Test
    fun linksAPhoneByQrAndUnlinksIt() {
        val raw = phoneLink()
        val parsed = LinkUri.parse(raw.replace("srv=http%3A", "srv=https%3A"))
        assertNotNull("the server's link must parse: $raw", parsed)
        val code = parsed!!.code

        val linked = Api(base).redeem(code, "Test phone")
        assertTrue(linked.token.startsWith("kyd_"))
        assertEquals(adminId, linked.userId)
        assertEquals(Account(adminId, "admin"), Api(base, linked.token).account())

        // Single use: a photographed code fails once the owner's scan has redeemed it.
        assertEquals(400, assertThrows(ApiError::class.java) { Api(base).redeem(code, "Thief") }.status)

        Api(base, linked.token).unlink()
        assertTrue(assertThrows(ApiError::class.java) { Api(base, linked.token).account() }.revoked)
    }

    @Test
    fun browsesUploadsAndChangesFiles() {
        val token = Api(base).redeem(LinkUri.parse(phoneLink().replace("srv=http%3A", "srv=https%3A"))!!.code, "Browsing phone").token
        val d = Drive(Api(base, token))

        val home = d.personal()
        assertEquals("personal", home.kind)
        assertTrue(home.canWrite)
        assertEquals(home, d.personal()) // created once
        assertEquals(emptyList<SharedItem>(), d.sharedWithMe())

        val docs = d.addFolder(home.id, "", "Docs")
        val small = d.upload(home.id, docs.id, "notes.txt", 5, "2026-01-02T03:04:05Z", "hello".byteInputStream())
        val empty = d.upload(home.id, docs.id, "empty.txt", 0, null, ByteArray(0).inputStream())
        // Over one chunk, so the session takes more than one PUT.
        val bytes = ByteArray(Drive.CHUNK + 12345) { (it * 31).toByte() }
        val big = d.upload(home.id, "", "big.bin", bytes.size.toLong(), null, bytes.inputStream())
        assertEquals(bytes.size.toLong(), big.size)

        assertEquals(Listing(listOf(docs), listOf(big)), d.list(home.id, ""))
        assertEquals(setOf("notes.txt", "empty.txt"), d.list(home.id, docs.id).files.map { it.name }.toSet())
        assertEquals(setOf("notes.txt", "empty.txt"), d.children(docs.id).files.map { it.name }.toSet())

        val out = java.io.ByteArrayOutputStream()
        d.download(big, out)
        assertTrue(bytes.contentEquals(out.toByteArray()))
        d.download(empty, java.io.ByteArrayOutputStream())
        assertEquals("ell", d.range(small, 1, 3).toString(Charsets.UTF_8))
        assertTrue(bytes.copyOfRange(Drive.CHUNK - 2, Drive.CHUNK + 2).contentEquals(d.range(big, Drive.CHUNK - 2L, 4)))
        // A listing that is out of date never yields bytes.
        assertThrows(TransferError::class.java) { d.download(small.copy(digest = "0".repeat(64)), java.io.ByteArrayOutputStream()) }
        assertThrows(TransferError::class.java) { d.range(small.copy(digest = "0".repeat(64)), 0, 2) }

        val renamed = d.renameFile(small, "renamed.txt")
        assertEquals("renamed.txt", d.file(small.id).name)
        assertEquals("Papers", d.renameFolder(docs, "Papers").name)
        // A stale revision is a conflict, not an overwrite.
        assertEquals(409, assertThrows(ApiError::class.java) { d.renameFile(small.copy(revision = small.revision + 7), "x.txt") }.status)

        d.trash(renamed, true)
        assertEquals(listOf("empty.txt"), d.list(home.id, docs.id).files.map { it.name })
        assertEquals(listOf("renamed.txt"), d.list(home.id, "", trashed = true).files.map { it.name })
        d.trash(renamed, false)
        d.trash(docs, true)
        assertEquals(listOf("Papers"), d.list(home.id, "", trashed = true).folders.map { it.name })
        d.trash(docs, false)
        assertEquals(listOf("Papers"), d.list(home.id, "").folders.map { it.name })
    }

    @Test
    fun aRevokedPhoneIsToldToWipe() {
        val linked = Api(base).redeem(LinkUri.parse(phoneLink().replace("srv=http%3A", "srv=https%3A"))!!.code, "Lost phone")
        val list = JSONArray(webText("GET", "/api/devices"))
        val device = (0 until list.length()).map { list.getJSONObject(it) }.single { it.getString("name") == "Lost phone" }
        assertEquals("android", device.getString("platform"))
        webText("DELETE", "/api/devices/${device.getString("id")}?wipe=false")
        val e = assertThrows(ApiError::class.java) { Api(base, linked.token).account() }
        assertTrue(e.revoked)
    }
}
