package com.danielealbano.androidremotecontrolmcp.benchmark.corpus

import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("UiCorpusGenerator")
class UiCorpusGeneratorTest {
    private val corpus = UiCorpusGenerator(seed = 1234L).generate()

    private fun valueSamplesAtRow(rowIndexes: Set<Int>): List<BenchmarkSample> =
        corpus.samples.filter { sample ->
            val row =
                sample.id
                    .substringAfterLast("-r")
                    .substringBefore("-")
                    .toIntOrNull()
            sample.id.endsWith("-value") && row != null && row in rowIndexes
        }

    private fun labelTextFor(valueSample: BenchmarkSample): String? {
        val labelId = valueSample.id.removeSuffix("-value") + "-label"
        return corpus.samples.firstOrNull { it.id == labelId }?.text
    }

    /** Row indexes for a given context style under the `index % 5` rotation over 10 rows. */
    private fun rowsFor(style: ContextStyle): Set<Int> = setOf(style.ordinal, style.ordinal + ContextStyle.entries.size)

    private fun maxDigitRun(text: String): Int {
        var best = 0
        Regex("""\d(?:[- ]?\d)*""").findAll(text).forEach { match ->
            val digits = match.value.count { it.isDigit() }
            if (digits > best) best = digits
        }
        return best
    }

    @Test
    fun `same seed produces identical corpus`() {
        assertEquals(UiCorpusGenerator(seed = 42L).generate(), UiCorpusGenerator(seed = 42L).generate())
    }

    @Test
    fun `different seed produces different corpus`() {
        assertNotEquals(UiCorpusGenerator(seed = 1L).generate(), UiCorpusGenerator(seed = 2L).generate())
    }

    @Test
    fun `gold spans are in bounds and non-blank`() {
        for (sample in corpus.samples) {
            for (span in sample.gold) {
                assertTrue(span.start in 0 until span.end, "bad span in ${sample.id}")
                assertTrue(span.end <= sample.text.length, "out of bounds span in ${sample.id}")
                assertFalse(sample.text.substring(span.start, span.end).isBlank(), "blank span in ${sample.id}")
            }
        }
    }

    @Test
    fun `generated cards pass luhn`() {
        val cards =
            corpus.samples.filter { sample ->
                sample.gold.any { it.category == PiiCategory.CARDS_AND_IBAN } &&
                    sample.text.first().isDigit()
            }
        assertTrue(cards.isNotEmpty())
        for (card in cards) {
            val digits = card.text.filter { it.isDigit() }
            var sum = 0
            digits.reversed().forEachIndexed { index, char ->
                var digit = char - '0'
                if (index % 2 == 1) {
                    digit *= 2
                    if (digit > 9) digit -= 9
                }
                sum += digit
            }
            assertEquals(0, sum % 10, "Luhn failure for '${card.text}' (${card.id})")
        }
    }

    @Test
    fun `generated ibans pass mod97`() {
        val ibans =
            corpus.samples.filter { sample ->
                sample.gold.any { it.category == PiiCategory.CARDS_AND_IBAN } &&
                    sample.text.first().isLetter()
            }
        assertTrue(ibans.isNotEmpty())
        for (iban in ibans) {
            val plain = iban.text.replace(" ", "")
            val rearranged = plain.substring(4) + plain.substring(0, 4)
            var remainder = 0
            for (char in rearranged) {
                val piece = if (char.isDigit()) (char - '0').toString() else ((char - 'A') + 10).toString()
                for (digit in piece) remainder = (remainder * 10 + (digit - '0')) % 97
            }
            assertEquals(1, remainder, "mod-97 failure for '${iban.text}' (${iban.id})")
        }
    }

    @Test
    fun `geometric style resolves nearest label`() {
        val geometric = valueSamplesAtRow(rowsFor(ContextStyle.GEOMETRIC)).filter { it.context.isEditable }
        assertTrue(geometric.isNotEmpty())
        for (sample in geometric) {
            assertEquals(labelTextFor(sample), sample.context.labelText, "wrong nearest label for ${sample.id}")
        }
    }

    @Test
    fun `labeled-by style sets label text directly`() {
        val labeled = valueSamplesAtRow(rowsFor(ContextStyle.LABELED_BY))
        assertTrue(labeled.isNotEmpty())
        for (sample in labeled) {
            assertEquals(labelTextFor(sample), sample.context.labelText, "wrong labeledBy for ${sample.id}")
        }
    }

    @Test
    fun `resource id style yields context words`() {
        val resource = valueSamplesAtRow(rowsFor(ContextStyle.RESOURCE_ID))
        assertTrue(resource.isNotEmpty())
        val knownWordLists = FieldKind.entries.map { it.resourceWords.split('_') }.toSet()
        for (sample in resource) {
            assertTrue(sample.context.resourceIdWords in knownWordLists, "bad words for ${sample.id}")
            assertNull(sample.context.labelText, "unexpected labelText for ${sample.id}")
        }
    }

    @Test
    fun `hint style sets hint text`() {
        val hints = valueSamplesAtRow(rowsFor(ContextStyle.HINT))
        assertTrue(hints.isNotEmpty())
        for (sample in hints) {
            assertFalse(sample.context.hintText.isNullOrBlank(), "missing hint for ${sample.id}")
            assertNull(sample.context.labelText, "unexpected labelText for ${sample.id}")
        }
    }

    @Test
    fun `none style has empty context`() {
        val none = valueSamplesAtRow(rowsFor(ContextStyle.NONE))
        assertTrue(none.isNotEmpty())
        for (sample in none) {
            assertTrue(sample.context.contextText().isEmpty(), "non-empty context for ${sample.id}")
            assertFalse(sample.context.isEditable, "NONE row must not be editable: ${sample.id}")
        }
    }

    @Test
    fun `every screen has exactly six gold-bearing samples`() {
        for (language in UiCorpusGenerator.LANGUAGES) {
            repeat(UiCorpusGenerator.SCREENS_PER_LANGUAGE) { screen ->
                val prefix = "b-$language-s$screen-"
                val withGold = corpus.samples.count { it.id.startsWith(prefix) && it.gold.isNotEmpty() }
                assertEquals(6, withGold, "screen $prefix")
            }
        }
    }

    @Test
    fun `password samples are structural`() {
        val passwords = corpus.samples.filter { it.context.isPassword }
        assertTrue(passwords.isNotEmpty())
        for (sample in passwords) {
            assertEquals(
                listOf(GoldSpan(0, sample.text.length, PiiCategory.CREDENTIALS)),
                sample.gold,
                "password gold mismatch for ${sample.id}",
            )
        }
    }

    @Test
    fun `negative digit runs stay below card minimum`() {
        val goldFree = corpus.samples.filter { it.gold.isEmpty() }
        assertTrue(goldFree.isNotEmpty())
        for (sample in goldFree) {
            assertTrue(maxDigitRun(sample.text) < 12, "digit run too long in '${sample.text}' (${sample.id})")
        }
    }

    @Test
    fun `corpus covers all languages and categories`() {
        assertEquals(UiCorpusGenerator.LANGUAGES.toSet(), corpus.samples.map { it.language }.toSet())
        val coveredCategories = corpus.samples.flatMap { sample -> sample.gold.mapNotNull { it.category } }.toSet()
        assertEquals(PiiCategory.entries.toSet(), coveredCategories)
    }
}
