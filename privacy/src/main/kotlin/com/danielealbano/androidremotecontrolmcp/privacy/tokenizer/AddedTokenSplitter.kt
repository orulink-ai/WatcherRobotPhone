package com.danielealbano.androidremotecontrolmcp.privacy.tokenizer

/**
 * Splits a (normalized) string into segments by greedily longest-matching the [AddedToken]s before
 * BPE. Whitespace is folded into an added token per its [AddedToken.lstrip]/[AddedToken.rstrip]
 * flags. All indices are UTF-16 offsets into the input string.
 */
class AddedTokenSplitter(
    addedTokens: List<AddedToken>,
) {
    private val sorted = addedTokens.sortedByDescending { it.content.length }

    sealed class Segment {
        /** A run of raw text to be pre-tokenized and BPE-encoded. */
        data class Raw(
            val start: Int,
            val end: Int,
        ) : Segment()

        /** A matched added token, spanning `[start, end)` (after lstrip/rstrip whitespace folding). */
        data class Token(
            val id: Int,
            val start: Int,
            val end: Int,
        ) : Segment()
    }

    fun split(text: String): List<Segment> {
        val segments = mutableListOf<Segment>()
        var pos = 0
        var segStart = 0
        while (pos < text.length) {
            val match = sorted.firstOrNull { text.startsWith(it.content, pos) }
            if (match == null) {
                pos++
                continue
            }
            var start = pos
            if (match.lstrip) {
                while (segStart < start && text[start - 1].isWhitespace()) start--
            }
            if (segStart < start) segments += Segment.Raw(segStart, start)
            var end = pos + match.content.length
            if (match.rstrip) {
                while (end < text.length && text[end].isWhitespace()) end++
            }
            segments += Segment.Token(match.id, start, end)
            pos = end
            segStart = pos
        }
        if (segStart < text.length) segments += Segment.Raw(segStart, text.length)
        return segments
    }
}
