package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import app.cash.turbine.test
import com.danielealbano.androidremotecontrolmcp.data.model.AvailableUpdate
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.update.AppVersionProvider
import com.danielealbano.androidremotecontrolmcp.services.update.UpdateCheckCoordinator
import com.danielealbano.androidremotecontrolmcp.services.update.UpdateCheckOutcome
import com.danielealbano.androidremotecontrolmcp.services.update.UpdateCheckTrigger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("UpdateViewModel")
class UpdateViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var settings: SettingsRepository
    private lateinit var coordinator: UpdateCheckCoordinator
    private lateinit var versionProvider: AppVersionProvider
    private lateinit var viewModel: UpdateViewModel

    private val releaseUrl = "https://github.com/danielealbano/android-remote-control-mcp/releases/tag/v1.11.0"

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        settings = mockk(relaxed = true)
        coordinator = mockk()
        versionProvider = mockk()
        every { settings.availableUpdate } returns flowOf(null)
        every { settings.autoUpdateCheckEnabled } returns flowOf(true)
        every { versionProvider.versionName } returns "1.10.0"
        viewModel = UpdateViewModel(settings, coordinator, versionProvider)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `exposes the installed version`() {
        assertEquals("1.10.0", viewModel.currentVersion)
    }

    @Test
    fun `checkNow maps UpToDate outcome`() =
        runTest {
            coEvery { coordinator.check(UpdateCheckTrigger.MANUAL) } returns UpdateCheckOutcome.UpToDate
            viewModel.checkNow()
            assertEquals(UpdateCheckUiState.UpToDate, viewModel.checkState.value)
        }

    @Test
    fun `checkNow maps Failed outcome`() =
        runTest {
            coEvery { coordinator.check(UpdateCheckTrigger.MANUAL) } returns UpdateCheckOutcome.Failed
            viewModel.checkNow()
            assertEquals(UpdateCheckUiState.Failed, viewModel.checkState.value)
        }

    @Test
    fun `checkNow maps DevBuild outcome`() =
        runTest {
            coEvery { coordinator.check(UpdateCheckTrigger.MANUAL) } returns UpdateCheckOutcome.DevBuild
            viewModel.checkNow()
            assertEquals(UpdateCheckUiState.DevBuild, viewModel.checkState.value)
        }

    @Test
    fun `checkNow maps UpdateAvailable to UpdateFound`() =
        runTest {
            val update = AvailableUpdate("1.11.0", releaseUrl)
            coEvery { coordinator.check(UpdateCheckTrigger.MANUAL) } returns UpdateCheckOutcome.UpdateAvailable(update)
            viewModel.checkNow()
            assertEquals(UpdateCheckUiState.UpdateFound(update), viewModel.checkState.value)
        }

    @Test
    fun `checkNow maps a coordinator failure to Failed instead of crashing`() =
        runTest {
            coEvery { coordinator.check(UpdateCheckTrigger.MANUAL) } throws RuntimeException("disk full")
            viewModel.checkNow()
            assertEquals(UpdateCheckUiState.Failed, viewModel.checkState.value)
        }

    @Test
    fun `checkNow exposes the Checking state while the check is in progress`() =
        runTest {
            val gate = CompletableDeferred<UpdateCheckOutcome>()
            coEvery { coordinator.check(UpdateCheckTrigger.MANUAL) } coAnswers { gate.await() }

            viewModel.checkNow()
            assertEquals(UpdateCheckUiState.Checking, viewModel.checkState.value)

            gate.complete(UpdateCheckOutcome.UpToDate)
            advanceUntilIdle()
            assertEquals(UpdateCheckUiState.UpToDate, viewModel.checkState.value)
        }

    @Test
    fun `availableUpdate reflects the repository value`() =
        runTest {
            val update = AvailableUpdate("1.11.0", releaseUrl)
            every { settings.availableUpdate } returns MutableStateFlow(update)
            val vm = UpdateViewModel(settings, coordinator, versionProvider)

            vm.availableUpdate.test {
                // stateIn starts at its null initial value, then adopts the stored update.
                val value = awaitItem() ?: awaitItem()
                assertEquals(update, value)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `setAutoCheckEnabled delegates to the repository`() =
        runTest {
            viewModel.setAutoCheckEnabled(false)
            coVerify { settings.updateAutoUpdateCheckEnabled(false) }
        }
}
