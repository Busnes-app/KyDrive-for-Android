package org.kysecurity.drive.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RangeReaderTest {
    private val content = ByteArray(100) { it.toByte() }
    private val fetches = mutableListOf<Pair<Long, Int>>()
    private var live = true
    private val reader = RangeReader(content.size.toLong(), 32, { live }) { off, len ->
        fetches += off to len
        content.copyOfRange(off.toInt(), off.toInt() + len)
    }

    private fun read(offset: Long, length: Int): ByteArray {
        val buf = ByteArray(length)
        return buf.copyOf(reader.read(offset, length, buf))
    }

    @Test
    fun readsThroughAWindow() {
        assertArrayEquals(content.copyOfRange(0, 4), read(0, 4))
        assertArrayEquals(content.copyOfRange(4, 20), read(4, 16))
        assertEquals(listOf(0L to 32), fetches) // the second read was cached
        assertArrayEquals(content.copyOfRange(90, 100), read(90, 50)) // clipped at the end
        assertEquals(0, read(100, 8).size)
    }

    @Test
    fun anEndedSessionGetsNoCachedBytes() {
        read(0, 4)
        live = false
        assertThrows(TransferError::class.java) { read(4, 4) }
        assertEquals(1, fetches.size)
    }

    @Test
    fun anEndedSessionFetchesNothing() {
        live = false
        assertThrows(TransferError::class.java) { read(50, 4) }
        assertEquals(emptyList<Pair<Long, Int>>(), fetches)
    }

    @Test
    fun bytesFetchedAsTheSessionEndsAreNotReturned() {
        val racing = RangeReader(100, 32, { fetches.isEmpty() }) { off, len -> fetches += off to len; ByteArray(len) }
        assertThrows(TransferError::class.java) { racing.read(0, 4, ByteArray(4)) }
    }

    @Test
    fun aShortAnswerIsAnError() {
        val short = RangeReader(100, 32, { true }) { _, _ -> ByteArray(2) }
        assertThrows(TransferError::class.java) { short.read(0, 4, ByteArray(4)) }
    }
}
