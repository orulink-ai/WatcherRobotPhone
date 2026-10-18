package com.danielealbano.androidremotecontrolmcp.benchmark

import com.danielealbano.androidremotecontrolmcp.benchmark.corpus.AdversarialCorpusLoader
import com.danielealbano.androidremotecontrolmcp.benchmark.corpus.Ai4PrivacyCorpusLoader
import com.danielealbano.androidremotecontrolmcp.benchmark.corpus.LoadedCorpus
import com.danielealbano.androidremotecontrolmcp.benchmark.corpus.UiCorpusGenerator
import com.danielealbano.androidremotecontrolmcp.benchmark.scoring.BenchmarkReport
import com.danielealbano.androidremotecontrolmcp.benchmark.scoring.CorpusScore
import com.danielealbano.androidremotecontrolmcp.benchmark.scoring.LayerScore
import com.danielealbano.androidremotecontrolmcp.benchmark.scoring.ReportWriter
import com.danielealbano.androidremotecontrolmcp.benchmark.scoring.Scorer
import com.danielealbano.androidremotecontrolmcp.privacy.PiiDetection
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Instant
import java.util.Locale
import kotlin.system.exitProcess

internal val USAGE =
    """
    usage: privacy-benchmark [--corpora=a,b,c] [--layers=deterministic,model,full]
           [--sample=N] [--seed=L] [--cache-dir=PATH] [--out=PATH]
    """.trimIndent()

internal fun parseArgs(args: Array<String>): BenchmarkArgs {
    var parsed = BenchmarkArgs()
    for (arg in args) {
        require(arg.startsWith("--") && "=" in arg) { "invalid argument: $arg" }
        val key = arg.substringBefore("=")
        val value = arg.substringAfter("=")
        parsed =
            when (key) {
                "--corpora" -> {
                    parsed.copy(
                        corpora =
                            value.split(",").onEach {
                                require(it in setOf("a", "b", "c")) { "unknown corpus: $it" }
                            },
                    )
                }

                "--layers" -> {
                    parsed.copy(layers = value.split(",").map { Layer.valueOf(it.uppercase(Locale.ROOT)) })
                }

                "--sample" -> {
                    parsed.copy(sample = value.toInt())
                }

                "--seed" -> {
                    parsed.copy(seed = value.toLong())
                }

                "--cache-dir" -> {
                    parsed.copy(cacheDir = File(value))
                }

                "--out" -> {
                    parsed.copy(outDir = File(value))
                }

                else -> {
                    throw IllegalArgumentException("unknown argument: $key")
                }
            }
    }
    return parsed
}

fun main(args: Array<String>) {
    val parsed =
        try {
            parseArgs(args)
        } catch (e: IllegalArgumentException) {
            System.err.println(e.message)
            System.err.println(USAGE)
            exitProcess(USAGE_EXIT_CODE)
        }
    // PhoneDetector reads the default region; a fixed locale keeps published numbers reproducible.
    Locale.setDefault(Locale.US)
    runBenchmark(parsed)
    // No catch-all: an uncaught exception exits non-zero with a stack trace (fail loudly).
}

private fun runBenchmark(args: BenchmarkArgs) {
    val downloader = BenchmarkDownloader()
    val pipeline = BenchmarkPipeline(args.cacheDir)
    // A deterministic-only run needs no model: skip the 151 MB download and the warm-up entirely.
    val modelNeeded = args.layers.any { it != Layer.DETERMINISTIC }
    if (modelNeeded) {
        downloader.ensure(BenchmarkAssets.MODEL, File(args.cacheDir, MODEL_DIR))
        downloader.ensure(BenchmarkAssets.TOKENIZER, File(args.cacheDir, MODEL_DIR))
        pipeline.store.writeVerifiedMarker()
    }
    val corpora = mutableListOf<CorpusScore>()
    runBlocking {
        if (modelNeeded) {
            pipeline.runner.warmUp().getOrElse { throw IllegalStateException("model warm-up failed", it) }
        }
        for (corpusId in args.corpora) {
            val corpus = loadCorpus(corpusId, args, downloader)
            println("[corpus] ${corpus.name}: ${corpus.samples.size} samples (${corpus.droppedRows} dropped)")
            val layerScores = args.layers.map { layer -> runLayer(pipeline, layer, corpus) }
            corpora +=
                CorpusScore(corpus.name, corpus.samples.size, corpus.droppedRows, corpus.unknownLabels, layerScores)
        }
    }
    pipeline.runner.close()
    val report =
        BenchmarkReport(
            generatedAtIso = Instant.now().toString(),
            datasetCommit = BenchmarkAssets.DATASET_COMMIT,
            datasetSha256 = BenchmarkAssets.DATASET.sha256,
            modelSha256 = BenchmarkAssets.MODEL.sha256,
            tokenizerSha256 = BenchmarkAssets.TOKENIZER.sha256,
            seed = args.seed,
            corpora = corpora,
        )
    ReportWriter().write(args.outDir, report)
    println("Report: ${File(args.outDir, "report.md")}")
}

private fun loadCorpus(
    id: String,
    args: BenchmarkArgs,
    downloader: BenchmarkDownloader,
): LoadedCorpus =
    when (id) {
        "a" -> {
            val dataset = downloader.ensure(BenchmarkAssets.DATASET, File(args.cacheDir, DATASET_DIR))
            Ai4PrivacyCorpusLoader().load(dataset, args.sample)
        }

        "b" -> {
            UiCorpusGenerator(args.seed).generate()
        }

        "c" -> {
            AdversarialCorpusLoader().load()
        }

        else -> {
            error("unreachable: corpus $id")
        }
    }

private suspend fun runLayer(
    pipeline: BenchmarkPipeline,
    layer: Layer,
    corpus: LoadedCorpus,
): LayerScore {
    val start = System.nanoTime()
    val predictions: List<List<PiiDetection>>
    var redacted: List<String>? = null
    if (layer == Layer.FULL) {
        val result = pipeline.detectAndRedactFull(corpus.samples)
        predictions = result.first
        redacted = result.second
    } else {
        predictions = pipeline.detect(layer, corpus.samples)
    }
    val durationMs = (System.nanoTime() - start) / NANOS_PER_MILLI
    val score = Scorer().score(layer.name.lowercase(Locale.ROOT), corpus.samples, predictions, durationMs, redacted)
    println(
        "[layer] ${corpus.name}/${score.layer}: micro F1 ${score.microPartial.f1}, " +
            "char-leak ${score.leakRate} ($durationMs ms)",
    )
    return score
}

private const val USAGE_EXIT_CODE = 2
private const val NANOS_PER_MILLI = 1_000_000L
private const val MODEL_DIR = "privacy_model"
private const val DATASET_DIR = "dataset"
