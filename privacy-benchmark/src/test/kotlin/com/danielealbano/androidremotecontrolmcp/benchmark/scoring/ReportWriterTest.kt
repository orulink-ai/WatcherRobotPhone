package com.danielealbano.androidremotecontrolmcp.benchmark.scoring

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@DisplayName("ReportWriter")
class ReportWriterTest {
    @TempDir
    lateinit var tempDir: File

    private fun report(): BenchmarkReport {
        val category =
            CategoryScore(
                category = "EMAILS",
                goldSpans = 10,
                partial = MetricValues.from(tp = 9, fp = 1, fn = 1),
                strict = MetricValues.from(tp = 8, fp = 2, fn = 2),
                goldChars = 100,
                leakedChars = 12,
                leakRate = 0.123,
            )
        val layer =
            LayerScore(
                layer = "full",
                samples = 10,
                durationMs = 42,
                categories = listOf(category),
                microPartial = MetricValues.from(tp = 9, fp = 1, fn = 1),
                microStrict = MetricValues.from(tp = 8, fp = 2, fn = 2),
                macroPartialF1 = 0.9,
                macroFBeta2 = 0.9,
                leakRate = 0.123,
                residualValueLeaks = 1,
                perLanguage =
                    listOf(
                        LanguageScore("en", 5, MetricValues.from(5, 0, 0), 0.0),
                        LanguageScore("fr", 5, MetricValues.from(4, 1, 1), 0.2),
                    ),
            )
        return BenchmarkReport(
            generatedAtIso = "2026-08-03T00:00:00Z",
            datasetCommit = "506996d6",
            datasetSha256 = "datasetsha",
            modelSha256 = "modelsha",
            tokenizerSha256 = "tokenizersha",
            seed = 20260803L,
            corpora = listOf(CorpusScore("adversarial", 10, 0, emptyMap(), listOf(layer))),
        )
    }

    @Test
    fun `writes json and markdown`() {
        ReportWriter().write(tempDir, report())

        val jsonFile = File(tempDir, "report.json")
        val mdFile = File(tempDir, "report.md")
        assertTrue(jsonFile.exists())
        assertTrue(mdFile.exists())
        val decoded = Json.decodeFromString(BenchmarkReport.serializer(), jsonFile.readText())
        assertEquals(report(), decoded)
        assertTrue(mdFile.readText().contains("| Category | Gold | P | R | F1 | Fβ=2 | Strict F1 | Leak % |"))
    }

    @Test
    fun `formats percentages with one decimal`() {
        ReportWriter().write(tempDir, report())

        assertTrue(File(tempDir, "report.md").readText().contains("12.3"))
    }
}
