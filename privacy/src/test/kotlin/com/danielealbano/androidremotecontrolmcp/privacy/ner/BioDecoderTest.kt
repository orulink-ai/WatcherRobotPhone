package com.danielealbano.androidremotecontrolmcp.privacy.ner

import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("BioDecoder")
class BioDecoderTest {
    private val decoder = BioDecoder()
    private val id2label = OrtPiiModelRunner.ID2LABEL
    private val labels = id2label.entries.associate { (id, label) -> label to id }

    private fun id(label: String): Int = requireNotNull(labels[label])

    private fun ranges(
        key: String,
        start: Int,
        end: Int,
    ) = listOf(SegmentRange(key, start, end, truncated = false))

    @Test
    fun `B and I merge into one span`() {
        val labelIds = intArrayOf(id("O"), id("B-CITY"), id("I-CITY"), id("O"))
        val offsets = listOf(null, 0..2, 3..7, null)

        val result = decoder.decode(labelIds, offsets, id2label, ranges("k", 0, 8))

        assertEquals(1, result.getValue("k").size)
        assertEquals(PiiCategory.ADDRESSES, result.getValue("k")[0].category)
        assertEquals(0, result.getValue("k")[0].start)
        assertEquals(8, result.getValue("k")[0].end)
    }

    @Test
    fun `I after O starts a new span`() {
        val labelIds = intArrayOf(id("B-GIVENNAME"), id("O"), id("I-GIVENNAME"))
        val offsets = listOf(0..3, 4..4, 5..8)

        val result = decoder.decode(labelIds, offsets, id2label, ranges("k", 0, 9))

        assertEquals(2, result.getValue("k").size)
    }

    @Test
    fun `special tokens skipped`() {
        val labelIds = intArrayOf(id("B-EMAIL"), id("I-EMAIL"))
        val offsets = listOf(null, null) // both treated as special (null offsets)

        val result = decoder.decode(labelIds, offsets, id2label, ranges("k", 0, 10))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `unmapped label dropped`() {
        val labelIds = intArrayOf(id("B-DATE"), id("I-DATE"))
        val offsets = listOf(0..3, 4..7)

        val result = decoder.decode(labelIds, offsets, id2label, ranges("k", 0, 8))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `span crossing segment boundary clamped`() {
        val labelIds = intArrayOf(id("B-GIVENNAME"), id("I-GIVENNAME"))
        val offsets = listOf(0..3, 4..19) // window span [0, 20)

        // Value region only covers [5, 10).
        val result = decoder.decode(labelIds, offsets, id2label, ranges("k", 5, 10))

        val detection = result.getValue("k")[0]
        assertEquals(0, detection.start) // 5 - 5
        assertEquals(5, detection.end) // 10 - 5
    }

    @Test
    fun `givenname surname adjacency stays two detections`() {
        val labelIds = intArrayOf(id("B-GIVENNAME"), id("B-SURNAME"))
        val offsets = listOf(0..4, 6..11)

        val result = decoder.decode(labelIds, offsets, id2label, ranges("k", 0, 12))

        val detections = result.getValue("k")
        assertEquals(2, detections.size)
        assertTrue(detections[0].start != detections[1].start)
        detections.forEach { assertEquals(PiiCategory.NAMES, it.category) }
    }
}
