package org.kysecurity.drive.core

/**
 * Random reads over a remote file of [size] bytes, through a read-ahead window of at least
 * [window] bytes that [fetch] fills. Every read first asks [live]: once the session that opened
 * it is gone (unlink, revoke), cached bytes are dropped and nothing more is fetched or returned.
 */
class RangeReader(
    private val size: Long,
    private val window: Int,
    private val live: () -> Boolean,
    private val fetch: (offset: Long, length: Int) -> ByteArray,
) {
    private var start = 0L
    private var bytes = ByteArray(0)

    /** Copies up to [length] bytes at [offset] into [into]; returns how many (0 at the end). */
    fun read(offset: Long, length: Int, into: ByteArray): Int {
        check()
        if (offset >= size) return 0
        val want = minOf(length.toLong(), size - offset).toInt()
        if (offset < start || offset + want > start + bytes.size) {
            val got = fetch(offset, minOf(maxOf(want, window).toLong(), size - offset).toInt())
            check()
            if (got.size < want) throw TransferError("the server sent less than asked")
            start = offset
            bytes = got
        }
        System.arraycopy(bytes, (offset - start).toInt(), into, 0, want)
        return want
    }

    fun close() {
        bytes = ByteArray(0)
    }

    private fun check() {
        if (!live()) {
            close()
            throw TransferError("the session that opened this file has ended")
        }
    }
}
