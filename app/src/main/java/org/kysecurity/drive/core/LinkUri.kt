package org.kysecurity.drive.core

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/**
 * A scanned `kydrive://link?srv=<origin>&code=<code>[&pin=sha256/<base64>]`. Nothing is sent
 * before it parses: the server must be a bare HTTPS origin, so a crafted code cannot point the
 * token request at a path, a userinfo host or plain HTTP. With a [pin], the redemption is pinned
 * to that leaf; without one, to the leaf it meets (trust on first use).
 */
data class LinkUri(val origin: String, val code: String, val pin: String? = null) {
    /** The host the user is asked to confirm, with any port. */
    val host: String get() = origin.removePrefix("https://")

    companion object {
        // secret256: 32 random bytes, base64url without padding.
        private val CODE = Regex("[A-Za-z0-9_-]{43}")

        fun parse(text: String): LinkUri? {
            val uri = uri(text.trim()) ?: return null
            if (uri.scheme != "kydrive" || uri.host != "link" || uri.rawPath.orEmpty().isNotEmpty() || uri.rawFragment != null) {
                return null
            }
            val params = uri.rawQuery?.split('&')?.map { it.split('=', limit = 2) } ?: return null
            val names = params.map { it[0] }.sorted()
            if (params.any { it.size != 2 } || names != listOf("code", "srv") && names != listOf("code", "pin", "srv")) return null
            val value = try {
                params.associate { it[0] to URLDecoder.decode(it[1], "UTF-8") }
            } catch (_: IllegalArgumentException) {
                return null // a malformed %-escape
            }
            val code = value.getValue("code").takeIf { CODE.matches(it) } ?: return null
            val pin = value["pin"]?.let { if (TlsPins.valid(it)) it else return null }
            return LinkUri(origin(value.getValue("srv")) ?: return null, code, pin)
        }

        private fun origin(text: String): String? {
            val srv = uri(text) ?: return null
            val host = srv.host?.lowercase() ?: return null
            if (srv.scheme != "https" || srv.rawUserInfo != null || srv.rawQuery != null || srv.rawFragment != null) return null
            if (srv.rawPath.orEmpty() !in setOf("", "/")) return null
            return "https://" + host + if (srv.port == -1) "" else ":${srv.port}"
        }

        private fun uri(text: String): URI? = try {
            URI(text)
        } catch (_: URISyntaxException) {
            null
        }
    }
}
