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

/**
 * Real-model test, gated on `PRIVACY_MODEL_DIR` like [OrtPiiModelRunnerRealModelTest]: detections
 * MUST survive window packing — a name in a segment packed AFTER other segments must still be found
 * and reported against ITS segment, identically to running that segment alone.
 */
@DisplayName("OrtPiiModelRunner (real model, packing)")
class OrtPiiModelRunnerPackingRealModelTest {
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
    fun `packed segments keep per-segment name detections`() =
        runBlocking {
            val runner = runnerOrSkip()
            val texts =
                listOf(
                    "The invoice was approved yesterday afternoon",
                    "Please call John Smith tomorrow morning",
                    "My name is Sarah Connor and I live in Berlin",
                    "Der Vertrag wurde von Jonas Becker unterschrieben",
                )
            val segments = texts.mapIndexed { index, text -> NerSegment("k$index", "", text) }

            val alone =
                segments.associate { segment ->
                    segment.key to runner.infer(listOf(segment)).single().detections
                }
            val packed = runner.infer(segments).associate { it.key to it.detections }
            runner.close()

            for (segment in segments) {
                val aloneNames = alone.getValue(segment.key).count { it.category == PiiCategory.NAMES }
                val packedNames = packed.getValue(segment.key).count { it.category == PiiCategory.NAMES }
                assertTrue(
                    packedNames >= aloneNames,
                    "segment ${segment.key} ('${segment.text}') lost NAMES when packed: " +
                        "alone=$aloneNames packed=$packedNames " +
                        "(alone=${alone.getValue(segment.key)}, packed=${packed.getValue(segment.key)})",
                )
            }
        }
}
