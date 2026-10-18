package com.danielealbano.androidremotecontrolmcp.benchmark.scoring

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

@Serializable
data class CorpusScore(
    val corpus: String,
    val samples: Int,
    val droppedRows: Int,
    val unknownLabels: Map<String, Int>,
    val layers: List<LayerScore>,
)

@Serializable
data class BenchmarkReport(
    val generatedAtIso: String,
    val datasetCommit: String,
    val datasetSha256: String,
    val modelSha256: String,
    val tokenizerSha256: String,
    val seed: Long,
    val corpora: List<CorpusScore>,
)

/** Writes report.json (full DTO) and report.md (README-pasteable tables). */
class ReportWriter {
    private val json = Json { prettyPrint = true }

    fun write(
        outDir: File,
        report: BenchmarkReport,
    ) {
        outDir.mkdirs()
        File(outDir, "report.json").writeText(json.encodeToString(BenchmarkReport.serializer(), report))
        File(outDir, "report.md").writeText(markdown(report))
    }

    private fun markdown(report: BenchmarkReport): String =
        buildString {
            appendLine("# Privacy Mode effectiveness report")
            for (corpus in report.corpora) {
                appendLine()
                appendLine(
                    "## Corpus: ${corpus.corpus} " +
                        "(${corpus.samples} samples, ${corpus.droppedRows} dropped rows)",
                )
                if (corpus.unknownLabels.isNotEmpty()) {
                    appendLine("Unknown dataset labels (excluded): ${corpus.unknownLabels}")
                }
                for (layer in corpus.layers) appendLayer(layer)
            }
            appendLine()
            appendLine("---")
            appendLine(
                "Generated ${report.generatedAtIso}; dataset commit ${report.datasetCommit} " +
                    "(sha256 ${report.datasetSha256}); model sha256 ${report.modelSha256}; " +
                    "tokenizer sha256 ${report.tokenizerSha256}; seed ${report.seed}.",
            )
        }

    private fun StringBuilder.appendLayer(layer: LayerScore) {
        appendLine()
        appendLine("### Layer: ${layer.layer} (${layer.samples} samples, ${layer.durationMs} ms)")
        appendLine("| Category | Gold | P | R | F1 | Fβ=2 | Strict F1 | Leak % |")
        appendLine("|---|---|---|---|---|---|---|---|")
        for (category in layer.categories) {
            appendLine(
                "| ${category.category} | ${category.goldSpans} | ${pct(category.partial.precision)} " +
                    "| ${pct(category.partial.recall)} | ${pct(category.partial.f1)} " +
                    "| ${pct(category.partial.fBeta2)} | ${pct(category.strict.f1)} " +
                    "| ${pct(category.leakRate)} |",
            )
        }
        append(
            "Micro P ${pct(layer.microPartial.precision)} / R ${pct(layer.microPartial.recall)} " +
                "/ F1 ${pct(layer.microPartial.f1)}; macro F1 ${pct(layer.macroPartialF1)}; " +
                "macro Fβ=2 ${pct(layer.macroFBeta2)}; char-leak ${pct(layer.leakRate)}%",
        )
        layer.residualValueLeaks?.let { append("; residual value leaks: $it") }
        appendLine()
        if (layer.layer == "full" && layer.perLanguage.size > 1) {
            appendLine()
            appendLine("| Language | Samples | R | Leak % |")
            appendLine("|---|---|---|---|")
            for (lang in layer.perLanguage) {
                appendLine(
                    "| ${lang.language} | ${lang.samples} | ${pct(lang.partial.recall)} " +
                        "| ${pct(lang.leakRate)} |",
                )
            }
        }
    }

    // Explicit locale: report output must not depend on the host's default decimal separator.
    private fun pct(value: Double): String = String.format(Locale.US, "%.1f", value * PERCENT)

    private companion object {
        const val PERCENT = 100.0
    }
}
