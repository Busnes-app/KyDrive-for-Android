package org.kysecurity.drive.core

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertificateException

/**
 * The certificate floor itself, over chains `scripts/tls-fixtures.sh` makes. Every leaf below
 * differs from `strong` in one measured property only.
 */
class CertFloorTest {
    private fun refusal(vararg chain: String): String =
        assertThrows(CertificateException::class.java) { CertFloor.check(Fixtures.chain(*chain)) }.message.orEmpty()

    @Test
    fun strongChainsPass() {
        for (leaf in listOf("strong", "rsa2048", "ec384", "ec521")) {
            CertFloor.check(Fixtures.chain(leaf, "ca"))
        }
    }

    @Test
    fun rsaBelow2048IsRefused() {
        assertTrue(refusal("rsa2047", "ca"), refusal("rsa2047", "ca").contains("an RSA key of 2047 bits"))
    }

    @Test
    fun ecBelow256IsRefused() {
        assertTrue(refusal("ec224", "ca"), refusal("ec224", "ca").contains("an elliptic-curve key of 224 bits"))
    }

    @Test
    fun aSha1SignatureIsRefused() {
        assertTrue(refusal("sha1", "ca"), refusal("sha1", "ca").contains("SHA1withECDSA"))
    }

    @Test
    fun aSelfIssuedLeafStillNeedsAStrongSignature() {
        val message = refusal("self-issued-sha1", "ca")
        assertTrue(message, message.contains("SHA1withECDSA"))
    }

    @Test
    fun aWeakIntermediateIsRefused() {
        val message = refusal("under-weak-int", "weak-int", "ca")
        assertTrue(message, message.contains("weak-intermediate") && message.contains("1024 bits"))
    }

    @Test
    fun aWeakAnchorIsRefused() {
        val message = refusal("under-weak-root", "weak-root")
        assertTrue(message, message.contains("weak-root") && message.contains("1024 bits"))
    }

    @Test
    fun anAnchorsOwnSignatureIsNotMeasured() {
        // Nothing verifies a self-signature, and system roots still carry SHA-1 ones; the
        // anchor's key is what matters, and this one meets the floor.
        CertFloor.check(Fixtures.chain("under-sha1-root", "sha1-root"))
    }

    @Test
    fun aKeyTheFloorCannotMeasureIsRefused() {
        assertTrue(refusal("ed25519", "ca"), refusal("ed25519", "ca").contains("EdDSA"))
    }

    @Test
    fun anEmptyChainIsRefused() {
        assertThrows(CertificateException::class.java) { CertFloor.check(emptyList()) }
    }
}
