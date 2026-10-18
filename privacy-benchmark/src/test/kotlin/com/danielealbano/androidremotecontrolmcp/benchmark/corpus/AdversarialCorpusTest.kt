package com.danielealbano.androidremotecontrolmcp.benchmark.corpus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("AdversarialCorpus")
class AdversarialCorpusTest {
    private val corpus = AdversarialCorpusLoader().load()

    private fun groupOf(sample: BenchmarkSample): String = sample.id.removePrefix("c-").substringBeforeLast("-")

    @Test
    fun `loads all cases with exact group counts`() {
        assertEquals(EXPECTED_GROUP_COUNTS.values.sum(), corpus.samples.size)
        val actual = corpus.samples.groupingBy { groupOf(it) }.eachCount()
        assertEquals(EXPECTED_GROUP_COUNTS, actual)
    }

    @Test
    fun `ids are unique and spans are valid`() {
        assertEquals(
            corpus.samples.size,
            corpus.samples
                .map { it.id }
                .toSet()
                .size,
        )
        for (sample in corpus.samples) {
            assertTrue(groupOf(sample) in EXPECTED_GROUP_COUNTS, "unknown group for ${sample.id}")
            for (span in sample.gold) {
                assertTrue(span.start in 0 until span.end, "bad span in ${sample.id}")
                assertTrue(span.end <= sample.text.length, "out of bounds span in ${sample.id}")
                assertFalse(sample.text.substring(span.start, span.end).isBlank(), "blank span in ${sample.id}")
            }
        }
    }

    @Test
    fun `negative group has no gold`() {
        for (sample in corpus.samples) {
            if (groupOf(sample) == "lookalike-negatives") {
                assertTrue(sample.gold.isEmpty(), "negative with gold: ${sample.id}")
            } else {
                assertTrue(sample.gold.isNotEmpty(), "non-negative without gold: ${sample.id}")
            }
        }
    }

    private companion object {
        val EXPECTED_GROUP_COUNTS =
            mapOf(
                "email-obfuscated" to 10,
                "email-exotic" to 8,
                "card-formats" to 12,
                "card-partial" to 6,
                "iban-variants" to 8,
                "phone-variants" to 12,
                "national-id-context" to 8,
                "national-id-bare" to 6,
                "credential-plaintext" to 10,
                "name-unicode" to 10,
                "name-hard" to 6,
                "address-freetext" to 8,
                "mixed-language" to 6,
                "lookalike-negatives" to 16,
            )
    }
}
