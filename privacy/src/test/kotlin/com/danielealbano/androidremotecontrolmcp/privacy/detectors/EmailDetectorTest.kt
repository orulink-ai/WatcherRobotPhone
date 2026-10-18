package com.danielealbano.androidremotecontrolmcp.privacy.detectors

import com.danielealbano.androidremotecontrolmcp.privacy.DetectionContext
import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("EmailDetector")
class EmailDetectorTest {
    private val detector = EmailDetector()

    @Test
    fun `plain subdomain and plus tag detected`() {
        listOf(
            "john@example.com",
            "jane.doe@mail.corp.example.co",
            "user+tag@example.org",
        ).forEach { email ->
            val result = detector.detect("contact $email please", DetectionContext.EMPTY)
            assertEquals(1, result.size, "expected $email to be detected")
            assertEquals(PiiCategory.EMAILS, result[0].category)
            assertEquals(email, "contact $email please".substring(result[0].start, result[0].end))
        }
    }

    @Test
    fun `incomplete address rejected`() {
        assertTrue(detector.detect("not@an", DetectionContext.EMPTY).isEmpty())
    }
}
