package com.danielealbano.androidremotecontrolmcp.benchmark.corpus

import com.danielealbano.androidremotecontrolmcp.privacy.DetectionContext
import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CorpusCSpan(
    val start: Int,
    val end: Int,
    val category: String,
)

@Serializable
data class CorpusCCase(
    val id: String,
    val group: String,
    val text: String,
    val language: String = "en",
    val labelText: String? = null,
    val hintText: String? = null,
    val resourceIdWords: List<String> = emptyList(),
    val isPassword: Boolean = false,
    val isEditable: Boolean = false,
    val gold: List<CorpusCSpan> = emptyList(),
)

/** Loads the checked-in adversarial suite from the module resources. */
class AdversarialCorpusLoader {
    private val json = Json { ignoreUnknownKeys = false }

    fun load(): LoadedCorpus {
        val resource =
            checkNotNull(javaClass.classLoader.getResourceAsStream(RESOURCE)) { "$RESOURCE missing" }
        val samples =
            resource.bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }.map { toSample(it) }.toList()
            }
        return LoadedCorpus("adversarial", samples, droppedRows = 0, unknownLabels = emptyMap())
    }

    private fun toSample(line: String): BenchmarkSample {
        val case = json.decodeFromString(CorpusCCase.serializer(), line)
        val context =
            DetectionContext(
                resourceIdWords = case.resourceIdWords,
                hintText = case.hintText,
                labelText = case.labelText,
                isPassword = case.isPassword,
                isEditable = case.isEditable,
            )
        val gold = case.gold.map { GoldSpan(it.start, it.end, PiiCategory.valueOf(it.category)) }
        return BenchmarkSample("c-${case.id}", case.text, context, gold, case.language)
    }

    companion object {
        const val RESOURCE = "corpus_c.jsonl"
    }
}
