package org.kysecurity.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** cleanName must produce a name the server's ValidName and 100-byte limit accept. */
class DeviceNameTest {
    @Test
    fun keepsAnOrdinaryName() = assertEquals("Ana's Pixel 9", cleanName("  Ana's Pixel 9 "))

    @Test
    fun dropsControlAndFormatCharactersAndSlashes() =
        assertEquals("a b cd", cleanName("a/b\\c\u0007\u200Bd"))

    @Test
    fun capsAtOneHundredBytesOnWholeCharacters() {
        val name = cleanName("é".repeat(80)) // 2 bytes each
        assertEquals(50, name.length)
        val emoji = cleanName("😀".repeat(30)) // 4 bytes each, a surrogate pair in Kotlin
        assertEquals(25, emoji.codePointCount(0, emoji.length))
        assertTrue(emoji.toByteArray().size <= 100)
    }

    @Test
    fun neverEmpty() = assertEquals("Android phone", cleanName(" \u200B "))
}
