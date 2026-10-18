package com.danielealbano.androidremotecontrolmcp.privacy.ner

import com.danielealbano.androidremotecontrolmcp.privacy.tokenizer.ModernBertTokenizer

/** The value region of one segment inside a packed window (window-relative char offsets). */
data class SegmentRange(
    val key: String,
    val valueStart: Int,
    val valueEnd: Int,
    val truncated: Boolean,
)

/** A packed inference window: the joined [text] plus the value ranges of the segments it contains. */
data class PackedWindow(
    val text: String,
    val segmentRanges: List<SegmentRange>,
)

/**
 * Packs many short segments into a few small windows to amortize inference (quadratic attention makes
 * one big window far slower than the same content split into ≤256-token windows). Each segment's
 * constructed string is `"<context>: <value>"` (or just the value when context is blank); the packer
 * records the VALUE char range so detections landing in the injected context prefix can be dropped.
 * A single segment whose constructed form exceeds the model's token cap is flagged [SegmentRange.truncated].
 */
class WindowPacker(
    private val tokenizer: ModernBertTokenizer,
) {
    private data class Prepared(
        val key: String,
        val constructed: String,
        val valuePrefixLen: Int,
        val contentTokens: Int,
        val truncated: Boolean,
    )

    fun pack(segments: List<NerSegment>): List<PackedWindow> {
        val windows = mutableListOf<PackedWindow>()
        val current = mutableListOf<Prepared>()
        var currentTokens = 0

        fun flush() {
            if (current.isEmpty()) return
            val builder = StringBuilder()
            val ranges = mutableListOf<SegmentRange>()
            current.forEachIndexed { index, prepared ->
                if (index > 0) builder.append(SEPARATOR)
                val offset = builder.length
                builder.append(prepared.constructed)
                ranges +=
                    SegmentRange(
                        key = prepared.key,
                        valueStart = offset + prepared.valuePrefixLen,
                        valueEnd = offset + prepared.constructed.length,
                        truncated = prepared.truncated,
                    )
            }
            windows += PackedWindow(builder.toString(), ranges)
            current.clear()
            currentTokens = 0
        }

        for (segment in segments) {
            val prepared = prepare(segment)
            val separatorCost = if (current.isEmpty()) 0 else 1
            val projectedTokens = currentTokens + prepared.contentTokens + separatorCost
            if (current.isNotEmpty() && projectedTokens > MAX_WINDOW_CONTENT_TOKENS) {
                flush()
            }
            val cost = prepared.contentTokens + if (current.isEmpty()) 0 else 1
            current += prepared
            currentTokens += cost
        }
        flush()
        return windows
    }

    private fun prepare(segment: NerSegment): Prepared {
        val constructed =
            if (segment.context.isBlank()) segment.text else "${segment.context.trim()}: ${segment.text}"
        val valuePrefixLen = constructed.length - segment.text.length
        val encoded = tokenizer.encode(constructed)
        return Prepared(
            key = segment.key,
            constructed = constructed,
            valuePrefixLen = valuePrefixLen,
            contentTokens = (encoded.ids.size - SPECIAL_TOKENS).coerceAtLeast(0),
            truncated = encoded.ids.size >= MAX_MODEL_TOKENS,
        )
    }

    companion object {
        const val MAX_WINDOW_CONTENT_TOKENS = 254
        const val MAX_MODEL_TOKENS = 1536
        private const val SPECIAL_TOKENS = 2
        private const val SEPARATOR = "\n"
    }
}
