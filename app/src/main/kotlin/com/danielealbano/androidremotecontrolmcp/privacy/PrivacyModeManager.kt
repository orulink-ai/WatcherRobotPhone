package com.danielealbano.androidremotecontrolmcp.privacy

import com.danielealbano.androidremotecontrolmcp.data.model.PrivacyModeConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.privacy.model.DownloadState
import com.danielealbano.androidremotecontrolmcp.privacy.model.PrivacyModelDownloader
import com.danielealbano.androidremotecontrolmcp.privacy.model.PrivacyModelStore
import com.danielealbano.androidremotecontrolmcp.privacy.ner.NerSegment
import com.danielealbano.androidremotecontrolmcp.privacy.ner.OrtPiiModelRunner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates Privacy Mode readiness: computes the [PrivacyModeStatus], drives the consent-gated model
 * download + warm-up, runs the first-time benchmark estimate, and owns model shutdown. The readiness
 * methods touch the filesystem ([PrivacyModelStore.isReady]) and are dispatched to [ioDispatcher] so
 * they never run disk I/O on a UI-thread caller.
 */
@Singleton
class PrivacyModeManager
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val store: PrivacyModelStore,
        private val downloader: PrivacyModelDownloader,
        private val runner: OrtPiiModelRunner,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        private val mutableStatus = MutableStateFlow<PrivacyModeStatus>(PrivacyModeStatus.Disabled)
        val status: StateFlow<PrivacyModeStatus> = mutableStatus.asStateFlow()

        val downloadState: StateFlow<DownloadState> get() = downloader.state

        // App-process-scoped: the enable flow (download + warm-up + first-time benchmark) MUST
        // survive UI navigation and server lifecycle; the process is its only boundary. NOT
        // cancelled in shutdown() on purpose — a running benchmark finishes and persists its result
        // even if the user toggles Privacy Mode off or leaves the screen.
        private val managerScope = CoroutineScope(SupervisorJob() + ioDispatcher)

        private val mutableEnableInProgress = MutableStateFlow(false)

        /** True from an enable request until download + warm-up complete (the benchmark is NOT included). */
        val enableInProgress: StateFlow<Boolean> = mutableEnableInProgress.asStateFlow()

        private val mutableBenchmarkRunning = MutableStateFlow(false)

        /** True while the on-device performance benchmark is measuring. */
        val benchmarkRunning: StateFlow<Boolean> = mutableBenchmarkRunning.asStateFlow()

        /**
         * Launches [enableWithDownload] in the manager's own scope so it survives UI navigation.
         * The UI observes [enableInProgress], [downloadState], [status] and [benchmarkRunning]
         * instead of awaiting a result.
         */
        fun enableWithDownloadInBackground() {
            managerScope.launch { enableWithDownload() }
        }

        suspend fun currentConfig(): PrivacyModeConfig = settingsRepository.getServerConfig().privacyModeConfig

        suspend fun isModelReady(): Boolean = withContext(ioDispatcher) { store.isReady() }

        suspend fun selfCheck(): PrivacyModeStatus =
            withContext(ioDispatcher) {
                val config = currentConfig()
                val status =
                    when {
                        !config.enabled -> PrivacyModeStatus.Disabled
                        !config.modelRequired() -> PrivacyModeStatus.ReadyDeterministicOnly
                        !store.isReady() -> PrivacyModeStatus.Unavailable("detection model not downloaded")
                        runner.warmUp().isSuccess -> PrivacyModeStatus.Ready
                        else -> PrivacyModeStatus.Unavailable("detection model failed to load")
                    }
                mutableStatus.value = status
                status
            }

        suspend fun enableWithDownload(): Result<PrivacyModeStatus> =
            withContext(ioDispatcher) {
                // enableInProgress covers download + warm-up only; the first-time benchmark below
                // runs OUTSIDE the window so the user can still toggle Privacy Mode off while it
                // measures (it finishes and persists its estimate regardless). The benchmark
                // decision is read INSIDE the window so the UI hands off from the enable indicator
                // to the benchmark indicator without a gap.
                var runFirstTimeBenchmark = false
                val status =
                    try {
                        mutableEnableInProgress.value = true
                        settingsRepository.updatePrivacyModeEnabled(true)
                        val config = currentConfig()
                        if (config.modelRequired() && !store.isReady()) {
                            val download = downloader.download()
                            if (download.isFailure) {
                                val failed =
                                    PrivacyModeStatus.Unavailable(
                                        download.exceptionOrNull()?.message ?: "model download failed",
                                    )
                                mutableStatus.value = failed
                                return@withContext Result.success(failed)
                            }
                        }
                        val checked = selfCheck()
                        runFirstTimeBenchmark =
                            checked is PrivacyModeStatus.Ready &&
                            settingsRepository.privacyBenchmarkEstimateSeconds.first() == null
                        checked
                    } finally {
                        mutableEnableInProgress.value = false
                    }
                if (runFirstTimeBenchmark) benchmark()
                Result.success(status)
            }

        suspend fun benchmark(): Double {
            mutableBenchmarkRunning.value = true
            try {
                val segments =
                    (0 until BENCHMARK_NODES).map { index ->
                        val (context, value) = BENCHMARK_CORPUS[index % BENCHMARK_CORPUS.size]
                        NerSegment("bench-$index", context, value)
                    }
                val timingsMs =
                    (1..BENCHMARK_RUNS).map {
                        val start = System.nanoTime()
                        runner.infer(segments)
                        (System.nanoTime() - start) / NANOS_PER_MILLI
                    }
                val medianSeconds = timingsMs.sorted()[timingsMs.size / 2] / MILLIS_PER_SECOND
                settingsRepository.updatePrivacyBenchmarkEstimateSeconds(medianSeconds)
                return medianSeconds
            } finally {
                mutableBenchmarkRunning.value = false
            }
        }

        fun shutdown() {
            runner.close()
        }

        companion object {
            private const val BENCHMARK_NODES = 100
            private const val BENCHMARK_RUNS = 3
            private const val NANOS_PER_MILLI = 1_000_000.0
            private const val MILLIS_PER_SECOND = 1_000.0

            private val BENCHMARK_CORPUS =
                listOf(
                    "Full name" to "Jonathan Michael Anderson",
                    "Email" to "jonathan.anderson@example.com",
                    "Phone" to "+1 (415) 555-0182",
                    "Home address" to "742 Evergreen Terrace, Springfield",
                    "Card number" to "4111 1111 1111 1111",
                    "SSN" to "078-05-1120",
                    "City" to "San Francisco",
                    "Contact" to "Maria Garcia Lopez",
                    "Message" to "Please call me back tomorrow afternoon",
                    "Note" to "Meeting rescheduled to next week Tuesday",
                )
        }
    }
