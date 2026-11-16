package org.kysecurity.drive.lock

/** PIN rules, from KyPost-for-Android: the Keystore pepper forces on-device guessing, and 10^6 is only hours. */
object PinPolicy {
    const val MIN_LENGTH = 8
    const val MAX_LENGTH = 12

    // A nudge against the PINs people reach for first, not a control: the cost of guessing is
    // the KDF and the pepper. Runs and repeats of any length are caught by isRun.
    private val WEAK = setOf(
        "12121212", "21212121", "11223344", "44332211", "12341234", "43214321",
        "10101010", "01010101", "12002100", "13131313", "69696969",
        "14725836", "36925814", "15935780", "78945612", "95135780",
        "19701970", "19801980", "19901990", "20002000", "20102010", "20202020",
        "01011990", "01012000",
    )

    enum class Result { VALID, TOO_SHORT, TOO_LONG, NOT_DIGITS, TOO_COMMON }

    /** Compared in place: the PIN stays a CharArray so it can be zeroed, never a String. */
    fun validate(pin: CharArray): Result = when {
        pin.size < MIN_LENGTH -> Result.TOO_SHORT
        pin.size > MAX_LENGTH -> Result.TOO_LONG
        // ASCII only: Char.isDigit() also accepts other scripts' digits.
        !pin.all { it in '0'..'9' } -> Result.NOT_DIGITS
        WEAK.any { w -> w.length == pin.size && w.indices.all { w[it] == pin[it] } } -> Result.TOO_COMMON
        isRun(pin) -> Result.TOO_COMMON
        else -> Result.VALID
    }

    private fun isRun(pin: CharArray): Boolean {
        val steps = pin.toList().zipWithNext { a, b -> b - a }
        return steps.all { it == 1 } || steps.all { it == -1 } || steps.all { it == 0 }
    }
}

/** From KyPost-for-Android: two free tries, then a delay ladder up to an hour; the erase is opt-out. */
object LockoutPolicy {
    private val DELAYS_MS = longArrayOf(30_000, 60_000, 300_000, 900_000, 1_800_000, 3_600_000)
    private const val FIRST_DELAYED = 3

    val WIPE_CHOICES = listOf(10, 20, 30)
    const val DEFAULT_WIPE = 30

    fun delayFor(attempts: Int): Long =
        if (attempts < FIRST_DELAYED) 0 else DELAYS_MS[(attempts - FIRST_DELAYED).coerceAtMost(DELAYS_MS.size - 1)]

    /** [wipeAfter] null: the user turned the erase off. */
    fun shouldWipe(attempts: Int, wipeAfter: Int?) = wipeAfter != null && attempts >= wipeAfter

    /** More tries before the erase, or none, is the weaker setting and needs the PIN. */
    fun weakens(from: Int?, to: Int?) = from != null && (to == null || to > from)
}
