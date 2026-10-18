package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import com.danielealbano.androidremotecontrolmcp.data.model.ControlEdgeColorMode
import com.danielealbano.androidremotecontrolmcp.data.model.ControlEffectsConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ControlEffectsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var repository: SettingsRepository
    private lateinit var persisted: MutableStateFlow<ControlEffectsConfig>
    private lateinit var viewModel: ControlEffectsViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = mockk(relaxed = true)
        persisted = MutableStateFlow(ControlEffectsConfig())
        every { repository.controlEffectsConfig } returns persisted
        coEvery { repository.updateControlEffectsConfig(any()) } coAnswers {
            persisted.value = firstArg()
        }
        viewModel = ControlEffectsViewModel(repository)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `stored appearance is observed without writing defaults`() =
        runTest {
            val stored = ControlEffectsConfig(colorMode = ControlEdgeColorMode.CUSTOM, customColorArgb = 0xFF8866EE.toInt())
            persisted.value = stored

            assertEquals(stored, viewModel.config.value)
            coVerify(exactly = 0) { repository.updateControlEffectsConfig(any()) }
        }

    @Test
    fun `slider previews update immediately but persist only the finished value`() =
        runTest {
            viewModel.preview(ControlEffectsConfig(glowWidthDp = 40f))
            viewModel.preview(ControlEffectsConfig(glowWidthDp = 55f))
            val final = ControlEffectsConfig(glowWidthDp = 72f)
            viewModel.preview(final)

            assertEquals(final, viewModel.config.value)
            coVerify(exactly = 0) { repository.updateControlEffectsConfig(any()) }

            viewModel.savePreview()

            coVerify(exactly = 1) { repository.updateControlEffectsConfig(final) }
            assertEquals(final, persisted.value)
        }

    @Test
    fun `a repository emission cannot replace an in-progress slider preview`() =
        runTest {
            val draft = ControlEffectsConfig(intensity = 0.6f)
            viewModel.preview(draft)

            persisted.value = ControlEffectsConfig(colorMode = ControlEdgeColorMode.BLUE)

            assertEquals(draft, viewModel.config.value)
            viewModel.savePreview()
            assertEquals(draft, persisted.value)
        }

    @Test
    fun `restoring defaults updates the preview and persists the multicolor preset`() =
        runTest {
            viewModel.preview(ControlEffectsConfig(colorMode = ControlEdgeColorMode.BLUE, speed = 2f))

            viewModel.restoreDefaults()

            assertEquals(ControlEffectsConfig(), viewModel.config.value)
            coVerify(exactly = 1) { repository.updateControlEffectsConfig(ControlEffectsConfig()) }
        }

    @Test
    fun `failed writes retain the preview and expose a retryable error`() =
        runTest {
            val draft = ControlEffectsConfig(motionRange = 0.4f)
            viewModel.preview(draft)
            coEvery { repository.updateControlEffectsConfig(any()) } throws IOException("Unavailable")

            viewModel.savePreview()

            assertTrue(viewModel.saveFailed.value)
            assertEquals(draft, viewModel.config.value)

            coEvery { repository.updateControlEffectsConfig(any()) } coAnswers { persisted.value = firstArg() }
            viewModel.savePreview()

            assertFalse(viewModel.saveFailed.value)
            assertEquals(draft, persisted.value)
        }

    @Test
    fun `finishing an older write does not end a newer gesture returning to the same value`() =
        runTest {
            val releaseWrite = CompletableDeferred<Unit>()
            coEvery { repository.updateControlEffectsConfig(any()) } coAnswers {
                releaseWrite.await()
                persisted.value = firstArg()
            }
            val draft = ControlEffectsConfig(intensity = 0.4f)
            viewModel.preview(draft)
            viewModel.savePreview()
            viewModel.preview(ControlEffectsConfig(intensity = 0.6f))
            viewModel.preview(draft)

            releaseWrite.complete(Unit)
            persisted.value = ControlEffectsConfig(intensity = 0.7f)

            assertEquals(draft, viewModel.config.value)
        }

    @Test
    fun `a delayed first repository value cannot replace a change before its flow acknowledgment`() =
        runTest {
            val delayedStore = MutableSharedFlow<ControlEffectsConfig>()
            every { repository.controlEffectsConfig } returns delayedStore
            coEvery { repository.updateControlEffectsConfig(any()) } returns Unit
            val delayedViewModel = ControlEffectsViewModel(repository)
            val draft = ControlEffectsConfig(colorMode = ControlEdgeColorMode.CUSTOM)
            delayedViewModel.preview(draft)
            delayedViewModel.savePreview()

            delayedStore.emit(ControlEffectsConfig())
            assertEquals(draft, delayedViewModel.config.value)

            delayedStore.emit(draft)
            val external = draft.copy(glowWidthDp = 80f)
            delayedStore.emit(external)
            assertEquals(external, delayedViewModel.config.value)
        }

    @Test
    fun `quick mode changes serialize persistence and retain the newest selected mode`() =
        runTest {
            val releaseFirstWrite = CompletableDeferred<Unit>()
            val writes = mutableListOf<ControlEdgeColorMode>()
            coEvery { repository.updateControlEffectsConfig(any()) } coAnswers {
                val value = firstArg<ControlEffectsConfig>()
                if (writes.isEmpty()) releaseFirstWrite.await()
                writes += value.colorMode
                persisted.value = value
            }
            viewModel.preview(ControlEffectsConfig(colorMode = ControlEdgeColorMode.BLUE))
            viewModel.savePreview()
            val selected = ControlEffectsConfig(colorMode = ControlEdgeColorMode.CUSTOM)
            viewModel.preview(selected)
            viewModel.savePreview()
            assertEquals(selected, viewModel.config.value)

            releaseFirstWrite.complete(Unit)

            assertEquals(listOf(ControlEdgeColorMode.BLUE, ControlEdgeColorMode.CUSTOM), writes)
            assertEquals(selected, viewModel.config.value)
            assertEquals(selected, persisted.value)
        }
}
