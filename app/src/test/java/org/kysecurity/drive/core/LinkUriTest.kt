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
}
