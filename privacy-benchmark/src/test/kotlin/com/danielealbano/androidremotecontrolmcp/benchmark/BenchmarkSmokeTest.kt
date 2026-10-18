package com.danielealbano.androidremotecontrolmcp.benchmark

import com.danielealbano.androidremotecontrolmcp.benchmark.corpus.AdversarialCorpusLoader
import com.danielealbano.androidremotecontrolmcp.benchmark.scoring.BenchmarkReport
import com.danielealbano.androidremotecontrolmcp.benchmark.scoring.ReportWriter
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

/**
 * Real-model smoke test, gated on `PRIVACY_MODEL_DIR` (absolute dir containing `model_int8.onnx` +
 * `tokenizer.json`), mirroring the OrtPiiModelRunnerRealModelTest gating. The store is pointed at
 * the real model dir via symlinks so nothing is copied and nothing is written outside the temp dir.
 */
@DisplayName("Benchmark (real model smoke)")
class BenchmarkSmokeTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `corpus c runs through full layer with real model`() {
        val dir = System.getenv("PRIVACY_MODEL_DIR")
        assumeTrue(dir != null, "PRIVACY_MODEL_DIR not set")

        val modelDir = File(tempDir, "privacy_model")
        modelDir.mkdirs()
        Files.createSymbolicLink(
            File(modelDir, "model_int8.onnx").toPath(),
            File(dir, "model_int8.onnx").toPath(),
        )
        Files.createSymbolicLink(
            File(modelDir, "tokenizer.json").toPath(),
            File(dir, "tokenizer.json").toPath(),
        )
        val pipeline = BenchmarkPipeline(tempDir)
        pipeline.store.writeVerifiedMarker()
        val corpus = AdversarialCorpusLoader().load()

        runBlocking {
            assertTrue(pipeline.runner.warmUp().isSuccess, "warm-up failed")
            val (predictions, redacted) = pipeline.detectAndRedactFull(corpus.samples)
            pipeline.runner.close()

            assertEquals(corpus.samples.size, predictions.size)
            assertEquals(corpus.samples.size, redacted.size)
        }

        val outDir = File(tempDir, "report")
        ReportWriter().write(
            outDir,
            BenchmarkReport(
                generatedAtIso = "smoke",
                datasetCommit = BenchmarkAssets.DATASET_COMMIT,
                datasetSha256 = BenchmarkAssets.DATASET.sha256,
                modelSha256 = BenchmarkAssets.MODEL.sha256,
                tokenizerSha256 = BenchmarkAssets.TOKENIZER.sha256,
                seed = 0L,
                corpora = emptyList(),
            ),
        )
        assertTrue(File(outDir, "report.md").exists(), "report not writable")
    }
}
