package com.danielealbano.androidremotecontrolmcp.benchmark.scoring

import com.danielealbano.androidremotecontrolmcp.benchmark.corpus.BenchmarkSample
import com.danielealbano.androidremotecontrolmcp.benchmark.corpus.GoldSpan
import com.danielealbano.androidremotecontrolmcp.privacy.PiiDetection
import kotlinx.serialization.Serializable

@Serializable
data class MetricValues(
    val tp: Int,
    val fp: Int,
    val fn: Int,
    val precision: Double,
    val recall: Double,
    val f1: Double,
    val fBeta2: Double,
) {
    companion object {
        fun from(
            tp: Int,
            fp: Int,
            fn: Int,
        ): MetricValues {
            val p = if (tp + fp == 0) 0.0 else tp.toDouble() / (tp + fp)
            val r = if (tp + fn == 0) 0.0 else tp.toDouble() / (tp + fn)
            val f1 = if (p + r == 0.0) 0.0 else 2 * p * r / (p + r)
            val fbDenominator = BETA_SQUARED * p + r
            val fb = if (fbDenominator == 0.0) 0.0 else (1 + BETA_SQUARED) * p * r / fbDenominator
            return MetricValues(tp, fp, fn, p, r, f1, fb)
        }

        /** β = 2 (recall-weighted Fβ per the plan's scoring rules), so β² = 4. */
        private const val BETA_SQUARED = 4.0
    }
}

@Serializable
data class CategoryScore(
    val category: String,
    val goldSpans: Int,
    val partial: MetricValues,
    val strict: MetricValues,
    val goldChars: Long,
    val leakedChars: Long,
    val leakRate: Double,
)

@Serializable
data class LanguageScore(
    val language: String,
    val samples: Int,
    val partial: MetricValues,
    val leakRate: Double,
)

@Serializable
data class LayerScore(
    val layer: String,
    val samples: Int,
    val durationMs: Long,
    val categories: List<CategoryScore>,
    val microPartial: MetricValues,
    val microStrict: MetricValues,
    val macroPartialF1: Double,
    val macroFBeta2: Double,
    val leakRate: Double,
    val residualValueLeaks: Int? = null,
    val perLanguage: List<LanguageScore>,
)

class Scorer {
    fun score(
        layer: String,
        samples: List<BenchmarkSample>,
        predictions: List<List<PiiDetection>>,
        durationMs: Long,
        redactedTexts: List<String>? = null,
    ): LayerScore {
        require(samples.size == predictions.size) { "samples/predictions size mismatch" }
        val categories = sortedMapOf<String, CategoryAcc>()
        val languages = sortedMapOf<String, LanguageAcc>()
        var residual = 0
        samples.forEachIndexed { index, sample ->
            val inScope = sample.gold.filter { it.category != null }
            val excluded = sample.gold.filter { it.category == null }
            val usable = usablePredictions(predictions[index], inScope, excluded)
            val language = languages.getOrPut(sample.language) { LanguageAcc() }
            language.samples++
            scoreGold(inScope, usable, predictions[index], categories, language)
            scoreFalsePositives(usable, inScope, categories, language)
            if (redactedTexts != null && hasResidualLeak(sample, inScope, redactedTexts[index])) {
                residual++
            }
        }
        val meta = LayerMeta(layer, samples.size, durationMs)
        return assemble(meta, categories, languages, redactedTexts?.let { residual })
    }

    /** Ignore rule: overlaps an excluded span AND matches no same-category in-scope gold span. */
    private fun usablePredictions(
        predictions: List<PiiDetection>,
        inScope: List<GoldSpan>,
        excluded: List<GoldSpan>,
    ): List<PiiDetection> =
        predictions.filterNot { p ->
            excluded.any { overlaps(p.start, p.end, it.start, it.end) } &&
                inScope.none { it.category == p.category && overlaps(p.start, p.end, it.start, it.end) }
        }

    private fun scoreGold(
        inScope: List<GoldSpan>,
        usable: List<PiiDetection>,
        allPredictions: List<PiiDetection>,
        categories: MutableMap<String, CategoryAcc>,
        language: LanguageAcc,
    ) {
        for (gold in inScope) {
            val category = requireNotNull(gold.category)
            val acc = categories.getOrPut(category.name) { CategoryAcc() }
            val length = (gold.end - gold.start).toLong()
            // Char-leak counts coverage by ANY prediction (even ignore-filtered ones): in production
            // every emitted span redacts, so a miscategorized-but-redacted char is not leaked.
            val leaked = length - coveredChars(gold, allPredictions)
            acc.goldSpans++
            acc.goldChars += length
            acc.leakedChars += leaked
            language.goldChars += length
            language.leakedChars += leaked
            val partialHit =
                usable.any { it.category == category && overlaps(it.start, it.end, gold.start, gold.end) }
            val strictHit =
                usable.any { it.category == category && it.start == gold.start && it.end == gold.end }
            if (partialHit) {
                acc.partialTp++
                language.partialTp++
            } else {
                acc.partialFn++
                language.partialFn++
            }
            if (strictHit) acc.strictTp++ else acc.strictFn++
        }
    }

    private fun scoreFalsePositives(
        usable: List<PiiDetection>,
        inScope: List<GoldSpan>,
        categories: MutableMap<String, CategoryAcc>,
        language: LanguageAcc,
    ) {
        for (prediction in usable) {
            val acc = categories.getOrPut(prediction.category.name) { CategoryAcc() }
            val partialHit =
                inScope.any {
                    it.category == prediction.category &&
                        overlaps(prediction.start, prediction.end, it.start, it.end)
                }
            if (!partialHit) {
                acc.partialFp++
                language.partialFp++
            }
            val strictHit =
                inScope.any {
                    it.category == prediction.category &&
                        it.start == prediction.start && it.end == prediction.end
                }
            if (!strictHit) acc.strictFp++
        }
    }

    /** Chars of [gold] covered by the union of prediction intervals (any category), clipped to gold. */
    private fun coveredChars(
        gold: GoldSpan,
        predictions: List<PiiDetection>,
    ): Long {
        val intervals =
            predictions
                .map { maxOf(it.start, gold.start) to minOf(it.end, gold.end) }
                .filter { it.first < it.second }
                .sortedBy { it.first }
        var covered = 0L
        var cursor = gold.start
        for ((start, end) in intervals) {
            val from = maxOf(cursor, start)
            if (end > from) {
                covered += end - from
                cursor = end
            }
        }
        return covered
    }

    private fun hasResidualLeak(
        sample: BenchmarkSample,
        inScope: List<GoldSpan>,
        redacted: String,
    ): Boolean =
        inScope.any { span ->
            val value = sample.text.substring(span.start, span.end)
            value.length >= MIN_RESIDUAL_LEN && redacted.contains(value)
        }

    private fun assemble(
        meta: LayerMeta,
        categories: Map<String, CategoryAcc>,
        languages: Map<String, LanguageAcc>,
        residual: Int?,
    ): LayerScore {
        val categoryScores =
            categories.map { (name, acc) ->
                CategoryScore(
                    category = name,
                    goldSpans = acc.goldSpans,
                    partial = MetricValues.from(acc.partialTp, acc.partialFp, acc.partialFn),
                    strict = MetricValues.from(acc.strictTp, acc.strictFp, acc.strictFn),
                    goldChars = acc.goldChars,
                    leakedChars = acc.leakedChars,
                    leakRate = ratio(acc.leakedChars, acc.goldChars),
                )
            }
        val withGold = categoryScores.filter { it.goldSpans > 0 }
        return LayerScore(
            layer = meta.layer,
            samples = meta.samples,
            durationMs = meta.durationMs,
            categories = categoryScores,
            microPartial =
                MetricValues.from(
                    categoryScores.sumOf { it.partial.tp },
                    categoryScores.sumOf { it.partial.fp },
                    categoryScores.sumOf { it.partial.fn },
                ),
            microStrict =
                MetricValues.from(
                    categoryScores.sumOf { it.strict.tp },
                    categoryScores.sumOf { it.strict.fp },
                    categoryScores.sumOf { it.strict.fn },
                ),
            macroPartialF1 = if (withGold.isEmpty()) 0.0 else withGold.sumOf { it.partial.f1 } / withGold.size,
            macroFBeta2 = if (withGold.isEmpty()) 0.0 else withGold.sumOf { it.partial.fBeta2 } / withGold.size,
            leakRate = ratio(categoryScores.sumOf { it.leakedChars }, categoryScores.sumOf { it.goldChars }),
            residualValueLeaks = residual,
            perLanguage =
                languages.map { (lang, acc) ->
                    LanguageScore(
                        language = lang,
                        samples = acc.samples,
                        partial = MetricValues.from(acc.partialTp, acc.partialFp, acc.partialFn),
                        leakRate = ratio(acc.leakedChars, acc.goldChars),
                    )
                },
        )
    }

    private fun ratio(
        numerator: Long,
        denominator: Long,
    ): Double = if (denominator == 0L) 0.0 else numerator.toDouble() / denominator

    private fun overlaps(
        aStart: Int,
        aEnd: Int,
        bStart: Int,
        bEnd: Int,
    ): Boolean = aStart < bEnd && bStart < aEnd

    private class CategoryAcc {
        var goldSpans = 0
        var goldChars = 0L
        var leakedChars = 0L
        var partialTp = 0
        var partialFp = 0
        var partialFn = 0
        var strictTp = 0
        var strictFp = 0
        var strictFn = 0
    }

    private class LanguageAcc {
        var samples = 0
        var goldChars = 0L
        var leakedChars = 0L
        var partialTp = 0
        var partialFp = 0
        var partialFn = 0
    }

    private data class LayerMeta(
        val layer: String,
        val samples: Int,
        val durationMs: Long,
    )

    private companion object {
        const val MIN_RESIDUAL_LEN = 4
    }
}
