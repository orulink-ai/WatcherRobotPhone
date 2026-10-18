package com.danielealbano.androidremotecontrolmcp.benchmark

import java.io.File

internal const val DEFAULT_SEED = 20260803L

data class BenchmarkArgs(
    val corpora: List<String> = listOf("a", "b", "c"),
    val layers: List<Layer> = listOf(Layer.DETERMINISTIC, Layer.MODEL, Layer.FULL),
    val sample: Int = 0,
    val seed: Long = DEFAULT_SEED,
    val cacheDir: File = File("privacy-benchmark/.cache"),
    val outDir: File = File("privacy-benchmark/build/reports/privacy-benchmark"),
)
