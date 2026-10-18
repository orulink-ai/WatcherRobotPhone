package com.danielealbano.androidremotecontrolmcp.privacy.tokenizer

import java.util.regex.Pattern

/**
 * GPT-2 byte-level pre-tokenizer: splits text into pieces using the standard contraction/word/number
 * pattern. On the desktop JVM the pattern needs [Pattern.UNICODE_CHARACTER_CLASS]; Android's ICU
 * backend rejects that flag but is Unicode-aware by default, so the flag is applied via try/catch.
 */
class BpePreTokenizer {
    /** A pre-token piece with its char offsets within the input string. */
    data class Piece(
        val text: String,
        val start: Int,
        val end: Int,
    )

    fun split(text: String): List<Piece> {
        val pieces = mutableListOf<Piece>()
        val matcher = PATTERN.matcher(text)
        while (matcher.find()) {
            pieces += Piece(matcher.group(), matcher.start(), matcher.end())
        }
        return pieces
    }

    companion object {
        private const val REGEX =
            "'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+"

        private val PATTERN: Pattern =
            try {
                Pattern.compile(REGEX, Pattern.UNICODE_CHARACTER_CLASS)
            } catch (_: IllegalArgumentException) {
                // Android rejects UNICODE_CHARACTER_CLASS but its ICU regex is Unicode-aware by default.
                Pattern.compile(REGEX)
            }
    }
}
