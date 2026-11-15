package org.kysecurity.drive

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTest {
    @Test
    fun unknownOrMissingValuesFallBackToDefaults() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.of(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.of("sepia"))
        assertEquals(RailSide.RIGHT, RailSide.of(null))
        assertEquals(RailSide.RIGHT, RailSide.of("up"))
    }

    @Test
    fun storedKeysRoundTrip() {
        for (m in ThemeMode.entries) assertEquals(m, ThemeMode.of(m.key))
        for (s in RailSide.entries) assertEquals(s, RailSide.of(s.key))
    }
}
