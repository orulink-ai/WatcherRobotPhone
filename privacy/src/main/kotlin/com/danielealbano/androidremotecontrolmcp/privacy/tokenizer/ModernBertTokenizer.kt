package com.danielealbano.androidremotecontrolmcp.privacy.tokenizer

import java.io.File
import java.text.Normalizer

/**
 * Purpose-built, on-device tokenizer for the ai4privacy ModernBERT model. The pipeline is hardcoded
 * (NFC normalize → added-token longest-match split → GPT-2 byte-level BPE → `[CLS] … [SEP]` →
 * truncate); only the data (vocab/merges/added tokens) comes from `tokenizer.json`.
 *
 * [encode] returns token ids plus, for every content token, the ORIGINAL-string UTF-16 char range it
 * covers (null for `[CLS]`/`[SEP]`). When NFC changes the string, offsets are mapped back to the
 * original via a per-unit alignment built during normalization.
 */
class ModernBertTokenizer(
    private val data: TokenizerData,
) {
    /** Encoded output: [ids] and matching [offsets] (null for the `[CLS]`/`[SEP]` special tokens). */
    data class Encoding(
        val ids: IntArray,
        val offsets: List<IntRange?>,
    ) {
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Encoding && ids.contentEquals(other.ids) && offsets == other.offsets)

        override fun hashCode(): Int = 31 * ids.contentHashCode() + offsets.hashCode()
    }

    private val preTokenizer = BpePreTokenizer()
    private val splitter = AddedTokenSplitter(data.addedTokens)

    fun encode(text: String): Encoding {
        val norm = normalize(text)
        val ids = mutableListOf<Int>()
        val offsets = mutableListOf<IntRange?>()
        ids += data.clsId
        offsets += null
        for (segment in splitter.split(norm.text)) {
            when (segment) {
                is AddedTokenSplitter.Segment.Raw -> {
                    encodeRaw(norm, segment.start, segment.end, ids, offsets)
                }

                is AddedTokenSplitter.Segment.Token -> {
                    ids += segment.id
                    offsets += norm.aStart[segment.start] until norm.aEnd[segment.end - 1]
                }
            }
        }
        ids += data.sepId
        offsets += null
        return truncate(ids, offsets)
    }

    private fun truncate(
        ids: MutableList<Int>,
        offsets: MutableList<IntRange?>,
    ): Encoding {
        if (ids.size <= data.maxLength) return Encoding(ids.toIntArray(), offsets.toList())
        val keep = data.maxLength - 1
        val truncIds = ids.subList(0, keep).toMutableList().apply { add(data.sepId) }
        val truncOffsets = offsets.subList(0, keep).toMutableList().apply { add(null) }
        return Encoding(truncIds.toIntArray(), truncOffsets.toList())
    }

    private fun encodeRaw(
        norm: Norm,
        rawStart: Int,
        rawEnd: Int,
        ids: MutableList<Int>,
        offsets: MutableList<IntRange?>,
    ) {
        val sub = norm.text.substring(rawStart, rawEnd)
        for (piece in preTokenizer.split(sub)) {
            encodePiece(norm, rawStart + piece.start, rawStart + piece.end, ids, offsets)
        }
    }

    private fun encodePiece(
        norm: Norm,
        pieceStart: Int,
        pieceEnd: Int,
        ids: MutableList<Int>,
        offsets: MutableList<IntRange?>,
    ) {
        val mapped = StringBuilder()
        val byteOrigStart = mutableListOf<Int>()
        val byteOrigEnd = mutableListOf<Int>()
        var unit = pieceStart
        while (unit < pieceEnd) {
            val cp = norm.text.codePointAt(unit)
            val units = Character.charCount(cp)
            val originStart = norm.aStart[unit]
            val originEnd = norm.aEnd[unit + units - 1]
            for (byte in String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)) {
                mapped.append(ByteLevelMapping.byteToChar[byte.toInt() and BYTE_MASK])
                byteOrigStart += originStart
                byteOrigEnd += originEnd
            }
            unit += units
        }
        var byteIndex = 0
        for (token in bpe(mapped.toString())) {
            val start = byteIndex
            val end = byteIndex + token.length
            val id = data.vocab[token] ?: error("No vocab id for byte-mapped token '$token'")
            ids += id
            offsets += byteOrigStart[start] until byteOrigEnd[end - 1]
            byteIndex = end
        }
    }

    private fun bpe(word: String): List<String> {
        val parts = word.map(Char::toString).toMutableList()
        while (parts.size > 1) {
            var bestRank = Int.MAX_VALUE
            var bestIdx = -1
            for (i in 0 until parts.size - 1) {
                val rank = data.mergeRanks[parts[i] to parts[i + 1]]
                if (rank != null && rank < bestRank) {
                    bestRank = rank
                    bestIdx = i
                }
            }
            if (bestIdx < 0) break
            parts[bestIdx] = parts[bestIdx] + parts[bestIdx + 1]
            parts.removeAt(bestIdx + 1)
        }
        return parts
    }

    /** NFC-normalized text plus a per-UTF16-unit alignment back to original char ranges. */
    private class Norm(
        val text: String,
        val aStart: IntArray,
        val aEnd: IntArray,
    )

    private fun normalize(original: String): Norm {
        val norm = StringBuilder()
        val aStart = mutableListOf<Int>()
        val aEnd = mutableListOf<Int>()
        var i = 0
        while (i < original.length) {
            val clusterStart = i
            val base = original.codePointAt(i)
            i += Character.charCount(base)
            while (i < original.length) {
                val next = original.codePointAt(i)
                if (!isCombiningMark(next)) break
                i += Character.charCount(next)
            }
            val cluster = original.substring(clusterStart, i)
            val nfc = Normalizer.normalize(cluster, Normalizer.Form.NFC)
            if (nfc == cluster) {
                for (u in nfc.indices) {
                    aStart += clusterStart + u
                    aEnd += clusterStart + u + 1
                }
            } else {
                val firstLen = Character.charCount(original.codePointAt(clusterStart))
                for (u in nfc.indices) {
                    aStart += clusterStart
                    aEnd += clusterStart + firstLen
                }
            }
            norm.append(nfc)
        }
        return Norm(norm.toString(), aStart.toIntArray(), aEnd.toIntArray())
    }

    private fun isCombiningMark(cp: Int): Boolean =
        when (Character.getType(cp)) {
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
            -> true

            else -> false
        }

    companion object {
        private const val BYTE_MASK = 0xFF

        fun fromFile(file: File) = file.inputStream().use { ModernBertTokenizer(TokenizerData.fromStream(it)) }
    }
}
