package com.danielealbano.androidremotecontrolmcp.privacy.ner

import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory
import com.danielealbano.androidremotecontrolmcp.privacy.PiiDetection

/**
 * Decodes per-token BIO label predictions for one packed window into per-segment [PiiDetection]s.
 * Special tokens (null offset) are skipped; consecutive `B-`/`I-` tokens of the same entity are merged
 * (with standard `I-`-after-`O` repair); entities are mapped to [PiiCategory] (unmapped labels dropped);
 * each window-coordinate span is intersected with the segments' value ranges and clamped/rebased to
 * segment-relative offsets, emitting [PiiDetection.Source.MODEL].
 */
class BioDecoder {
    private data class Span(
        val category: PiiCategory,
        val start: Int,
        val end: Int,
    )

    fun decode(
        labelIds: IntArray,
        offsets: List<IntRange?>,
        id2label: Map<Int, String>,
        segmentRanges: List<SegmentRange>,
    ): Map<String, List<PiiDetection>> {
        val spans = mergeSpans(labelIds, offsets, id2label)
        val result = HashMap<String, MutableList<PiiDetection>>()
        for (span in spans) {
            for (range in segmentRanges) {
                val start = maxOf(span.start, range.valueStart)
                val end = minOf(span.end, range.valueEnd)
                if (start < end) {
                    result.getOrPut(range.key) { mutableListOf() } +=
                        PiiDetection(
                            span.category,
                            start - range.valueStart,
                            end - range.valueStart,
                            PiiDetection.Source.MODEL,
                        )
                }
            }
        }
        return result
    }

    private fun mergeSpans(
        labelIds: IntArray,
        offsets: List<IntRange?>,
        id2label: Map<Int, String>,
    ): List<Span> {
        val spans = mutableListOf<Span>()
        var category: PiiCategory? = null
        var start = -1
        var end = -1

        fun flush() {
            val current = category
            if (current != null) spans += Span(current, start, end)
            category = null
        }

        for (index in labelIds.indices) {
            val offset = offsets.getOrNull(index) ?: continue // special token: keep the current span open
            val label = id2label[labelIds[index]] ?: OUTSIDE
            val entity = label.substringAfter('-')
            val entityCategory = if (label == OUTSIDE) null else ENTITY_TO_CATEGORY[entity]
            if (entityCategory == null) {
                // OUTSIDE label or an ignored entity type closes any open span.
                flush()
            } else {
                val prefix = label.substringBefore('-')
                val tokenStart = offset.first
                val tokenEnd = offset.last + 1
                if (prefix == BEGIN || category != entityCategory) {
                    flush()
                    category = entityCategory
                    start = tokenStart
                    end = tokenEnd
                } else {
                    end = tokenEnd
                }
            }
        }
        flush()
        return spans
    }

    companion object {
        private const val OUTSIDE = "O"
        private const val BEGIN = "B"

        /**
         * Model entity name → protected [PiiCategory].
         * Unlisted entities (DATE/TIME/AGE/GENDER/SEX/TITLE) are ignored.
         */
        val ENTITY_TO_CATEGORY: Map<String, PiiCategory> =
            mapOf(
                "GIVENNAME" to PiiCategory.NAMES,
                "SURNAME" to PiiCategory.NAMES,
                "EMAIL" to PiiCategory.EMAILS,
                "TELEPHONENUM" to PiiCategory.PHONE_NUMBERS,
                "CREDITCARDNUMBER" to PiiCategory.CARDS_AND_IBAN,
                "STREET" to PiiCategory.ADDRESSES,
                "CITY" to PiiCategory.ADDRESSES,
                "ZIPCODE" to PiiCategory.ADDRESSES,
                "BUILDINGNUM" to PiiCategory.ADDRESSES,
                "SOCIALNUM" to PiiCategory.NATIONAL_IDS,
                "TAXNUM" to PiiCategory.NATIONAL_IDS,
                "PASSPORTNUM" to PiiCategory.NATIONAL_IDS,
                "DRIVERLICENSENUM" to PiiCategory.NATIONAL_IDS,
                "IDCARDNUM" to PiiCategory.NATIONAL_IDS,
            )
    }
}
