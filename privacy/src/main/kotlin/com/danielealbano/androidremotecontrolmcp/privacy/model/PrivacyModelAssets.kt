package com.danielealbano.androidremotecontrolmcp.privacy.model

/**
 * The Privacy Mode model assets, fetched at runtime from Hugging Face pinned to an immutable commit
 * (never bundled in the APK). URLs, byte sizes and SHA-256 hashes are pinned so a changed upstream
 * file fails verification rather than being trusted.
 */
object PrivacyModelAssets {
    const val MODELS_DIR = "privacy_model"

    data class ModelAsset(
        val fileName: String,
        val url: String,
        val sha256: String,
        val sizeBytes: Long,
    )

    private const val BASE =
        "https://huggingface.co/ai4privacy/llama-ai4privacy-multilingual-categorical-anonymiser-openpii/" +
            "resolve/83ef30d5e7c9d113ad80ce745b564cdd2320c5d5"

    val MODEL =
        ModelAsset(
            fileName = "model_int8.onnx",
            url = "$BASE/onnx/model_int8.onnx",
            sha256 = "8e8af012cee32e14820f13bdc855868f6984e507dff84d92abbe2eeaf713e43f",
            sizeBytes = 150_904_485L,
        )

    val TOKENIZER =
        ModelAsset(
            fileName = "tokenizer.json",
            url = "$BASE/tokenizer.json",
            sha256 = "6c8aaa9a542084f2457eab775d4eeb51f92a70c0fd9de28d5edb0ddec3c08d30",
            sizeBytes = 3_583_228L,
        )

    val ALL = listOf(MODEL, TOKENIZER)
}
