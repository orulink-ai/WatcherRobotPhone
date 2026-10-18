package com.danielealbano.androidremotecontrolmcp.privacy.tokenizer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("TokenizerData")
class TokenizerDataTest {
    private fun loadReal(): TokenizerData =
        requireNotNull(javaClass.getResourceAsStream("/privacy/tokenizer.json")).use {
            TokenizerData.fromStream(it)
        }

    @Test
    fun `loads real tokenizer metadata`() {
        val data = loadReal()

        assertEquals(50280, data.vocab.size)
        assertEquals(50281, data.clsId)
        assertEquals(50282, data.sepId)
        assertEquals(116, data.addedTokens.size)
        assertEquals(TokenizerData.DEFAULT_MAX_LENGTH, data.maxLength)
    }

    @Test
    fun `parses both merges serialization forms`() {
        val json =
            """
            {
              "added_tokens": [
                {"content":"[CLS]","id":50281,"lstrip":false,"rstrip":false,"normalized":false,"special":true},
                {"content":"[SEP]","id":50282,"lstrip":false,"rstrip":false,"normalized":false,"special":true}
              ],
              "model": {
                "type":"BPE",
                "vocab": {"a":0,"b":1,"ab":2,"c":3,"d":4,"cd":5},
                "merges": [["a","b"], "c d"]
              }
            }
            """.trimIndent()

        val data = TokenizerData.fromStream(json.byteInputStream(Charsets.UTF_8))

        assertEquals(0, data.mergeRanks[Pair("a", "b")])
        assertEquals(1, data.mergeRanks[Pair("c", "d")])
        assertEquals(50281, data.clsId)
        assertEquals(50282, data.sepId)
    }
}
