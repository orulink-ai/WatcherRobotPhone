package com.danielealbano.androidremotecontrolmcp.privacy.detectors

import com.danielealbano.androidremotecontrolmcp.privacy.DetectionContext
import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("CredentialDetector")
class CredentialDetectorTest {
    private val detector = CredentialDetector()

    @Test
    fun `isPassword flags whole text`() {
        val result = detector.detect("hunter2", DetectionContext(isPassword = true))

        assertEquals(1, result.size)
        assertEquals(PiiCategory.CREDENTIALS, result[0].category)
        assertEquals(0, result[0].start)
        assertEquals("hunter2".length, result[0].end)
    }

    @Test
    fun `editable field with credential keyword flags whole text`() {
        val context = DetectionContext(isEditable = true, fieldName = "password")
        val result = detector.detect("hunter2", context)

        assertEquals(1, result.size)
    }

    @Test
    fun `non-editable keyword only yields empty`() {
        val context = DetectionContext(isEditable = false, fieldName = "password")
        val result = detector.detect("hunter2", context)

        assertTrue(result.isEmpty())
    }
}
