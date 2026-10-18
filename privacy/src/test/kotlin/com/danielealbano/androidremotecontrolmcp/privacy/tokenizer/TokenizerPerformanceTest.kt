package com.danielealbano.androidremotecontrolmcp.privacy.tokenizer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.system.measureNanoTime

@Serializable
private data class PerfCase(
    val text: String,
)

/**
 * Measure-and-report only (no threshold gate): prints the median and total encode time over the fuzz
 * corpus so tokenizer throughput is visible in CI logs without gating on host-specific timings.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("ModernBertTokenizer performance")
class TokenizerPerformanceTest {
    private val tokenizer: ModernBertTokenizer =
        requireNotNull(javaClass.getResourceAsStream("/privacy/tokenizer.json")).use {
            ModernBertTokenizer(TokenizerData.fromStream(it))
        }

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `encode throughput over fuzz corpus`() {
        val cases: List<PerfCase> =
            json.decodeFromString(
                requireNotNull(javaClass.getResourceAsStream("/privacy/tokenizer_fixtures/fuzz.json"))
                    .reader(Charsets.UTF_8)
                    .readText(),
            )

        // Warm up.
        cases.forEach { tokenizer.encode(it.text) }

        val timingsMs = cases.map { case -> measureNanoTime { tokenizer.encode(case.text) } / 1_000_000.0 }
        val median = timingsMs.sorted()[timingsMs.size / 2]
        val total = timingsMs.sum()

        println(
            "Tokenizer throughput: ${cases.size} strings, median ${"%.3f".format(median)} ms, " +
                "total ${"%.1f".format(total)} ms",
        )
        assertEquals(cases.size, timingsMs.size)
    }
}
