package com.danielealbano.androidremotecontrolmcp.benchmark.corpus

import java.util.Locale
import kotlin.random.Random

/** Generators for checksum-valid financial/identity values (cards, IBANs, national IDs, UUIDs). */
object IdValueGenerators {
    fun card(rng: Random): String {
        val (prefix, length) = CARD_SPECS[rng.nextInt(CARD_SPECS.size)]
        val body = StringBuilder(prefix)
        while (body.length < length - 1) {
            body.append(rng.nextInt(RandomText.DECIMAL))
        }
        body.append(luhnCheckDigit(body.toString()))
        return formatCard(body.toString(), rng)
    }

    /** Check digit for [payload] (PAN without its last digit): double from the rightmost payload digit. */
    private fun luhnCheckDigit(payload: String): Int {
        var sum = 0
        payload.reversed().forEachIndexed { index, char ->
            var digit = char - '0'
            if (index % 2 == 0) {
                digit *= 2
                if (digit > LUHN_NINE) digit -= LUHN_NINE
            }
            sum += digit
        }
        return (RandomText.DECIMAL - sum % RandomText.DECIMAL) % RandomText.DECIMAL
    }

    private fun formatCard(
        digits: String,
        rng: Random,
    ): String {
        val groups = if (digits.length == AMEX_LENGTH) AMEX_GROUPS else FOUR_GROUPS
        return when (rng.nextInt(CARD_FORMAT_COUNT)) {
            0 -> digits
            1 -> groupDigits(digits, groups, " ")
            else -> groupDigits(digits, groups, "-")
        }
    }

    private fun groupDigits(
        digits: String,
        groups: List<Int>,
        separator: String,
    ): String {
        val parts = mutableListOf<String>()
        var cursor = 0
        for (size in groups) {
            if (cursor >= digits.length) break
            val end = minOf(cursor + size, digits.length)
            parts += digits.substring(cursor, end)
            cursor = end
        }
        if (cursor < digits.length) parts += digits.substring(cursor)
        return parts.joinToString(separator)
    }

    fun iban(
        language: String,
        rng: Random,
    ): String {
        val (country, template) = IBAN_SPECS[language] ?: IBAN_SPECS.getValue("en")
        val bban = RandomText.fromPattern(rng, template)
        val check = IBAN_CHECK_BASE - mod97("$bban${country}00")
        val plain = "%s%02d%s".format(Locale.ROOT, country, check, bban)
        return if (rng.nextBoolean()) plain else plain.chunked(IBAN_GROUP).joinToString(" ")
    }

    private fun mod97(input: String): Int {
        var remainder = 0
        for (char in input) {
            val piece = if (char.isDigit()) (char - '0').toString() else ((char - 'A') + LETTER_BASE).toString()
            for (digit in piece) {
                remainder = (remainder * RandomText.DECIMAL + (digit - '0')) % IBAN_MOD_DIVISOR
            }
        }
        return remainder
    }

    fun nationalId(
        language: String,
        rng: Random,
    ): String =
        when (language) {
            "en" -> ssn(rng)
            "fr" -> RandomText.digits(rng, INSEE_DIGITS)
            "de" -> RandomText.digits(rng, STEUER_DIGITS)
            "es" -> dni(rng)
            "it" -> RandomText.fromPattern(rng, CF_PATTERN)
            "nl" -> RandomText.digits(rng, BSN_DIGITS)
            else -> RandomText.fromPattern(rng, PAN_ID_PATTERN)
        }

    private fun ssn(rng: Random): String {
        val area = SSN_AREA_MIN + rng.nextInt(SSN_AREA_RANGE)
        val group = 1 + rng.nextInt(SSN_GROUP_MAX)
        val serial = 1 + rng.nextInt(SSN_SERIAL_MAX)
        return "%03d-%02d-%04d".format(Locale.ROOT, area, group, serial)
    }

    private fun dni(rng: Random): String {
        val number = rng.nextInt(DNI_MAX)
        return "%08d%c".format(Locale.ROOT, number, DNI_LETTERS[number % DNI_LETTERS.length])
    }

    fun uuidLike(rng: Random): String = UUID_GROUP_SIZES.joinToString("-") { RandomText.hexGroup(rng, it) }

    private const val LUHN_NINE = 9
    private const val AMEX_LENGTH = 15
    private const val CARD_FORMAT_COUNT = 3
    private const val LETTER_BASE = 10
    private const val IBAN_MOD_DIVISOR = 97
    private const val IBAN_CHECK_BASE = 98
    private const val IBAN_GROUP = 4
    private const val SSN_AREA_MIN = 100
    private const val SSN_AREA_RANGE = 566
    private const val SSN_GROUP_MAX = 99
    private const val SSN_SERIAL_MAX = 9999
    private const val INSEE_DIGITS = 15
    private const val STEUER_DIGITS = 11
    private const val BSN_DIGITS = 9
    private const val DNI_MAX = 100_000_000
    private const val DNI_LETTERS = "TRWAGMYFPDXBNJZSQVHLCKE"
    private const val CF_PATTERN = "LLLLLLddLddLdddL"
    private const val PAN_ID_PATTERN = "LLLLLddddL"

    // MagicNumber: a plain object gets NO companion/const exemption for list literals, so every
    // element below is a named const (review finding P59-028).
    private const val GROUP_OF_FOUR = 4
    private const val AMEX_GROUP_MIDDLE = 6
    private const val AMEX_GROUP_TAIL = 5
    private const val UUID_GROUP_HEAD = 8
    private const val UUID_GROUP_TAIL = 12
    private const val PAN_LENGTH_STANDARD = 16
    private val UUID_GROUP_SIZES =
        listOf(UUID_GROUP_HEAD, GROUP_OF_FOUR, GROUP_OF_FOUR, GROUP_OF_FOUR, UUID_GROUP_TAIL)
    private val FOUR_GROUPS = listOf(GROUP_OF_FOUR, GROUP_OF_FOUR, GROUP_OF_FOUR, GROUP_OF_FOUR)
    private val AMEX_GROUPS = listOf(GROUP_OF_FOUR, AMEX_GROUP_MIDDLE, AMEX_GROUP_TAIL)
    private val CARD_SPECS =
        listOf(
            "4" to PAN_LENGTH_STANDARD,
            "51" to PAN_LENGTH_STANDARD,
            "34" to AMEX_LENGTH,
            "6011" to PAN_LENGTH_STANDARD,
            "5019" to PAN_LENGTH_STANDARD,
        )
    private val IBAN_SPECS =
        mapOf(
            "en" to ("GB" to "aaaadddddddddddddd"),
            "fr" to ("FR" to "ddddddddddaaaaaaaaaaadd"),
            "de" to ("DE" to "dddddddddddddddddd"),
            "es" to ("ES" to "dddddddddddddddddddd"),
            "it" to ("IT" to "addddddddddaaaaaaaaaaaa"),
            "nl" to ("NL" to "aaaadddddddddd"),
        )
}
