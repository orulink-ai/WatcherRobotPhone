package com.danielealbano.androidremotecontrolmcp.privacy.ner

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.danielealbano.androidremotecontrolmcp.di.DefaultDispatcher
import com.danielealbano.androidremotecontrolmcp.privacy.PiiDetection
import com.danielealbano.androidremotecontrolmcp.privacy.model.PrivacyModelStore
import com.danielealbano.androidremotecontrolmcp.privacy.tokenizer.ModernBertTokenizer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ONNX Runtime-backed [PiiModelInference] for the ai4privacy ModernBERT token classifier. Model and
 * tokenizer are loaded lazily from the [PrivacyModelStore]. The heavy work (151 MB session load + ONNX
 * `run`) is dispatched to [dispatcher] (CPU-bound default) and serialized by a coroutine [Mutex] — it
 * suspends rather than blocking the calling thread, and NEVER runs on the caller's (possibly main)
 * thread. All failures surface as [PrivacyModelException] so the pipeline can fail closed.
 */
@Singleton
class OrtPiiModelRunner
    @Inject
    constructor(
        private val store: PrivacyModelStore,
        @DefaultDispatcher private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : PiiModelInference {
        private val mutex = Mutex()
        private val decoder = BioDecoder()

        @Volatile private var environment: OrtEnvironment? = null

        @Volatile private var session: OrtSession? = null

        @Volatile private var tokenizer: ModernBertTokenizer? = null

        @Volatile private var packer: WindowPacker? = null

        override suspend fun infer(segments: List<NerSegment>): List<NerResult> =
            withContext(dispatcher) { mutex.withLock { runInference(segments) } }

        suspend fun warmUp(): Result<Unit> =
            withContext(dispatcher) {
                mutex.withLock {
                    runCatching {
                        runInference(listOf(NerSegment("self-check", "", "self check")))
                        Unit
                    }.recoverCatching { throw asModelException(it) }
                }
            }

        fun close() {
            // Cleanup runs from the non-suspend service onDestroy; block briefly so an in-flight
            // inference finishes before the session is freed (inference is serialized, so bounded).
            runBlocking {
                mutex.withLock {
                    session?.close()
                    session = null
                    tokenizer = null
                    packer = null
                    environment = null
                }
            }
        }

        private fun runInference(segments: List<NerSegment>): List<NerResult> =
            try {
                ensureLoaded()
                val windows = packer!!.pack(segments)
                if (windows.any { window -> window.segmentRanges.any { it.truncated } }) {
                    // A value too long to fit the model's token cap cannot be fully analyzed -> fail closed.
                    throw PrivacyModelException("A field is too long to analyze under Privacy Mode")
                }
                val byKey = HashMap<String, MutableList<PiiDetection>>()
                for (window in windows) {
                    decodeWindow(window).forEach { (key, detections) ->
                        byKey.getOrPut(key) { mutableListOf() }.addAll(detections)
                    }
                }
                segments.map { NerResult(it.key, byKey[it.key].orEmpty()) }
            } catch (e: PrivacyModelException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Throwable,
            ) {
                throw asModelException(e)
            }

        private fun decodeWindow(window: PackedWindow): Map<String, List<PiiDetection>> {
            val encoded = tokenizer!!.encode(window.text)
            val labelIds = predictLabels(encoded.ids)
            return decoder.decode(labelIds, encoded.offsets, ID2LABEL, window.segmentRanges)
        }

        private fun predictLabels(ids: IntArray): IntArray {
            val env = environment!!
            val inputIds = ids.map(Int::toLong).toLongArray()
            val attentionMask = LongArray(ids.size) { 1L }
            OnnxTensor.createTensor(env, arrayOf(inputIds)).use { idsTensor ->
                OnnxTensor.createTensor(env, arrayOf(attentionMask)).use { maskTensor ->
                    session!!.run(mapOf("input_ids" to idsTensor, "attention_mask" to maskTensor)).use { result ->
                        val logits = (result[0].value as Array<*>)[0] as Array<*>
                        return IntArray(logits.size) { position -> argMax(logits[position] as FloatArray) }
                    }
                }
            }
        }

        private fun argMax(values: FloatArray): Int {
            var best = 0
            for (i in 1 until values.size) {
                if (values[i] > values[best]) best = i
            }
            return best
        }

        private fun ensureLoaded() {
            if (session != null) return
            if (!store.isReady()) throw PrivacyModelException("Privacy model files are not available")
            val env = OrtEnvironment.getEnvironment()
            val loadedSession = env.createSession(store.modelFile().absolutePath, OrtSession.SessionOptions())
            val loadedTokenizer = ModernBertTokenizer.fromFile(store.tokenizerFile())
            environment = env
            session = loadedSession
            tokenizer = loadedTokenizer
            packer = WindowPacker(loadedTokenizer)
        }

        private fun asModelException(cause: Throwable): PrivacyModelException =
            if (cause is PrivacyModelException) {
                cause
            } else {
                PrivacyModelException(cause.message ?: "model inference failed", cause)
            }

        companion object {
            val ID2LABEL: Map<Int, String> =
                mapOf(
                    0 to "B-AGE",
                    1 to "B-BUILDINGNUM",
                    2 to "B-CITY",
                    3 to "B-CREDITCARDNUMBER",
                    4 to "B-DATE",
                    5 to "B-DRIVERLICENSENUM",
                    6 to "B-EMAIL",
                    7 to "B-GENDER",
                    8 to "B-GIVENNAME",
                    9 to "B-IDCARDNUM",
                    10 to "B-PASSPORTNUM",
                    11 to "B-SEX",
                    12 to "B-SOCIALNUM",
                    13 to "B-STREET",
                    14 to "B-SURNAME",
                    15 to "B-TAXNUM",
                    16 to "B-TELEPHONENUM",
                    17 to "B-TIME",
                    18 to "B-TITLE",
                    19 to "B-ZIPCODE",
                    20 to "I-BUILDINGNUM",
                    21 to "I-CITY",
                    22 to "I-CREDITCARDNUMBER",
                    23 to "I-DATE",
                    24 to "I-DRIVERLICENSENUM",
                    25 to "I-EMAIL",
                    26 to "I-GENDER",
                    27 to "I-GIVENNAME",
                    28 to "I-IDCARDNUM",
                    29 to "I-PASSPORTNUM",
                    30 to "I-SEX",
                    31 to "I-SOCIALNUM",
                    32 to "I-STREET",
                    33 to "I-SURNAME",
                    34 to "I-TAXNUM",
                    35 to "I-TELEPHONENUM",
                    36 to "I-TIME",
                    37 to "I-TITLE",
                    38 to "I-ZIPCODE",
                    39 to "O",
                )
        }
    }
