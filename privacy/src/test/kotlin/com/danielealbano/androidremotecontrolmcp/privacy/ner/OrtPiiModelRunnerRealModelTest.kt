package com.danielealbano.androidremotecontrolmcp.privacy.ner

import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory
import com.danielealbano.androidremotecontrolmcp.privacy.model.PrivacyModelStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.system.measureNanoTime

/**
 * Real-model test, gated on `PRIVACY_MODEL_DIR` (absolute dir containing `model_int8.onnx` +
 * `tokenizer.json`). Skipped when the env var is unset, mirroring the ngrok integration test.
 */
@DisplayName("OrtPiiModelRunner (real model)")
class OrtPiiModelRunnerRealModelTest {
    private fun runnerOrSkip(): OrtPiiModelRunner {
        val dir = System.getenv("PRIVACY_MODEL_DIR")
        assumeTrue(dir != null, "PRIVACY_MODEL_DIR not set")
        val store = mockk<PrivacyModelStore>()
        every { store.isReady() } returns true
        every { store.modelFile() } returns File(dir, "model_int8.onnx")
        every { store.tokenizerFile() } returns File(dir, "tokenizer.json")
        return OrtPiiModelRunner(store)
    }

    @Test
    fun `real model detects name and email`() =
        runBlocking {
            val runner = runnerOrSkip()
            val text = "My name is Sarah Connor and my email is sarah.connor@example.com"

            val results = runner.infer(listOf(NerSegment("k", "", text)))
            runner.close()

            val categories =
                results
                    .single()
                    .detections
                    .map { it.category }
                    .toSet()
            assertTrue(PiiCategory.NAMES in categories, "expected a NAMES detection")
            assertTrue(PiiCategory.EMAILS in categories, "expected an EMAILS detection")
        }

    @Test
    fun `real model warmUp succeeds`() =
        runBlocking {
            val runner = runnerOrSkip()

            val result = runner.warmUp()
            runner.close()

            assertTrue(result.isSuccess)
        }

    @Test
    fun `real model measures window latency`() =
        runBlocking {
            val runner = runnerOrSkip()
            val segments = (1..30).map { NerSegment("k$it", "", "Contact John Smith at 42 Baker Street number $it") }

            runner.infer(segments) // warm up
            val timings =
                (1..3).map { measureNanoTime { runner.infer(segments) } / 1_000_000.0 }
            runner.close()

            println("Real-model inference over ${segments.size} segments: median ${timings.sorted()[1]} ms")
            assertTrue(timings.all { it >= 0 })
        }
}
