package com.danielealbano.androidremotecontrolmcp.benchmark.corpus

import kotlin.random.Random

/** Seeded random-text primitives shared by the corpus value generators. */
object RandomText {
    /** `d` → random digit, anything else → random uppercase letter. */
    fun fromPattern(
        rng: Random,
        pattern: String,
    ): String =
        pattern
            .map { symbol -> if (symbol == 'd') '0' + rng.nextInt(DECIMAL) else 'A' + rng.nextInt(ALPHABET) }
            .joinToString("")

    fun fromAlphabet(
        rng: Random,
        alphabet: String,
        count: Int,
    ): String = buildString { repeat(count) { append(alphabet[rng.nextInt(alphabet.length)]) } }

    fun digits(
        rng: Random,
        count: Int,
    ): String = buildString { repeat(count) { append(rng.nextInt(DECIMAL)) } }

    // First char forced to a-f: an all-digit group would let CardDetector's dash-tolerant digit-run
    // regex see a >= 12-digit Luhn candidate across the dashes.
    fun hexGroup(
        rng: Random,
        size: Int,
    ): String =
        buildString {
            append('a' + rng.nextInt(HEX_LETTERS))
            repeat(size - 1) { append(HEX_CHARS[rng.nextInt(HEX_CHARS.length)]) }
        }

    const val DECIMAL = 10
    const val ALPHABET = 26
    const val ALNUM_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    const val UPPER_ALNUM_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private const val HEX_LETTERS = 6
    private const val HEX_CHARS = "0123456789abcdef"
}
