package com.danielealbano.androidremotecontrolmcp.privacy.detectors

import com.danielealbano.androidremotecontrolmcp.privacy.DetectionContext
import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("NationalIdDetector")
class NationalIdDetectorTest {
    private val detector = NationalIdDetector()

    @Test
    fun `ssn shaped value with ssn context detected`() {
        val context = DetectionContext(fieldName = "ssn")
        val result = detector.detect("078-05-1120", context)

        assertEquals(1, result.size)
        assertEquals(PiiCategory.NATIONAL_IDS, result[0].category)
    }

    @Test
    fun `same value without context yields empty`() {
        val result = detector.detect("078-05-1120", DetectionContext.EMPTY)

        assertTrue(result.isEmpty())
    }
}
