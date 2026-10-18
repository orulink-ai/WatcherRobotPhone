package com.danielealbano.androidremotecontrolmcp.benchmark

import com.danielealbano.androidremotecontrolmcp.privacy.model.PrivacyModelAssets
import com.danielealbano.androidremotecontrolmcp.privacy.model.PrivacyModelAssets.ModelAsset

/** Benchmark inputs, pinned to immutable revisions so published numbers stay reproducible. */
object BenchmarkAssets {
    const val DATASET_COMMIT = "506996d625ed970a0063432daf6007cf4a3a48e3"

    /** ai4privacy open-pii-masking-500k validation split (CC-BY-4.0, DOI 10.57967/hf/4852). */
    val DATASET =
        ModelAsset(
            fileName = "open-pii-masking-500k-validation.jsonl",
            url =
                "https://huggingface.co/datasets/ai4privacy/open-pii-masking-500k-ai4privacy/" +
                    "resolve/$DATASET_COMMIT/data/validation/test.jsonl",
            sha256 = "4e908e60d8d88f90015301e1ab4a8b7899ec713f1fc903860e1d9e0b91677ebf",
            sizeBytes = 141_836_910L,
        )

    val MODEL = PrivacyModelAssets.MODEL
    val TOKENIZER = PrivacyModelAssets.TOKENIZER
}
