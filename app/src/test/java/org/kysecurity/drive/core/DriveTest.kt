package org.kysecurity.drive.core

import org.junit.Assert.assertTrue
import org.junit.Test

class DriveTest {
    @Test
    fun namesTheServerRefusesAreCaughtFirst() {
        for (bad in listOf("", " a", "a ", ".", "..", "a/b", "a\\b", "a\u0000b", "a\u200Bb", "x".repeat(256))) {
            assertTrue(bad, !Drive.validName(bad))
        }
        assertTrue(Drive.validName("Résumé 2026 (final).pdf"))
    }
}
