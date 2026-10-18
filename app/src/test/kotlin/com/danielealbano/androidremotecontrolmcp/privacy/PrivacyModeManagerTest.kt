package com.danielealbano.androidremotecontrolmcp.privacy

import com.danielealbano.androidremotecontrolmcp.data.model.PrivacyModeConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.privacy.model.PrivacyModelDownloader
import com.danielealbano.androidremotecontrolmcp.privacy.model.PrivacyModelStore
import com.danielealbano.androidremotecontrolmcp.privacy.ner.OrtPiiModelRunner
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("PrivacyModeManager")
class PrivacyModeManagerTest {
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val store = mockk<PrivacyModelStore>()
    private val downloader = mockk<PrivacyModelDownloader>()
    private val runner = mockk<OrtPiiModelRunner>(relaxed = true)
    private lateinit var manager: PrivacyModeManager

    @BeforeEach
    fun setUp() {
        manager = PrivacyModeManager(settingsRepository, store, downloader, runner, UnconfinedTestDispatcher())
    }

    private fun withConfig(config: PrivacyModeConfig) {
        coEvery { settingsRepository.getServerConfig() } returns ServerConfig(privacyModeConfig = config)
    }

    @Test
    fun `selfCheck disabled`() =
        runTest {
            withConfig(PrivacyModeConfig(enabled = false))
            assertEquals(PrivacyModeStatus.Disabled, manager.selfCheck())
        }

    @Test
    fun `selfCheck deterministic only when model categories disabled`() =
        runTest {
            withConfig(
                PrivacyModeConfig(
                    enabled = true,
                    disabledCategories = setOf(PiiCategory.NAMES, PiiCategory.ADDRESSES, PiiCategory.NATIONAL_IDS),
                ),
            )

            assertEquals(PrivacyModeStatus.ReadyDeterministicOnly, manager.selfCheck())
            coVerify(exactly = 0) { runner.warmUp() }
        }

    @Test
    fun `selfCheck unavailable when model required and store not ready`() =
        runTest {
            withConfig(PrivacyModeConfig(enabled = true))
            every { store.isReady() } returns false

            val status = manager.selfCheck()
            assertTrue(status is PrivacyModeStatus.Unavailable)
            assertTrue((status as PrivacyModeStatus.Unavailable).reason.contains("download"))
        }

    @Test
    fun `selfCheck unavailable when warmUp fails`() =
        runTest {
            withConfig(PrivacyModeConfig(enabled = true))
            every { store.isReady() } returns true
            coEvery { runner.warmUp() } returns Result.failure(IllegalStateException("bad model"))

            assertTrue(manager.selfCheck() is PrivacyModeStatus.Unavailable)
        }

    @Test
    fun `selfCheck ready when store ready and warmUp succeeds`() =
        runTest {
            withConfig(PrivacyModeConfig(enabled = true))
            every { store.isReady() } returns true
            coEvery { runner.warmUp() } returns Result.success(Unit)

            assertEquals(PrivacyModeStatus.Ready, manager.selfCheck())
        }

    @Test
    fun `enableWithDownload deterministic only skips download`() =
        runTest {
            withConfig(
                PrivacyModeConfig(
                    enabled = true,
                    disabledCategories = setOf(PiiCategory.NAMES, PiiCategory.ADDRESSES, PiiCategory.NATIONAL_IDS),
                ),
            )

            val result = manager.enableWithDownload()

            assertEquals(PrivacyModeStatus.ReadyDeterministicOnly, result.getOrNull())
            coVerify { settingsRepository.updatePrivacyModeEnabled(true) }
            coVerify(exactly = 0) { downloader.download() }
        }

    @Test
    fun `enableWithDownload download failure yields unavailable`() =
        runTest {
            withConfig(PrivacyModeConfig(enabled = true))
            every { store.isReady() } returns false
            coEvery { downloader.download() } returns Result.failure(IOException("offline"))

            val result = manager.enableWithDownload()

            assertTrue(result.getOrNull() is PrivacyModeStatus.Unavailable)
        }

    @Test
    fun `benchmark persists estimate`() =
        runTest {
            coEvery { runner.infer(any()) } returns emptyList()

            manager.benchmark()

            coVerify { settingsRepository.updatePrivacyBenchmarkEstimateSeconds(any()) }
        }

    @Test
    fun `enableWithDownload ready runs first-time benchmark`() =
        runTest {
            withConfig(PrivacyModeConfig(enabled = true))
            every { store.isReady() } returns true
            coEvery { runner.warmUp() } returns Result.success(Unit)
            coEvery { runner.infer(any()) } returns emptyList()
            every { settingsRepository.privacyBenchmarkEstimateSeconds } returns flowOf(null)

            val result = manager.enableWithDownload()

            assertEquals(PrivacyModeStatus.Ready, result.getOrNull())
            coVerify { settingsRepository.updatePrivacyBenchmarkEstimateSeconds(any()) }
        }

    @Test
    fun `enableWithDownloadInBackground launches the enable flow`() =
        runTest {
            withConfig(
                PrivacyModeConfig(
                    enabled = true,
                    disabledCategories = setOf(PiiCategory.NAMES, PiiCategory.ADDRESSES, PiiCategory.NATIONAL_IDS),
                ),
            )

            manager.enableWithDownloadInBackground()

            coVerify { settingsRepository.updatePrivacyModeEnabled(true) }
        }

    @Test
    fun `benchmark exposes running state while measuring and clears it after`() =
        runTest {
            coEvery { runner.infer(any()) } answers {
                assertTrue(manager.benchmarkRunning.value, "benchmarkRunning must be true while measuring")
                emptyList()
            }

            manager.benchmark()

            assertFalse(manager.benchmarkRunning.value)
        }

    @Test
    fun `enable window covers download but not the benchmark`() =
        runTest {
            withConfig(PrivacyModeConfig(enabled = true))
            every { store.isReady() } returns false andThen true
            coEvery { downloader.download() } answers {
                assertTrue(manager.enableInProgress.value, "enableInProgress must cover the download")
                Result.success(Unit)
            }
            coEvery { runner.warmUp() } returns Result.success(Unit)
            every { settingsRepository.privacyBenchmarkEstimateSeconds } returns flowOf(null)
            coEvery { runner.infer(any()) } answers {
                assertFalse(manager.enableInProgress.value, "the benchmark must run OUTSIDE the enable window")
                assertTrue(manager.benchmarkRunning.value)
                emptyList()
            }

            manager.enableWithDownload()

            assertFalse(manager.enableInProgress.value)
            assertFalse(manager.benchmarkRunning.value)
        }

    @Test
    fun `enableInProgress cleared when download fails`() =
        runTest {
            withConfig(PrivacyModeConfig(enabled = true))
            every { store.isReady() } returns false
            coEvery { downloader.download() } returns Result.failure(IOException("offline"))

            manager.enableWithDownload()

            assertFalse(manager.enableInProgress.value)
        }
}
