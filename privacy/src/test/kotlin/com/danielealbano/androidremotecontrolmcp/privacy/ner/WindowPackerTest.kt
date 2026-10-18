package com.danielealbano.androidremotecontrolmcp.privacy.ner

import com.danielealbano.androidremotecontrolmcp.privacy.tokenizer.ModernBertTokenizer
import com.danielealbano.androidremotecontrolmcp.privacy.tokenizer.TokenizerData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("WindowPacker")
class WindowPackerTest {
    private val tokenizer: ModernBertTokenizer =
        requireNotNull(javaClass.getResourceAsStream("/privacy/tokenizer.json")).use {
            ModernBertTokenizer(TokenizerData.fromStream(it))
        }
    private val packer = WindowPacker(tokenizer)

    @Test
    fun `packs multiple short segments into one window`() {
        val segments = (1..5).map { NerSegment("k$it", "", "value number $it") }

        val windows = packer.pack(segments)

        assertEquals(1, windows.size)
        assertEquals(5, windows[0].segmentRanges.size)
    }

    @Test
    fun `splits when budget exceeded`() {
        val segments =
            (1..40).map { NerSegment("k$it", "", "The quick brown fox jumps over the lazy dog number $it today.") }

        val windows = packer.pack(segments)

        assertTrue(windows.size >= 2, "expected multiple windows")
        windows.forEach { window ->
            assertTrue(
                tokenizer.encode(window.text).ids.size <= 256,
                "window exceeds 256 tokens: ${tokenizer.encode(window.text).ids.size}",
            )
        }
    }

    @Test
    fun `context prefix excluded from value range`() {
        val windows = packer.pack(listOf(NerSegment("k", "Name", "John Smith")))

        val range = windows[0].segmentRanges[0]
        assertEquals("John Smith", windows[0].text.substring(range.valueStart, range.valueEnd))
    }

    @Test
    fun `oversized single segment flagged`() {
        val windows = packer.pack(listOf(NerSegment("k", "", "word ".repeat(2000))))

        assertTrue(windows.flatMap { it.segmentRanges }.any { it.truncated })
    }
}
