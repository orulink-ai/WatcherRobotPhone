package com.danielealbano.androidremotecontrolmcp.privacy.tokenizer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@Serializable
private data class FixtureCase(
    val text: String,
    val ids: List<Int>,
    val offsets: List<List<Int>>,
)

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("ModernBertTokenizer parity")
class ModernBertTokenizerParityTest {
    private val tokenizer: ModernBertTokenizer =
        requireNotNull(javaClass.getResourceAsStream("/privacy/tokenizer.json")).use {
            ModernBertTokenizer(TokenizerData.fromStream(it))
        }

    private fun loadFixtures(name: String): List<FixtureCase> {
        val text =
            requireNotNull(javaClass.getResourceAsStream("/privacy/tokenizer_fixtures/$name.json"))
                .reader(Charsets.UTF_8)
                .readText()
        return Json.decodeFromString(text)
    }

    private fun assertIdParity(name: String) {
        loadFixtures(name).forEach { case ->
            val encoded = tokenizer.encode(case.text)
            assertEquals(case.ids, encoded.ids.toList(), "id mismatch for ${case.text}")
        }
    }

    private fun assertOffsetParity(name: String) {
        loadFixtures(name).forEach { case ->
            val encoded = tokenizer.encode(case.text)
            assertEquals(case.ids, encoded.ids.toList(), "id mismatch for ${case.text}")
            case.offsets.forEachIndexed { index, expected ->
                val actual = encoded.offsets[index]
                if (expected[0] == 0 && expected[1] == 0) {
                    assertNull(actual, "expected special-token null offset at $index for ${case.text}")
                } else {
                    requireNotNull(actual) { "expected offset at $index for ${case.text}" }
                    assertEquals(expected[0], actual.first, "offset start at $index for ${case.text}")
                    assertEquals(expected[1], actual.last + 1, "offset end at $index for ${case.text}")
                }
            }
        }
    }

    @Test
    fun `standard fixtures exact id parity`() = assertIdParity("standard")

    @Test
    fun `standard fixtures exact offset parity`() = assertOffsetParity("standard")

    @Test
    fun `edge case fixtures exact parity`() = assertOffsetParity("edge_cases")

    @Test
    fun `fuzz fixtures exact parity`() = assertIdParity("fuzz")

    @Test
    fun `truncation caps at 1536 with final SEP`() {
        val encoded = tokenizer.encode("word ".repeat(2000))

        assertEquals(TokenizerData.DEFAULT_MAX_LENGTH, encoded.ids.size)
        assertEquals(50282, encoded.ids.last())
        assertNull(encoded.offsets.last())
    }
}
