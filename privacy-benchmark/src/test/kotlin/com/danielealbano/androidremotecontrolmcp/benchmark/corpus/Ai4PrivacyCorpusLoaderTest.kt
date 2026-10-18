package com.danielealbano.androidremotecontrolmcp.benchmark.corpus

import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@DisplayName("Ai4PrivacyCorpusLoader")
class Ai4PrivacyCorpusLoaderTest {
    @TempDir
    lateinit var tempDir: File

    private val loader = Ai4PrivacyCorpusLoader()

    private fun row(
        uid: Long,
        text: String,
        masks: String,
    ): String =
        """{"source_text": ${jsonString(text)}, "privacy_mask": [$masks], """ +
            """"language": "en", "uid": $uid}"""

    private fun jsonString(value: String): String = "\"" + value.replace("\"", "\\\"") + "\""

    private fun mask(
        label: String,
        start: Int,
        end: Int,
        value: String,
    ): String = """{"label": "$label", "start": $start, "end": $end, "value": ${jsonString(value)}, "label_index": 1}"""

    private fun writeJsonl(vararg lines: String): File {
        val file = File(tempDir, "corpus.jsonl")
        file.writeText(lines.joinToString("\n"))
        return file
    }

    @Test
    fun `loads rows and maps labels`() {
        val file =
            writeJsonl(
                row(1, "Anna met on 12/03", "${mask("GIVENNAME", 0, 4, "Anna")}, ${mask("DATE", 12, 17, "12/03")}"),
                row(2, "4111111111111111 charged", mask("CREDITCARDNUMBER", 0, 16, "4111111111111111")),
            )

        val corpus = loader.load(file)

        assertEquals(2, corpus.samples.size)
        val gold = corpus.samples.first().gold
        assertEquals(PiiCategory.NAMES, gold[0].category)
        assertNull(gold[1].category)
        assertEquals(
            PiiCategory.CARDS_AND_IBAN,
            corpus.samples[1]
                .gold
                .single()
                .category,
        )
        assertEquals(0, corpus.droppedRows)
    }

    @Test
    fun `drops row with mismatched span value and counts it`() {
        val file =
            writeJsonl(
                row(1, "Anna met", mask("GIVENNAME", 0, 4, "WRONG")),
                row(2, "Bob met", mask("GIVENNAME", 0, 3, "Bob")),
            )

        val corpus = loader.load(file)

        assertEquals(1, corpus.samples.size)
        assertEquals("a-2", corpus.samples.first().id)
        assertEquals(1, corpus.droppedRows)
    }

    @Test
    fun `unknown label becomes excluded span and is counted`() {
        val file = writeJsonl(row(1, "Anna met", mask("NEWLABEL", 0, 4, "Anna")))

        val corpus = loader.load(file)

        assertEquals(1, corpus.samples.size)
        assertNull(
            corpus.samples
                .first()
                .gold
                .first()
                .category,
        )
        assertEquals(1, corpus.unknownLabels["NEWLABEL"])
    }

    @Test
    fun `subsample returns evenly spaced N`() {
        val file =
            writeJsonl(
                row(1, "Anna met", mask("GIVENNAME", 0, 4, "Anna")),
                row(2, "Ben met", mask("GIVENNAME", 0, 3, "Ben")),
                row(3, "Cara met", mask("GIVENNAME", 0, 4, "Cara")),
                row(4, "Dan met", mask("GIVENNAME", 0, 3, "Dan")),
            )

        val corpus = loader.load(file, sample = 2)

        assertEquals(listOf("a-1", "a-3"), corpus.samples.map { it.id })
    }
}
