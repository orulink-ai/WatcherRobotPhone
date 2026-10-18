package com.danielealbano.androidremotecontrolmcp.benchmark.scoring

import com.danielealbano.androidremotecontrolmcp.benchmark.corpus.BenchmarkSample
import com.danielealbano.androidremotecontrolmcp.benchmark.corpus.GoldSpan
import com.danielealbano.androidremotecontrolmcp.privacy.DetectionContext
import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory
import com.danielealbano.androidremotecontrolmcp.privacy.PiiDetection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Scorer")
class ScorerTest {
    private val scorer = Scorer()

    private fun sample(
        text: String,
        gold: List<GoldSpan>,
        language: String = "en",
    ) = BenchmarkSample("s", text, DetectionContext.EMPTY, gold, language)

    private fun det(
        category: PiiCategory,
        start: Int,
        end: Int,
    ) = PiiDetection(category, start, end, PiiDetection.Source.MODEL)

    private fun LayerScore.category(name: String): CategoryScore = categories.first { it.category == name }

    @Test
    fun `partial match counts overlap with same category`() {
        val samples =
            listOf(
                sample("aaaaaaaaaa", listOf(GoldSpan(0, 10, PiiCategory.EMAILS))),
                sample("bbbbb", listOf(GoldSpan(0, 5, PiiCategory.EMAILS))),
            )
        val predictions = listOf(listOf(det(PiiCategory.EMAILS, 2, 8)), emptyList())

        val score = scorer.score("full", samples, predictions, durationMs = 0)

        val emails = score.category("EMAILS")
        assertEquals(1, emails.partial.tp)
        assertEquals(1, emails.partial.fn)
        assertEquals(0, emails.partial.fp)
    }

    @Test
    fun `strict requires exact boundaries`() {
        val samples = listOf(sample("aaaaaaaaaa", listOf(GoldSpan(0, 10, PiiCategory.EMAILS))))
        val predictions = listOf(listOf(det(PiiCategory.EMAILS, 2, 8)))

        val score = scorer.score("full", samples, predictions, durationMs = 0)

        val emails = score.category("EMAILS")
        assertEquals(1, emails.partial.tp)
        assertEquals(0, emails.strict.tp)
        assertEquals(1, emails.strict.fn)
        assertEquals(1, emails.strict.fp)
    }

    @Test
    fun `prediction on excluded span is ignored`() {
        val samples = listOf(sample("2026-08-03", listOf(GoldSpan(0, 10, null))))
        val predictions = listOf(listOf(det(PiiCategory.NAMES, 1, 4)))

        val score = scorer.score("full", samples, predictions, durationMs = 0)

        assertEquals(0, score.microPartial.fp)
        assertEquals(0, score.microPartial.tp)
    }

    @Test
    fun `prediction overlapping excluded and matching in-scope gold counts as tp`() {
        val samples =
            listOf(
                sample(
                    "aaaaaaaaaa",
                    listOf(GoldSpan(0, 5, PiiCategory.NAMES), GoldSpan(3, 8, null)),
                ),
            )
        val predictions = listOf(listOf(det(PiiCategory.NAMES, 2, 6)))

        val score = scorer.score("full", samples, predictions, durationMs = 0)

        assertEquals(1, score.category("NAMES").partial.tp)
        assertEquals(0, score.category("NAMES").partial.fp)
    }

    @Test
    fun `wrong-category overlap is fn plus fp but not leaked`() {
        val samples = listOf(sample("aaaaaaaaaa", listOf(GoldSpan(0, 10, PiiCategory.EMAILS))))
        val predictions = listOf(listOf(det(PiiCategory.NAMES, 0, 10)))

        val score = scorer.score("full", samples, predictions, durationMs = 0)

        assertEquals(1, score.category("EMAILS").partial.fn)
        assertEquals(1, score.category("NAMES").partial.fp)
        assertEquals(0.0, score.leakRate)
    }

    @Test
    fun `leak rate uses interval union`() {
        val samples = listOf(sample("aaaaaaaaaa", listOf(GoldSpan(0, 10, PiiCategory.EMAILS))))
        val predictions =
            listOf(listOf(det(PiiCategory.EMAILS, 0, 5), det(PiiCategory.EMAILS, 5, 10)))

        val score = scorer.score("full", samples, predictions, durationMs = 0)

        assertEquals(0.0, score.leakRate)
        assertEquals(1, score.category("EMAILS").partial.tp)
    }

    @Test
    fun `fbeta2 formula`() {
        val metrics = MetricValues.from(tp = 1, fp = 1, fn = 0)

        assertEquals(0.5, metrics.precision)
        assertEquals(1.0, metrics.recall)
        assertEquals(5.0 * 0.5 / 3.0, metrics.fBeta2, 1e-9)
    }

    @Test
    fun `residual value leak detected`() {
        val samples = listOf(sample("call Sarah now", listOf(GoldSpan(5, 10, PiiCategory.NAMES))))
        val predictions = listOf(emptyList<PiiDetection>())

        val leaked = scorer.score("full", samples, predictions, 0, redactedTexts = listOf("call Sarah now"))
        val clean = scorer.score("full", samples, predictions, 0, redactedTexts = listOf("call [NAME_1] now"))
        val without = scorer.score("full", samples, predictions, 0, redactedTexts = null)

        assertEquals(1, leaked.residualValueLeaks)
        assertEquals(0, clean.residualValueLeaks)
        assertNull(without.residualValueLeaks)
    }

    @Test
    fun `macro averages only categories with gold`() {
        val samples = listOf(sample("aaaaa bbb", listOf(GoldSpan(0, 5, PiiCategory.EMAILS))))
        val predictions = listOf(listOf(det(PiiCategory.EMAILS, 0, 5), det(PiiCategory.NAMES, 6, 9)))

        val score = scorer.score("full", samples, predictions, durationMs = 0)

        assertEquals(1.0, score.macroPartialF1)
        assertEquals(0, score.category("NAMES").goldSpans)
    }

    @Test
    fun `per-language accumulation`() {
        val samples =
            listOf(
                sample("aaaaa", listOf(GoldSpan(0, 5, PiiCategory.EMAILS)), language = "en"),
                sample("bbbbb", listOf(GoldSpan(0, 5, PiiCategory.EMAILS)), language = "fr"),
            )
        val predictions = listOf(listOf(det(PiiCategory.EMAILS, 0, 5)), emptyList())

        val score = scorer.score("full", samples, predictions, durationMs = 0)

        assertEquals(2, score.perLanguage.size)
        val en = score.perLanguage.first { it.language == "en" }
        val fr = score.perLanguage.first { it.language == "fr" }
        assertEquals(1, en.partial.tp)
        assertEquals(1, fr.partial.fn)
        assertEquals(1.0, fr.leakRate)
    }
}
