package com.danielealbano.androidremotecontrolmcp.privacy.detectors

import com.danielealbano.androidremotecontrolmcp.privacy.DetectionContext
import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("CardDetector")
class CardDetectorTest {
    private val detector = CardDetector()

    @Test
    fun `visa 16 digit with spaces detected`() {
        val result = detector.detect("pay 4111 1111 1111 1111 now", DetectionContext.EMPTY)

        assertEquals(1, result.size)
        assertEquals(PiiCategory.CARDS_AND_IBAN, result[0].category)
        assertEquals("4111 1111 1111 1111", "pay 4111 1111 1111 1111 now".substring(result[0].start, result[0].end))
    }

    @Test
    fun `amex 15 digit detected`() {
        val result = detector.detect("card 378282246310005", DetectionContext.EMPTY)

        assertEquals(1, result.size)
    }

    @Test
    fun `luhn invalid run not detected`() {
        val result = detector.detect("4111 1111 1111 1112", DetectionContext.EMPTY)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `19 and 12 digit boundaries`() {
        assertEquals(1, detector.detect("0".repeat(12), DetectionContext.EMPTY).size)
        assertEquals(1, detector.detect("0".repeat(19), DetectionContext.EMPTY).size)
        assertTrue(detector.detect("0".repeat(11), DetectionContext.EMPTY).isEmpty())
        assertTrue(detector.detect("0".repeat(20), DetectionContext.EMPTY).isEmpty())
    }

    @Test
    fun `negative context suppresses`() {
        val context = DetectionContext(fieldName = "tracking number")
        val result = detector.detect("4111 1111 1111 1111", context)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `negative plus positive context keeps`() {
        val context = DetectionContext(fieldName = "order card number")
        val result = detector.detect("4111 1111 1111 1111", context)

        assertEquals(1, result.size)
    }
}
