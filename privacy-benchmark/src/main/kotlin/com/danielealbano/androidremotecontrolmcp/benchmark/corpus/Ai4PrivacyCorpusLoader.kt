package com.danielealbano.androidremotecontrolmcp.benchmark.corpus

import com.danielealbano.androidremotecontrolmcp.privacy.DetectionContext
import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
private data class Ai4PrivacyMask(
    val label: String,
    val start: Int,
    val end: Int,
    val value: String,
)

@Serializable
private data class Ai4PrivacyRow(
    @SerialName("source_text") val sourceText: String,
    @SerialName("privacy_mask") val privacyMask: List<Ai4PrivacyMask>,
    val language: String,
    val uid: Long,
)

/**
 * Streams the pinned validation JSONL into [BenchmarkSample]s. Rows whose gold offsets fail the
 * `value == substring(start, end)` integrity check are dropped and counted; unknown labels map to an
 * excluded span and are counted.
 */
class Ai4PrivacyCorpusLoader {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(
        file: File,
        sample: Int = 0,
    ): LoadedCorpus {
        val samples = mutableListOf<BenchmarkSample>()
        var dropped = 0
        val unknownLabels = mutableMapOf<String, Int>()
        file.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.forEach { line ->
                val row = json.decodeFromString(Ai4PrivacyRow.serializer(), line)
                val gold = toGoldSpans(row, unknownLabels)
                if (gold == null) {
                    dropped++
                } else {
                    samples +=
                        BenchmarkSample("a-${row.uid}", row.sourceText, DetectionContext.EMPTY, gold, row.language)
                }
            }
        }
        return LoadedCorpus("ai4privacy-500k-validation", subsample(samples, sample), dropped, unknownLabels)
    }

    private fun toGoldSpans(
        row: Ai4PrivacyRow,
        unknownLabels: MutableMap<String, Int>,
    ): List<GoldSpan>? {
        val gold = mutableListOf<GoldSpan>()
        for (mask in row.privacyMask) {
            val inBounds = mask.start in 0..mask.end && mask.end <= row.sourceText.length
            if (!inBounds || row.sourceText.substring(mask.start, mask.end) != mask.value) return null
            if (mask.label !in LABEL_TO_CATEGORY) unknownLabels.merge(mask.label, 1, Int::plus)
            gold += GoldSpan(mask.start, mask.end, LABEL_TO_CATEGORY[mask.label])
        }
        return gold
    }

    private fun subsample(
        samples: List<BenchmarkSample>,
        sample: Int,
    ): List<BenchmarkSample> {
        if (sample !in 1 until samples.size) return samples
        val step = samples.size.toDouble() / sample
        return (0 until sample).map { samples[(it * step).toInt()] }
    }

    companion object {
        /** Pinned dataset-label mapping (see plan header); null = out of scope. */
        val LABEL_TO_CATEGORY: Map<String, PiiCategory?> =
            mapOf(
                "GIVENNAME" to PiiCategory.NAMES,
                "SURNAME" to PiiCategory.NAMES,
                "EMAIL" to PiiCategory.EMAILS,
                // Present in the pinned validation split (2,609 spans) although absent from the
                // dataset card's label list; mapping user-approved 2026-08-03.
                "CREDITCARDNUMBER" to PiiCategory.CARDS_AND_IBAN,
                "TELEPHONENUM" to PiiCategory.PHONE_NUMBERS,
                "STREET" to PiiCategory.ADDRESSES,
                "CITY" to PiiCategory.ADDRESSES,
                "ZIPCODE" to PiiCategory.ADDRESSES,
                "BUILDINGNUM" to PiiCategory.ADDRESSES,
                "SOCIALNUM" to PiiCategory.NATIONAL_IDS,
                "TAXNUM" to PiiCategory.NATIONAL_IDS,
                "PASSPORTNUM" to PiiCategory.NATIONAL_IDS,
                "DRIVERLICENSENUM" to PiiCategory.NATIONAL_IDS,
                "IDCARDNUM" to PiiCategory.NATIONAL_IDS,
                "DATE" to null,
                "TIME" to null,
                "AGE" to null,
                "SEX" to null,
                "GENDER" to null,
                "TITLE" to null,
                "ORGANISATIONPLACEHOLDER" to null,
            )
    }
}
