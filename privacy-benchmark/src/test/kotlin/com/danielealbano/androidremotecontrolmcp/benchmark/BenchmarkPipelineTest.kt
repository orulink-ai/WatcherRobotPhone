package com.danielealbano.androidremotecontrolmcp.benchmark

import com.danielealbano.androidremotecontrolmcp.benchmark.corpus.BenchmarkSample
import com.danielealbano.androidremotecontrolmcp.privacy.DetectionContext
import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory
import com.danielealbano.androidremotecontrolmcp.privacy.PiiDetection
import com.danielealbano.androidremotecontrolmcp.privacy.ner.NerResult
import com.danielealbano.androidremotecontrolmcp.privacy.ner.NerSegment
import com.danielealbano.androidremotecontrolmcp.privacy.ner.PiiModelInference
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@DisplayName("BenchmarkPipeline")
class BenchmarkPipelineTest {
    @TempDir
    lateinit var tempDir: File

    private class FakeInference(
        private val handler: (NerSegment) -> List<PiiDetection>,
    ) : PiiModelInference {
        var invocations = 0

        override suspend fun infer(segments: List<NerSegment>): List<NerResult> {
            invocations++
            return segments.map { NerResult(it.key, handler(it)) }
        }
    }

    private fun sample(
        id: String,
        text: String,
    ) = BenchmarkSample(id, text, DetectionContext.EMPTY, emptyList(), "en")

    @Test
    fun `deterministic layer detects email without model`() =
        runTest {
            val fake = FakeInference { emptyList() }
            val pipeline = BenchmarkPipeline(tempDir, fake)

            val detections = pipeline.detect(Layer.DETERMINISTIC, listOf(sample("s1", "mail a@b.com now")))

            assertEquals(PiiCategory.EMAILS, detections.single().single().category)
            assertEquals(0, fake.invocations)
        }

    @Test
    fun `model layer maps chunked predictions back to samples`() =
        runTest {
            val fake =
                FakeInference { segment ->
                    listOf(PiiDetection(PiiCategory.NAMES, 0, segment.text.length, PiiDetection.Source.MODEL))
                }
            val pipeline = BenchmarkPipeline(tempDir, fake)
            val samples = listOf(sample("s1", "Sarah"), sample("s2", "   "), sample("s3", "Miguel"))

            val detections = pipeline.detect(Layer.MODEL, samples)

            assertEquals(3, detections.size)
            assertEquals("Sarah".length, detections[0].single().end)
            assertTrue(detections[1].isEmpty())
            assertEquals("Miguel".length, detections[2].single().end)
        }

    @Test
    fun `model layer keying survives multiple chunks`() =
        runTest {
            val fake =
                FakeInference { segment ->
                    listOf(PiiDetection(PiiCategory.NAMES, 0, segment.text.length, PiiDetection.Source.MODEL))
                }
            val pipeline = BenchmarkPipeline(tempDir, fake)
            val samples = (0..BenchmarkPipeline.CHUNK).map { index -> sample("s$index", "P".repeat(index + 1)) }

            val detections = pipeline.detect(Layer.MODEL, samples)

            assertEquals(samples.size, detections.size)
            samples.forEachIndexed { index, s ->
                assertEquals(s.text.length, detections[index].single().end, "wrong mapping at $index")
            }
        }

    @Test
    fun `full layer merges deterministic and model and redacts`() =
        runTest {
            val fake =
                FakeInference { segment ->
                    if (segment.text.startsWith("Sarah")) {
                        listOf(PiiDetection(PiiCategory.NAMES, 0, "Sarah".length, PiiDetection.Source.MODEL))
                    } else {
                        emptyList()
                    }
                }
            val pipeline = BenchmarkPipeline(tempDir, fake)
            val samples = listOf(sample("s1", "Sarah a@b.com"))

            val (detections, redacted) = pipeline.detectAndRedactFull(samples)

            val categories = detections.single().map { it.category }.toSet()
            assertTrue(PiiCategory.NAMES in categories, "expected NAMES in $categories")
            assertTrue(PiiCategory.EMAILS in categories, "expected EMAILS in $categories")
            assertFalse(redacted.single().contains("Sarah"), "name leaked: ${redacted.single()}")
            assertFalse(redacted.single().contains("a@b.com"), "email leaked: ${redacted.single()}")
        }
}
