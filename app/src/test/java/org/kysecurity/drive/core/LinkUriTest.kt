package org.kysecurity.drive.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URLEncoder

class LinkUriTest {
    private val code = "A1b_-".repeat(9).take(43) // secret256 is 43 base64url characters

    private fun link(srv: String, c: String = code) = "kydrive://link?srv=${URLEncoder.encode(srv, "UTF-8")}&code=$c"

    @Test
    fun acceptsTheServersLink() {
        // The server builds it with url.Values{"srv", "code"}.Encode(): code first, sorted.
        val real = "kydrive://link?code=$code&srv=https%3A%2F%2Fdrive.example.org"
        assertEquals(LinkUri("https://drive.example.org", code), LinkUri.parse(real))
    }

    @Test
    fun keepsAPortAndNormalisesTheHost() {
        assertEquals("https://drive.example.org:8443", LinkUri.parse(link("https://Drive.Example.org:8443/"))?.origin)
        assertEquals("drive.example.org:8443", LinkUri.parse(link("https://drive.example.org:8443"))?.host)
    }

    @Test
    fun refusesAnythingButABareHttpsOrigin() {
        for (srv in listOf(
            "http://drive.example.org",
            "https://user:pw@drive.example.org",
            "https://drive.example.org/path",
            "https://drive.example.org/?q=1",
            "https://drive.example.org/#x",
            "https:///nohost",
            "javascript:alert(1)",
            "",
        )) {
            assertNull(srv, LinkUri.parse(link(srv)))
        }
    }

    @Test
    fun refusesMalformedCodesAndLinks() {
        val ok = "https://drive.example.org"
        for (text in listOf(
            link(ok, code.dropLast(1)),
            link(ok, code + "A"),
            link(ok, code.dropLast(1) + "%"),
            link(ok, code.dropLast(1) + "+"),
            "kydrive://link?srv=$ok",
            "kydrive://link?srv=$ok&code=$code&code=$code",
            "kydrive://link?srv=$ok&code=$code&x=1",
            "kydrive://other?srv=$ok&code=$code",
            "kydrive://link/path?srv=$ok&code=$code",
            "https://link?srv=$ok&code=$code",
            "kydrive://link?srv=%ZZ&code=$code",
            "not a link",
        )) {
            assertNull(text, LinkUri.parse(text))
        }
    }

    @Test
    fun acceptsAnOptionalLeafPin() {
        val pin = "sha256/Y7NDZ8IpLOXohWKxDNkLJh8TVXxPrhajRfRkYgJvV5U="
        val parsed = LinkUri.parse(link("https://drive.example.org") + "&pin=" + URLEncoder.encode(pin, "UTF-8"))
        assertEquals(LinkUri("https://drive.example.org", code, pin), parsed)
        assertNull(LinkUri.parse(link("https://drive.example.org"))?.pin)
    }

    @Test
    fun refusesAMalformedPin() {
        for (pin in listOf("", "sha1/Y7NDZ8IpLOXohWKxDNkLJh8TVXxPrhajRfRkYgJvV5U=", "sha256/short=", "sha256/Y7NDZ8IpLOXohWKxDNkLJh8TVXxPrhajRfRkYgJvV5U", "sha256/Y7NDZ8IpLOXohWKxDNkLJh8TVXxPrhajRfRkYgJvV5U=x")) {
            assertNull(pin, LinkUri.parse(link("https://drive.example.org") + "&pin=" + URLEncoder.encode(pin, "UTF-8")))
        }
        // An unescaped '+' decodes to a space, which no pin holds.
        assertNull(LinkUri.parse(link("https://drive.example.org") + "&pin=sha256/Y7NDZ8IpLOXohWKxDNkLJh8TVXxPrhajRfRkYgJvV5U+="))
        // At most one pin.
        val one = "&pin=" + URLEncoder.encode("sha256/Y7NDZ8IpLOXohWKxDNkLJh8TVXxPrhajRfRkYgJvV5U=", "UTF-8")
        assertNull(LinkUri.parse(link("https://drive.example.org") + one + one))
    }
}
