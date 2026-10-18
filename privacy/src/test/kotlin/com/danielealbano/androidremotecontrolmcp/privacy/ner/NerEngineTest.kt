package com.danielealbano.androidremotecontrolmcp.privacy.ner

import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory
import com.danielealbano.androidremotecontrolmcp.privacy.PiiDetection
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("NerEngine")
class NerEngineTest {
    private val inference = mockk<PiiModelInference>()
    private val engine = NerEngine(inference, NerCache())
    private val detections = listOf(PiiDetection(PiiCategory.NAMES, 0, 4, PiiDetection.Source.MODEL))

    @Test
    fun `cached segments are not re-inferred`() =
        runTest {
            val segment = NerSegment("k", "", "John Smith")
            coEvery { inference.infer(any()) } returns listOf(NerResult("k", detections))

            val first = engine.detect(listOf(segment))
            val second = engine.detect(listOf(segment))

            assertEquals(detections, first["k"])
            assertEquals(detections, second["k"])
            coVerify(exactly = 1) { inference.infer(any()) }
        }

    @Test
    fun `failure propagates`() =
        runTest {
            coEvery { inference.infer(any()) } throws PrivacyModelException("boom")

            var thrown: Throwable? = null
            try {
                engine.detect(listOf(NerSegment("k", "", "text")))
            } catch (e: PrivacyModelException) {
                thrown = e
            }
            assertNotNull(thrown)
        }
}
