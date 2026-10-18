package com.danielealbano.androidremotecontrolmcp.privacy.tokenizer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream

/** A HuggingFace added token (matched verbatim before BPE). */
data class AddedToken(
    val content: String,
    val id: Int,
    val lstrip: Boolean,
    val rstrip: Boolean,
    val normalized: Boolean,
    val special: Boolean,
)

/**
 * The data half of the ModernBERT tokenizer, parsed once from a HuggingFace `tokenizer.json`:
 * the BPE [vocab], the ranked [mergeRanks] (both `"a b"` string and `["a","b"]` array forms are
 * accepted), the [addedTokens], and the special-token ids.
 */
class TokenizerData(
    val vocab: Map<String, Int>,
    val mergeRanks: Map<Pair<String, String>, Int>,
    val addedTokens: List<AddedToken>,
    val clsId: Int,
    val sepId: Int,
    val maxLength: Int = DEFAULT_MAX_LENGTH,
) {
    companion object {
        const val DEFAULT_MAX_LENGTH = 1536
        private const val CLS_CONTENT = "[CLS]"
        private const val SEP_CONTENT = "[SEP]"

        fun fromStream(input: InputStream): TokenizerData {
            val root = Json.parseToJsonElement(input.reader(Charsets.UTF_8).readText()).jsonObject
            val model = root.getValue("model").jsonObject

            val vocab =
                model.getValue("vocab").jsonObject.entries.associate { (token, id) ->
                    token to id.jsonPrimitive.content.toInt()
                }

            val mergeRanks = HashMap<Pair<String, String>, Int>()
            model.getValue("merges").jsonArray.forEachIndexed { rank, element ->
                val pair =
                    when {
                        element is kotlinx.serialization.json.JsonArray -> {
                            element[0].jsonPrimitive.content to element[1].jsonPrimitive.content
                        }

                        else -> {
                            val parts = element.jsonPrimitive.content.split(' ', limit = 2)
                            parts[0] to parts[1]
                        }
                    }
                mergeRanks[pair] = rank
            }

            val addedTokens =
                root.getValue("added_tokens").jsonArray.map { element ->
                    val obj = element.jsonObject
                    AddedToken(
                        content = obj.getValue("content").jsonPrimitive.content,
                        id =
                            obj
                                .getValue("id")
                                .jsonPrimitive.content
                                .toInt(),
                        lstrip =
                            obj
                                .getValue("lstrip")
                                .jsonPrimitive.content
                                .toBoolean(),
                        rstrip =
                            obj
                                .getValue("rstrip")
                                .jsonPrimitive.content
                                .toBoolean(),
                        normalized =
                            obj
                                .getValue("normalized")
                                .jsonPrimitive.content
                                .toBoolean(),
                        special =
                            obj
                                .getValue("special")
                                .jsonPrimitive.content
                                .toBoolean(),
                    )
                }

            val clsId = addedTokens.first { it.content == CLS_CONTENT }.id
            val sepId = addedTokens.first { it.content == SEP_CONTENT }.id
            return TokenizerData(vocab, mergeRanks, addedTokens, clsId, sepId)
        }
    }
}
